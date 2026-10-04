package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 * now plain CHM gets (~10ns); creation is an atomic compute behind a cold-path
 * fair READ gate. Ordinary snapshots stay weakly consistent (sweep/heal both
 * re-run continuously); a topology-domain rebuild or the global-domain
 * dispatch cut takes the matching level WRITE so creation and its final
 * successor-snapshot publication cannot straddle. An island's dispatch cut
 * takes only its own domain's {@link EpochDomain#regionCreationGate} WRITE.
 */
public final class ThreadedRegionizer {

    public final ServerLevel level;
    public final RegionGrid grid;
    /** Back-ref to the owning server, set right after construction. Source of the live
     *  domain clocks and entity-dispatch membership cut used to stamp a fresh region. */
    RegionizedServer owner;
    private final Long2ObjectConcurrentHashMap<Region> regionsByGridKey =
            new Long2ObjectConcurrentHashMap<>(256);
    /**
     * Linearizes the cold create/replace path with topology-domain publication.
     * Existing ACTIVE lookups remain lock-free.  A fair lock is intentional:
     * mass chunk generation may continuously create cells, but a topology writer
     * must eventually close a finite map generation and publish its successor.
     */
    private final ReentrantReadWriteLock creationTopologyGate =
            new ReentrantReadWriteLock(true);

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
        creationTopologyGate.readLock().lock();
        try {
            // Recheck after admission: a topology writer may have published a
            // successor snapshot while this creator waited, or another creator
            // may already have installed the cell.
            r = regionsByGridKey.get(gridKey);
            if (r != null && r.state() == RegionState.ACTIVE) {
                return r;
            }
            // Resolve the destination cell's domain before publishing the
            // region. A cold cross-island task must not inherit the submitting
            // worker's claimed region or unit-coordinator timeline. Stable for
            // this whole admission: the cell snapshot is only republished
            // under the level WRITE this READ excludes.
            RegionizedServer o = owner;
            EpochDomain d = o == null ? null : o.domainForCreation(gridKey);
            if (d == null) {
                return install(gridKey, null);
            }
            // The domain READ pairs with that domain's own dispatch cut.
            d.regionCreationGate.readLock().lock();
            try {
                return install(gridKey, d);
            } finally {
                d.regionCreationGate.readLock().unlock();
            }
        } finally {
            creationTopologyGate.readLock().unlock();
        }
    }

    private Region install(long gridKey, EpochDomain d) {
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
            // A newborn region must enter one coherent point in its domain
            // timeline. The creation READ is admitted either before the
            // entity-dispatch WRITE cut (captured candidate, all stamps
            // E-1) or after it (not barrier-counted, all stamps E).
            // Publishing only doneEpoch used to strand a pre-cut island
            // region: dispatch grouped it, ColorGate refused it as already
            // done, and entityPending retained one contribution.
            if (d != null) {
                created.initializeDomainAtCreation(d);
            }
            return created;
        });
    }

    /** Topology writer admission. Never retain this while awaiting a claim tail
     * or executing a migrated queue body: either operation may create a Region. */
    void lockTopologyCreationWrite() {
        creationTopologyGate.writeLock().lock();
    }

    void unlockTopologyCreationWrite() {
        creationTopologyGate.writeLock().unlock();
    }

    /**
     * One island domain's entity-dispatch membership cut. Excludes exactly the
     * creators whose new region takes {@code domain}; never the level gate, so
     * other islands' cuts and creators proceed. The holder may only read the
     * region map (it must not create a Region or wait on anything).
     */
    void lockDomainCreationCut(EpochDomain domain) {
        domain.regionCreationGate.writeLock().lock();
    }

    void unlockDomainCreationCut(EpochDomain domain) {
        domain.regionCreationGate.writeLock().unlock();
    }

    /** Test/model hook for an already-admitted creator. Production creators use
     * {@link #regionForGridKey(long)}, which owns this pair internally. */
    void lockRegionCreationRead() {
        creationTopologyGate.readLock().lock();
    }

    void unlockRegionCreationRead() {
        creationTopologyGate.readLock().unlock();
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
        return regionsByGridKey.valuesSnapshot();
    }

    /**
     * Capture one finite region-map generation and publish its entity-dispatch
     * cutoff atomically with respect to cold create/replace admissions.
     */
    List<Region> snapshotAtEntityDispatchCut(EpochDomain domain) {
        creationTopologyGate.writeLock().lock();
        try {
            List<Region> captured = snapshot();
            domain.entityDispatchCutEpoch = domain.epoch;
            return captured;
        } finally {
            creationTopologyGate.writeLock().unlock();
        }
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
            synchronized (r.admissionLock) {
                if (r.state() != RegionState.ACTIVE) {
                    continue;
                }
                if (r.fullChunkCount.get() != 0
                        || r.entityCount.get() != 0
                        || !r.queue().isEmpty()
                        || !r.helpQueue.isEmpty()
                        || r.hasClaimOrDoneSeal()) {
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
                ColorGate.markDone(r, rs);
                // CAS to STOPPING first: fences a concurrent regionForGridKey that may have
                // just read this region as ACTIVE. Task admission takes this same monitor.
                if (r.compareAndSetState(RegionState.ACTIVE, RegionState.STOPPING)) {
                    regionsByGridKey.remove(r.gridKey, r);
                    r.compareAndSetState(RegionState.STOPPING, RegionState.STOPPED);
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Force-drop a provably idle region; pending work is never discarded. */
    public void drop(Region r) {
        synchronized (r.admissionLock) {
            if (r.state() != RegionState.ACTIVE
                    || r.fullChunkCount.get() != 0
                    || r.entityCount.get() != 0
                    || !r.queue().isEmpty() || !r.helpQueue.isEmpty()
                    || r.hasClaimOrDoneSeal()) {
                throw new IllegalStateException(
                        "cannot drop a populated/non-active region or one with accepted work");
            }
            if (!r.compareAndSetState(RegionState.ACTIVE, RegionState.STOPPING)) {
                throw new IllegalStateException("region state changed during drop admission");
            }
            regionsByGridKey.remove(r.gridKey, r);
            if (!r.compareAndSetState(RegionState.STOPPING, RegionState.STOPPED)) {
                throw new IllegalStateException("region failed to reach STOPPED after drop");
            }
        }
    }

    public void clear() {
        for (Region r : regionsByGridKey.values()) {
            r.compareAndSetState(RegionState.ACTIVE, RegionState.STOPPING);
            r.compareAndSetState(RegionState.STOPPING, RegionState.STOPPED);
        }
        regionsByGridKey.clear();
    }
}
