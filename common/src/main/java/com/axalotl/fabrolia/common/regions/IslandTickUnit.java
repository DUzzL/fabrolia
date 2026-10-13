package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.LockSupport;

/**
 * A2 — one island's independent tick unit, executed as a COORDINATOR task on a
 * region worker (docs/ASYNC-ISLANDS.md; the a2 checkpoint tracks increments).
 *
 * <p>Per unit, all under the island's claim, all on the island's own
 * {@link EpochDomain} timeline (its epoch, its barrier, its entity tick token,
 * its L-clock via the getGameTime redirect):
 *
 * <ol>
 *   <li><b>chunk pass</b> — after this island's scheduled ticks and raids,
 *       before its block events (vanilla's chunkSource.tick slot): natural
 *       spawning for the island's spawnable chunks, then random/fluid/thunder
 *       ticks for its entity-ticking chunks, at the island's cadence on its
 *       L-clock ({@link IslandChunkPassAccess}). Main never arms island-owned
 *       regions; the global mob cap reads the summed {@link SpawnCapLanes}.</li>
 *   <li><b>entity phase</b> — join member-region queues, then visit one
 *       finite snapshot of Vanilla's linked EntityTickList in insertion order
 *       under the exclusive unit claim. IslandEntityPhaseScope authenticates
 *       the successful queue join and permits complete block callbacks inline
 *       throughout the island and its halo. Independent islands stay parallel;
 *       nonlocal compatibility ticks retain the fenced Main fallback.</li>
 *   <li><b>deferred cross-region replay</b> — effects that crossed member
 *       region borders during the phase, routed here by target; replayed
 *       single-threaded with every member region quiescent — the exact
 *       island-internal ordering the old main replay gave.</li>
 *   <li><b>BE phase</b> — the island's cells from the persistent BE index,
 *       ascending key order, plus BEs registered mid-unit (vanilla ticks
 *       those the same tick via its direct-list add). Hoppers & co. read the
 *       island's L from getGameTime.</li>
 *   <li><b>bookkeeping</b> — L++, real measured cost, deadline roll (overrun
 *       reschedules from completion: only this island slips below 20 TPS),
 *       claim release.</li>
 * </ol>
 *
 * <p>The deadline drive selects WHICH islands run instead of running all
 * every level tick.
 */
public final class IslandTickUnit {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-A2");
    static final long TICK_NANOS = 50_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong STALE_FOLLOWERS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STALE_FOLLOWERS_REPORTED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STALE_FOLLOWER_LAST_LOG_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long STALE_FOLLOWER_LOG_INTERVAL_NANOS = 10_000_000_000L;

    private IslandTickUnit() {}

    /** Authoritative result of one publication attempt.  In particular,
     *  {@link AttemptOutcome#ADVANCED} is written in the same scheduler cut as the sole L++;
     *  it is not inferred from a wake-up future. */
    enum AttemptOutcome {
        NO_BODY,
        HOLD,
        ADVANCED,
        RETIRED
    }

    /**
     * Poll-only unit receipt.  It deliberately is not a CompletableFuture:
     * completing a future invokes arbitrary synchronous callbacks, so a peer W
     * could win after the completion permit but before those callbacks entered.
     * The outcome below is committed under the scheduler lifecycle cut and
     * terminal publication invokes no callback and enqueues no continuation;
     * it only wakes the one registered pacer (a flag write and a bare unpark;
     * the pacer then polls under its own envelope).
     */
    public static final class AttemptReceipt {
        private final java.util.concurrent.atomic.AtomicBoolean terminal =
                new java.util.concurrent.atomic.AtomicBoolean();
        private volatile AttemptOutcome outcome;
        private volatile long fromLogicalTick = Long.MIN_VALUE;
        private volatile long toLogicalTick = Long.MIN_VALUE;
        private volatile Throwable failure;
        private volatile TemporalControl.Pacer watcher;

        private AttemptReceipt() {}

        public boolean isDone() {
            return terminal.get();
        }

        /** Wake {@code pacer} at the terminal edge. Register BEFORE the next
         *  {@link #isDone} read: the edge then either precedes that read or
         *  observes the registration. */
        void wakeOnTerminal(TemporalControl.Pacer pacer) {
            watcher = pacer;
        }

        AttemptOutcome outcome() {
            return outcome;
        }

        long fromLogicalTick() {
            return fromLogicalTick;
        }

        long toLogicalTick() {
            return toLogicalTick;
        }

        Throwable failureOrNull() {
            return failure;
        }

        void requireUncommitted() {
            if (outcome != null || terminal.get()) {
                throw new IllegalStateException("island unit receipt was already committed");
            }
        }

        /** Caller validated before entering the lifecycle cut; fixed writes only. */
        void commitOutcome(AttemptOutcome committed, long fromL, long toL) {
            fromLogicalTick = fromL;
            toLogicalTick = toL;
            outcome = committed; // final publication token for the result fields
        }

        /** Fixed terminal publication only: no callback or queue mutation can
         *  escape the lifecycle success cut and race a later W. */
        void finishSuccessfully() {
            if (!terminal.compareAndSet(false, true)) {
                throw new IllegalStateException("island unit receipt finished twice");
            }
            wakeWatcher();
        }

        /** Failure cleanup after the exact non-null W was selected: fixed
         *  publication only, with no callback, allocation or retry path. */
        void finishAfterFailure(Throwable selected) {
            if (selected == null) throw new NullPointerException("selected");
            failure = selected;
            if (terminal.compareAndSet(false, true)) wakeWatcher();
        }

        private void wakeWatcher() {
            TemporalControl.Pacer waiting = watcher;
            if (waiting != null) waiting.wake();
        }

        public void throwIfFailed() {
            Throwable selected = failure;
            if (selected != null) IslandTickUnit.<RuntimeException>rethrow(selected);
            RegionScheduler.throwWorkerFailureIfAny();
        }
    }

    /** One exact global-sprint request, prepared while Main holds the island
     *  claim and published only after every older ordinary submission drained. */
    static final class GlobalSprintRequest {
        final RegionizedServer runtime;
        final IslandRegistry.PIsland island;
        final long fromLogicalTick;
        final long toLogicalTick;
        final AttemptReceipt receipt;
        final UnitPublication publication;
        final Runnable queuedUnit;

        private GlobalSprintRequest(RegionizedServer runtime,
                                    IslandRegistry.PIsland island,
                                    long fromLogicalTick) {
            this.runtime = runtime;
            this.island = island;
            this.fromLogicalTick = fromLogicalTick;
            this.toLogicalTick = Math.addExact(fromLogicalTick, 1L);
            this.receipt = new AttemptReceipt();
            this.publication = new UnitPublication(runtime, island, receipt,
                    fromLogicalTick, false, Long.MIN_VALUE, 0L, this);
            this.queuedUnit = RegionScheduler.cancellableRoot(
                    publication, publication::cancelBeforeStart);
        }

        boolean tryClaim() {
            synchronized (island.unitAdmissionLock) {
                if (!runtime.asyncActive || island.retired
                        || island.globalSprintRequest != this || !island.globalSprintFenced) {
                    throw new IllegalStateException("global sprint unit lost its exact claim reservation");
                }
                // The sprint fence itself suspends transfers. Its exact reserved
                // unit must not wait on a transfer prelude which waits on that fence.
                return island.unitClaim.compareAndSet(false, true);
            }
        }

        void start() {
            try {
                if (!RegionScheduler.enqueueUnit(runtime, queuedUnit)) {
                    throw new IllegalStateException(
                            "global sprint unit publication was refused by its live generation");
                }
                publication.accept();
            } catch (Throwable publicationFailure) {
                Throwable failure = RegionScheduler.publishFatalAndSelectWinner(
                        runtime, publicationFailure);
                try {
                    publication.cancelBeforeStart(failure);
                } catch (Throwable cleanupFailure) {
                    failure = publishMergedFailure(failure, cleanupFailure);
                }
                if (queuedUnit instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                    try {
                        cancellable.cancelQueuedBeforeStartWithFailure(failure);
                    } catch (Throwable cleanupFailure) {
                        failure = publishMergedFailure(failure, cleanupFailure);
                    }
                }
                IslandTickUnit.<RuntimeException>rethrow(failure);
            }
        }
    }

    static void installGlobalSprintFence(RegionizedServer runtime,
                                         IslandRegistry.PIsland island) {
        synchronized (island.unitAdmissionLock) {
            if (!runtime.asyncActive || island.retired
                    || island.globalSprintFenced
                    || island.globalSprintRequest != null) {
                throw new IllegalStateException(
                        "cannot install a fresh global sprint fence for island#"
                                + island.id);
            }
            island.globalSprintFenced = true;
        }
    }

    static GlobalSprintRequest prepareGlobalSprintRequest(
            RegionizedServer runtime, IslandRegistry.PIsland island) {
        long fromLogicalTick;
        synchronized (island.unitAdmissionLock) {
            if (!runtime.currentThreadHoldsAllClaims() || !island.unitClaim.get()
                    || !island.globalSprintFenced
                    || island.globalSprintRequest != null
                    || island.submitQueued.get()
                    || !runtime.asyncActive || island.retired) {
                throw new IllegalStateException(
                        "global sprint request was not prepared at an exact quiescent boundary"
                                + " for island#" + island.id);
            }
            fromLogicalTick = island.logicalTick;
        }
        // Allocation/wrapper preparation is outside the admission monitor.
        // Main still owns unitClaim and the installed fence excludes every
        // ordinary publication, so fromL cannot move in this interval.
        GlobalSprintRequest request = new GlobalSprintRequest(
                runtime, island, fromLogicalTick);
        synchronized (island.unitAdmissionLock) {
            if (!runtime.currentThreadHoldsAllClaims() || !island.unitClaim.get()
                    || !island.globalSprintFenced
                    || island.globalSprintRequest != null
                    || island.submitQueued.get()
                    || island.logicalTick != fromLogicalTick
                    || !runtime.asyncActive || island.retired) {
                throw new IllegalStateException(
                        "global sprint boundary changed while preparing island#"
                                + island.id);
            }
            if (!island.submitQueued.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "global sprint lost its reserved unit publication for island#"
                                + island.id);
            }
            island.globalSprintRequest = request;
            return request;
        }
    }

    static void closeGlobalSprintRequest(GlobalSprintRequest request) {
        AttemptReceipt receipt = request.receipt;
        receipt.throwIfFailed();
        synchronized (request.island.unitAdmissionLock) {
            IslandRegistry.PIsland island = request.island;
            if (island.globalSprintRequest != request
                    || !island.globalSprintFenced
                    || island.submitQueued.get()
                    || !receipt.isDone()
                    || receipt.outcome() != AttemptOutcome.ADVANCED
                    || receipt.fromLogicalTick() != request.fromLogicalTick
                    || receipt.toLogicalTick() != request.toLogicalTick
                    || island.logicalTick != request.toLogicalTick) {
                throw new IllegalStateException(
                        "global sprint receipt did not prove exactly one L advance"
                                + " for island#" + island.id);
            }
            island.globalSprintRequest = null;
            island.globalSprintFenced = false;
        }
    }

    static Throwable cancelGlobalSprintRequest(GlobalSprintRequest request,
                                                Throwable primary) {
        Throwable failure = primary;
        try {
            request.publication.cancelBeforeStart(failure);
        } catch (Throwable cleanupFailure) {
            failure = publishMergedFailure(failure, cleanupFailure);
        }
        if (request.queuedUnit instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
            try {
                cancellable.cancelQueuedBeforeStartWithFailure(failure);
            } catch (Throwable cleanupFailure) {
                failure = publishMergedFailure(failure, cleanupFailure);
            }
        }
        // start1 may already be RUNNING when publication/start2 selects W.
        // cancelBeforeStart is deliberately a no-op for that state: Main must
        // not forge a terminal receipt while the worker still owns unitClaim or
        // another mandatory unit cleanup resource. Wait for runCoordinated's
        // natural failure receipt and UnitPublication's outer TERMINAL marker.
        // This loop executes no Main/chunk/routed success body after W.
        while (!request.publication.isTerminalDisposition()) {
            Thread.onSpinWait();
            LockSupport.parkNanos(20_000L);
        }
        if (!request.receipt.isDone()) {
            failure = publishMergedFailure(failure, new IllegalStateException(
                    "terminal global sprint publication retained a live receipt"
                            + " for island#" + request.island.id));
            // The publication is now terminal, so this is failure notification
            // after mandatory execution cleanup, never an early forged wake.
            request.receipt.finishAfterFailure(failure);
        }
        int ownershipFault = 0;
        try {
            synchronized (request.island.unitAdmissionLock) {
                if (request.island.globalSprintRequest == request) {
                    if (!request.island.globalSprintFenced) {
                        ownershipFault = 1;
                    }
                    // A terminal exact publication owns this reservation. No
                    // ordinary publisher can replace it while the runtime fence
                    // remains installed, so clearing a retained bit is exact
                    // failure cleanup rather than forged completion.
                    request.island.submitQueued.set(false);
                    request.island.globalSprintRequest = null;
                    request.island.globalSprintFenced = false;
                } else if (request.island.globalSprintRequest != null
                        || request.island.globalSprintFenced) {
                    ownershipFault = 2;
                }
            }
        } catch (Throwable cleanupFailure) {
            failure = publishMergedFailure(failure, cleanupFailure);
        }
        if (ownershipFault != 0) {
            failure = publishMergedFailure(failure, new IllegalStateException(
                    (ownershipFault == 1
                            ? "global sprint request lost its owned fence"
                            : "global sprint cleanup refused a foreign fence owner")
                            + " for island#" + request.island.id));
        }
        return failure;
    }

    static Throwable clearGlobalSprintFence(IslandRegistry.PIsland island,
                                              Throwable primary) {
        Throwable failure = primary;
        try {
            synchronized (island.unitAdmissionLock) {
                if (island.globalSprintRequest == null) {
                    island.globalSprintFenced = false;
                }
            }
        } catch (Throwable cleanupFailure) {
            failure = publishMergedFailure(failure, cleanupFailure);
        }
        return failure;
    }

    /**
     * Submit one tick unit for {@code island} to the pool's unit lane.
     * The returned poll-only receipt becomes terminal after the unit's mandatory
     * cleanup. A started unit failure records the exact original Throwable and is
     * propagated to the physical server thread; it is never converted into a
     * successful skipped tick.
     */
    public static AttemptReceipt submitUnit(RegionizedServer rs,
                                            IslandRegistry.PIsland island) {
        AttemptReceipt receipt = new AttemptReceipt();
        try {
            RegionScheduler.runSuccessBodyIfHealthy(() ->
                    submitStampedEntered(rs, island, receipt, island.logicalTick,
                            false, Long.MIN_VALUE, 0L));
        } catch (Throwable submissionFailure) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(
                    rs, submissionFailure);
            receipt.finishAfterFailure(failure);
            IslandTickUnit.<RuntimeException>rethrow(failure);
        }
        return receipt;
    }

    /**
     * Deadline-drive/pacer submission bound to the island's current L frontier.
     * A follower stamped from a running unit's still-unrolled deadline is
     * rejected after claim; the stamp closes the remaining check→claim race.
     */
    public static AttemptReceipt submitDeadlineUnit(RegionizedServer rs,
                                                     IslandRegistry.PIsland island,
                                                     long nowNanos, long slackNanos) {
        AttemptReceipt receipt = new AttemptReceipt();
        try {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                var srv = RegionScheduler.getServer();
                if (srv != null && !srv.tickRateManager().runsNormally()) {
                    commitAndFinishReceipt(receipt, AttemptOutcome.NO_BODY,
                            island.logicalTick, island.logicalTick);
                    return;
                }
                // Capture BEFORE the deadline observation. If a unit starts in the
                // window, its L++ makes this ticket stale; if complete() had already
                // published L++ but not yet rolled the deadline, the exact deadline
                // stamp below changes and closes that second half of the window.
                long submittedAtL = island.logicalTick;
                long deadline = island.nextDeadlineNanos;
                if (deadline != 0 && deadline > nowNanos + slackNanos) {
                    commitAndFinishReceipt(receipt, AttemptOutcome.NO_BODY,
                            submittedAtL, submittedAtL);
                    return;
                }
                submitStampedEntered(rs, island, receipt, submittedAtL,
                        true, deadline, slackNanos);
            });
        } catch (Throwable submissionFailure) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(
                    rs, submissionFailure);
            receipt.finishAfterFailure(failure);
            IslandTickUnit.<RuntimeException>rethrow(failure);
        }
        return receipt;
    }

    /** Caller has won one success-body permit covering the complete submission decision. */
    private static void submitStampedEntered(RegionizedServer rs,
                                             IslandRegistry.PIsland island,
                                             AttemptReceipt receipt,
                                             long submittedAtL,
                                             boolean frontierBound,
                                             long submittedDeadlineNanos,
                                             long deadlineSlackNanos) {
        // /tick freeze parity (2026-07-22): vanilla's frozen tick runs NO
        // simulation (tickTime, LevelTicks, runBlockEvents, tickChunks and the
        // BE pass are all runsNormally-gated), but island units self-drive on
        // real-time deadlines — a frozen server kept ticking islands at full
        // speed (probe: L 315->434 across a 6 s freeze while gt held 318;
        // observer pulses ran against runsNormally-frozen moving-piston BEs
        // and the oscillator wedged — the production flying-machine stop).
        // runsNormally() is the exact per-tick vanilla flag: false while
        // frozen, true during /tick step frames and sprint frames — so this
        // one choke point covers the deadline drive, coherent pin, local
        // sprint chaining, the rate pacer and the manual kick command alike.
        // Deadlines stay due through the freeze; the first post-unfreeze
        // drive resumes every island immediately. Nothing consumed or lost.
        var srv = RegionScheduler.getServer();
        if (srv != null && !srv.tickRateManager().runsNormally()) {
            commitAndFinishReceipt(receipt, AttemptOutcome.NO_BODY,
                    island.logicalTick, island.logicalTick);
            return;
        }
        // DUPLICATE-QUEUE GUARD (2026-07-16 audit follow-up: the reset at the
        // top of runCoordinated existed but this CAS never landed — the guard
        // was dead): a queued-but-unstarted unit must not be enqueued again
        // (unbounded lane + free claim until the task starts → two back-to-
        // back units after pool backlog; via the BE pending/fold gap that
        // stretched a fresh moving-piston lifecycle by one unit).
        boolean refused;
        synchronized (island.unitAdmissionLock) {
            if (island.globalSprintFenced
                    || (rs != null && rs.globalSprintAdmissionFenced)
                    || (srv != null && srv.tickRateManager().isSprinting())) {
                refused = true;
            } else {
                refused = !island.submitQueued.compareAndSet(false, true);
            }
        }
        if (refused) {
            commitAndFinishReceipt(receipt, AttemptOutcome.NO_BODY,
                    island.logicalTick, island.logicalTick);
            return;
        }
        UnitPublication publication = new UnitPublication(rs, island, receipt,
                submittedAtL, frontierBound, submittedDeadlineNanos,
                deadlineSlackNanos, null);
        Runnable queuedUnit = null;
        try {
            queuedUnit = RegionScheduler.cancellableRoot(publication, publication::cancelBeforeStart);
            if (RegionScheduler.ownsExactExecutionTurn()) {
                // The exact actor is already the causal owner; accept before
                // running inline so an OFFERING waiter can never self-deadlock.
                publication.accept();
                RegionScheduler.runSuccessBodyIfHealthy(queuedUnit);
                return;
            }
            if (!RegionScheduler.enqueueUnit(rs, queuedUnit)) {
                // The generation closed before this receipt became visible.
                // No simulation body exists to fail or retry; retire the old
                // obligation locally so it cannot contaminate a later runtime.
                publication.retireBeforeStart();
                return;
            }
            // enqueueUnit may make the wrapper visible before it returns.  Its
            // body waits here until the publisher has an unambiguous receipt.
            publication.accept();
            return;
        } catch (Throwable publicationFailure) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(rs,
                    publicationFailure);
            try {
                publication.cancelBeforeStart(failure);
            } catch (Throwable cleanupFailure) {
                failure = publishMergedFailure(failure, cleanupFailure);
            }
            if (queuedUnit instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                try {
                    cancellable.cancelQueuedBeforeStartWithFailure(failure);
                } catch (Throwable cleanupFailure) {
                    failure = publishMergedFailure(failure, cleanupFailure);
                }
            }
            IslandTickUnit.<RuntimeException>rethrow(failure);
        }
    }

    private static void commitAndFinishReceipt(AttemptReceipt receipt,
                                               AttemptOutcome outcome,
                                               long fromL,
                                               long toL) {
        receipt.requireUncommitted();
        RegionScheduler.commitSuccessIfHealthy(() -> {
            receipt.commitOutcome(outcome, fromL, toL);
            receipt.finishSuccessfully();
        });
    }

    /**
     * Queue-publication receipt for one island-unit root.  A worker that wins
     * the queue race cannot enter runCoordinated while the publisher is still
     * inside enqueueUnit; offer-then-throw therefore cancels an unentered root
     * instead of producing a ghost tick.
     */
    private static final class UnitPublication implements Runnable {
        private static final int OFFERING = 0;
        private static final int ACCEPTED = 1;
        private static final int RUNNING = 2;
        private static final int TERMINAL = 3;
        private static final int CANCELLING = 4;
        private static final int CANCELLED = 5;

        private final RegionizedServer rs;
        private final IslandRegistry.PIsland island;
        private final AttemptReceipt receipt;
        private final long submittedAtL;
        private final boolean frontierBound;
        private final long submittedDeadlineNanos;
        private final long deadlineSlackNanos;
        private final GlobalSprintRequest globalSprintRequest;
        private final java.util.concurrent.atomic.AtomicInteger state =
                new java.util.concurrent.atomic.AtomicInteger(OFFERING);

        private UnitPublication(RegionizedServer rs, IslandRegistry.PIsland island,
                                AttemptReceipt receipt, long submittedAtL,
                                boolean frontierBound, long submittedDeadlineNanos,
                                long deadlineSlackNanos,
                                GlobalSprintRequest globalSprintRequest) {
            this.rs = rs;
            this.island = island;
            this.receipt = receipt;
            this.submittedAtL = submittedAtL;
            this.frontierBound = frontierBound;
            this.submittedDeadlineNanos = submittedDeadlineNanos;
            this.deadlineSlackNanos = deadlineSlackNanos;
            this.globalSprintRequest = globalSprintRequest;
        }

        void accept() {
            if (!state.compareAndSet(OFFERING, ACCEPTED)) {
                throw new IllegalStateException(
                        "island unit publication was already decided");
            }
        }

        void cancelBeforeStart(Throwable failure) {
            while (true) {
                int observed = state.get();
                if (observed == RUNNING || observed == TERMINAL || observed == CANCELLED) {
                    return;
                }
                if (observed == CANCELLING) {
                    Thread.onSpinWait();
                    LockSupport.parkNanos(20_000L);
                    continue;
                }
                if (state.compareAndSet(observed, CANCELLING)) {
                    try {
                        synchronized (island.unitAdmissionLock) {
                            island.submitQueued.set(false);
                        }
                        receipt.finishAfterFailure(failure);
                    } finally {
                        // CANCELLED is the post-cleanup terminal, not merely the
                        // winning cancellation decision. A concurrent strict
                        // failure waiter may trust it exactly like TERMINAL.
                        state.set(CANCELLED);
                    }
                    return;
                }
            }
        }

        boolean isTerminalDisposition() {
            int observed = state.get();
            return observed == TERMINAL || observed == CANCELLED;
        }

        void retireBeforeStart() {
            if (!state.compareAndSet(OFFERING, CANCELLING)) {
                throw new IllegalStateException(
                        "entered island unit was retired as an unaccepted publication");
            }
            try {
                synchronized (island.unitAdmissionLock) {
                    island.submitQueued.set(false);
                }
                // This generation refused the root before it became a body.  Do not
                // publish a "success" into whichever generation may now be current;
                // RETIRED is terminal bookkeeping for the dead receipt only.
                receipt.requireUncommitted();
                receipt.commitOutcome(AttemptOutcome.RETIRED,
                        island.logicalTick, island.logicalTick);
                receipt.finishSuccessfully();
            } finally {
                state.set(CANCELLED);
            }
        }

        @Override
        public void run() {
            int observed;
            while ((observed = state.get()) == OFFERING
                    || observed == CANCELLING) {
                Thread.onSpinWait();
            }
            if (observed == CANCELLED) return;
            if (!state.compareAndSet(ACCEPTED, RUNNING)) {
                if (state.get() == CANCELLED) return;
                throw new IllegalStateException("island unit publication executed twice");
            }
            try {
                runCoordinated(rs, island, receipt, submittedAtL, frontierBound,
                        submittedDeadlineNanos, deadlineSlackNanos,
                        globalSprintRequest);
            } finally {
                state.set(TERMINAL);
            }
        }
    }

    /**
     * Successor prepared behind an OFFERING receipt.  Queue publication may be
     * fallible and therefore happens before the unit success cut; body entry is
     * impossible until commit accepts the receipt in that cut.
     */
    private static final class PreparedSuccessor {
        private static final PreparedSuccessor NONE =
                new PreparedSuccessor(null, null, null);

        private final UnitPublication unit;
        private final Runnable queuedUnit;
        private TemporalControl.Pacer.PreparedArm paced;

        private PreparedSuccessor(UnitPublication unit,
                                  Runnable queuedUnit,
                                  TemporalControl.Pacer.PreparedArm paced) {
            this.unit = unit;
            this.queuedUnit = queuedUnit;
            this.paced = paced;
        }

        static PreparedSuccessor unit(UnitPublication publication, Runnable queuedUnit) {
            return new PreparedSuccessor(publication, queuedUnit, null);
        }

        static PreparedSuccessor preparePaced(RegionizedServer runtime,
                                              IslandRegistry.PIsland island,
                                              long deadlineNanos) {
            // Allocate the owner before prepareArmAfterUnit can expose an
            // OFFERING DelayQueue node. After that offer, only this fixed field
            // write remains before the cancellation handle is returned.
            PreparedSuccessor prepared = new PreparedSuccessor(null, null, null);
            prepared.paced = TemporalControl.prepareArmAfterUnit(
                    runtime, island, deadlineNanos);
            return prepared;
        }

        void commit() {
            if (unit != null) unit.accept();
            if (paced != null) paced.commit();
        }

        Throwable cancel(Throwable primary) {
            Throwable failure = primary;
            if (unit != null) {
                try {
                    unit.cancelBeforeStart(failure);
                } catch (Throwable cleanupFailure) {
                    failure = publishMergedFailure(failure, cleanupFailure);
                }
            }
            if (queuedUnit instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                try {
                    cancellable.cancelQueuedBeforeStartWithFailure(failure);
                } catch (Throwable cleanupFailure) {
                    failure = publishMergedFailure(failure, cleanupFailure);
                }
            }
            if (paced != null) {
                try {
                    failure = paced.cancel(failure);
                } catch (Throwable cleanupFailure) {
                    failure = publishMergedFailure(failure, cleanupFailure);
                }
            }
            return failure;
        }
    }

    private static PreparedSuccessor prepareChainedSuccessor(
            RegionizedServer rs, IslandRegistry.PIsland island, long submittedAtL) {
        RegionScheduler.throwWorkerFailureIfAny();
        var server = RegionScheduler.getServer();
        if (server != null && !server.tickRateManager().runsNormally()) {
            return PreparedSuccessor.NONE;
        }
        synchronized (island.unitAdmissionLock) {
            if (island.globalSprintFenced
                    || (rs != null && rs.globalSprintAdmissionFenced)
                    || (server != null && server.tickRateManager().isSprinting())) {
                return PreparedSuccessor.NONE;
            }
            if (!island.submitQueued.compareAndSet(false, true)) {
                return PreparedSuccessor.NONE;
            }
        }
        AttemptReceipt receipt;
        UnitPublication publication = null;
        Runnable queuedUnit = null;
        try {
            receipt = new AttemptReceipt();
            publication = new UnitPublication(rs, island, receipt, submittedAtL,
                    false, Long.MIN_VALUE, 0L, null);
            queuedUnit = RegionScheduler.cancellableRoot(publication, publication::cancelBeforeStart);
            if (RegionScheduler.ownsExactExecutionTurn()) {
                throw new IllegalStateException(
                        "a chained island successor cannot publish inline in an exact turn");
            }
            if (!RegionScheduler.enqueueUnit(rs, queuedUnit)) {
                // Normal generation retirement: the successor never became a
                // body and therefore has neither a success edge nor a W edge.
                publication.retireBeforeStart();
                return PreparedSuccessor.NONE;
            }
            return PreparedSuccessor.unit(publication, queuedUnit);
        } catch (Throwable publicationFailure) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(rs,
                    publicationFailure);
            if (publication != null) {
                try {
                    publication.cancelBeforeStart(failure);
                } catch (Throwable cleanupFailure) {
                    failure = publishMergedFailure(failure, cleanupFailure);
                }
            } else {
                synchronized (island.unitAdmissionLock) {
                    island.submitQueued.set(false);
                }
            }
            if (queuedUnit instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                try {
                    cancellable.cancelQueuedBeforeStartWithFailure(failure);
                } catch (Throwable cleanupFailure) {
                    failure = publishMergedFailure(failure, cleanupFailure);
                }
            }
            IslandTickUnit.<RuntimeException>rethrow(failure);
            return PreparedSuccessor.NONE;
        }
    }

    /** Rig fault injection (task #25, {@code regions debug failunittail <n>}):
     *  the next n units throw at the head of the finally TAIL — exercises the
     *  fail-fast + unskippable-release chain end to end. Default 0;
     *  never set outside explicit operator/rig use. */
    static volatile int debugFailTail;

    public static void setDebugFailTail(int n) {
        debugFailTail = n;
    }

    private static void runCoordinated(RegionizedServer rs, IslandRegistry.PIsland island,
                                       AttemptReceipt receipt, long submittedAtL,
                                       boolean frontierBound, long submittedDeadlineNanos,
                                       long deadlineSlackNanos,
                                       GlobalSprintRequest globalSprintRequest) {
        Throwable failure = null;
        AttemptOutcome terminalOutcome = AttemptOutcome.NO_BODY;
        boolean claimAcquired = false;
        try {
            NetherPortalTransfers.beforeUnitAdmission();
            try {
                if (!rs.asyncActive || island.retired) {
                    if (globalSprintRequest != null) {
                        throw new IllegalStateException(
                                "global sprint unit lost its live island before claim");
                    }
                    // Shutdown/ASYNC->SYNC may quiesce and collapse the island
                    // after this unit was queued but before it acquired the claim.
                    // Such a future-cadence unit has not started any vanilla phase;
                    // retire the queue reservation instead of executing it against
                    // the already-collapsed wall domain.
                    terminalOutcome = AttemptOutcome.RETIRED;
                    return;
                }
                if (globalSprintRequest != null) {
                    long waitStarted = System.nanoTime();
                    int stuckReports = 0;
                    while (!(claimAcquired = globalSprintRequest.tryClaim())) {
                        RegionScheduler.throwWorkerFailureIfAny();
                        if (!rs.asyncActive || island.retired
                                || island.globalSprintRequest != globalSprintRequest
                                || !island.globalSprintFenced) {
                            throw new IllegalStateException(
                                    "global sprint unit lost its exact claim reservation");
                        }
                        if (!RegionScheduler.poolRunning()) {
                            throw new IllegalStateException(
                                    "global sprint unit lost its worker pool before claim");
                        }
                        if (System.nanoTime() - waitStarted > 5_000_000_000L) {
                            waitStarted = System.nanoTime();
                            if (++stuckReports >= 3) {
                                throw new IllegalStateException(
                                        "global sprint unit could not acquire island claim: "
                                                + IslandCommands.claimForensics(island));
                            }
                        }
                        LockSupport.parkNanos(20_000L);
                    }
                } else if (island.mainInterest() || rs.mainClaimsFenceActive()) {
                    // Main announced interest (a claim waiter, entity-status
                    // escalation, a MAIN_SYNC service request or a global fence
                    // intent). A back-to-back chaining island would otherwise
                    // win this claim on every pass; yield with NO_BODY so the
                    // pacer (CLAIM_RETRY_NANOS) or the sprint/coherent Main
                    // drive (submits on !unitClaim each wall tick) retries
                    // afterwards. A service request is answered at Main's next
                    // safe point: wake an idle Main.
                    if (island.mainSyncWanted) RegionScheduler.requestMainSyncService();
                    return;
                } else if (!(claimAcquired = island.tryClaimAutonomousWork())) {
                    // Brief claim contention need not cost the island a tick.
                    // A longer topology/save/Main hold defers to the next drive.
                    long spinUntil = System.nanoTime() + 200_000L;
                    boolean claimed = false;
                    while (System.nanoTime() < spinUntil) {
                        // Interest announced while we spin wins: Main may be
                        // between its bucket release and the bucket's tails.
                        if (island.mainInterest() || rs.mainClaimsFenceActive()) break;
                        if ((claimAcquired = island.tryClaimAutonomousWork())) {
                            claimed = true;
                            break;
                        }
                        Thread.onSpinWait();
                    }
                    if (!claimed) {
                        if (island.mainSyncWanted) RegionScheduler.requestMainSyncService();
                        return; // quiesced by topology/save/Main — the next drive retries
                    }
                }
            } finally {
                // CLAIM-ADMISSION HANDSHAKE (2026-08-22 c2me enhanced autosave):
                // keep submitQueued=true while this task is queued AND while it is
                // trying to claim. C2ME's main-idle incremental save checks the flag
                // before/after its own claim CAS, closing the default main deadline
                // queue→idle-save gap that could drop this unit after 200us and advance
                // W while L stayed frozen. Once we own unitClaim, the claim itself excludes
                // the save and the flag may reopen for the next submission. Finally
                // makes every pre-claim Throwable/return release the reservation.
                synchronized (island.unitAdmissionLock) {
                    island.submitQueued.set(false);
                }
            }
            // The pre-claim retired check can race a topology rebuild that
            // acquires/releases this claim in between. Once we own the claim,
            // revalidate the identity before touching its dead domain/cells.
            if (island.retired) {
                if (globalSprintRequest != null) {
                    throw new IllegalStateException(
                            "global sprint island retired after exact claim");
                }
                terminalOutcome = AttemptOutcome.RETIRED;
                return;
            }
            // Async OFF races the pre-claim check: Main writes asyncActive=false
            // and then reads unitClaim (its OFF catch-up fences iff a claim is
            // visible); this unit wrote unitClaim and now reads asyncActive.
            // Both volatile: at least one side sees the other, so a unit never
            // runs beside Main's unfenced catch-up over the same entities.
            if (globalSprintRequest == null && !rs.asyncActive) {
                terminalOutcome = AttemptOutcome.RETIRED;
                return;
            }
            if (globalSprintRequest != null
                    && (island.globalSprintRequest != globalSprintRequest
                        || !island.globalSprintFenced)) {
                throw new IllegalStateException(
                        "global sprint request identity changed after exact claim");
            }
            // STALE-FOLLOWER ADMISSION (2026-08-22 universal-crafter incident):
            // submitQueued reopens after U acquires its claim, before U completes.
            // While U still owns that claim, main can therefore enqueue follower F
            // from U's still-due deadline. If the pool delays F until after U rolls L,
            // the old code ran F without any deadline/L recheck: two complete island
            // units back-to-back while main advanced once. Under a main/save stall that
            // can phase-compress redstone machines and let cart banks surge together.
            // Deliberate local-sprint/coherence chaining captures the post-complete L,
            // so it remains fresh. Release this unrecorded claim and let the next real
            // drive decide; no phase and no logical-clock increment has happened yet.
            if (!IslandUnitAdmission.isFresh(submittedAtL, island.logicalTick)
                    || (frontierBound
                        && (!IslandUnitAdmission.isSameDeadline(submittedDeadlineNanos,
                                    island.nextDeadlineNanos)
                            || !IslandUnitAdmission.isDeadlineDue(island.nextDeadlineNanos,
                                    System.nanoTime(), deadlineSlackNanos)))) {
                // The explicit CAS marker owns release even though this claim
                // has not reached noteClaim. A hostile diagnostic is caught by
                // the outer envelope and cannot retain the island claim.
                reportStaleFollower(island, submittedAtL);
                if (globalSprintRequest != null) {
                    throw new IllegalStateException(
                            "global sprint unit did not start at its exact fromL="
                                    + globalSprintRequest.fromLogicalTick
                                    + " currentL=" + island.logicalTick);
                }
                return;
            }
            island.noteClaim("unit@" + Thread.currentThread().getName());
            long start = System.nanoTime();
            final long lAtStart = island.logicalTick;
            boolean scheduledHold = false;
            boolean bodyCompleted = false;
            Throwable unitFailure = null;
            try {
                RegionScheduler.throwWorkerFailureIfAny();
                EpochDomain d = island.domain;
                // L-clock publication: everything executing under this domain
                // reads island time L+1 from getGameTime for this unit's
                // duration (vanilla's increment-at-head convention). Volatile
                // write ordered before the barrier arm/seed in the dispatch.
                d.unitGameTime = island.logicalTick + 1;

                RegionWorkerThread me = (RegionWorkerThread) Thread.currentThread();
                me.currentDomain = d;
                try {
                    {
                    // The old due causal edge must be admitted before any
                    // command/task/arrival producer can encounter vanilla's
                    // unique (pos,type) entry. Pure terrain containers take
                    // only the cold monotonic-hint rejection here.
                    boolean scheduledHeadAdmitted =
                            IslandScheduledTicks.preflightIslandScheduled(
                                    rs, d, d.unitGameTime);
                    RegionScheduler.throwWorkerFailureIfAny();
                    if (!scheduledHeadAdmitted) {
                        scheduledHold = true;
                        if (globalSprintRequest != null) {
                            throw new IllegalStateException(
                                    "global sprint unit reached scheduled-tick head HOLD at L="
                                            + d.unitGameTime);
                        }
                    } else {
                    // Cadence-bound work applies before scheduled ticks.
                    d.handlingTickPhase = true; // vanilla phases 0-6 window
                    IslandBlockEvents.beginUnitCapture(island);
                    RegionScheduler.throwWorkerFailureIfAny();
                    // Unit-head work is stamped before execution. The stamp is
                    // inherited across topology splits and keeps this cadence
                    // point idempotent if another admission seam ever retries
                    // the same candidate L.
                    if (ScheduledTickIslandAdmission.shouldApplyUnitHead(
                            island.unitHeadAppliedL, d.unitGameTime)) {
                        island.unitHeadAppliedL = d.unitGameTime;
                        RegionScheduler.throwWorkerFailureIfAny();
                        CommandShards.serviceIslandTasks(island);
                        RegionScheduler.throwWorkerFailureIfAny();
                    }
                    // Cross-dimension arrival cohorts: publish WHOLE batches
                    // at the unit head, before scheduled ticks and the entity
                    // phase — every member becomes tickable at this same
                    // boundary (vanilla: arrivals precede the destination's
                    // entire tick).
                    RegionScheduler.throwWorkerFailureIfAny();
                    CrossDimArrivals.publishIsland(island);
                    RegionScheduler.throwWorkerFailureIfAny();
                    // DATAPACK FUNCTION HEAD — armed per-tick roots
                    // (#minecraft:tick, 1t self-rearming loops) for this
                    // island's entities at its own cadence: after arrivals
                    // (they precede the destination's whole tick), before
                    // scheduled ticks and the entity phase. Vanilla runs
                    // functions outside the level tick, so handlingTick is
                    // off for the pass. Own stamp, unitHeadAppliedL discipline.
                    if (ScheduledTickIslandAdmission.shouldApplyUnitHead(
                            island.functionHeadAppliedL, d.unitGameTime)) {
                        island.functionHeadAppliedL = d.unitGameTime;
                        d.handlingTickPhase = false;
                        try {
                            IslandFunctionHead.runIslandHead(rs, island);
                        } finally {
                            d.handlingTickPhase = true;
                        }
                        RegionScheduler.throwWorkerFailureIfAny();
                    }
                    // Scheduled block/fluid ticks collected at THIS island's
                    // time — a lagging island's repeaters keep its own cadence.
                    // First segment: vanilla order [scheduled → events →
                    // entities → BEs] island-relative.
                    boolean scheduledAdmitted = IslandScheduledTicks.runIslandScheduled(
                            rs, d, d.unitGameTime);
                    RegionScheduler.throwWorkerFailureIfAny();
                    if (!scheduledAdmitted) {
                        scheduledHold = true;
                        if (globalSprintRequest != null) {
                            throw new IllegalStateException(
                                    "global sprint unit reached scheduled-tick HOLD at L="
                                            + d.unitGameTime);
                        }
                    } else {
                    // RAIDS — vanilla's exact slot: after this island's
                    // scheduled ticks, before its block events; unit-driven
                    // (1:1 with the island's own cadence, main-rate-proof).
                    RegionScheduler.throwWorkerFailureIfAny();
                    RaidIslandDispatch.service(rs, island, rs.level);
                    RegionScheduler.throwWorkerFailureIfAny();
                    // CHUNK PASS — vanilla's exact slot (ServerLevel.tick:
                    // raids → chunkSource.tick → runBlockEvents): natural
                    // spawning for this island's spawnable chunks, then
                    // random/fluid/thunder ticks for its entity-ticking
                    // chunks, on this island's L, serially on the coordinator.
                    // Main arms only regions with no island owner.
                    ((IslandChunkPassAccess) rs.level.getChunkSource())
                            .fabrolia$tickIslandChunks(rs, island, d.unitGameTime);
                    RegionScheduler.throwWorkerFailureIfAny();
                    // Block events routed to this island by the main slot's
                    // partition — BEFORE the entity phase.
                    IslandBlockEvents.runIslandEvents(rs, island, d);
                    RegionScheduler.throwWorkerFailureIfAny();
                    IslandBlockEvents.endUnitCapture(rs.level);
                    RegionScheduler.throwWorkerFailureIfAny();

                    d.handlingTickPhase = false; // vanilla flips before entities
                    d.epoch++;

                    // Linearize this finite core+halo membership generation
                    // against cold Region creation/replacement for THIS
                    // domain. A creator already admitted through the domain
                    // READ finishes and appears below; a later one waits,
                    // observes cut=E, and cannot owe or contribute to this
                    // barrier. Creators for other domains cannot become
                    // members here, so other islands' cuts do not wait.
                    ThreadedRegionizer regionizer = rs.regionizer();
                    ArrayList<Region> coreAtCut =
                            new ArrayList<>(island.cells.size());
                    ArrayList<Region> haloAtCut =
                            new ArrayList<>(island.haloCells.size());
                    regionizer.lockDomainCreationCut(d);
                    try {
                        LongIterator it = island.cells.iterator();
                        while (it.hasNext()) {
                            RegionScheduler.throwWorkerFailureIfAny();
                            Region r = regionizer.peekByGridKey(it.nextLong());
                            if (r != null) coreAtCut.add(r);
                        }
                        LongIterator hstamp = island.haloCells.iterator();
                        while (hstamp.hasNext()) {
                            RegionScheduler.throwWorkerFailureIfAny();
                            Region r = regionizer.peekByGridKey(hstamp.nextLong());
                            if (r != null) haloAtCut.add(r);
                        }
                        d.entityDispatchCutEpoch = d.epoch;
                    } finally {
                        regionizer.unlockDomainCreationCut(d);
                    }

                    // Do domain-rebase waits and callbacks only after releasing
                    // the domain cut WRITE: a drained callback may itself create a
                    // destination Region. Every object below was already on the
                    // captured side of the cut.
                    ArrayList<Region> members =
                            new ArrayList<>(coreAtCut.size() + haloAtCut.size());
                    for (int ri = 0, rn = coreAtCut.size(); ri < rn; ri++) {
                        RegionScheduler.throwWorkerFailureIfAny();
                        Region r = coreAtCut.get(ri);
                        long cellKey = r.gridKey;
                        if (r.state() == RegionState.ACTIVE && r.domain == d) {
                            members.add(r);
                        } else if (r.domain == d) {
                            // Same-domain but not dispatchable (state flipped
                            // since rebuild): stamp done so same-domain
                            // neighbors' color eligibility can't wait on it.
                            r.doneEpoch = d.epoch;
                        } else if (r.state() == RegionState.ACTIVE) {
                            // STALE-DOMAIN HEAL (task #26 — reproduced portal-cart
                            // inert arrival, 2026-07-23). This loop iterates THE
                            // ISLAND'S OWN core cells: a mismatched (or null)
                            // domain pointer on an ACTIVE region here is a
                            // rebuild-race artifact, not foreign ownership — and
                            // the old policy of "leave it to the global lane"
                            // abandoned it to NOBODY (main's remainder lane
                            // filters domain != global; the 2026-07-15 halo-parity
                            // class, resurfacing for core cells). Every entity in
                            // the region — including a freshly PUBLISHED cross-
                            // dimension arrival — froze until the next topology
                            // change: the production minecart stuck at the portal
                            // boundary until a player visit. Heal the pointer
                            // under the unit claim (rebuilds quiesce this unit;
                            // core-cell ownership is exclusive) and dispatch
                            // normally. Scheduled-tick clock tags self-heal at
                            // consume via the task-#31 lane enforcement; entity
                            // tick-token dedup is domain-prefix-unique, so no
                            // cross-domain token collision is possible.
                            FlightRecorder.record("STALE-DOMAIN-HEAL island#" + island.id
                                    + "/g" + island.gen + " cell=" + Long.toHexString(cellKey)
                                    + " oldDomain=" + (r.domain == null ? "null"
                                            : (r.domain.owner == null ? "global"
                                            : "island#" + r.domain.owner.id + "/g" + r.domain.owner.gen))
                                    + " entities=" + r.entities.size());
                            // Exact safe-point adoption: close stale claim
                            // admission, reset every epoch gate to E-1, and
                            // publish the new domain last. A delayed ready
                            // claimant from the old timeline then sees one
                            // coherent new-domain pre-dispatch baseline.
                            RegionizedServer.rebaseDomainEpochAtSafePoint(r, d);
                            members.add(r);
                        }
                    }
                    // HALO PARTITION PARITY (2026-07-15, Folia V2 verdict —
                    // "the partition function for entities must equal the
                    // partition function for block events"): a halo-domain
                    // region's entities were ticked by NOBODY (not island
                    // membership, filtered out of main's remainder lane by
                    // domain != global) — a rebuild that flickered a machine
                    // cell to halo froze its falling blocks mid-air
                    // (rig storm: 72 falls, 0 portal contacts). Halo regions
                    // WITH work join the phase as members; empty ones keep
                    // the done-stamp (FRONTIER HALO epoch discipline,
                    // 2026-07-14 watchdogs: an unstamped same-domain halo
                    // neighbor blocks every core edge region's ColorGate
                    // eligibility forever).
                    for (int ri = 0, rn = haloAtCut.size(); ri < rn; ri++) {
                        RegionScheduler.throwWorkerFailureIfAny();
                        Region hr = haloAtCut.get(ri);
                        if (hr.domain != d) continue;
                        if (hr.state() == RegionState.ACTIVE
                                && RegionizedServer.hasEntityDispatchWork(hr, true)) {
                            members.add(hr); // ticks in THIS island's phase
                        } else {
                            hr.doneEpoch = d.epoch;
                        }
                    }
                    RegionScheduler.throwWorkerFailureIfAny();
                    // Serial pass in Vanilla insertion order, or (opt-in) the
                    // members' own entity phases in parallel under the
                    // ColorGate. TNT and minecarts keep the serial pass in both modes.
                    boolean regionParallel = com.axalotl.fabrolia.common.config.FabroliaConfig.islandEntityParallel
                            && !RegionizedServer.holdsOrderSensitiveEntity(members);
                    CompletableFuture<Void> barrier = rs.dispatchEntityPhase(d, members, regionParallel);

                    awaitEntityBarrier(rs, island, d, members, barrier);
                    if (!regionParallel) rs.tickIslandEntitiesInVanillaOrder(island);
                    long debugCost = island.debugCostNanos;
                    if (debugCost > 0) {
                        // Diagnostics knob: park in a loop — pool wake permits
                        // land on this worker thread and return parkNanos
                        // early (the 2026-07-12 a2lagk run measured ~50ms of
                        // an injected 120ms), which would understate the
                        // simulated overload.
                        long until = System.nanoTime() + debugCost;
                        long left;
                        while ((left = until - System.nanoTime()) > 0) {
                            RegionScheduler.throwWorkerFailureIfAny();
                            LockSupport.parkNanos(left);
                        }
                        RegionScheduler.throwWorkerFailureIfAny();
                    }
                    // FRONTIER HALO tail (2026-07-14): a halo carrying work at
                    // the dispatch snapshot joined the ordinary queue->entity
                    // phase above. A task admitted after an ungrouped halo's
                    // dispatch cutoff, or after a grouped halo committed its
                    // entity body, belongs to the NEXT unit and must remain in
                    // FIFO; draining it here would run queue work after entities.
                    // Keep the old claim/drain belt only for a pre-entity
                    // participant which somehow still owns current-E work.
                    LongIterator hit = island.haloCells.iterator();
                    while (hit.hasNext()) {
                        RegionScheduler.throwWorkerFailureIfAny();
                        Region hr = rs.regionizer().peekByGridKey(hit.nextLong());
                        if (hr == null || hr.state() != RegionState.ACTIVE || hr.domain != d) {
                            continue;
                        }
                        if (hr.queue().isEmpty() && hr.helpQueue.isEmpty()) {
                            continue;
                        }
                        EpochDomain haloDomain = hr.domain;
                        long haloEpoch = haloDomain != null
                                ? haloDomain.epoch : Long.MIN_VALUE;
                        if (haloDomain != null
                                && haloDomain.dispatchedEpoch == haloEpoch
                                && (hr.groupedEpoch != haloEpoch
                                    || hr.entityEpoch >= haloEpoch)) {
                            continue; // retained, and re-seeded by the next dispatch
                        }
                        if (!hr.tryClaim(Thread.currentThread(), haloDomain, haloEpoch)) {
                            continue; // helper mid-drain — it owns the queue
                        }
                        Region prevR = me.currentRegion;
                        me.currentRegion = hr;
                        Throwable haloFailure = null;
                        try {
                            while (runOneHaloTask(hr.queue())) {
                                // One queued body per fatal-cut admission.
                            }
                            while (runOneHaloTask(hr.helpQueue)) {
                                // One queued body per fatal-cut admission.
                            }
                        } catch (Throwable thrown) {
                            haloFailure = RegionScheduler.publishFatalAndSelectWinner(thrown);
                        }
                        try {
                            me.currentRegion = prevR;
                            hr.release(Thread.currentThread());
                        } catch (Throwable cleanupFailure) {
                            haloFailure = publishMergedFailure(haloFailure, cleanupFailure);
                        }
                        try {
                            // This halo belt has no later ready/barrier receipt;
                            // release is its complete claim-transaction tail.
                            hr.finishClaimTransaction();
                        } catch (Throwable cleanupFailure) {
                            haloFailure = publishMergedFailure(haloFailure, cleanupFailure);
                        }
                        if (haloFailure != null) {
                            IslandTickUnit.<RuntimeException>rethrow(haloFailure);
                        }
                    }
                    // Island-internal cross-region replay: single-threaded,
                    // every member region quiescent, other islands unreachable
                    // by the separation invariant.
                    RegionScheduler.throwWorkerFailureIfAny();
                    RegionizedServer.drainDeferredOps(d);
                    RegionScheduler.throwWorkerFailureIfAny();
                    // BE phase on the island's timeline.
                    IslandBlockEntityTicks.tickIslandCells(rs.level, rs, island);
                    RegionScheduler.throwWorkerFailureIfAny();
                    // Successful block changes are accumulated by ChunkHolder.
                    // Attempt this island's holder broadcasts before the real-
                    // player listener tail. A holder demoted after blockChanged
                    // is legitimately unavailable here; runLate's exact direct
                    // actor correction remains the hard authority before its
                    // ClientboundBlockChangedAckPacket while observers follow
                    // holder/full-chunk lifecycle delivery.
                    ((IslandBlockUpdateAccess) rs.level.getChunkSource())
                            .fabrolia$flushIslandBlockUpdates(rs, island);
                    RegionScheduler.throwWorkerFailureIfAny();
                    // Pairing/membership remains globally coordinated, but
                    // movement, metadata and velocity packets advance at the
                    // same clock as the entities that produced them. This
                    // general generation publishes non-player/READY state now;
                    // exact real PendingLate self trackers are deferred until
                    // runLate has applied listener.tick/doTick physics.
                    IslandEntityTracking.flush(rs, island);
                    RegionScheduler.throwWorkerFailureIfAny();
                    // A5 real-player late half. The complete play-listener tick runs
                    // exactly once here under the same island claim, after BEs and
                    // entity publication but before the final coherent chunk snapshot.
                    // Connection transport, disconnect and flush stay on physical
                    // Main; Carpet fake players have a combined early body and never
                    // enter this queue.
                    AsyncIslandPlayerTicks.runLate(island);
                    RegionScheduler.throwWorkerFailureIfAny();
                    // Chunk-packet snapshots LAST: the tick's final coherent
                    // state, still under the unit claim (sender redesign
                    // 2026-07-14 — no per-packet claims anywhere).
                    ChunkDelivery.sealPending(rs, island);
                    RegionScheduler.throwWorkerFailureIfAny();
                    bodyCompleted = true;
                    }
                    }
                    }
                } finally {
                    me.currentDomain = null;
                }
            } catch (Throwable thrown) {
                // Capture only to preserve the exact primary while every
                // claim/domain/capture resource is released below.
                unitFailure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            } finally {
                // Tail checks and state cleanup never convert a failed phase
                // into success. Secondary cleanup failures are diagnostics on
                // the exact primary; the island claim is released regardless.
                try {
                    // MACHINE INVARIANT: L may move only through this unit's
                    // successful complete(). Any mid-unit write is fatal.
                    if (island.logicalTick != lAtStart) {
                        FlightRecorder.record("CLOCK-DISCONTINUITY island#" + island.id
                                + " L " + lAtStart + " -> " + island.logicalTick + " mid-unit");
                        FlightRecorder.dump("island#" + island.id + " logical clock changed mid-unit ("
                                + lAtStart + " -> " + island.logicalTick + ")");
                        throw new IllegalStateException("island logical clock changed mid-unit: "
                                + lAtStart + " -> " + island.logicalTick);
                    }
                    if (debugFailTail > 0) {
                        debugFailTail--;
                        throw new IllegalStateException("debug: injected unit-tail failure");
                    }
                } catch (Throwable tailFailure) {
                    unitFailure = publishMergedFailure(unitFailure, tailFailure);
                }

                try {
                    // A fatal body must not publish an open vanilla
                    // phase/capture after the claim becomes visible.
                    island.domain.handlingTickPhase = false;
                    IslandBlockEvents.endUnitCapture(rs.level);
                } catch (Throwable cleanupFailure) {
                    unitFailure = publishMergedFailure(unitFailure, cleanupFailure);
                }

                if (unitFailure == null) {
                    unitFailure = RegionScheduler.workerFailureOrNull();
                }
                if (unitFailure == null) {
                    CrossDimArrivals.PreparedSeal preparedSeal = null;
                    PreparedSuccessor preparedSuccessor = null;
                    try {
                        if (scheduledHold) {
                            // No scheduled handler or later vanilla phase ran.
                            // Seal unit-head arrival publication, restore the
                            // between-units clock view, and return the same L to
                            // the pacer.  No completion metrics/deadline/L write
                            // and no successor publication is permitted here.
                            preparedSeal = CrossDimArrivals.prepareSeal(island.domain);
                            RegionScheduler.throwWorkerFailureIfAny();
                            CrossDimArrivals.PreparedSeal sealCommit = preparedSeal;
                            receipt.requireUncommitted();
                            RegionScheduler.commitSuccessIfHealthy(() -> {
                                sealCommit.commit();
                                island.domain.unitGameTime = island.logicalTick;
                                receipt.commitOutcome(AttemptOutcome.HOLD,
                                        lAtStart, lAtStart);
                            });
                        } else {
                            // Commit output only for a phase-clean unit. A
                            // throwing phase never seals partial cross-dimension
                            // work and never advances L/deadline/completion metrics.
                            if (!bodyCompleted) {
                                throw new IllegalStateException(
                                        "island unit reached completion without its full body");
                            }
                            // Prepare every fallible completion dependency, then
                            // offer arrivals behind a receipt. Successor
                            // publication still happens under this claim; only
                            // after it succeeds do the raw completion fields and
                            // the arrival receipt become visible.
                            CompletionPlan completion = prepareCompletion(island, start);
                            RegionScheduler.throwWorkerFailureIfAny();
                            preparedSeal = CrossDimArrivals.prepareSeal(island.domain);
                            RegionScheduler.throwWorkerFailureIfAny();
                            preparedSuccessor = globalSprintRequest == null
                                    ? completion.prepareSuccessor()
                                    : PreparedSuccessor.NONE;
                            RegionScheduler.throwWorkerFailureIfAny();
                            CrossDimArrivals.PreparedSeal sealCommit = preparedSeal;
                            PreparedSuccessor successorCommit = preparedSuccessor;
                            if (globalSprintRequest != null
                                    && (island.globalSprintRequest
                                            != globalSprintRequest
                                        || completion.nextLogicalTick()
                                            != globalSprintRequest.toLogicalTick
                                        || lAtStart
                                            != globalSprintRequest.fromLogicalTick)) {
                                throw new IllegalStateException(
                                        "global sprint completion lost its exact L receipt");
                            }
                            receipt.requireUncommitted();
                            // This is the unit's sole success linearization:
                            // logical clock/metrics, arrival visibility and the
                            // next cadence receipt all win before W, or none do.
                            RegionScheduler.commitSuccessIfHealthy(() -> {
                                completion.commit();
                                sealCommit.commit();
                                successorCommit.commit();
                                receipt.commitOutcome(AttemptOutcome.ADVANCED,
                                        lAtStart, completion.nextLogicalTick());
                            });
                        }
                    } catch (Throwable completionFailure) {
                        unitFailure = publishMergedFailure(unitFailure, completionFailure);
                        if (preparedSuccessor != null) {
                            unitFailure = preparedSuccessor.cancel(unitFailure);
                        }
                        if (preparedSeal != null) {
                            unitFailure = preparedSeal.cancel(unitFailure);
                        }
                    }
                }
                if (unitFailure != null) {
                    NetherPortalTransfers.abortDomain(island.domain, unitFailure);
                    try {
                        island.domain.unitGameTime = island.logicalTick;
                    } catch (Throwable cleanupFailure) {
                        unitFailure = publishMergedFailure(unitFailure, cleanupFailure);
                    }
                }
            }
            if (unitFailure != null) IslandTickUnit.<RuntimeException>rethrow(unitFailure);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        } finally {
            // The local marker is assigned by the exact successful CAS before
            // noteClaim or any other fallible work. It therefore remains the
            // release authority even if ledger publication itself fails.
            if (claimAcquired) {
                try {
                    island.clearClaim();
                } catch (Throwable releaseFailure) {
                    failure = publishMergedFailure(failure, releaseFailure);
                }
                try {
                    island.unitClaim.set(false);
                } catch (Throwable releaseFailure) {
                    failure = publishMergedFailure(failure, releaseFailure);
                }
                claimAcquired = false;
                // A pending MAIN_SYNC service request stopped this island at
                // this boundary (no successor, see prepareCompletion): the
                // claim is free now, so wake an idle Main to take it.
                if (failure == null && island.mainSyncWanted) {
                    try {
                        RegionScheduler.requestMainSyncService();
                    } catch (Throwable wakeFailure) {
                        failure = publishMergedFailure(failure, wakeFailure);
                    }
                }
            }
            if (failure == null) failure = RegionScheduler.workerFailureOrNull();
            if (failure == null) {
                try {
                    if (receipt.outcome() == null) {
                        AttemptOutcome completionOutcome = terminalOutcome;
                        RegionScheduler.commitSuccessIfHealthy(() -> {
                            receipt.commitOutcome(completionOutcome,
                                    submittedAtL, island.logicalTick);
                            receipt.finishSuccessfully();
                        });
                    } else {
                        // Claim/capture cleanup is part of the terminal result.
                        // Publish no wake until that cleanup also wins before W.
                        RegionScheduler.commitSuccessIfHealthy(
                                receipt::finishSuccessfully);
                    }
                } catch (Throwable completionFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            completionFailure);
                }
            }
            if (failure != null) {
                // The receipt is a waiter notification, never the generation's
                // crash authority. Select W first, then make the poller terminal;
                // no callback or successor body is invoked here.
                failure = RegionScheduler.publishFatalAndSelectWinner(failure);
                receipt.finishAfterFailure(failure);
                IslandTickUnit.<RuntimeException>rethrow(failure);
            }
        }
    }

    private static void joinOriginal(CompletableFuture<?> future) {
        try {
            future.join();
        } catch (CompletionException wrapped) {
            Throwable original = wrapped.getCause();
            IslandTickUnit.<RuntimeException>rethrow(
                    original == null ? wrapped : original);
        }
    }

    static void awaitEntityBarrier(
            RegionizedServer rs,
            IslandRegistry.PIsland island,
            EpochDomain d,
            ArrayList<Region> members,
            CompletableFuture<Void> barrier) {
        // The coordinator retains this island's claim through the whole unit.
        // Help its own frontier and dependency lanes, not another island's
        // unbounded ready body: our barrier may finish before that body returns.
        // Ordinary pool consumers still own the untouched shared ready FIFO.
        // Bounded on pool liveness: a barrier stranded across a
        // shutdown must not spin this coordinator forever (the
        // save-all quiesce means no unit is normally in flight by
        // pool stop; this is the belt for abnormal teardown).
        EpochDomain.EntityDispatchPublication receiptPublication =
                d.entityDispatchPublication;
        EntityBarrierWatchdog barrierWatchdog =
                new EntityBarrierWatchdog(
                        d.entityPending.get(), System.nanoTime());
        OwnBarrierCursor ownHelpCursor = new OwnBarrierCursor();
        // The last contribution usually lands on another worker while this
        // coordinator sleeps in the 20 us poll below (~70 us with Linux timer
        // slack); wake it at completion instead. A stale permit only shortens
        // one later park, and every park in the pool re-checks its condition.
        if (!barrier.isDone()) {
            Thread coordinator = Thread.currentThread();
            barrier.whenComplete((ignored, failure) -> LockSupport.unpark(coordinator));
        }
        while (!barrier.isDone() && RegionScheduler.poolRunning()) {
            RegionScheduler.throwWorkerFailureIfAny();
            // The direct helper uses the ordinary ready transaction and leaves
            // its published entry as a benign duplicate. Queue-only lower-color
            // work also participates in closing this island's frontier.
            boolean helped = helpOwnBarrierOnce(
                    rs, d, members, receiptPublication, ownHelpCursor);
            if (!helped && !barrier.isDone()) {
                // An own member can depend on these lanes even when every
                // available worker is a coordinator. Never poll/requeue B's
                // ready entry merely to find such a dependency.
                helped = RegionScheduler.workerHelpBarrierDependenciesOnce();
            }
            long observedNow = System.nanoTime();
            int observedPending = d.entityPending.get();
            EntityBarrierWatchdog.Action watchdogAction =
                    barrierWatchdog.observe(
                            observedPending,
                            observedNow,
                            receiptPublication.hasReadyReceipts());
            if (watchdogAction != EntityBarrierWatchdog.Action.WAIT
                    && anyMemberClaimHeld(island, d, members)) {
                watchdogAction = barrierWatchdog.observe(
                        observedPending, observedNow, true);
            }
            if (watchdogAction == EntityBarrierWatchdog.Action.HEAL) {
                // One admitted recovery for this unchanged pending
                // version. The returned count includes only ready
                // receipts which were actually accepted; a 19-second
                // lifecycle wait before that publication therefore
                // cannot spend the replacement receipt's observation
                // window before it even exists.
                int acceptedRecoveries = rs.healIslandBarrier(members, d);
                observedNow = System.nanoTime();
                observedPending = d.entityPending.get();
                boolean exactWitness =
                        receiptPublication.hasReadyReceipts();
                if (!exactWitness && observedPending > 0) {
                    exactWitness = anyMemberClaimHeld(island, d, members);
                }
                watchdogAction = barrierWatchdog.afterHeal(
                        observedPending, acceptedRecoveries,
                        observedNow, exactWitness);
            }
            if (watchdogAction == EntityBarrierWatchdog.Action.FAIL) {
                // LEAKED-CONTRIBUTION FAILURE (2026-07-14 forensics
                // 23:18: unit@worker held island#4 40s+, every worker
                // idle, barrier pending — an ACTIVE member's phase task
                // provably no longer exists anywhere in the pool, so
                // its contribution is LEAKED, not late; waiting longer
                // freezes main's next fence forever (watchdog). Dump
                // the exact member state for the root-cause hunt, then
                // This cannot be repaired by declaring an entity phase
                // successful: that would skip a vanilla sub-tick against
                // partial state. Record the exact topology and fail the
                // unit/server instead.
                StringBuilder fb = new StringBuilder(256);
                fb.append("BARRIER-LEAK island#").append(island.id)
                  .append(" L=").append(island.logicalTick)
                  .append(" pending=").append(d.entityPending.get())
                  .append(" receipts=")
                  .append(receiptPublication.readyReceiptCount())
                  .append(" epoch=").append(d.epoch).append(" members:");
                for (int mi = 0; mi < members.size(); mi++) {
                    Region m = members.get(mi);
                    Thread holder = m.currentWorker();
                    fb.append(" [").append(Long.toHexString(m.gridKey))
                      .append(" state=").append(m.state())
                      .append(" grouped=").append(m.groupedEpoch)
                      .append(" done=").append(m.doneEpoch)
                      .append(" sealed=").append(m.sealedDoneEpoch)
                      .append(" entity=").append(m.entityEpoch)
                      .append(" enqueued=").append(m.entityEnqueuedEpoch.get())
                      .append(" barrier=").append(m.entityBarrierEpoch.get())
                      .append(" q=").append(m.queue().isEmpty() ? 0 : 1)
                      .append(m.helpQueue.isEmpty() ? "" : " h+")
                      .append(holder == null ? "" : " holder=" + holder.getName())
                      .append(']');
                }
                FlightRecorder.record(fb.toString());
                FlightRecorder.dump("island#" + island.id
                        + " entity barrier leaked a contribution (state above)");
                throw new IllegalStateException(
                        "entity barrier lost a contribution: " + fb);
            }
            if (!helped) LockSupport.parkNanos(20_000L);
        }
        if (!barrier.isDone()) {
            RegionScheduler.throwWorkerFailureIfAny();
            throw new IllegalStateException(
                    "entity barrier remained incomplete after region pool stop");
        }
        joinOriginal(barrier);
        RegionScheduler.throwWorkerFailureIfAny();
    }

    /** One allocation per unit, never one per barrier-pump iteration. */
    private static final class OwnBarrierCursor {
        int next;
    }

    /**
     * Find one locally runnable obligation in round-robin member order.  A
     * member with an already-completed entity phase can still be the exact
     * lower-color blocker when a routed queue item arrived before done
     * publication, so queue/help work participates alongside owed phases.
     */
    private static boolean helpOwnBarrierOnce(
            RegionizedServer rs,
            EpochDomain domain,
            ArrayList<Region> members,
            EpochDomain.EntityDispatchPublication publication,
            OwnBarrierCursor ownerCursor) {
        int size = members.size();
        if (size == 0) {
            ownerCursor.next = 0;
            return false;
        }
        int startCursor = ownerCursor.next;
        int cursor = startCursor >= 0 && startCursor < size ? startCursor : 0;
        long epoch = domain.epoch;
        for (int scanned = 0; scanned < size; scanned++) {
            Region region = members.get(cursor);
            cursor++;
            if (cursor == size) cursor = 0;
            if (region.state() != RegionState.ACTIVE || region.domain != domain
                    || region.doneEpoch >= epoch) {
                continue;
            }
            boolean ownsPhase = rs.owesEntityPhase(region);
            boolean ownsQueue = !region.queue().isEmpty()
                    || !region.helpQueue.isEmpty();
            if ((!ownsPhase && !ownsQueue) || !ColorGate.eligible(region, rs)) {
                continue;
            }
            // A held claim with no published sub-batch is already making the
            // exact progress this barrier needs. Do not manufacture a hot
            // claim-CAS loop around it; service dependencies or await its release.
            if (region.currentWorker() != null && region.helpQueue.isEmpty()) {
                continue;
            }
            ownerCursor.next = cursor;
            return RegionScheduler.workerHelpOwnEntityOnce(
                    region, publication, epoch);
        }
        ownerCursor.next = cursor;
        return false;
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // The unit/server is already failing with primary. Suppression
                // is diagnostic only and may allocate under low memory.
            }
        }
        return primary;
    }

    private static Throwable publishMergedFailure(Throwable primary, Throwable secondary) {
        return RegionScheduler.publishFatalAndSelectWinner(
                mergeFailure(primary, secondary));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /** Flood-safe field evidence for the load-dependent admission race. */
    private static void reportStaleFollower(IslandRegistry.PIsland island, long submittedAtL) {
        long total = STALE_FOLLOWERS.incrementAndGet();
        long now = System.nanoTime();
        long last = STALE_FOLLOWER_LAST_LOG_NANOS.get();
        if (now - last < STALE_FOLLOWER_LOG_INTERVAL_NANOS
                || !STALE_FOLLOWER_LAST_LOG_NANOS.compareAndSet(last, now)) {
            return;
        }
        long previous = STALE_FOLLOWERS_REPORTED.getAndSet(total);
        LOGGER.warn("[async-islands] discarded stale queued unit: total={} sinceLast={} "
                        + "island#{}/gen{} submittedL={} currentL={} claimInterest={} "
                        + "statusClaimWanted={} mainSyncWanted={} deadline={}",
                total, total - previous, island.id, island.gen, submittedAtL,
                island.logicalTick, island.claimInterestAnnounced(), island.statusClaimWanted,
                island.mainSyncWanted, island.nextDeadlineNanos);
    }

    /**
     * Post-phase bookkeeping: advance the island's REAL logical clock, record
     * measured cost, roll the deadline (overrun → reschedule from completion:
     * only this island slips below 20 TPS).
     */
    private static CompletionPlan prepareCompletion(IslandRegistry.PIsland island,
                                                    long startNanos) {
        long end = System.nanoTime();
        long nextLogicalTick = island.logicalTick + 1;
        long cost = end - startNanos;
        long ema = island.emaUnitNanos;
        long mx = island.maxUnitNanos - (island.maxUnitNanos >> 6);
        long nextEma = ema == 0 ? cost : (ema * 3 + cost) >> 2;
        long nextMax = Math.max(cost, mx);
        long nextUnitsCompleted = island.unitsCompleted + 1;
        long observedPacingGen = island.pacingGen;
        // Region-aware /tick rate: per-island pacing target (rate overrides →
        // lower-tps wins; else the global /tick rate target). Deadlines keep
        // their tick-count semantics — only the wall-clock step changes.
        RegionizedServer rsT = com.axalotl.fabrolia.common.regions.RegionScheduler
                .forLevel(island.level);
        long tickNanos = rsT != null
                ? TemporalControl.effectiveTargetNanos(rsT, island) : TICK_NANOS;
        // Task #43 — cross-dimension cadence coupling: a linked
        // Overworld↔Nether member rolls its deadline at the coupling's SHARED
        // rate (lower-TPS-wins over the members, resolved on main by
        // CrossDimLinks), from the shared anchor CrossDimLinks wrote at link
        // time. Both members therefore advance in lockstep and the pre-link
        // phase offset stops drifting (dΔ/dunit == 0). Clocks are untouched —
        // phase-lock, never value-merge.
        CrossDimLinks.Coupling coupling = island.crossDimCoupling;
        if (coupling != null) {
            long shared = coupling.sharedTargetNanos;
            if (shared > 0) {
                tickNanos = shared;
            }
        }
        long next = island.nextDeadlineNanos;
        long nextMissedDeadlines = island.missedDeadlines;
        long nextLagNanos = island.lagNanos;
        next = (next == 0 ? startNanos : next) + tickNanos;
        if (end - next >= tickNanos) {
            // ≥1 FULL tick behind: genuine overload — count it and re-run
            // ASAP (deadline = completion time, still due at the next drive;
            // only this island slips). Sub-tick drift deliberately keeps the
            // rolled phase: a healthy unit completes ε after the 20Hz drive
            // point, and re-anchoring on that ε made the deadline oscillate
            // around the drive grid — the quiet-island starvation of the
            // 2026-07-12 a2lag run (offset=345, miss=262 on an EMPTY island).
            nextMissedDeadlines++;
            nextLagNanos += end - next;
            next = end;
        } else if (next > end) {
            nextLagNanos = Math.max(0, nextLagNanos - (next - end));
        }
        // AHEAD-OF-SCHEDULE clamp (2026-07-20 sprint-stop 0-TPS fix): the roll
        // above adds one period per COMPLETED unit, but global sprint lockstep
        // completes units far faster than the period — /tick sprint banked
        // ~50ms of future deadline per sprint tick (a 10s sprint at ~2800 TPS
        // = ~23 MINUTES of debt), so after /tick sprint stop the drive saw no
        // island due until the debt drained: exactly 0 TPS, revived only by
        // the next sprint's deadline-bypassing branch. The deadline may never
        // sit more than one period past completion; the 12ms grace keeps the
        // clamp strictly outside the healthy path (drive submits up to 10ms
        // early — re-anchoring on that ε is the quiet-island starvation this
        // roll's phase-keeping exists to prevent), so it fires only under
        // genuine faster-than-target production (sprint lockstep).
        if (next > end + tickNanos + 12_000_000L) {
            next = end + tickNanos;
        }
        TemporalControl.UnitCompletionDecision temporal = rsT == null
                ? null
                : TemporalControl.prepareUnitCompletion(rsT, island, nextLogicalTick);
        // FENCE FAIRNESS (2026-07-14 rate-freeze fix): a standing queue of
        // chained units re-claimed the island the instant the claim released,
        // starving every main-side claim spin (tick-datapack fences, rebuilds,
        // saves) for MINUTES. Chaining and pacing now YIELD whenever main has
        // announced interest in this island's claim (mainInterest: a claim
        // spin, a status escalation or a MAIN_SYNC service request); the fence
        // completes and the deadline drive re-kicks the island afterwards
        // (deadline stays due — nothing is lost, only deferred).
        // Publish pacing observation FIRST: any session change made before
        // this point is visible to this read (volatile), so a command's
        // commit-ack fires only after a complete unit ran under (or after)
        // its generation.
        boolean fenceWaiting = island.mainInterest()
                || (rsT != null && rsT.mainClaimsFenceActive())
                || RegionWorkerPool.currentUnitChainMustYield()
                || island.retired
                || com.axalotl.fabrolia.common.config.FabroliaConfig.asyncFailClosedTripped;
        // Local sprint: self-chain the next unit immediately (back-to-back on
        // the pool — as fast as safely possible; each iteration is a complete
        // unit with exact phase order). Paused during global sprint lockstep.
        boolean chain = rsT != null && !fenceWaiting
                && temporal != null && temporal.chain();
        if (chain) next = end;
        return new CompletionPlan(island, rsT, temporal, chain, nextLogicalTick,
                cost, nextEma, nextMax, nextUnitsCompleted,
                nextMissedDeadlines, nextLagNanos, next, observedPacingGen);
    }

    /** Kept as the stable reflection/test facade for a standalone completion. */
    private static void complete(IslandRegistry.PIsland island, long startNanos) {
        CompletionPlan plan = prepareCompletion(island, startNanos);
        PreparedSuccessor successor = null;
        try {
            successor = plan.prepareSuccessor();
            PreparedSuccessor successorCommit = successor;
            RegionScheduler.commitSuccessIfHealthy(() -> {
                plan.commit();
                successorCommit.commit();
            });
        } catch (Throwable completionFailure) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(completionFailure);
            if (successor != null) failure = successor.cancel(failure);
            IslandTickUnit.<RuntimeException>rethrow(failure);
        }
    }

    private record CompletionPlan(
            IslandRegistry.PIsland island,
            RegionizedServer runtime,
            TemporalControl.UnitCompletionDecision temporal,
            boolean chain,
            long nextLogicalTick,
            long cost,
            long nextEma,
            long nextMax,
            long nextUnitsCompleted,
            long nextMissedDeadlines,
            long nextLagNanos,
            long nextDeadlineNanos,
            long observedPacingGen) {

        PreparedSuccessor prepareSuccessor() {
            if (chain && !RegionWorkerPool.currentUnitChainMustYield()) {
                // Offer while the current unit still owns the claim.  The
                // wrapper remains behind its receipt until the shared success
                // cut publishes the prospective L.
                // Recheck after completion planning: preparation may have
                // requested its cut while arrivals were being sealed. A request
                // after this admission decision can drain this one successor;
                // its completion observes REQUESTED and switches to the pacer.
                // The current accepted step pins the cut until publication and
                // claim release, so no queued/owed unit is discarded.
                return prepareChainedSuccessor(runtime, island, nextLogicalTick);
            }
            if (runtime != null) {
                return PreparedSuccessor.preparePaced(
                        runtime, island, nextDeadlineNanos);
            }
            return PreparedSuccessor.NONE;
        }

        void commit() {
            // Fixed-object atomic/field writes only: no allocation, logging,
            // collection mutation or task publication is permitted here.
            if (temporal != null) temporal.commit();
            island.costNanos = cost;
            island.emaUnitNanos = nextEma;
            island.maxUnitNanos = nextMax;
            island.unitsCompleted = nextUnitsCompleted;
            island.missedDeadlines = nextMissedDeadlines;
            island.lagNanos = nextLagNanos;
            island.nextDeadlineNanos = nextDeadlineNanos;
            island.appliedPacingGen = observedPacingGen;
            if (chain) island.chainedUnits++;
            // Logical completion is the final publication token.
            island.logicalTick = nextLogicalTick;
        }
    }

    /** Detach and execute one halo obligation under the same fatal-cut permit. */
    private static boolean runOneHaloTask(RegionizedTaskQueue queue) {
        return RegionScheduler.callSuccessBooleanIfHealthy(() -> {
            Runnable task = queue.poll();
            if (task == null) return false;
            task.run();
            return true;
        });
    }

    /** Detach and execute one halo help obligation under the same fatal-cut permit. */
    private static boolean runOneHaloTask(java.util.Queue<Runnable> queue) {
        return RegionScheduler.callSuccessBooleanIfHealthy(() -> {
            Runnable task = queue.poll();
            if (task == null) return false;
            task.run();
            return true;
        });
    }

    /** BELT CLAIM-GUARD (2026-07-16 audit): "provably-empty pool" checked
     *  only the queues — a phase task mid-run on another worker is in NO
     *  queue, so a ≥3s stall (portal-ticket monitor, GC/OS pause, Tier-3
     *  park) was indistinguishable from a leaked contribution and the
     *  barrier was force-completed MID-PHASE: the resumed half of the
     *  entity list staged into the NEXT unit's seal → an End-portal
     *  cohort published as two destination batches (production 6/6
     *  half-and-half split). A held claim = stalled-in-flight, NOT a
     *  leak: keep waiting, record the holder + its stack (sampled from
     *  outside — a blocked thread cannot sample itself). The belt still
     *  fires for true leaks (no member claim held). */
    private static long lastStallSampleNanos;

    private static boolean anyMemberClaimHeld(IslandRegistry.PIsland island, EpochDomain d,
                                              java.util.ArrayList<Region> members) {
        boolean held = false;
        for (int i = 0; i < members.size(); i++) {
            Region m = members.get(i);
            Thread w = m.currentWorker();
            if (w == null || m.doneEpoch == d.epoch) {
                continue;
            }
            held = true;
            long now = System.nanoTime();
            if (now - lastStallSampleNanos > 3_000_000_000L) {
                lastStallSampleNanos = now;
                long ageMs = (now - m.claimAcquiredNanos) / 1_000_000L;
                StringBuilder sb = new StringBuilder(512);
                sb.append("STALLED-MEMBER island#").append(island.id)
                  .append(" L=").append(island.logicalTick)
                  .append(" region=").append(Long.toHexString(m.gridKey))
                  .append(" worker=").append(w.getName())
                  .append(" claimPath=").append(m.claimPath)
                  .append(" ageMs=").append(ageMs).append(" stack:");
                StackTraceElement[] st = w.getStackTrace();
                for (int f = 0; f < Math.min(st.length, 14); f++) {
                    sb.append(" <- ").append(st[f]);
                }
                FlightRecorder.record(sb.toString());
                LOGGER.warn("[async-islands] entity phase stalled in-flight (holding, not forcing): {}", sb);
            }
        }
        return held;
    }
}
