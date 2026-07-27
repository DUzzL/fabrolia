package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Partitions one {@link ServerLevel}'s chunks into {@link Region}s by fixed grid.
 *
 * <p>Phase 2+ uses {@link RegionGrid} for routing. Region creation is lazy: the
 * first lookup for a grid cell creates that cell's region. Regions are never
 * removed automatically — they accumulate until the level shuts down. On a wild
 * server with 18 players spread across ~3 dimensions this caps out around a few
 * hundred regions, which is fine for linear scans in the worker dispatcher.
 * If we ever ship a real game with thousands of regions, add LRU eviction here.
 *
 * <p>Thread safety: lock-free CHM wrapper. The original single-monitor variant
 * serialized every {@link ColorGate#eligible} (8 peeks per claim attempt × 30
 * workers) against the barrier pump's heal-sweep snapshot — the monitor showed
 * up as worker-wave latency on the 2026-06-13 color-gate profile. Reads are
 * now plain CHM gets (~10ns); creation is an atomic computeIfAbsent; snapshot
 * is a weakly-consistent values() copy (regions created mid-copy are simply
 * picked up by the next sweep/heal pass — both re-run continuously).
 */
public final class ThreadedRegionizer {

    public final ServerLevel level;
    public final RegionGrid grid;
    /** Back-ref to the owning server, set right after construction. Source of the live
     *  epoch / dispatchedEpoch used to stamp a fresh region's doneEpoch (see below). */
    RegionizedServer owner;
    private final Long2ObjectConcurrentHashMap<Region> regionsByGridKey =
            new Long2ObjectConcurrentHashMap<>(256);

    ThreadedRegionizer(ServerLevel level, RegionGrid grid) {
        this.level = level;
        this.grid = grid;
    }

    /**
     * Look up or create the region for a chunk. Also records the chunk on the
     * region's observed set for debug visibility — cheap, drops dupes via
     * underlying LongOpenHashSet.
     */
    public Region regionForChunk(long packedChunkPos) {
        Region r = regionForGridKey(grid.keyFor(packedChunkPos));
        r.noteChunk(packedChunkPos);
        return r;
    }

    /** Lookup-or-create by grid key directly (entity index hot path — no ChunkPos round-trip). */
    public Region regionForGridKey(long gridKey) {
        Region r = regionsByGridKey.get(gridKey);
        if (r != null && r.state() == RegionState.ACTIVE) {
            return r;
        }
        // Absent, or a region the idle-evictor is retiring (STOPPING/STOPPED): atomically
        // install a fresh ACTIVE region, REPLACING any dying mapping. compute() races
        // cleanly with evictIdle()'s conditional remove(key, dyingRegion) — whichever runs
        // last, the cell ends up mapped to exactly one ACTIVE region, so a chunk/entity that
        // lands here right as its region is reclaimed is never lost (boxing on the Long key
        // is on this cold create path only, never the hot get above).
        return regionsByGridKey.compute(gridKey, (k, existing) -> {
            if (existing != null && existing.state() == RegionState.ACTIVE) {
                return existing;
            }
            Region created = new Region(level, k);
            created.compareAndSetState(RegionState.NEW, RegionState.ACTIVE);
            // Color-DAG safety for regions BORN AFTER this epoch's dispatch sweep. The sweep
            // (dispatchOwnedEntityTicks) marks every then-existing empty region done; a region
            // created later — entity moved into a reclaimed cell, or a post-dispatch chunk
            // lookup — misses it and would sit at doneEpoch=0, a PHANTOM lower-colour blocker
            // that never marks done, stranding a higher-colour neighbour's task forever
            // (stale-future watchdog deadlock, 2026-06-27). It owes no work this epoch (anything
            // in it already ticked elsewhere this tick), so stamp it done now, exactly as the
            // sweep would have. Pre-dispatch creation keeps doneEpoch=0 and participates normally.
            RegionizedServer o = owner;
            if (o != null) {
                // A2: join the creating context's timeline (a worker mid-phase
                // creating an index-move destination inherits its claimed
                // region's island domain; main-thread creations join the global
                // domain until the next island rebuild adopts them). Domain is
                // set BEFORE publication — every later stamp compares in it.
                EpochDomain d = o.domainForCreation(created.gridKey);
                created.domain = d;
                long epoch = d.epoch;
                // Island domains: a newborn region ALWAYS starts done for the
                // current epoch — it has no dispatchable work by construction
                // (adoption happens at the next rebuild), and an unstamped
                // same-domain neighbor blocks core color eligibility forever
                // (2026-07-14 frontier watchdogs). Global keeps the original
                // mid-dispatch-only stamp (its sweep covers the rest).
                if (d.dispatchedEpoch == epoch || d.owner != null) {
                    created.doneEpoch = epoch;
                }
            }
            return created;
        });
    }

    public Region peekByGridKey(long gridKey) {
        return regionsByGridKey.get(gridKey);
    }

    public Region regionForChunk(ChunkPos pos) {
        return regionForChunk(pos.pack());
    }

    public Region peekRegionForChunk(long packedChunkPos) {
        return regionsByGridKey.get(grid.keyFor(packedChunkPos));
    }

    public List<Region> snapshot() {
        return new ArrayList<>(regionsByGridKey.values());
    }

    public int regionCount() {
        return regionsByGridKey.size();
    }

    /** Idle-region grace: consecutive tick-head observations of full idleness before
     *  a region is reclaimed. ~5s — long enough that the moving load frontier (regions
     *  that go briefly empty between the player leaving and chunks unloading) is not
     *  thrashed, short enough that an exploring player's wake doesn't pile up. */
    private static final int EVICT_GRACE_TICKS = 100;

    /**
     * Reclaim regions that have stayed completely idle — no FULL chunks, no entities,
     * empty work queues, unclaimed — for {@link #EVICT_GRACE_TICKS}. MUST be called on
     * the server thread at level-tick HEAD: region workers are parked between barriers
     * there, and the only region mutators (entity index via EntityTickList, FULL-count
     * via updateChunkScheduling) are server-thread too, so the read-then-reclaim is not
     * racing a concurrent populate. A late lookup of a reclaimed cell self-heals through
     * {@link #regionForGridKey} (compute() reinstalls a fresh ACTIVE region).
     *
     * <p>Without this the map grew without bound: regions are created lazily as players
     * explore and were NEVER removed, so every O(regions) scan (island rebuild snapshot+BFS,
     * worker dispatch) climbed and mspt rose monotonically on a long flight (live: 49 bots
     * flying to z=56535 → tens of thousands of permanent regions, 2026-06-27).
     *
     * @return number of regions reclaimed this pass.
     */
    public int evictIdle(RegionizedServer rs) {
        int removed = 0;
        for (Region r : regionsByGridKey.values()) {
            if (r.state() != RegionState.ACTIVE) {
                continue;
            }
            if (r.fullChunkCount.get() != 0
                    || r.entityCount.get() != 0
                    || !r.queue().isEmpty()
                    || !r.helpQueue.isEmpty()
                    || r.currentWorker() != null) {
                r.idleStreak = 0;
                continue;
            }
            if (++r.idleStreak < EVICT_GRACE_TICKS) {
                continue;
            }
            // Removing a region tears it out of the color-DAG. If it was a lower-color
            // BLOCKER for a higher-color neighbour, that neighbour was dropped from the
            // ready queue waiting for this region's done-mark to re-arm it — and a removed
            // region never marks done. So re-arm dependents NOW (mark done = re-enqueue any
            // higher-colour neighbour with pending work), exactly as a normal completion
            // would, BEFORE unmapping. Safe pre-dispatch: an idle no-work region has no
            // entity phase to race (the crossUNSAFE window markDone normally guards against).
            // Without this, an evicted blocker stranded its dependent's task forever →
            // spawn future never completed → watchdog killed the server (2026-06-27).
            ColorGate.markDone(r, rs);
            // CAS to STOPPING first: fences a concurrent regionForGridKey that may have
            // just read this region as ACTIVE — it will see non-ACTIVE and compute() a
            // replacement. The conditional remove(key, r) then only unmaps THIS region,
            // never a fresh one installed in between.
            if (r.compareAndSetState(RegionState.ACTIVE, RegionState.STOPPING)) {
                regionsByGridKey.remove(r.gridKey, r);
                r.compareAndSetState(RegionState.STOPPING, RegionState.STOPPED);
                removed++;
            }
        }
        return removed;
    }

    /** Force-drop a region. Pending queued tasks are silently discarded. */
    public void drop(Region r) {
        regionsByGridKey.remove(r.gridKey);
        r.compareAndSetState(RegionState.ACTIVE, RegionState.STOPPING);
        r.compareAndSetState(RegionState.STOPPING, RegionState.STOPPED);
    }

    public void clear() {
        for (Region r : regionsByGridKey.values()) {
            r.compareAndSetState(RegionState.ACTIVE, RegionState.STOPPING);
            r.compareAndSetState(RegionState.STOPPING, RegionState.STOPPED);
        }
        regionsByGridKey.clear();
    }
}
