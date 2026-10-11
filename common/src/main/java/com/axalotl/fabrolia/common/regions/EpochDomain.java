package com.axalotl.fabrolia.common.regions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A2 — one color-DAG scheduling timeline: an epoch counter plus the
 * entity-phase barrier armed against it.
 *
 * <p>Every {@link Region} belongs to exactly one domain ({@link Region#domain});
 * ALL eligibility, completion and barrier bookkeeping compare the region's
 * epoch stamps against ITS domain — never a global counter. Sync mode
 * (asyncIslands off): a single instance per level
 * ({@code RegionizedServer.globalDomain}) — field-for-field the state that
 * used to live directly on RegionizedServer, so behavior is identical.
 * Async mode: each persistent island ({@link IslandRegistry.PIsland#domain})
 * advances its own epoch once per island tick unit, so two islands ticking
 * at different rates never share a barrier, an eligibility horizon, or an
 * entity tick token. Unpopulated gap regions stay on the global domain.
 *
 * <p>The island separation invariant (≥2 unpopulated cells between islands,
 * radius-2 connectivity) guarantees a region's radius-1 ColorGate neighbors
 * are either same-domain or gap regions; cross-domain neighbors are excluded
 * from the gate (see ColorGate) because no same-tick causal chain crosses
 * the gap.
 *
 * <p>Stamp discipline: a region's sealedDoneEpoch/doneEpoch/entityEpoch/
 * groupedEpoch and the CAS gates (entityEnqueuedEpoch/entityBarrierEpoch) are
 * meaningful only in the timeline of the domain that stamped them. Whoever
 * reassigns {@link Region#domain} MUST reset all six through
 * RegionizedServer.rebaseDomainEpochAtSafePoint, and may only do so at a safe
 * point (no unit/dispatch in flight for either domain).
 */
final class EpochDomain {

    private static final long TICK_TOKEN_EPOCH_MASK = (1L << 48) - 1L;
    /** Distinct prefix per domain: an entity's dedup token can never collide
     *  across domains even when two timelines pass through equal epoch values
     *  (e.g. after a merge/split reassignment). Starts at 1L<<48 so token 0
     *  (the unmarked state) is never produced. */
    private static final AtomicLong NEXT_TICK_TOKEN_PREFIX = new AtomicLong(1L << 48);

    /** Color-DAG epoch — bumped once per drive (level tick in sync mode,
     *  island tick unit in async mode), read by the eligibility gate and
     *  completion marks of THIS domain's regions. */
    volatile long epoch;

    /** Epoch whose entity dispatch has run (groupedEpochs stamped, sweep done).
     *  Done-publication is FORBIDDEN before this matches {@link #epoch}: until
     *  dispatch decides who owes an entity phase, "complete" is undecidable —
     *  the early-done window was the certified crossUNSAFE leak (2026-06-12). */
    volatile long dispatchedEpoch;

    /**
     * Epoch whose region-map membership was closed for entity dispatch.
     * Region creation for a cell of this domain holds {@link #regionCreationGate}
     * READ; dispatch records its finite member snapshot and this cut while
     * holding the matching WRITE (the global domain's cut holds the level-wide
     * topology WRITE instead, which excludes every creator). A creator admitted
     * before the cut is captured and starts at E-1. One admitted after it was
     * not counted in the barrier and starts at E.
     */
    volatile long entityDispatchCutEpoch = Long.MIN_VALUE;

    /**
     * Per-domain shard of the region-creation cut. Island units of different
     * domains close their membership concurrently: a creator for a cell of
     * domain X can never become a member of domain Y's cut, so excluding it
     * there was pure serialization (2026-10-03 production: 27 s of workers
     * parked on the one level-wide fair WRITE lock). Lock order: level
     * topology READ, then this READ; the WRITE side never takes the level lock.
     * Fair for the same reason as the level gate: continuous creation must not
     * starve a cut.
     */
    final java.util.concurrent.locks.ReentrantReadWriteLock regionCreationGate =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);

    /** Completed by the LAST owing region's barrier contribution (R5 event path). */
    volatile CompletableFuture<Void> entityBarrier;

    /** Queue-only decision and exact join which admit an island's serial entity pass. */
    volatile long queueOnlyEntityEpoch = Long.MIN_VALUE;
    volatile long islandEntityQueueEpoch = Long.MIN_VALUE;
    volatile CompletableFuture<Void> islandEntityQueueBarrier;

    /** Owing regions still to contribute this epoch. */
    final AtomicInteger entityPending = new AtomicInteger();

    /** Epoch whose dispatch selected the event protocol. The desired config may
     *  change mid-dispatch, but execution and completion keep this decision. */
    volatile long entityBarrierArmedEpoch = -1L;

    /** Watchdog anchor for stall forensics. */
    volatile long entityBarrierArmedNanos;

    /**
     * Member list of the dispatch that armed {@link #dispatchedEpoch}
     * (participants and swept non-participants), published with that
     * dispatch and never mutated afterwards: the dirty set of Main's heal
     * backstop for this domain ({@code RegionScheduler.healDomainScope}).
     */
    volatile java.util.List<Region> healScope = java.util.List.of();

    /**
     * Publication receipt for the current entity-dispatch generation. Ready
     * entries may be visible before every group/region has been offered, but a
     * consumer cannot claim one until the dispatcher accepts the whole plan.
     */
    volatile EntityDispatchPublication entityDispatchPublication =
            EntityDispatchPublication.ACCEPTED_PUBLICATION;

    /**
     * Mode witness under the one-dispatch-per-domain/epoch contract, not an
     * acceptance or pending-work test. A completed event epoch stays an event
     * epoch; a later legacy/queue-only epoch cannot borrow its barrier.
     */
    boolean isEventEntityEpoch(long expectedEpoch) {
        return epoch == expectedEpoch && entityBarrierArmedEpoch == expectedEpoch;
    }

    static final class EntityDispatchPublication {
        private static final int OFFERING = 0;
        private static final int ACCEPTED = 1;
        private static final int CANCELLED = 2;
        private static final EntityDispatchPublication ACCEPTED_PUBLICATION =
                new EntityDispatchPublication(ACCEPTED);

        private final java.util.concurrent.atomic.AtomicInteger state;
        /**
         * Exact ready-lane receipts belonging to this publication object, not
         * merely to its domain. Benign duplicate ready entries may outlive the
         * barrier they helped complete; keeping the counter generation-local
         * lets those old entries close harmlessly after the next dispatch has
         * installed a different publication.
         */
        private final java.util.concurrent.atomic.AtomicInteger readyReceipts =
                new java.util.concurrent.atomic.AtomicInteger();

        /**
         * Consumers parked in {@link #awaitAccepted}: a Treiber stack drained by
         * the decision. Parking used to be a 1 us timed poll, which Linux timer
         * slack turns into ~50 us per round — every ready consumer that popped
         * an entry before the dispatcher finished its sweep started that much
         * late (2026-10-03 production: 15 s parked here over 3 min).
         */
        private final java.util.concurrent.atomic.AtomicReference<Waiter> waiters =
                new java.util.concurrent.atomic.AtomicReference<>();

        private static final class Waiter {
            final Thread thread;
            Waiter next;

            Waiter(Thread thread) {
                this.thread = thread;
            }
        }

        EntityDispatchPublication() {
            this(OFFERING);
        }

        private EntityDispatchPublication(int initial) {
            state = new java.util.concurrent.atomic.AtomicInteger(initial);
        }

        void accept() {
            if (!state.compareAndSet(OFFERING, ACCEPTED)) {
                throw new IllegalStateException("entity dispatch publication was already decided");
            }
            wakeWaiters();
        }

        void cancel() {
            if (state.compareAndSet(OFFERING, CANCELLED)) wakeWaiters();
        }
        boolean isAccepted() {
            return state.get() == ACCEPTED;
        }

        /** After the decision write: a waiter pushed later re-reads the decided state before parking. */
        private void wakeWaiters() {
            for (Waiter w = waiters.getAndSet(null); w != null; w = w.next) {
                java.util.concurrent.locks.LockSupport.unpark(w.thread);
            }
        }

        boolean awaitAccepted() {
            int observed;
            int spins = 64;
            Waiter self = null;
            while ((observed = state.get()) == OFFERING) {
                if (spins-- > 0) {
                    Thread.onSpinWait();
                } else if (self == null) {
                    // Register, then re-check the state before the first park:
                    // the decision writes state before draining the stack.
                    self = new Waiter(Thread.currentThread());
                    Waiter head;
                    do {
                        head = waiters.get();
                        self.next = head;
                    } while (!waiters.compareAndSet(head, self));
                } else {
                    // This receipt can only become ACCEPTED/CANCELLED; parking is
                    // not a timeout or refusal. The decision unparks every
                    // registered consumer; the bound only covers a spurious
                    // or consumed permit.
                    java.util.concurrent.locks.LockSupport.parkNanos(this, 100_000L);
                }
            }
            return observed == ACCEPTED;
        }

        /** Open before linking the tagged ready node into its CLQ. */
        void openReadyReceipt() {
            int outstanding = readyReceipts.incrementAndGet();
            if (outstanding <= 0) {
                throw new IllegalStateException(
                        "entity-ready receipt counter overflow");
            }
        }

        /** Close once, after publication cancellation or the full consumer tail. */
        void closeReadyReceipt() {
            int outstanding = readyReceipts.decrementAndGet();
            if (outstanding < 0) {
                throw new IllegalStateException(
                        "entity-ready receipt counter underflow");
            }
        }

        boolean hasReadyReceipts() {
            return readyReceipts.get() > 0;
        }

        int readyReceiptCount() {
            return readyReceipts.get();
        }
    }

    /** Vanilla ServerLevel.handlingTick equivalent for THIS timeline
     *  (2026-07-15 piston D1 fix): true while the unit runs its scheduled-
     *  tick + block-event segments (vanilla phases 0-6), false during the
     *  entity/BE segments (vanilla 7-10). PistonBaseBlock.checkIfExtend's
     *  retract-code disambiguation (b0=1 pull-back vs b0=2 TRIGGER_DROP)
     *  reads isHandlingTick — un-redirected, island workers read MAIN's
     *  flag at an arbitrary point of MAIN's own tick: a per-cycle coin flip
     *  that yanked duper sand back instead of dropping it (source loss in
     *  ~5 cycles on the production machine). */
    volatile boolean handlingTickPhase;

    /** Cross-dimension arrival staging (2026-07-15 cohort atomicity): the
     *  destination-side halves of teleports executed during THIS domain's
     *  phases, sealed into one cohort batch at phase end. */
    final java.util.concurrent.ConcurrentLinkedQueue<CrossDimArrivals.StagedOp> crossDimStage =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Cross-region effects deferred INTO this timeline (CrossRegionGuard,
     *  routed by target position). Global domain: replayed by main after the
     *  phase pump (the pre-A2 behavior, unchanged in sync mode). Island
     *  domains: replayed by the island's unit coordinator between its entity
     *  barrier and its BE phase — same island-internal ordering the main
     *  replay gave ([entities → replay → BEs]), all member regions quiescent,
     *  and no other island reachable by construction. MPSC-ish: workers offer
     *  during phases, exactly one drainer per domain per tick. */
    final java.util.concurrent.ConcurrentLinkedQueue<Runnable> deferredOps =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** A2 L-clock: the island-local game time presented to getGameTime readers
     *  executing under this domain (set to L+1 at unit dispatch — the tick
     *  being executed, matching vanilla's increment-at-head convention; stays
     *  put between units, where it equals the completed-ticks count, exactly
     *  what vanilla getGameTime reads between ticks). 0 = no unit has ever run
     *  (global timeline / sync mode) → readers fall through to the level's
     *  real game time. */
    volatile long unitGameTime;

    // Flight-recorder streak state (coordinator-only writes, one unit at a time):
    // strong-signal gating so production never dumps for expected fringe aging.
    long overdueStreakChunkB; int overdueStreakB;
    long overdueStreakChunkF; int overdueStreakF;
    int budgetExhaustStreak;

    /** The island this timeline belongs to (null = the global domain). Lets
     *  registration-time seams route by execution context (e.g. mid-unit BE
     *  registrations into the island's same-tick pending list).
     *  audit13: volatile — written once at island construction, but READ from
     *  arbitrary threads with no other happens-before edge (vibration emitters
     *  via VibrationDomains and cross-dim route()'s owner resolution). A
     *  reader reaching the domain through a non-volatile path could observe
     *  null and mis-resolve ownership (wall-clock stamp / global-lane detour)
     *  for that read; every sibling field consumed cross-thread here is
     *  already volatile. */
    volatile IslandRegistry.PIsland owner;

    private volatile long tickTokenPrefix = NEXT_TICK_TOKEN_PREFIX.getAndAdd(1L << 48);

    /**
     * audit10 — adopt {@code pred}'s CURRENT tick identity, so a mark stamped before
     * an island transition still compares as already-ticked immediately after it.
     *
     * <p>Both halves are required. {@link #tickToken()} is
     * {@code tickTokenPrefix | (epoch & MASK)}: inheriting the prefix alone leaves
     * the epochs free to differ, and the token would still not match.
     *
     * <p>This CANNOT freeze an entity. The token embeds the epoch, so the
     * successor's very next {@code epoch++} yields a different token and every
     * entity is eligible again. An inherited mark can therefore suppress at most
     * the single unit that was already accounted for under the predecessor — never
     * a later one.
     *
     * <p>Called at a safe point during rebuild/split, before the successor is
     * dispatched, so no worker is reading these fields concurrently.
     */
    void adoptTickIdentityFrom(EpochDomain pred) {
        if (pred == null || pred == this) return;
        this.tickTokenPrefix = pred.tickTokenPrefix;
        this.epoch = pred.epoch;
    }

    /** Per-entity dedup token for the current epoch (an entity migrating
     *  between two regions ticking in the same epoch runs exactly once). */
    long tickToken() {
        return tickTokenPrefix | (epoch & TICK_TOKEN_EPOCH_MASK);
    }
}
