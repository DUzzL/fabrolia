package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
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
    private static final AtomicLongFieldUpdater<Region> DONE_EPOCH =
            AtomicLongFieldUpdater.newUpdater(Region.class, "doneEpoch");

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

    /** Heal-loop forensic counter (main-only): consecutive sightings of one
     *  acquisition held outside any claim body by a holder that does not
     *  publish this region. */
    int staleClaimSightings;
    /** Heal-loop forensic counter (main-only): consecutive sightings of an
     *  UNCLAIMED region with pending queue work (lost ready entry). */
    int strandedQueueSightings;
    /** Main-only: when {@link #strandedQueueSightings} last counted. Sightings
     *  further apart are not consecutive (a scoped heal pass may skip the
     *  region for a while) and restart the count. */
    long strandedQueueSeenNanos;

    /** Last epoch whose queue/entity phase cutoff was published. Queue/help
     *  admissions retained beyond that cutoff belong to the following epoch. */
    public volatile long doneEpoch;
    /** Epoch whose entity phase has run (the final per-region act of a tick). */
    public volatile long entityEpoch;
    /** Epoch in which this region was included in entity dispatch — such a
     *  region OWES an entity phase before it may complete; one that wasn't is
     *  sealed at the accepted dispatch cutoff (a concurrent late arm remains
     *  queued for the next epoch instead of wedging the color DAG). */
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

    /**
     * Result of applying one FULL-status delivery to this cell's authoritative
     * membership set.  Island connectivity depends on the cell's populated
     * predicate ({@code fullChunkCount > 0}), not on which of its 64 chunks are
     * FULL, so only {@link #POPULATION_BOUNDARY} requires a topology rebuild.
     */
    enum FullChunkTransition {
        REDUNDANT,
        MEMBERSHIP_ONLY,
        POPULATION_BOUNDARY
    }

    /**
     * Apply one idempotent FULL-status transition. Main-thread only: this owns
     * the same plain fastutil set as the old inline RegionizedServer code.
     *
     * <p>The membership set and derived count still advance for every accepted
     * chunk transition. The return value only separates a local membership
     * change from a 0↔1 populated-cell edge, because only the latter can alter
     * radius-2 island connectivity or queue/tracking ownership.</p>
     */
    FullChunkTransition applyFullChunkTransition(long packedChunkPos, boolean nowFull) {
        if (nowFull) {
            if (!fullChunks.add(packedChunkPos)) {
                return FullChunkTransition.REDUNDANT;
            }
            int populatedChunks = fullChunkCount.incrementAndGet();
            return populatedChunks == 1
                    ? FullChunkTransition.POPULATION_BOUNDARY
                    : FullChunkTransition.MEMBERSHIP_ONLY;
        }

        if (!fullChunks.remove(packedChunkPos)) {
            return FullChunkTransition.REDUNDANT;
        }
        int populatedChunks = fullChunkCount.decrementAndGet();
        if (populatedChunks < 0) {
            throw new IllegalStateException("negative FULL chunk population for region "
                    + gridKey);
        }
        return populatedChunks == 0
                ? FullChunkTransition.POPULATION_BOUNDARY
                : FullChunkTransition.MEMBERSHIP_ONLY;
    }

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
     * Exact size of {@link #entities} (skip-list size() is O(n)), used by load
     * balancing and diagnostics. Index operations update it only when the set
     * operation actually changes membership.
     */
    public final AtomicLong entityCount = new AtomicLong();

    /** EWMA (α=1/4) of this region's measured entity-phase nanos, written by the
     *  phase runner after each run (single writer per epoch — the claim holder),
     *  read by dispatch for LPT ordering. Scheduling hint only; 0 = no sample yet. */
    public volatile long emaPhaseNanos;

    // ---- /fabrolia regions top diagnostics (phase owner writes, command reads) ----
    public volatile long lastPhaseNanos;

    /**
     * Claim-private completion draft.  The entity body may finish before its
     * portal visibility frame has been published; keep diagnostics here until
     * that frame closes successfully.  These fields are never read by commands
     * or dispatch and therefore are not a successful-phase publication.
     */
    long stagedPhaseNanos;

    /**
     * Chunk-phase helper lane. The coordinator retains the region claim until
     * every published helper finishes; entity bodies never enter this queue.
     * Helpers install this region's execution context and lock discipline.
     */
    public final ConcurrentLinkedQueue<Runnable> helpQueue =
            new ConcurrentLinkedQueue<>();

    private final LongOpenHashSet observedChunks = new LongOpenHashSet();
    private final RegionizedTaskQueue queue = new RegionizedTaskQueue();
    private final AtomicReference<RegionState> state = new AtomicReference<>(RegionState.NEW);
    /**
     * Claim and done-publication share one CAS word.  DONE_SEAL is held only
     * while ColorGate validates and publishes a quiescent completion; a
     * claimant can therefore be before that publication or after it, never
     * inside its null-check -> done-write window.
     */
    private static final Object DONE_SEAL = new Object();
    private static final Object DOMAIN_REBASE_SEAL = new Object();
    private final AtomicReference<Object> currentWorker = new AtomicReference<>();
    /**
     * Exact claim-transaction admission cut for a domain migration.  Bit zero
     * is CLOSED_FOR_REBASE; every admitted claim attempt owns one count unit
     * (value 2) until either its CAS refuses or its complete body + successor
     * publication + barrier/ready receipt tail has terminated.  A topology
     * rebase first closes bit zero and then waits for the value to become
     * exactly {@link #CLAIM_REBASE_CLOSED}.  Consequently currentWorker==null
     * is never mistaken for transaction completion: workOnce/stealOne release
     * that CAS before their ColorGate/contribution/receipt tail.
     */
    private static final long CLAIM_REBASE_CLOSED = 1L;
    private static final long CLAIM_TRANSACTION_UNIT = 2L;
    private final AtomicLong claimTransactionState = new AtomicLong();
    /** Task admissions which linearized after CLOSED_FOR_REBASE. Guarded by
     * admissionLock and moved to the ordinary FIFO only after domain-last
     * publication, so a migration cannot execute a post-cut task in the old
     * timeline or lose its already-accepted external permit. */
    private final ArrayDeque<Runnable> domainRebaseDeferredTasks = new ArrayDeque<>();
    /** Duplicate-tolerant ready nudge admitted after the same cut. Guarded by
     * admissionLock; one fresh new-domain publication represents all nudges. */
    private boolean domainRebaseDeferredReady;
    /** Epoch whose done stamp was published while DONE_SEAL owned the CAS word. */
    volatile long sealedDoneEpoch = Long.MIN_VALUE;
    /** Serializes task admission with idle retirement. Lock order: region -> pool. */
    final Object admissionLock = new Object();

    // ------------------------------------------------------------------
    // Claim ledger (2026-07-13, production "SUSPECT LEAKED CLAIM" audit).
    // Single-writer discipline: every field below is written ONLY by the
    // thread that owns the claim transition (the CAS winner right after
    // acquiring, the holder right before releasing), so plain volatile
    // stamps are coherent without extra atomics. claimSeq counts every
    // acquisition; claimGen counts the stamped ones, i.e. the claims that
    // entered a body. The heal forensic reads both through snapshotClaim():
    // the SAME claimSeq across two passes is one acquisition that persisted
    // (a thread re-claiming the region gets a new seq, so A-B-A cannot blur
    // two claims together), and a stamped acquisition without its release
    // stamp is inside its body, working or stalled there but not retained.
    // Cost: a few volatile writes per claim transition, zero allocation
    // (paths are interned literals).
    // ------------------------------------------------------------------
    /** Every successful acquisition, stamped or not; ++ by the CAS winner
     *  once the post-CAS validation admitted it. */
    public volatile long claimSeq;
    /** {@link #claimSeq} of the acquisition that stamped last. */
    public volatile long stampedClaimSeq;
    /** Monotonic counter of stamped acquisitions; ++ by each stamping holder,
     *  written last so a reader that sees it also sees the other stamps. */
    public volatile long claimGen;
    /** Nanotime of the current/last stamped acquisition. */
    public volatile long claimAcquiredNanos;
    /** Interned tag of the acquiring site ("workOnce", "stealOne", ...). */
    public volatile String claimPath = "-";
    /** Generation stamped by the last RELEASE (lastReleaseGen == claimGen ⇒
     *  the ledger saw a paired release for the current generation). */
    public volatile long lastReleaseGen;
    public volatile String lastReleasePath = "-";
    /** Heal-forensic correlation (main-only, like the sighting counters): the
     *  acquisition and generation of the previous retention sighting, and
     *  when that acquisition was first seen outside a body. */
    long staleClaimSeqSeen = -1L;
    long staleClaimGenSeen = -1L;
    long staleClaimFirstSeenNanos;

    /** CAS winner bookkeeping — call immediately after a successful
     *  {@link #tryClaim}. */
    public void stampAcquire(String path) {
        stampedClaimSeq = claimSeq;
        claimAcquiredNanos = System.nanoTime();
        claimPath = path;
        claimGen++;
    }

    /** Holder bookkeeping — call immediately BEFORE {@link #release}. */
    public void stampRelease(String path) {
        lastReleasePath = path;
        lastReleaseGen = claimGen;
    }

    /**
     * One validated read of the claim ledger for the heal forensic, or
     * {@code null} while a claim transition is in flight. The holder,
     * sequence and generations are re-read after the other fields, so every
     * value belongs to the same acquisition; the holder's work itself is not
     * paused by this read.
     */
    ClaimSnapshot snapshotClaim() {
        Thread holder = currentWorker();
        long seq = claimSeq;
        long gen = claimGen;
        long stampedSeq = stampedClaimSeq;
        long releasedGen = lastReleaseGen;
        String acquirePath = claimPath;
        long acquiredNanos = claimAcquiredNanos;
        String releasePath = lastReleasePath;
        if (holder != currentWorker() || seq != claimSeq || gen != claimGen
                || releasedGen != lastReleaseGen) {
            return null;
        }
        return new ClaimSnapshot(holder, seq, gen, stampedSeq == seq, releasedGen,
                acquirePath, acquiredNanos, releasePath);
    }

    /** {@link #snapshotClaim}'s values. {@code stamped}: this acquisition
     *  called {@link #stampAcquire}, so the path, age and generation are its
     *  own; otherwise they describe the last stamped acquisition. */
    record ClaimSnapshot(Thread holder, long seq, long gen, boolean stamped,
                         long releasedGen, String acquirePath, long acquiredNanos,
                         String releasePath) {
        /** Stamped and not yet release-stamped: the holder is inside this
         *  acquisition's body. */
        boolean inBody() {
            return stamped && gen != releasedGen;
        }
    }

    /** Game time of the first consecutive reclamation-pass observation of this
     *  region being fully idle (no full chunks, no entities, empty queues,
     *  unclaimed); {@code Long.MIN_VALUE} = not idle at the last pass.
     *  Server-thread only — mutated solely by {@link ThreadedRegionizer#evictIdle}
     *  under the region's admissionLock. Idle for the grace window → the region is
     *  reclaimed (see ThreadedRegionizer doc for why this is needed). */
    long idleSinceGameTime = Long.MIN_VALUE;

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

    /**
     * Claim only for the exact eligibility generation captured by the caller.
     * Domain rebases hold the same CAS word, but a delayed old claimant can
     * reach CAS after that marker releases; identity+epoch validation is the
     * required post-CAS fence against entering the replacement timeline.
     */
    boolean tryClaim(Thread worker, EpochDomain expectedDomain, long expectedEpoch) {
        return tryClaim(worker, expectedDomain, expectedEpoch, false);
    }

    /**
     * The queue dispatch is already sealed, but its island coordinator still
     * owns the complete unit. Only its authenticated serial entity phase may
     * reopen a region claim; ready/queue consumers retain the ordinary seal.
     */
    boolean tryClaimIslandEntityPhase(RegionWorkerThread worker,
                                     EpochDomain expectedDomain, long expectedEpoch) {
        if (worker != Thread.currentThread() || !IslandEntityPhaseScope.owns(this)) {
            throw new IllegalStateException("serial entity region claim without exclusive island authority");
        }
        return tryClaim(worker, expectedDomain, expectedEpoch, true);
    }

    private boolean tryClaim(Thread worker, EpochDomain expectedDomain, long expectedEpoch,
                             boolean islandEntityPhase) {
        if (worker == null) throw new NullPointerException("worker");
        if (expectedDomain != null && expectedDomain.epoch != expectedEpoch) {
            return false;
        }
        if (!tryOpenClaimTransaction()) return false;
        boolean retainedTransaction = false;
        try {
            if (!currentWorker.compareAndSet(null, worker)) return false;
            // A claimant may have read ColorGate eligibility before a concurrent
            // done publisher acquired/released DONE_SEAL or before a topology
            // migration closed claim admission. These post-CAS reads are the
            // other half of both handshakes: an old claimant either belongs to
            // the complete old transaction or self-releases before body entry.
            EpochDomain observedDomain = domain;
            if (claimRebaseClosed()
                    || observedDomain != expectedDomain
                    || (observedDomain != null
                        && (observedDomain.epoch != expectedEpoch
                            || !islandEntityPhase && sealedDoneEpoch >= expectedEpoch))) {
                if (!currentWorker.compareAndSet(worker, null)) {
                    throw new IllegalStateException(
                            "region claim changed while refusing a stale/sealed/rebasing epoch");
                }
                return false;
            }
            claimSeq++;
            retainedTransaction = true;
            return true;
        } finally {
            if (!retainedTransaction) finishClaimTransaction();
        }
    }

    /** Open one attempt/transaction only on the pre-rebase side of the cut. */
    private boolean tryOpenClaimTransaction() {
        for (;;) {
            long state = claimTransactionState.get();
            if ((state & CLAIM_REBASE_CLOSED) != 0L) return false;
            if (state > Long.MAX_VALUE - CLAIM_TRANSACTION_UNIT) {
                throw new IllegalStateException("region claim transaction counter overflow");
            }
            if (claimTransactionState.compareAndSet(
                    state, state + CLAIM_TRANSACTION_UNIT)) return true;
        }
    }

    /**
     * Reserve one claim-free, region-targeted body on the pre-rebase side of
     * the same cut as {@link #tryClaim}. Main-safe publication calls this before
     * CLQ visibility; the exact-turn path calls it before inline body entry.
     * Both own {@link #admissionLock}. A domain rebase therefore either closes
     * first or waits for this exact queued/running body terminal.
     */
    boolean tryOpenMainSafeBodyTransaction(
            EpochDomain expectedDomain, long expectedEpoch) {
        if (!Thread.holdsLock(admissionLock)) {
            throw new IllegalStateException(
                    "main-safe body transaction opened without admissionLock");
        }
        if (domain != expectedDomain
                || (expectedDomain != null
                    && expectedDomain.epoch != expectedEpoch)) {
            return false;
        }
        return tryOpenClaimTransaction();
    }

    /** Exact terminal of a successful tryClaim, after its complete tail. */
    void finishClaimTransaction() {
        for (;;) {
            long state = claimTransactionState.get();
            long active = state & ~CLAIM_REBASE_CLOSED;
            if (active < CLAIM_TRANSACTION_UNIT) {
                throw new IllegalStateException(
                        "region claim transaction completed without an open receipt");
            }
            if (claimTransactionState.compareAndSet(
                    state, state - CLAIM_TRANSACTION_UNIT)) return;
        }
    }

    /** Caller owns admissionLock. Linearization point which fences fresh claims. */
    boolean tryCloseClaimTransactionsForDomainRebase() {
        if (!Thread.holdsLock(admissionLock)) {
            throw new IllegalStateException(
                    "domain rebase claim cut published without admissionLock");
        }
        for (;;) {
            long state = claimTransactionState.get();
            if ((state & CLAIM_REBASE_CLOSED) != 0L) return false;
            if (claimTransactionState.compareAndSet(
                    state, state | CLAIM_REBASE_CLOSED)) return true;
        }
    }

    boolean claimTransactionsDrainedForDomainRebase() {
        return claimTransactionState.get() == CLAIM_REBASE_CLOSED;
    }

    int activeClaimTransactions() {
        long active = claimTransactionState.get() >>> 1;
        return active > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) active;
    }

    boolean claimRebaseClosed() {
        return (claimTransactionState.get() & CLAIM_REBASE_CLOSED) != 0L;
    }

    /** Caller owns admissionLock; domain-last commit and seal release precede this. */
    void reopenClaimTransactionsAfterDomainRebase() {
        if (!Thread.holdsLock(admissionLock)) {
            throw new IllegalStateException(
                    "domain rebase claim cut reopened without admissionLock");
        }
        if (!claimTransactionState.compareAndSet(CLAIM_REBASE_CLOSED, 0L)) {
            throw new IllegalStateException(
                    "domain rebase claim cut reopened with live transactions");
        }
    }

    /**
     * Rebase-owner-only claim. Ordinary claim admission is already closed and
     * every old transaction terminal; this claim settles the finite pre-cut
     * FIFO without opening a new old-domain successor transaction.
     */
    boolean tryClaimForDomainRebase(
            Thread worker, EpochDomain expectedDomain, long expectedEpoch) {
        if (worker == null) throw new NullPointerException("worker");
        if (!Thread.holdsLock(admissionLock)
                || !claimTransactionsDrainedForDomainRebase()) {
            throw new IllegalStateException(
                    "domain rebase queue claim without a closed, drained claim cut");
        }
        if (expectedDomain != null && expectedDomain.epoch != expectedEpoch) {
            return false;
        }
        if (!currentWorker.compareAndSet(null, worker)) return false;
        EpochDomain observedDomain = domain;
        if (observedDomain != expectedDomain
                || (observedDomain != null
                    && (observedDomain.epoch != expectedEpoch
                        || sealedDoneEpoch >= expectedEpoch))) {
            if (!currentWorker.compareAndSet(worker, null)) {
                throw new IllegalStateException(
                        "domain rebase queue claim changed while self-refusing");
            }
            return false;
        }
        claimSeq++;
        return true;
    }

    /** Caller owns admissionLock. Post-cut task stays behind domain publication. */
    boolean deferTaskForDomainRebase(Runnable task) {
        if (!Thread.holdsLock(admissionLock)) {
            throw new IllegalStateException(
                    "domain rebase task deferral without admissionLock");
        }
        if (!claimRebaseClosed()) return false;
        domainRebaseDeferredTasks.addLast(task);
        return true;
    }

    /** Failed-generation sweep only. Caller owns {@link #admissionLock}. */
    Runnable pollDomainRebaseDeferredTaskForCancellation() {
        if (!Thread.holdsLock(admissionLock)) {
            throw new IllegalStateException(
                    "domain rebase deferred cancellation without admissionLock");
        }
        return domainRebaseDeferredTasks.pollFirst();
    }

    /** Failed-generation sweep only. Caller owns {@link #admissionLock}. */
    void clearDomainRebaseDeferredReadyForCancellation() {
        if (!Thread.holdsLock(admissionLock)) {
            throw new IllegalStateException(
                    "domain rebase deferred-ready cancellation without admissionLock");
        }
        domainRebaseDeferredReady = false;
    }

    /** Failed-generation fixed-point inspection. Caller owns admissionLock. */
    boolean domainRebaseDeferredAdmissionsEmpty() {
        if (!Thread.holdsLock(admissionLock)) {
            throw new IllegalStateException(
                    "domain rebase deferred inspection without admissionLock");
        }
        return domainRebaseDeferredTasks.isEmpty() && !domainRebaseDeferredReady;
    }

    /** Caller owns admissionLock. Returns true when the nudge was deferred. */
    boolean deferReadyForDomainRebase() {
        if (!Thread.holdsLock(admissionLock)) {
            throw new IllegalStateException(
                    "domain rebase ready deferral without admissionLock");
        }
        if (!claimRebaseClosed()) return false;
        domainRebaseDeferredReady = true;
        return true;
    }

    /**
     * Caller owns admissionLock. Move every accepted post-cut task behind the
     * new domain before reopening claim admission; returns whether one fresh
     * ready publication is required after leaving the monitor.
     */
    boolean flushDomainRebaseDeferredAdmissions() {
        if (!Thread.holdsLock(admissionLock)) {
            throw new IllegalStateException(
                    "domain rebase admission flush without admissionLock");
        }
        boolean moved = false;
        Runnable task;
        // Keep the accepted obligation owned by this lane until its destination
        // publication succeeds.  poll-before-submit loses the only ExternalTask
        // permit if CLQ allocation fails.
        while ((task = domainRebaseDeferredTasks.peekFirst()) != null) {
            queue.submit(task);
            domainRebaseDeferredTasks.removeFirst();
            moved = true;
        }
        boolean ready = moved || domainRebaseDeferredReady
                || !queue.isEmpty() || !helpQueue.isEmpty();
        domainRebaseDeferredReady = false;
        return ready;
    }

    void release(Thread worker) {
        currentWorker.compareAndSet(worker, null);
    }

    public Thread currentWorker() {
        Object holder = currentWorker.get();
        return holder instanceof Thread thread ? thread : null;
    }

    /** Admission/retirement view: unlike currentWorker(), includes internal seals. */
    boolean hasClaimOrDoneSeal() {
        return currentWorker.get() != null;
    }

    /**
     * Called with {@link #admissionLock} held. The epoch read is the fast
     * admission refusal; the authoritative post-CAS validation lives in
     * ColorGate. The domain clock may advance after that validation while this
     * exact old-epoch seal remains held.
     */
    boolean tryBeginDoneSeal(long epoch) {
        EpochDomain observedDomain = domain;
        return observedDomain != null && observedDomain.epoch == epoch
                && currentWorker.compareAndSet(null, DONE_SEAL);
    }

    /**
     * Publish the validated epoch while DONE_SEAL is still held. An autonomous
     * pacer may legally advance {@code expectedDomain.epoch} after ColorGate's
     * post-CAS validation; that does not revoke this seal's authority to stamp
     * its old epoch. The monotonic CAS also prevents that old tail from
     * regressing a direct next-epoch done writer.
     */
    void commitDoneSeal(EpochDomain expectedDomain, long epoch) {
        if (expectedDomain == null || currentWorker.get() != DONE_SEAL
                || domain != expectedDomain) {
            throw new IllegalStateException(
                    "region done seal lost its exact claim/domain ownership");
        }
        long observedDone = doneEpoch;
        while (observedDone < epoch
                && !DONE_EPOCH.compareAndSet(this, observedDone, epoch)) {
            observedDone = doneEpoch;
        }
        // Final publication token: readers which observe this volatile stamp
        // also observe the preceding doneEpoch CAS and all completed work.
        sealedDoneEpoch = Math.max(sealedDoneEpoch, epoch);
    }

    /** Cleanup after either a committed or refused done publication. */
    void releaseDoneSeal() {
        if (!currentWorker.compareAndSet(DONE_SEAL, null)) {
            throw new IllegalStateException("region done seal was not held");
        }
    }

    boolean hasSealedDone(long epoch) {
        return sealedDoneEpoch >= epoch && doneEpoch >= epoch;
    }
    /**
     * Publish the complete epoch baseline before this region becomes visible
     * in the regionizer map. A region admitted before the current entity
     * dispatch membership cut must remain eligible for that decision
     * ({@code E - 1}); one admitted after the cut belongs to the next epoch
     * ({@code E}). Initializing the enqueue and contribution CAS gates with
     * the same baseline also prevents a post-cut region, which was not counted
     * in {@code entityPending}, from contributing to that already-armed barrier.
     */
    void initializeDomainAtCreation(EpochDomain target) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (domain != null) {
            throw new IllegalStateException("region creation domain already initialized");
        }
        long epoch = target.epoch;
        long baselineEpoch =
                target.entityDispatchCutEpoch == epoch ? epoch : epoch - 1L;
        sealedDoneEpoch = baselineEpoch;
        doneEpoch = baselineEpoch;
        groupedEpoch = baselineEpoch;
        entityEpoch = baselineEpoch;
        entityEnqueuedEpoch.set(baselineEpoch);
        entityBarrierEpoch.set(baselineEpoch);
        // Volatile publication is last: map readers see one coherent baseline.
        domain = target;
    }

    /**
     * Close claim admission for an exact domain/epoch rebase.  The caller also
     * owns {@link #admissionLock}; failure means its advertised topology safe
     * point was false and must be fatal rather than repaired over a live owner.
     */
    boolean tryBeginDomainRebaseSeal() {
        return currentWorker.compareAndSet(null, DOMAIN_REBASE_SEAL);
    }

    /** Fixed-field safe-point commit while DOMAIN_REBASE_SEAL remains held. */
    void commitDomainRebaseSeal(EpochDomain target, long baselineEpoch) {
        if (target == null || currentWorker.get() != DOMAIN_REBASE_SEAL) {
            throw new IllegalStateException(
                    "region domain rebase lost its exclusive seal");
        }
        sealedDoneEpoch = baselineEpoch;
        doneEpoch = baselineEpoch;
        groupedEpoch = baselineEpoch;
        entityEpoch = baselineEpoch;
        entityEnqueuedEpoch.set(baselineEpoch);
        entityBarrierEpoch.set(baselineEpoch);
        // Volatile publication is LAST: any delayed old-domain claimant which
        // acquires after seal release sees the complete new baseline.
        domain = target;
    }

    void releaseDomainRebaseSeal() {
        if (!currentWorker.compareAndSet(DOMAIN_REBASE_SEAL, null)) {
            throw new IllegalStateException("region domain rebase seal was not held");
        }
    }

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

    @Override
    public String toString() {
        return "Region#" + id + "[key=" + Long.toHexString(gridKey) + ", "
                + level.dimension().identifier() + ", observed=" + observedChunkCount() + "]";
    }
}
