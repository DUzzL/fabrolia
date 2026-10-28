package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region-aware temporal control (2026-07-14): island tick-rate overrides and
 * sprint sessions with MERGE-SAFE LINEAGE semantics. Sessions attach to CELL
 * SETS, not to transient island objects — island ids are reused and identities
 * permute at every rebuild, so {@link #remap} re-resolves each session against
 * the fresh topology after every rebuild: a session covering any cell of an
 * island EXPANDS to the whole island (merged areas are one causal domain and
 * cannot run at two rates), and a later split leaves the session covering all
 * descendants that inherited its cells.
 *
 * <p>Deterministic conflict policy (documented per the task):
 * <ul>
 *   <li>RATE, two explicit overrides meeting in one island → the LOWER rate
 *       (conservative) wins as the effective rate; both lineage sessions are
 *       kept so a later split restores each side's desired rate.</li>
 *   <li>SPRINT, two sessions meeting in one island → shared ticks satisfy
 *       both obligations concurrently: the island sprints until the LARGEST
 *       remaining obligation completes (remaining = max), and every covered
 *       session's counter decrements per completed unit. Neither request is
 *       discarded; the resolution is visible in status output.</li>
 * </ul>
 *
 * <p>Rates never touch clocks or deadlines: logical delays stay measured in
 * ticks; only wall-clock pacing (the unit deadline step) changes. During a
 * GLOBAL sprint (vanilla /tick sprint), the deadline drive switches to
 * lockstep — one complete unit per island per main tick, awaited at the tick
 * barrier — and local sprint chaining pauses so the exact-tick-count contract
 * holds.
 */
public final class TemporalControl {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-Temporal");
    private static final AtomicLong SESSION_IDS = new AtomicLong();

    /** Global island target (nanos per logical tick). Vanilla /tick rate sets it. */
    public static volatile long globalIslandTargetNanos = 50_000_000L;

    public static final class Session {
        public final long id = SESSION_IDS.incrementAndGet();
        /** Membership snapshot — treat as IMMUTABLE after the session is
         *  published; remap() swaps a NEW set in (audit #6: in-place
         *  clear+addAll raced the pacer thread's intersects read). */
        public volatile LongOpenHashSet cells = new LongOpenHashSet();
        /** 0 = no rate override; else nanos per logical tick. */
        public volatile long rateNanos;
        /** Remaining sprint ticks; 0 = not sprinting. */
        public final AtomicLong sprintRemaining = new AtomicLong();
        /** Coherence pin: the covered island's L must track the wall clock
         *  1:1 (never ahead, catch-up chaining when behind) and rate/sprint
         *  overrides are ignored for it — the machine-phase half of the
         *  wireless-redstone compatibility model (detector reference is
         *  wall-coherent unconditionally; see DaylightDetectorBlockMixin). */
        public volatile boolean coherent;
        public volatile String label;

        Session(String label) {
            this.label = label;
        }
    }

    private TemporalControl() {}

    /** Scheduler-stop hook: drop queued pacer fires from the closing session
     *  (the fire-time forLevel identity check is the belt; this is the
     *  suspenders — audit #5). The pacer service is reusable across sessions;
     *  normal teardown interrupts and retires the exact old daemon identity. */
    public static void drainPacer() {
        Pacer p = PACER;
        if (p != null) p.shutdownNormally();
    }

    /**
     * Single dedicated pacing thread for every ordinary async-island rate.
     * Main's level tick is a bootstrap/watchdog only: after the first seed, a
     * completed unit publishes the next ticket and every refused attempt
     * re-arms itself. No region worker is occupied by a pacing park and no
     * standing unit queue can out-race a main-side claim fence.
     *
     * <p>Event-driven: the daemon parks until the earliest queued fire time
     * (capped at {@link #CONTROL_RETRY_NANOS}) and is unparked by exactly the
     * events that can create work earlier — a new queue node, an OFFERING
     * node's acceptance/cancellation, and the terminal publication of a
     * receipt it watches. Fire times, retry delays and the per-island cadence
     * are unchanged; only the 100 µs receipt polling (≈10k wake-ups/s while any
     * unit was in flight) and the OFFERING spin are gone.
     */
    public static final Pacer PACER = new Pacer();

    public static final class Pacer implements Runnable {
        private static final long CLAIM_RETRY_NANOS = 2_000_000L;
        private static final long READINESS_RETRY_NANOS = 50_000_000L;
        private static final long CONTROL_RETRY_NANOS = 25_000_000L;
        private final java.util.concurrent.DelayQueue<Entry> queue = new java.util.concurrent.DelayQueue<>();
        private final java.util.concurrent.ConcurrentHashMap<IslandRegistry.PIsland, TicketState> states =
                new java.util.concurrent.ConcurrentHashMap<>();
        private volatile Thread thread;
        /** Exact thread identity authorized to consume one normal-stop interrupt. */
        private Thread normalStopOwner;
        /** Raised by {@link #wake} before its unpark and consumed right before the
         *  daemon parks. The daemon also parks inside the DelayQueue lock and
         *  scheduler monitors, and such a park can absorb the bare unpark; the
         *  flag carries the wake-up across it. */
        private final java.util.concurrent.atomic.AtomicBoolean signalled =
                new java.util.concurrent.atomic.AtomicBoolean();
        /** Islands whose ticket {@link #fire} re-armed at the claim-retry cadence
         *  while their unit claim was held, with that exact retry ticket. Main's
         *  claim release pulls only that retry forward ({@link #claimReleased})
         *  instead of leaving it up to 2 ms late. */
        private final java.util.concurrent.ConcurrentHashMap<IslandRegistry.PIsland, ClaimWaiter>
                claimRetryWaiters = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentLinkedQueue<ClaimWaiter> releasedClaims =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        private record ClaimWaiter(RegionizedServer rs, IslandRegistry.PIsland island, long ticketId) {}

        /** Re-evaluate the daemon's park deadline. A flag write plus a bare unpark:
         *  it runs no callback and publishes nothing, so it is safe inside any
         *  success cut; the woken daemon re-reads every fact under its own
         *  fail-stop envelope. */
        void wake() {
            signalled.set(true);
            Thread owner = thread;
            if (owner != null && owner != Thread.currentThread()) {
                java.util.concurrent.locks.LockSupport.unpark(owner);
            }
        }

        /**
         * Main, after clearing a Main-held island claim: if the pacer refused that
         * island's attempt on the held claim, re-evaluate its retry now. Only a
         * map removal when nothing waits; the daemon does the re-arm in its own
         * pacer body (see {@link #pullReleasedClaimRetries}).
         */
        void claimReleased(IslandRegistry.PIsland island) {
            ClaimWaiter waiter = claimRetryWaiters.remove(island);
            if (waiter == null) return;
            releasedClaims.add(waiter);
            wake();
        }

        static long retryDelayNanos(IslandTickUnit.AttemptOutcome outcome) {
            return outcome == IslandTickUnit.AttemptOutcome.HOLD
                    ? READINESS_RETRY_NANOS : CLAIM_RETRY_NANOS;
        }

        /**
         * Pure exactly-one authority for one island. A replacement ticket
         * invalidates the previous queue entry before it can submit; an entry
         * that has begun an attempt remains authoritative until either the unit
         * boundary replaces it or the poll receipt reports refusal.
         * Package-private for the focused concurrency-free state tests.
         */
        static final class TicketState {
            record Ticket(long id, long pacingGen, long deadlineNanos, long fireAtNanos) {}

            private long nextId;
            private Ticket current;
            private boolean queued;
            private boolean inFlight;
            /** Pacer-daemon confined: last claim-release pull (bounds pulls to one
             *  per claim-retry period, so short Main claim churn cannot turn the
             *  2 ms retry into one attempt per release). */
            long lastPullNanos = Long.MIN_VALUE;

            synchronized Ticket arm(long pacingGen, long deadlineNanos,
                                    long fireAtNanos, boolean replaceInFlight) {
                Ticket fresh = prepareArm(pacingGen, deadlineNanos, fireAtNanos,
                        replaceInFlight);
                if (fresh != null) acceptArm(fresh);
                return fresh;
            }

            /** Caller must hold this monitor until either acceptArm or discard. */
            private Ticket prepareArm(long pacingGen, long deadlineNanos,
                                      long fireAtNanos, boolean replaceInFlight) {
                Ticket old = current;
                if (inFlight && !replaceInFlight
                        && old != null && old.pacingGen == pacingGen) {
                    return null;
                }
                if (queued && old != null
                        && old.pacingGen == pacingGen
                        && old.deadlineNanos == deadlineNanos) {
                    // The queued ticket already owns this exact pacing frontier.
                    // Main's watchdog must not duplicate or pre-empt the one
                    // queued authority merely because its logical deadline is
                    // already due.
                    return null;
                }
                return new Ticket(++nextId, pacingGen, deadlineNanos, fireAtNanos);
            }

            /**
             * The queued ticket {@code expectedId} pulled forward to its own pacing
             * frontier (never before its deadline): the replacement supersedes it
             * once accepted. Null when that exact ticket is no longer the queued
             * authority of this generation or already fires no later.
             */
            synchronized Ticket prepareEarlier(long expectedId, long pacingGen, long nowNanos) {
                Ticket old = current;
                if (!queued || old == null || old.id != expectedId
                        || old.pacingGen != pacingGen) return null;
                long fireAt = old.deadlineNanos == 0
                        ? nowNanos : Math.max(nowNanos, old.deadlineNanos);
                if (fireAt >= old.fireAtNanos) return null;
                return new Ticket(++nextId, pacingGen, old.deadlineNanos, fireAt);
            }

            /** Publication receipt commit; caller still holds this monitor. */
            private void acceptArm(Ticket fresh) {
                current = fresh;
                queued = true;
                inFlight = false;
            }

            /** Failed publication cleanup after W was selected. */
            private void cancelAcceptedArm(Ticket ticket) {
                if (current != ticket) return;
                nextId++;
                current = null;
                queued = false;
                inFlight = false;
            }

            synchronized boolean begin(Ticket ticket) {
                if (!queued || current != ticket) return false;
                queued = false;
                inFlight = true;
                return true;
            }

            synchronized boolean finish(long ticketId) {
                if (!inFlight || current == null || current.id != ticketId) return false;
                inFlight = false;
                return true;
            }

            synchronized void cancel() {
                nextId++;
                current = null;
                queued = false;
                inFlight = false;
            }
        }

        static final class Entry implements java.util.concurrent.Delayed {
            private static final int OFFERING = 0;
            private static final int ACCEPTED = 1;
            private static final int CANCELLED = 2;
            /** Bound of one OFFERING park; the decision itself unparks the daemon. */
            private static final long OFFERING_PARK_NANOS = 1_000_000L;

            final Pacer pacer;
            final RegionizedServer rs;
            final IslandRegistry.PIsland island;
            final TicketState state;
            final TicketState.Ticket ticket;
            final long islandGen;
            /** One-shot result written only inside this entry's admitted fire body. */
            IslandTickUnit.AttemptReceipt firedAttempt;
            private final java.util.concurrent.atomic.AtomicInteger publication =
                    new java.util.concurrent.atomic.AtomicInteger(OFFERING);

            Entry(Pacer pacer, RegionizedServer rs, IslandRegistry.PIsland island,
                  TicketState state, TicketState.Ticket ticket) {
                this.pacer = pacer;
                this.rs = rs;
                this.island = island;
                this.state = state;
                this.ticket = ticket;
                this.islandGen = island.gen;
            }

            void acceptPublication() {
                if (!publication.compareAndSet(OFFERING, ACCEPTED)) {
                    throw new IllegalStateException("pacer ticket publication was already decided");
                }
                pacer.wake();
            }

            void cancelPublication() {
                if (publication.compareAndSet(OFFERING, CANCELLED)) pacer.wake();
            }

            /** The node became visible before its publisher's success cut decided
             *  it; park (not spin) until that decision unparks the daemon. A stop
             *  interrupt or a selected W ends the wait: an interrupted park returns
             *  at once and would otherwise spin until the publisher decides. */
            boolean awaitAcceptedPublication() throws InterruptedException {
                int state;
                while ((state = publication.get()) == OFFERING) {
                    if (Thread.interrupted()) throw new InterruptedException();
                    RegionScheduler.throwWorkerFailureIfAny();
                    java.util.concurrent.locks.LockSupport.parkNanos(this, OFFERING_PARK_NANOS);
                }
                return state == ACCEPTED;
            }

            @Override
            public long getDelay(java.util.concurrent.TimeUnit unit) {
                return unit.convert(ticket.fireAtNanos - System.nanoTime(),
                        java.util.concurrent.TimeUnit.NANOSECONDS);
            }

            @Override
            public int compareTo(java.util.concurrent.Delayed o) {
                return Long.compare(ticket.fireAtNanos, ((Entry) o).ticket.fireAtNanos);
            }
        }

        /** Pacer-thread-local ownership of an admitted publication attempt.
         *  Workers only flip the poll-only receipt and unpark its watcher; they
         *  never enqueue or invoke a continuation at the terminal edge. */
        private record ActiveAttempt(Entry entry,
                                     IslandTickUnit.AttemptReceipt receipt) {}

        /**
         * A pacer successor whose DelayQueue node is visible but cannot begin.
         * Unit completion accepts this receipt in the same scheduler cut as L
         * and its other success publications; a competing W cancels it instead.
         */
        static final class PreparedArm {
            private static final PreparedArm NONE =
                    new PreparedArm(null, null, null, null);

            private final TicketState state;
            private final TicketState.Ticket ticket;
            private final Entry entry;
            private final TicketState.Ticket expectedInFlight;
            /** Claim-release pull: the exact queued ticket this one supersedes. */
            private final TicketState.Ticket expectedQueued;

            private PreparedArm(TicketState state, TicketState.Ticket ticket, Entry entry,
                                TicketState.Ticket expectedInFlight) {
                this(state, ticket, entry, expectedInFlight, null);
            }

            private PreparedArm(TicketState state, TicketState.Ticket ticket, Entry entry,
                                TicketState.Ticket expectedInFlight,
                                TicketState.Ticket expectedQueued) {
                this.state = state;
                this.ticket = ticket;
                this.entry = entry;
                this.expectedInFlight = expectedInFlight;
                this.expectedQueued = expectedQueued;
            }

            void commit() {
                if (entry == null) return;
                synchronized (state) {
                    TicketState.Ticket current = state.current;
                    if (expectedInFlight != null
                            && (current != expectedInFlight || !state.inFlight)) {
                        // Unit completion/control already replaced the attempt
                        // between prepare and the global success cut.
                        entry.cancelPublication();
                        return;
                    }
                    if (expectedQueued != null
                            && (current != expectedQueued || !state.queued)) {
                        // The pulled retry was consumed, replaced or cancelled
                        // meanwhile; never resurrect authority over it.
                        entry.cancelPublication();
                        return;
                    }
                    // A newer independently prepared/committed control ticket
                    // supersedes this one.  Never resurrect an older deadline.
                    if (current != null && current.id() > ticket.id()) {
                        entry.cancelPublication();
                        return;
                    }
                    state.acceptArm(ticket);
                    entry.acceptPublication();
                }
            }

            Throwable cancel(Throwable primary) {
                if (entry == null) return primary;
                entry.cancelPublication();
                synchronized (state) {
                    // Also rolls back the exact state write if a supposedly
                    // fixed commit primitive ever failed after acceptArm.
                    state.cancelAcceptedArm(ticket);
                }
                return primary;
            }
        }

        private PreparedArm prepareArm(RegionizedServer rs, IslandRegistry.PIsland island,
                                       long deadlineNanos, long fireAtNanos,
                                       boolean replaceInFlight) {
            return prepareArm(rs, island, deadlineNanos, fireAtNanos,
                    replaceInFlight, null);
        }

        private PreparedArm prepareArm(RegionizedServer rs, IslandRegistry.PIsland island,
                                       long deadlineNanos, long fireAtNanos,
                                       boolean replaceInFlight,
                                       TicketState.Ticket expectedInFlight) {
            if (!eligible(rs, island)) {
                cancel(island);
                return PreparedArm.NONE;
            }
            if (!ensureStarted() || !eligible(rs, island)) {
                cancel(island);
                return PreparedArm.NONE;
            }
            TicketState state = states.computeIfAbsent(island, ignored -> new TicketState());
            TicketState.Ticket ticket;
            synchronized (state) {
                if (expectedInFlight != null
                        && (state.current != expectedInFlight || !state.inFlight)) {
                    return PreparedArm.NONE;
                }
                ticket = state.prepareArm(island.pacingGen,
                        deadlineNanos, fireAtNanos, replaceInFlight);
                if (ticket == null) return PreparedArm.NONE;
            }
            Entry entry = new Entry(this, rs, island, state, ticket);
            try {
                // The consumer may observe the node immediately, but its
                // OFFERING receipt blocks begin() until the caller commits;
                // that decision (not this add) wakes the daemon.
                queue.add(entry);
                return new PreparedArm(state, ticket, entry, expectedInFlight);
            } catch (Throwable publicationFailure) {
                Throwable failure = RegionScheduler.publishFatalAndSelectWinner(rs,
                        publicationFailure);
                entry.cancelPublication();
                FailStops.<RuntimeException>rethrow(failure);
                return PreparedArm.NONE;
            }
        }

        private void arm(RegionizedServer rs, IslandRegistry.PIsland island,
                         long deadlineNanos, long fireAtNanos, boolean replaceInFlight) {
            PreparedArm prepared = null;
            try {
                prepared = prepareArm(rs, island, deadlineNanos, fireAtNanos,
                        replaceInFlight);
                PreparedArm commit = prepared;
                if (!RegionScheduler.commitUnitSuccessIfCurrent(rs, commit::commit)) {
                    commit.cancel(null);
                    cancel(island);
                }
            } catch (Throwable armFailure) {
                Throwable failure = RegionScheduler.publishFatalAndSelectWinner(
                        rs, armFailure);
                if (prepared != null) failure = prepared.cancel(failure);
                FailStops.<RuntimeException>rethrow(failure);
            }
        }

        /** Main-side bootstrap/watchdog. Idempotent while a matching ticket or
         *  attempt already owns the island's current deadline. */
        void ensure(RegionizedServer rs, IslandRegistry.PIsland island) {
            if (!eligible(rs, island)) {
                cancel(island);
                return;
            }
            if (isCoherent(rs, island)) {
                cancel(island); // wall-coherent mode remains main-driven
                return;
            }
            long now = System.nanoTime();
            long deadline = island.nextDeadlineNanos;
            long fireAt = deadline == 0 ? now : Math.max(now, deadline);
            arm(rs, island, deadline, fireAt, false);
        }

        PreparedArm prepareAfterUnit(RegionizedServer rs, IslandRegistry.PIsland island,
                                     long deadlineNanos) {
            if (!eligible(rs, island)) {
                cancel(island);
                return PreparedArm.NONE;
            }
            if (isCoherent(rs, island)) {
                cancel(island);
                return PreparedArm.NONE;
            }
            var server = RegionScheduler.getServer();
            if (server != null && server.tickRateManager().isSprinting()) {
                return prepareArm(rs, island, deadlineNanos,
                        System.nanoTime() + CONTROL_RETRY_NANOS, true);
            }
            long fireAt = deadlineNanos == 0
                    ? System.nanoTime() : Math.max(System.nanoTime(), deadlineNanos);
            return prepareArm(rs, island, deadlineNanos, fireAt, true);
        }

        /** Explicit rate/sprint transition. An older pacing generation becomes
         *  inert immediately, even when its previous unit is still finishing. */
        void reseed(RegionizedServer rs, IslandRegistry.PIsland island) {
            if (!eligible(rs, island)) {
                cancel(island);
                return;
            }
            if (isCoherent(rs, island)) {
                cancel(island);
                return;
            }
            long now = System.nanoTime();
            long deadline = island.nextDeadlineNanos;
            arm(rs, island, deadline, deadline == 0 ? now : Math.max(now, deadline), true);
        }

        /** Topology commit: retire dead identities, then seed every fresh or
         *  surviving ordinary island after the quiesce claims were released. */
        void reconcile(RegionizedServer rs) {
            if (rs == null || !rs.asyncActive) {
                cancelLevel(rs);
                return;
            }
            java.util.Set<IslandRegistry.PIsland> live =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            live.addAll(rs.islandRegistry.islands());
            states.forEach((island, state) -> {
                if (island.level == rs.level && !live.contains(island)) {
                    state.cancel();
                    states.remove(island, state);
                }
            });
            queue.removeIf(entry -> entry.rs == rs && !live.contains(entry.island));
            claimRetryWaiters.keySet().removeIf(
                    island -> island.level == rs.level && !live.contains(island));
            for (IslandRegistry.PIsland island : live) ensure(rs, island);
        }

        private void cancelLevel(RegionizedServer rs) {
            if (rs == null) return;
            states.forEach((island, state) -> {
                if (island.level == rs.level) {
                    state.cancel();
                    states.remove(island, state);
                }
            });
            queue.removeIf(entry -> entry.rs == rs);
            claimRetryWaiters.values().removeIf(waiter -> waiter.rs() == rs);
        }

        private void cancel(IslandRegistry.PIsland island) {
            claimRetryWaiters.remove(island);
            TicketState state = states.remove(island);
            if (state != null) state.cancel();
        }

        private void cancelAll() {
            states.forEach((island, state) -> state.cancel());
            states.clear();
            queue.clear();
            claimRetryWaiters.clear();
            releasedClaims.clear();
        }

        private static boolean eligible(RegionizedServer rs, IslandRegistry.PIsland island) {
            return rs != null && island != null && rs.asyncActive && !island.retired
                    && RegionScheduler.acceptsUnitFor(rs)
                    && !com.axalotl.fabrolia.common.config.FabroliaConfig.asyncFailClosedTripped
                    && island.level != null
                    && RegionScheduler.forLevel(island.level) == rs;
        }

        boolean ensureStarted() {
            boolean interrupted = false;
            try {
                synchronized (this) {
                    // A new runtime must not mistake the terminating daemon for
                    // a live owner. Wait for shutdownNormally to join and retire
                    // that exact identity before publishing the replacement.
                    while (normalStopOwner != null) {
                        // This daemon cannot wait for the join of itself. Retire
                        // its arm, leaving the stop interrupt for the run loop.
                        if (normalStopOwner == Thread.currentThread()) {
                            RegionScheduler.throwWorkerFailureIfAny();
                            return false;
                        }
                        try {
                            wait();
                        } catch (InterruptedException waitInterrupt) {
                            interrupted = true;
                        }
                    }
                    RegionScheduler.throwWorkerFailureIfAny();
                    if (thread == null) {
                        Thread t = new Thread(this, "Fabrolia-Rate-Pacer");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY + 1);
                        thread = t;
                        try {
                            t.start();
                        } catch (Throwable startFailure) {
                            if (thread == t) thread = null;
                            notifyAll();
                            FailStops.<RuntimeException>rethrow(startFailure);
                        }
                    }
                    return true;
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /** Normal scheduler teardown.  The identity marker is installed before
         *  interrupt, so the loop can distinguish this wakeup from an external
         *  interrupt which would otherwise silently abandon every pacing ticket. */
        void shutdownNormally() {
            Thread owner;
            synchronized (this) {
                owner = thread;
                normalStopOwner = owner;
            }
            Throwable failure = null;
            try {
                cancelAll();
            } catch (Throwable cleanupFailure) {
                failure = cleanupFailure;
            }
            if (owner != null) {
                try {
                    owner.interrupt();
                } catch (Throwable interruptFailure) {
                    failure = FailStops.mergeFailure(failure, interruptFailure);
                }
            }
            boolean interrupted = false;
            if (owner == Thread.currentThread()) {
                failure = FailStops.mergeFailure(failure, new IllegalStateException(
                        "rate pacer cannot join itself during normal shutdown"));
            } else if (owner != null) {
                while (owner.isAlive()) {
                    try {
                        owner.join();
                    } catch (InterruptedException waitInterrupt) {
                        // Shutdown ownership does not move on interruption. Keep
                        // waiting for the exact daemon and restore Main's flag.
                        interrupted = true;
                    } catch (Throwable waitFailure) {
                        failure = FailStops.mergeFailure(failure, waitFailure);
                        java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
                    }
                }
            }
            try {
                synchronized (this) {
                    if (thread == owner && (owner == null || !owner.isAlive())) {
                        thread = null;
                    }
                    if (normalStopOwner == owner) normalStopOwner = null;
                    notifyAll();
                }
            } catch (Throwable cleanupFailure) {
                failure = FailStops.mergeFailure(failure, cleanupFailure);
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (failure != null) FailStops.<RuntimeException>rethrow(failure);
        }

        synchronized Thread ownerThreadForTest() {
            return thread;
        }

        @Override
        public void run() {
            Throwable failure = null;
            try {
                java.util.ArrayList<ActiveAttempt> activeAttempts =
                        new java.util.ArrayList<>();
                pacerLoop:
                while (true) {
                    RegionScheduler.throwWorkerFailureIfAny();
                    for (int i = 0; i < activeAttempts.size();) {
                        ActiveAttempt active = activeAttempts.get(i);
                        IslandTickUnit.AttemptReceipt completed = active.receipt();
                        if (!completed.isDone()) {
                            i++;
                            continue;
                        }
                        activeAttempts.remove(i);
                        try {
                            Entry entry = active.entry();
                            if (!RegionScheduler.runPacerBodyIfCurrent(entry.rs,
                                    () -> finishAttempt(entry, completed))) {
                                cancel(entry.island);
                            }
                        } catch (InterruptedException interrupted) {
                            throw interrupted;
                        } catch (Throwable entryFailure) {
                            failure = terminalizeEntryFailure(
                                    active.entry(), entryFailure);
                            break pacerLoop;
                        }
                    }

                    // Interrupt semantics of the former interruptible poll.
                    if (Thread.interrupted()) throw new InterruptedException();
                    pullReleasedClaimRetries();
                    Entry e = queue.poll();
                    if (e == null) {
                        // Every event that can make work due earlier unparks
                        // this sole owner: an entry decision, a watched receipt
                        // reaching terminal. The cap keeps the fail-stop check
                        // above at the former idle cadence.
                        parkUntilDue();
                        continue;
                    }
                    try {
                        RegionScheduler.throwWorkerFailureIfAny();
                        if (!e.awaitAcceptedPublication()) continue;
                        if (!e.state.begin(e.ticket)) continue; // superseded queue entry
                        if (!RegionScheduler.runPacerBodyIfCurrent(
                                e.rs, () -> e.firedAttempt = fire(e))) {
                            cancel(e.island);
                            continue;
                        }
                        if (e.firedAttempt != null) {
                            activeAttempts.add(new ActiveAttempt(e, e.firedAttempt));
                            // Registered before the next isDone sweep: a terminal
                            // edge either precedes that read or wakes us.
                            e.firedAttempt.wakeOnTerminal(this);
                        }
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    } catch (Throwable entryFailure) {
                        failure = terminalizeEntryFailure(e, entryFailure);
                        break pacerLoop;
                    }
                }
            } catch (InterruptedException interrupted) {
                boolean expectedStop;
                synchronized (this) {
                    expectedStop = normalStopOwner == Thread.currentThread();
                }
                Throwable selected = RegionScheduler.workerFailureOrNull();
                if (selected != null) {
                    // stopPacerAfterFatal uses interrupt only as a wakeup. W was
                    // already selected and remains the exact primary.
                    failure = selected;
                } else if (!expectedStop) {
                    // The interruption itself is the first escaped Throwable;
                    // preserve that exact object as W.
                    failure = RegionScheduler.publishFatalAndSelectWinner(interrupted);
                }
            } catch (Throwable escaped) {
                failure = RegionScheduler.publishFatalAndSelectWinner(escaped);
            } finally {
                if (failure != null) {
                    try {
                        cancelAll();
                    } catch (Throwable cleanupFailure) {
                        failure = RegionScheduler.publishFatalAndSelectWinner(
                                FailStops.mergeFailure(failure, cleanupFailure));
                    }
                }
                try {
                    synchronized (this) {
                        if (thread == Thread.currentThread()) thread = null;
                        // shutdownNormally owns this marker until its exact join
                        // returns; an arriving ensureStarted cannot cross early.
                        notifyAll();
                    }
                } catch (Throwable cleanupFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            FailStops.mergeFailure(failure, cleanupFailure));
                }
            }
            if (failure != null) FailStops.<RuntimeException>rethrow(failure);
        }

        /** Park until the head is due (capped). A wake signalled since the last
         *  park is consumed here instead: its unpark may already have been
         *  absorbed by a park inside the queue lock or a scheduler monitor, and
         *  any signal raised after this read leaves the permit for parkNanos. */
        void parkUntilDue() {
            Entry head = queue.peek();
            long waitNanos = CONTROL_RETRY_NANOS;
            if (head != null) {
                waitNanos = Math.min(waitNanos,
                        head.getDelay(java.util.concurrent.TimeUnit.NANOSECONDS));
            }
            if (waitNanos <= 0L || signalled.getAndSet(false)) return;
            java.util.concurrent.locks.LockSupport.parkNanos(this, waitNanos);
        }

        private Throwable terminalizeEntryFailure(Entry entry, Throwable thrown) {
            // begin() may have transferred the only ordinary-cadence authority
            // into this iteration. Select W before retiring that ticket; never
            // seed a fresh tick after the failure.
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(
                    entry.rs, thrown);
            try {
                entry.state.finish(entry.ticket.id);
            } catch (Throwable cleanupFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(entry.rs,
                        FailStops.mergeFailure(failure, cleanupFailure));
            }
            return failure;
        }

        private IslandTickUnit.AttemptReceipt fire(Entry e) {
            if (!eligible(e.rs, e.island) || e.island.gen != e.islandGen) {
                cancel(e.island);
                return null;
            }
            if (e.ticket.pacingGen != e.island.pacingGen) {
                finishAndReseed(e, 0L);
                return null;
            }
            if (isCoherent(e.rs, e.island)) {
                finishTerminal(e); // coherent wall cadence is intentionally unchanged
                return null;
            }

            var server = RegionScheduler.getServer();
            if (server != null && (!server.tickRateManager().runsNormally()
                    || server.tickRateManager().isSprinting())) {
                finishAndReseed(e, CONTROL_RETRY_NANOS);
                return null;
            }
            if (e.island.mainInterest()
                    || e.rs.mainClaimsFenceActive()
                    || e.island.unitClaim.get()
                    || e.island.submitQueued.get()) {
                // A MAIN_SYNC service request whose island sits idle (its last
                // unit ended before the request, or a short non-unit hold made
                // Main's double check miss it): nobody else wakes an idle Main.
                if (e.island.mainServiceWanted() && !e.island.unitClaim.get()) {
                    RegionScheduler.requestMainSyncService();
                }
                TicketState.Ticket retry = finishAndReseed(e, CLAIM_RETRY_NANOS);
                long now = System.nanoTime();
                long lastPull = e.state.lastPullNanos;
                if (retry != null && e.island.unitClaim.get()
                        && (lastPull == Long.MIN_VALUE || now - lastPull >= CLAIM_RETRY_NANOS)) {
                    // Register, then re-read: Main clears the claim before it
                    // looks the waiter up, so a release either finds this
                    // registration or is observed here (pulled at once).
                    ClaimWaiter waiter = new ClaimWaiter(e.rs, e.island, retry.id());
                    claimRetryWaiters.put(e.island, waiter);
                    if (!e.island.unitClaim.get()
                            && claimRetryWaiters.remove(e.island, waiter)) {
                        pullForward(waiter);
                    }
                }
                return null;
            }

            // A local sprint keeps its existing exact-count self-chain. The
            // pacer only supplies an idempotent bootstrap if the command-time
            // kick or a fence refusal left no unit queued.
            if (isSprinting(e.rs, e.island)) {
                return IslandTickUnit.submitUnit(e.rs, e.island);
            }
            long now = System.nanoTime();
            long deadline = e.island.nextDeadlineNanos;
            if (deadline != 0 && deadline > now) {
                finishAndReseed(e, deadline - now);
                return null;
            }
            return IslandTickUnit.submitDeadlineUnit(e.rs, e.island, now, 0L);
        }

        private void finishAttempt(Entry e, IslandTickUnit.AttemptReceipt attempt) {
                Throwable failure = attempt.failureOrNull();
                if (failure != null) {
                    // UnitPublication completed exceptionally only after its
                    // entered body (or failed-generation cancellation) had
                    // already selected this exact W in the owning pool.
                    Throwable primary = failure;
                    try {
                        // Terminal cleanup only. A failed unit must never seed
                        // another cadence attempt as if it merely refused.
                        e.state.finish(e.ticket.id);
                    } catch (Throwable cleanup) {
                        primary = RegionScheduler.publishFatalAndSelectWinner(e.rs,
                                FailStops.mergeFailure(primary, cleanup));
                    }
                    if (RegionScheduler.ownsRuntimeGeneration(e.rs)) {
                        stopPacerAfterFatal(e.rs, primary);
                        FailStops.<RuntimeException>rethrow(primary);
                    } else {
                        cancel(e.island);
                    }
                    return;
                }
                if (!eligible(e.rs, e.island) || e.island.gen != e.islandGen) {
                    // Either an accepted unit finished while its generation was
                    // retiring, or submitStampedEntered retired an unaccepted receipt.
                    // This is pacer bookkeeping only: destroy the old authority,
                    // publish no successor and never touch the current generation.
                    cancel(e.island);
                    return;
                }
                // If complete() published a successor, finish() is stale and
                // the conditional retry receipt is cancelled at commit.  A
                // refusal still owns this exact in-flight ticket, so its
                // replacement and the old-ticket terminal share one W cut.
                if (attempt.outcome() == IslandTickUnit.AttemptOutcome.ADVANCED) {
                    // The unit's sole L++ cut already replaced this in-flight
                    // ticket with its next pacer authority. Retire only the old
                    // ticket if no replacement was present.
                    finishTerminal(e);
                    return;
                }
                PreparedArm retry = null;
                try {
                    long retryDelay = retryDelayNanos(attempt.outcome());
                    retry = prepareRetryAfterAttempt(e, retryDelay);
                    PreparedArm retryCommit = retry;
                    boolean committed = RegionScheduler.commitUnitSuccessIfCurrent(e.rs, () -> {
                        retryCommit.commit();
                        // False after either a unit-success successor or the
                        // conditional replacement above; true only when the
                        // attempt terminally returned authority without rearm.
                        e.state.finish(e.ticket.id);
                        e.island.chainedUnits++;
                    });
                    if (!committed) {
                        retry.cancel(null);
                        cancel(e.island);
                    }
                } catch (Throwable completionFailure) {
                    Throwable primary = RegionScheduler.publishFatalAndSelectWinner(
                            e.rs, completionFailure);
                    if (retry != null) primary = retry.cancel(primary);
                    if (RegionScheduler.ownsRuntimeGeneration(e.rs)) {
                        stopPacerAfterFatal(e.rs, primary);
                        FailStops.<RuntimeException>rethrow(primary);
                    } else {
                        cancel(e.island);
                    }
                }
        }

        /** Close every pacing authority after an observed current-generation W
         *  and interrupt the poll so this daemon cannot consume another ticket. */
        private void stopPacerAfterFatal(RegionizedServer expected, Throwable primary) {
            Throwable failure = primary;
            try {
                cancelAll();
            } catch (Throwable cleanupFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(expected,
                        FailStops.mergeFailure(failure, cleanupFailure));
            }
            Thread owner = thread;
            if (owner != null) {
                try {
                    owner.interrupt();
                } catch (Throwable cleanupFailure) {
                    RegionScheduler.publishFatalAndSelectWinner(expected,
                            FailStops.mergeFailure(failure, cleanupFailure));
                }
            }
        }

        /** @return the committed retry ticket, or null when none was armed. */
        private TicketState.Ticket finishAndReseed(Entry e, long delayNanos) {
            PreparedArm retry = null;
            try {
                retry = prepareRetryAfterAttempt(e, delayNanos);
                PreparedArm retryCommit = retry;
                boolean committed = RegionScheduler.commitUnitSuccessIfCurrent(e.rs, () -> {
                    retryCommit.commit();
                    e.state.finish(e.ticket.id);
                });
                if (!committed) {
                    retry.cancel(null);
                    cancel(e.island);
                    return null;
                }
                return retry.ticket;
            } catch (Throwable completionFailure) {
                Throwable primary = RegionScheduler.publishFatalAndSelectWinner(
                        e.rs, completionFailure);
                if (retry != null) primary = retry.cancel(primary);
                FailStops.<RuntimeException>rethrow(primary);
                return null;
            }
        }

        /**
         * Daemon: re-arm every island whose held claim Main released since the
         * last pass to fire at its pacing frontier now, superseding exactly the
         * claim retry that was refused. A stale runtime, an ineligible island or
         * a retry already consumed/replaced is left to the existing authority.
         */
        private void pullReleasedClaimRetries() throws InterruptedException {
            ClaimWaiter released;
            while ((released = releasedClaims.poll()) != null) {
                ClaimWaiter waiter = released;
                RegionScheduler.runPacerBodyIfCurrent(waiter.rs(), () -> pullForward(waiter));
            }
        }

        private void pullForward(ClaimWaiter waiter) {
            RegionizedServer rs = waiter.rs();
            IslandRegistry.PIsland island = waiter.island();
            if (!eligible(rs, island) || isCoherent(rs, island)) return;
            TicketState state = states.get(island);
            if (state == null) return;
            long now = System.nanoTime();
            TicketState.Ticket superseded;
            TicketState.Ticket ticket;
            synchronized (state) {
                superseded = state.current;
                ticket = state.prepareEarlier(waiter.ticketId(), island.pacingGen, now);
            }
            if (ticket == null) return;
            state.lastPullNanos = now;
            Entry entry = new Entry(this, rs, island, state, ticket);
            PreparedArm prepared = null;
            try {
                queue.add(entry);
                prepared = new PreparedArm(state, ticket, entry, null, superseded);
                PreparedArm commit = prepared;
                if (!RegionScheduler.commitUnitSuccessIfCurrent(rs, commit::commit)) {
                    commit.cancel(null);
                    cancel(island);
                }
            } catch (Throwable armFailure) {
                Throwable failure = RegionScheduler.publishFatalAndSelectWinner(rs, armFailure);
                failure = prepared != null ? prepared.cancel(failure) : failure;
                if (prepared == null) entry.cancelPublication();
                FailStops.<RuntimeException>rethrow(failure);
            }
        }

        private PreparedArm prepareRetryAfterAttempt(Entry e, long delayNanos) {
            if (!eligible(e.rs, e.island)) {
                return PreparedArm.NONE;
            }
            if (isCoherent(e.rs, e.island)) {
                return PreparedArm.NONE;
            }
            long deadline = e.island.nextDeadlineNanos;
            long fireAt = delayNanos > 0
                    ? System.nanoTime() + delayNanos
                    : (deadline == 0 ? System.nanoTime()
                            : Math.max(System.nanoTime(), deadline));
            return prepareArm(e.rs, e.island, deadline, fireAt, true, e.ticket);
        }

        private void finishTerminal(Entry e) {
            if (!RegionScheduler.commitUnitSuccessIfCurrent(
                    e.rs, () -> e.state.finish(e.ticket.id))) {
                cancel(e.island);
            }
        }
    }

    /** Main drive bootstrap/watchdog; never directly submits an ordinary unit. */
    public static void ensurePaced(RegionizedServer rs, IslandRegistry.PIsland island) {
        PACER.ensure(rs, island);
    }

    /** Prepare, but do not yet accept, the unit's ordinary-cadence successor. */
    static Pacer.PreparedArm prepareArmAfterUnit(RegionizedServer rs,
                                                 IslandRegistry.PIsland island,
                                                 long deadlineNanos) {
        return PACER.prepareAfterUnit(rs, island, deadlineNanos);
    }

    /** Rebuild/toggle transaction completed and its claims have been released. */
    static void onTopologyCommitted(RegionizedServer rs) {
        PACER.reconcile(rs);
    }

    // Per-level session registry lives on RegionizedServer.temporalSessions.

    /** REPLACE SEMANTICS (2026-07-14 "rate stops applying" fix): an explicit
     *  rate command on an island REPLACES every rate session touching it. The
     *  old append-only behavior + the lower-tps-wins conflict scan meant the
     *  lowest rate ever set stayed effective forever (a 20→40→60 ladder
     *  wedged at the first low value — the user's "ignored after ~3 uses").
     *  Lower-wins now applies only to sessions that MEET through a topology
     *  merge; one control source re-issuing on the same island replaces.
     *  @return replaced session ids for command feedback. */
    public static long[] replaceRateSessions(RegionizedServer rs, IslandRegistry.PIsland island) {
        java.util.ArrayList<Long> removed = new java.util.ArrayList<>(2);
        rs.temporalSessions.removeIf(s -> {
            if (s.rateNanos > 0 && s.sprintRemaining.get() == 0 && intersects(s.cells, island.cells)) {
                removed.add(s.id);
                return true;
            }
            return false;
        });
        long[] out = new long[removed.size()];
        for (int i = 0; i < out.length; i++) out[i] = removed.get(i);
        return out;
    }

    public static Session addCoherentSession(RegionizedServer rs, IslandRegistry.PIsland island,
                                             String label) {
        Session s = new Session(label);
        s.coherent = true;
        s.cells.addAll(island.cells);
        rs.temporalSessions.add(s);
        island.pacingGen++;
        PACER.reseed(rs, island);
        return s;
    }

    public static boolean intersectsPublic(it.unimi.dsi.fastutil.longs.LongOpenHashSet a,
                                           it.unimi.dsi.fastutil.longs.LongOpenHashSet b) {
        return intersects(a, b);
    }

    public static boolean isCoherent(RegionizedServer rs, IslandRegistry.PIsland island) {
        List<Session> sessions = rs.temporalSessions;
        if (sessions.isEmpty()) return false;
        for (Session s : sessions) {
            if (s.coherent && intersects(s.cells, island.cells)) return true;
        }
        return false;
    }

    public static Session addRateSession(RegionizedServer rs, IslandRegistry.PIsland island,
                                         double tps, String label) {
        Session s = new Session(label);
        s.rateNanos = (long) (1_000_000_000.0 / Math.max(0.01, tps));
        s.cells.addAll(island.cells);
        rs.temporalSessions.add(s);
        island.pacingGen++;
        PACER.reseed(rs, island);
        return s;
    }

    public static Session addSprintSession(RegionizedServer rs, IslandRegistry.PIsland island,
                                           long ticks, String label) {
        // Later sprint replaces earlier sprint on the same island (same
        // control-source invariant as rates; merge-shared decrements still
        // satisfy distinct-lineage sessions concurrently). Finished sprints
        // anywhere in the level go too: they no longer affect any island.
        rs.temporalSessions.removeIf(s2 -> finishedSprint(s2)
                || s2.sprintRemaining.get() > 0 && s2.rateNanos == 0
                && intersects(s2.cells, island.cells));
        Session s = new Session(label);
        s.sprintRemaining.set(Math.max(0, ticks));
        s.cells.addAll(island.cells);
        rs.temporalSessions.add(s);
        island.pacingGen++;
        PACER.reseed(rs, island);
        return s;
    }

    /** A sprint session whose ticks are used up and that carries no rate or pin:
     *  every reader skips it, and its counter only ever decreases. */
    private static boolean finishedSprint(Session s) {
        return !s.coherent && s.rateNanos == 0 && s.sprintRemaining.get() <= 0;
    }

    /** Pending unit-boundary commit acknowledgements for rate/sprint commands:
     *  feedback prints only after the island's completing unit observed the
     *  new pacing generation (appliedPacingGen). Bounded wait = liveness
     *  diagnostic, not silence. Main thread only. */
    private static final java.util.ArrayList<Object[]> PENDING_ACKS = new java.util.ArrayList<>();

    public static void awaitCommit(RegionizedServer rs, IslandRegistry.PIsland island,
                                   net.minecraft.commands.CommandSourceStack src, String what) {
        synchronized (PENDING_ACKS) {
            PENDING_ACKS.add(new Object[]{rs, island, island.pacingGen, 0L, src, what});
        }
    }

    /** Called once per main tick (overworld drive). */
    public static void tickAcks() {
        synchronized (PENDING_ACKS) {
            if (PENDING_ACKS.isEmpty()) return;
            var it = PENDING_ACKS.iterator();
            while (it.hasNext()) {
                Object[] a = it.next();
                var island = (IslandRegistry.PIsland) a[1];
                long gen = (Long) a[2];
                long age = (Long) a[3] + 1;
                a[3] = age;
                var src = (net.minecraft.commands.CommandSourceStack) a[4];
                if (island.retired) {
                    // topology replaced the object; the session is cell-keyed
                    // and remap re-attached it — confirm against any current
                    // island covering the old cells at the next rebuild tick.
                    src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            a[5] + ": island identity changed (merge/split) — session remapped by lineage"));
                    it.remove();
                } else if (island.appliedPacingGen >= gen) {
                    src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            a[5] + " committed at unit boundary (pacingGen " + gen
                            + ", L=" + island.logicalTick + ")"));
                    it.remove();
                } else if (age > 100) {
                    src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "WARN " + a[5] + " NOT committed after 100 main ticks — island #"
                            + island.id + " units are not completing (pacingGen " + island.pacingGen
                            + " applied " + island.appliedPacingGen + "); check /fabrolia regions island list"));
                    it.remove();
                }
            }
        }
    }

    /** Vanilla /tick rate: every domain gets the same target; explicit island
     *  rate overrides are cleared (the command means "set every domain"). */
    public static void setGlobalRate(float tps) {
        globalIslandTargetNanos = (long) (1_000_000_000.0 / Math.max(0.01, tps));
        for (RegionizedServer rs : RegionScheduler.allLevels()) {
            rs.temporalSessions.removeIf(s -> {
                if (s.sprintRemaining.get() > 0) {
                    return false; // sprints are tick-count obligations, keep
                }
                return true;
            });
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                p.pacingGen++;
                PACER.reseed(rs, p);
            }
        }
        LOGGER.info("[temporal] global island target rate set to {} tps", tps);
    }

    /** Effective pacing for one island: min explicit rate override, else global. */
    public static long effectiveTargetNanos(RegionizedServer rs, IslandRegistry.PIsland island) {
        long best = 0;
        List<Session> sessions = rs.temporalSessions;
        if (!sessions.isEmpty()) {
            for (Session s : sessions) {
                // Coherence outranks every rate override: pinned islands run
                // at exactly the wall cadence (drive holds L <= W).
                if (s.coherent && intersects(s.cells, island.cells)) {
                    return 50_000_000L;
                }
                if (s.rateNanos > 0 && intersects(s.cells, island.cells)) {
                    if (best == 0 || s.rateNanos > best) {
                        best = s.rateNanos; // larger nanos = LOWER tps = conservative
                    }
                }
            }
        }
        return best != 0 ? best : globalIslandTargetNanos;
    }

    /** Is any sprint obligation active for this island? */
    public static boolean isSprinting(RegionizedServer rs, IslandRegistry.PIsland island) {
        if (isCoherent(rs, island)) return false; // pinned: sprint sessions inert

        List<Session> sessions = rs.temporalSessions;
        if (sessions.isEmpty()) {
            return false;
        }
        for (Session s : sessions) {
            if (s.sprintRemaining.get() > 0 && intersects(s.cells, island.cells)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Allocation/fallible-read half of a unit's temporal completion.  It is
     * deliberately prepared before the unit publishes L/deadline/metrics, so
     * session scans, wall-clock adapters and server lookups cannot leave a
     * partially successful unit behind.
     */
    static UnitCompletionDecision prepareUnitCompletion(RegionizedServer rs,
                                                        IslandRegistry.PIsland island,
                                                        long completingLogicalTick) {
        List<Session> sessions = rs.temporalSessions;
        if (sessions.isEmpty()) {
            return UnitCompletionDecision.NONE;
        }
        java.util.ArrayList<Session> decrements = null;
        boolean chain = false;
        for (Session s : sessions) {
            if (s.coherent && intersects(s.cells, island.cells)) {
                // Catch-up chaining: a pinned island that fell behind the
                // wall clock re-submits until L == W, then holds (the drive
                // releases exactly one unit per wall tick thereafter).
                if (completingLogicalTick < IslandClock.wallTime(island.level)) {
                    chain = true;
                }
                continue;
            }
            long rem = s.sprintRemaining.get();
            if (rem > 0 && intersects(s.cells, island.cells)) {
                if (decrements == null) decrements = new java.util.ArrayList<>(2);
                decrements.add(s);
                if (rem > 1) chain = true;
            }
        }
        // Global sprint lockstep owns tick production — never self-chain then.
        if (chain && RegionScheduler.getServer() != null
                && RegionScheduler.getServer().tickRateManager().isSprinting()) {
            chain = false;
        }
        Session[] decrementArray = decrements == null
                ? UnitCompletionDecision.NO_SESSIONS
                : decrements.toArray(Session[]::new);
        return new UnitCompletionDecision(decrementArray, chain);
    }

    /**
     * Prepared sprint-counter commit.  Completed zero-valued sessions remain
     * inert until the next main-side sprint command or topology remap prunes
     * them; CopyOnWrite removal and logging are intentionally not allowed to
     * turn a completed vanilla unit into a later failure.
     */
    static final class UnitCompletionDecision {
        private static final Session[] NO_SESSIONS = new Session[0];
        private static final UnitCompletionDecision NONE =
                new UnitCompletionDecision(NO_SESSIONS, false);

        private final Session[] decrements;
        private final boolean chain;

        private UnitCompletionDecision(Session[] decrements, boolean chain) {
            this.decrements = decrements;
            this.chain = chain;
        }

        boolean chain() {
            return chain;
        }

        void commit() {
            for (Session session : decrements) {
                long remaining;
                do {
                    remaining = session.sprintRemaining.get();
                    if (remaining <= 0) break;
                } while (!session.sprintRemaining.compareAndSet(remaining, remaining - 1));
            }
        }
    }

    /** Rebuild hook: re-resolve every session against fresh topology. A session
     *  intersecting an island expands to cover the WHOLE island; sessions that
     *  now share an island merge deterministically (rate: lower wins via the
     *  effective-rate scan; sprint: max obligation via shared decrements).
     *  Finished sprints are dropped first. */
    public static void remap(RegionizedServer rs) {
        List<Session> sessions = rs.temporalSessions;
        if (sessions.isEmpty()) {
            return;
        }
        sessions.removeIf(TemporalControl::finishedSprint);
        for (Session s : sessions) {
            LongOpenHashSet expanded = null;
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                if (intersects(s.cells, p.cells)) {
                    if (expanded == null) {
                        expanded = new LongOpenHashSet(s.cells);
                    }
                    boolean grew = expanded.addAll(p.cells);
                    if (grew) {
                        FlightRecorder.record("TEMPORAL session#" + s.id + " (" + s.label
                                + ") expanded onto island#" + p.id + " (merge lineage)");
                    }
                }
            }
            if (expanded != null) {
                s.cells = expanded; // copy-and-swap: readers see old OR new, never mid-rehash
            }
        }
    }

    public static String status(RegionizedServer rs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT, "main=%.1ftps/%.2fms ",
                TpsHud.mainTpsValue(), TpsHud.mainMsptNanos() / 1_000_000.0));
        sb.append("globalTarget=").append(String.format(java.util.Locale.ROOT, "%.1f",
                1_000_000_000.0 / globalIslandTargetNanos)).append("tps");
        var srv = RegionScheduler.getServer();
        if (srv != null && srv.tickRateManager().isSprinting()) {
            sb.append(" GLOBAL-SPRINT(lockstep)");
        }
        for (Session s : rs.temporalSessions) {
            sb.append(" | session#").append(s.id).append(" '").append(s.label).append("'");
            if (s.rateNanos > 0) {
                sb.append(" rate=").append(String.format(java.util.Locale.ROOT, "%.1f",
                        1_000_000_000.0 / s.rateNanos)).append("tps");
            }
            long rem = s.sprintRemaining.get();
            if (rem > 0) {
                sb.append(" sprintRemaining=").append(rem);
            }
            sb.append(" cells=").append(s.cells.size());
        }
        return sb.toString();
    }

    private static boolean intersects(LongOpenHashSet a, LongOpenHashSet b) {
        LongOpenHashSet small = a.size() <= b.size() ? a : b;
        LongOpenHashSet big = small == a ? b : a;
        var it = small.iterator();
        while (it.hasNext()) {
            if (big.contains(it.nextLong())) {
                return true;
            }
        }
        return false;
    }
}
