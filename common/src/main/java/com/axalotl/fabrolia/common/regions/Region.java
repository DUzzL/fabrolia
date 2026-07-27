package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Comparator;

/**
 * A grid-cell region. Identity = {@code gridKey}; one region per cell per
 * {@link ServerLevel}. Owns a task queue that a single worker drains at a time.
 *
 * <p>Phase 1 stored the explicit chunk set; Phase 2+ uses fixed-grid routing so
 * chunk ownership is derivable from {@code gridKey} via {@link RegionGrid}. The
 * owned-chunks set is kept only as a debug accuracy hint (which chunks have been
 * referenced through this region) — not load-bearing for tick dispatch.
 *
 * <p>Concurrency: queue is lock-free. State + claim are atomic. The debug chunk
 * set is guarded by {@code synchronized(this)} and intentionally NOT consulted on
 * the dispatch hot path.
 */
public final class Region {
    private static final AtomicLong NEXT_ID = new AtomicLong();

    public final long id = NEXT_ID.incrementAndGet();
    public final long gridKey;
    public final ServerLevel level;

    /** R4 color-DAG: checkerboard class ((rx&1) | ((rz&1)<<1)). Two regions of
     *  one color are never neighbors (edge OR corner), so scheduling colors in
     *  dependency order makes any cross-border effect up to a full region wide
     *  conflict-free BY CONSTRUCTION — the vine/amethyst class dies here. */
    public final int color;

    /** A2: the scheduling timeline this region belongs to — the level's global
     *  domain (sync mode / gap regions) or its island's domain (async mode).
     *  Every epoch stamp below is meaningful ONLY in this domain's timeline;
     *  reassignment (RegionizedServer.assignDomain, safe point only) resets
     *  all stamps to the new domain's current epoch. Set before publication
     *  at creation (ThreadedRegionizer), then only at island rebuilds. */
    volatile EpochDomain domain;

    /** Heal-loop forensic counter (main-only): consecutive sightings of a
     *  claim whose holder is not working this region. */
    int staleClaimSightings;
    /** Heal-loop forensic counter (main-only): consecutive sightings of an
     *  UNCLAIMED region with pending queue work (lost ready entry). */
    int strandedQueueSightings;

    /** Last epoch this region fully completed (queue drained + entity phase). */
    public volatile long doneEpoch;
    /** Epoch whose entity phase has run (the final per-region act of a tick). */
    public volatile long entityEpoch;
    /** Epoch in which this region was included in entity dispatch — such a
     *  region OWES an entity phase before it may complete; one that wasn't
     *  completes as soon as its queue drains (late-armed work can't wedge
     *  the color DAG waiting for an entity phase that will never come). */
    public volatile long groupedEpoch;

    /** R5 event scheduler: epoch of the last ENTITY-phase ready-lane push. CAS-gated so
     *  the dispatch seed and concurrent markDone re-arms push a region at most once per
     *  epoch (the push itself is duplicate-tolerant; the gate just bounds churn). */
    public final AtomicLong entityEnqueuedEpoch = new AtomicLong(-1L);

    /** R5 event scheduler: epoch whose barrier decrement this region has contributed.
     *  CAS winner decrements {@code RegionizedServer.entityPending} exactly once. */
    public final AtomicLong entityBarrierEpoch = new AtomicLong(-1L);

    /** F1 islands: count of FULL-or-better chunks in this cell, maintained
     *  from ChunkMap.updateChunkScheduling crossings (main thread). A region
     *  with zero full chunks conducts nothing — blocks there can't be read
     *  or written, entities can't exist. Populated = fullChunkCount > 0. */
    public final java.util.concurrent.atomic.AtomicInteger fullChunkCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** A2-blocker fix (2026-07-12): the authoritative FULL-chunk membership set.
     *  Population transitions are IDEMPOTENT through this set — the counter above
     *  moves only when membership actually changes, so duplicate or missing
     *  lifecycle events can no longer inflate counts or leave them negative
     *  (the ticket-only-unload split bug: fabric CHUNK_UNLOAD misses the 26.2
     *  demote path; counts stayed high forever and islands never split).
     *  Main-thread mutations only (both event sources deliver on main). */
    public final LongOpenHashSet fullChunks = new LongOpenHashSet(64);

    /** Island id this region was assigned at the last rebuild; -1 = no-man's
     *  land (unpopulated). Stamped by RegionizedServer.rebuildIslandsIfDirty
     *  on the level tick head, read by the island-partitioned phases. */
    public volatile int islandId = -1;

    /**
     * Per-region tick lock. Used by {@link FabroliaLocks#tickLockOr(Object)} to
     * scope what were previously global static-Object monitors in entity/world
     * mixins down to the region currently doing the tick. Region invariant
     * "only one worker holds a region's claim at a time" means this monitor is
     * essentially uncontended from the tick side — its purpose is to keep the
     * SHAPE of vanilla invariant protection (mutual exclusion against other
     * mixins on the same scope) while letting different regions tick in
     * parallel without globally serializing.
     */
    public final Object lock = new Object();

    /**
     * Region-owned tickable entities — the region-local mirror of this level's
     * EntityTickList membership for chunks in this grid cell. Maintained incrementally
     * (EntityTickListMixin add/remove → RegionScheduler.onEntityAdd/Remove; cross-cell
     * moves re-routed lazily by the owning region's tick loop). The region tick task
     * iterates THIS set — no global list walk, no per-tick grouping on main.
     *
     * <p>Skip-list ordered by entity id (monotonically assigned at creation ≈ vanilla's
     * EntityTickList insertion order): the within-region tick order is deterministic and
     * spawn-ordered, which contraptions with stacked TNT / precise entity timing depend
     * on. A CHM key-set was hash-ordered — non-vanilla and run-to-run unstable. Skip-list
     * ops cost slightly more per add/remove, but index churn is tiny next to tick work.
     *
     * <p>Concurrent add/remove from main + workers, weakly-consistent iteration during
     * drain. An entity added mid-drain may tick this tick or next; the per-entity
     * region tick token guarantees at most one region-owned tick per epoch.
     */
    public final Set<Entity> entities = new ConcurrentSkipListSet<>(
            Comparator.comparingInt(Entity::getId));

    /**
     * Exact size of {@link #entities} (skip-list size() is O(n); the dense-region
     * fan-out check reads this every dispatch). Maintained by RegionizedServer index
     * ops — incremented/decremented only when the set op actually changed the set.
     */
    public final AtomicLong entityCount = new AtomicLong();

    /** EWMA (α=1/4) of this region's measured entity-phase nanos, written by the
     *  phase runner after each run (single writer per epoch — the claim holder),
     *  read by dispatch for LPT ordering. Scheduling hint only; 0 = no sample yet. */
    public volatile long emaPhaseNanos;

    // ---- /fabrolia regions top diagnostics (phase owner writes, command reads) ----
    public volatile long lastPhaseNanos;
    public volatile int lastFanoutBatches;
    public volatile long lastBatchMaxNanos;
    public volatile long lastOrderedNanos;

    /**
     * Intra-region fan-out lane (dense regions): parallel sub-batches of this region's
     * entity tick that ANY region worker may execute WITHOUT claiming the region — the
     * claim stays with the coordinating worker for the whole drain. Runners must set
     * {@code currentRegion} to this region while executing (lock discipline: tickLockOr
     * then returns THIS region's monitor, so a dense village's contention stays scoped
     * to itself instead of serializing every dense blob on global static locks).
     */
    public final ConcurrentLinkedQueue<Runnable> helpQueue =
            new ConcurrentLinkedQueue<>();

    private final LongOpenHashSet observedChunks = new LongOpenHashSet();
    private final RegionizedTaskQueue queue = new RegionizedTaskQueue();
    private final AtomicReference<RegionState> state = new AtomicReference<>(RegionState.NEW);
    private final AtomicReference<Thread> currentWorker = new AtomicReference<>();

    // ------------------------------------------------------------------
    // Claim ledger (2026-07-13, production "SUSPECT LEAKED CLAIM" audit).
    // Single-writer discipline: every field below is written ONLY by the
    // thread that owns the claim transition (the CAS winner right after
    // acquiring, the holder right before releasing), so plain volatile
    // stamps are coherent without extra atomics. The heal forensic uses
    // claimGen to correlate sightings: the SAME generation observed across
    // two passes means one acquisition persisted (a real retention, with
    // its acquire path and age in hand); different generations mean claim
    // churn, which is never reported. Cost: a few volatile writes per
    // claim transition, zero allocation (paths are interned literals).
    // ------------------------------------------------------------------
    /** Monotonic acquisition counter; ++ by each CAS winner after claiming. */
    public volatile long claimGen;
    /** Nanotime of the current/last acquisition. */
    public volatile long claimAcquiredNanos;
    /** Interned tag of the acquiring site ("workOnce", "stealOne", ...). */
    public volatile String claimPath = "-";
    /** Generation stamped by the last RELEASE (lastReleaseGen == claimGen ⇒
     *  the ledger saw a paired release for the current generation). */
    public volatile long lastReleaseGen;
    public volatile String lastReleasePath = "-";
    /** Heal-forensic correlation: generation seen at the previous mismatch
     *  sighting (main-only, like the sighting counters). */
    long staleClaimGenSeen = -1L;

    /** CAS winner bookkeeping — call immediately after a successful
     *  {@link #tryClaim}. */
    public void stampAcquire(String path) {
        claimGen++;
        claimAcquiredNanos = System.nanoTime();
        claimPath = path;
    }

    /** Holder bookkeeping — call immediately BEFORE {@link #release}. */
    public void stampRelease(String path) {
        lastReleaseGen = claimGen;
        lastReleasePath = path;
    }

    /** Consecutive level-tick-head observations of this region being fully idle
     *  (no full chunks, no entities, empty queues, unclaimed). Server-thread only —
     *  mutated solely by {@link ThreadedRegionizer#evictIdle()} at tick HEAD, where
     *  region workers are parked between barriers. Crosses the grace threshold →
     *  the region is reclaimed (see ThreadedRegionizer doc for why this is needed). */
    int idleStreak;

    Region(ServerLevel level, long gridKey) {
        this.level = level;
        this.gridKey = gridKey;
        int rx = (int) gridKey;
        int rz = (int) (gridKey >>> 32);
        this.color = (rx & 1) | ((rz & 1) << 1);
    }

    public RegionizedTaskQueue queue() { return queue; }
    public RegionState state() { return state.get(); }

    boolean compareAndSetState(RegionState expected, RegionState next) {
        return state.compareAndSet(expected, next);
    }

    boolean tryClaim(Thread worker) {
        return currentWorker.compareAndSet(null, worker);
    }

    void release(Thread worker) {
        currentWorker.compareAndSet(worker, null);
    }

    public Thread currentWorker() { return currentWorker.get(); }

    /** Note a chunk that touched this region. Cheap, no-op if already observed. */
    public synchronized void noteChunk(long packedChunkPos) {
        observedChunks.add(packedChunkPos);
    }

    public synchronized int observedChunkCount() {
        return observedChunks.size();
    }

    public synchronized long[] snapshotObservedChunks() {
        return observedChunks.toLongArray();
    }

    public boolean owns(ChunkPos pos) {
        return owns(pos.pack());
    }

    public synchronized boolean owns(long packedChunkPos) {
        return observedChunks.contains(packedChunkPos);
    }

    @Override
    public String toString() {
        return "Region#" + id + "[key=" + Long.toHexString(gridKey) + ", "
                + level.dimension().identifier() + ", observed=" + observedChunkCount() + "]";
    }
}
