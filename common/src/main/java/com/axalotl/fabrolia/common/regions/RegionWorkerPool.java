package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Region-worker pool.
 *
 * <p>Ready regions are claimed with {@link Region#tryClaim}, published as the
 * worker's {@code currentRegion}, drained and released. A lost claim may run
 * one available holder helper under the existing region-lock discipline.
 * Rearming when work arrives during a drain prevents a lost wake from
 * stranding accepted work or its entity-barrier obligation.
 * Retained unit/global/Main-safe roots keep their admission and receipt paths.
 *
 * <p>Workers are not bound to regions; N≈cores-2 threads multiplex hundreds of regions.
 * Submit paths call {@link RegionScheduler#wakeWorkers()}; idle workers park with a
 * 10ms fallback timeout, so a missed wake costs at most one slice.
 */
public final class RegionWorkerPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionWorkerPool.class);
    private static final AtomicInteger WORKER_COUNTER = new AtomicInteger();
    /** Explicitly propagated parent step for a worker -> foreign-executor handoff. */
    private static final ThreadLocal<StepToken> CAUSAL_STEP = new ThreadLocal<>();

    private final Thread[] workers;
    private volatile boolean running;
    private boolean accepting;
    private final Object lifecycleLock = new Object();
    /** Threads currently registered in an Object.wait on {@link #lifecycleLock}. */
    private int lifecycleWaiters;
    private boolean stopping;
    /**
     * Root submissions are closed before scheduler async-domain collapse starts.
     * Volatile because the worker hot path uses this monotonic generation flag to
     * enable shutdown-only activity accounting without taking lifecycleLock during
     * ordinary ticks.
     */
    private volatile boolean rootAdmissionClosed;
    private Thread drainOwner;
    private boolean terminated;
    private Throwable terminationFailure;
    private Throwable publicationFailure;
    /**
     * First worker failure of this pool generation.  It is never cleared or
     * replaced: the physical server thread must observe the exact Throwable
     * which first made the parallel tick diverge from vanilla execution.
     */
    private volatile Throwable fatalFailure;
    private Thread stoppingOwner;
    /**
     * Exact player mode temporarily transfers the server turn to one serial actor.
     * New work may still be published while the turn is held, but no worker may
     * start another step until the physical server thread releases this fence.
     */
    private boolean exactTurnPaused;
    private boolean exactTurnRequested;
    private Thread exactTurnOwner;
    private boolean exactReplayClosing;
    private long exactRootAdmissions;
    /** Non-causal roots arriving after the exact-turn cut are replayed on resume. */
    private final ConcurrentLinkedQueue<Runnable> exactDeferredRoots =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeSteps = new AtomicInteger();
    /** Published work held outside the pool's own CLQs (region/help/MAIN_SYNC). */
    private long externalObligations;
    /** Region/help receipts still QUEUED; their next unit may follow a Main preparation turn. */
    private long queuedRegionalObligations;
    /**
     * Completion sequence for causal producers which can leave Main-retained work.
     * The normal-stop fixed point compares this under {@link #lifecycleLock}; counters
     * returning to zero are not by themselves proof that the preceding progress pass
     * observed the producer's result.
     */
    private long shutdownActivityEpoch;

    boolean isRunning() { return running; }

    /** The generation still admits ordinary roots: running, before its STOPPING
     *  cut and without a fatal failure. Long-lived tracked bodies poll this to end
     *  themselves instead of holding the normal-stop fixed point open. */
    boolean admitsRoots() {
        synchronized (lifecycleLock) {
            return running && accepting && fatalFailure == null;
        }
    }

    /** Rethrow the generation's exact first worker failure on the caller. */
    void throwIfFailed() {
        Throwable failure = fatalFailure;
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
    }

    boolean hasFatalFailure() {
        return fatalFailure != null;
    }

    Throwable fatalFailureOrNull() {
        return fatalFailure;
    }

    /**
     * Close admission at the first worker failure.  Already-entered steps are
     * not interrupted; every worker observes the slot before its next step and
     * exits after its current finally chain.
     */
    void publishFatalFailure(Throwable failure) {
        if (failure == null) throw new NullPointerException("failure");
        synchronized (lifecycleLock) {
            if (fatalFailure == null) fatalFailure = failure;
            accepting = false;
            signalLifecycleWaiters();
        }
        for (Thread worker : workers) if (worker != null) LockSupport.unpark(worker);
    }

    /** Select this pool generation's exact W without consulting stale orphan/test state. */
    Throwable publishAndSelectFatal(Throwable candidate) {
        if (candidate == null) candidate = new NullPointerException("fatal failure");
        publishFatalFailure(candidate);
        Throwable winner = fatalFailure;
        return winner == candidate ? winner : mergeFailure(winner, candidate);
    }

    /**
     * Linearize a prepared success commit against selection of the generation's
     * exact first fatal Throwable.  The action may only accept already-offered
     * receipt-gated publications and perform fixed, non-blocking field/atomic
     * writes; fallible queue publication must happen before entering this method.
     * A causal caller is already lifetime-pinned and uses volatile admission plus
     * terminal W observations; an unowned caller retains the lifecycle-monitor cut.
     */
    void commitSuccessIfHealthy(Runnable commit) {
        if (commit == null) throw new NullPointerException("commit");
        StepToken causalAuthority = currentThreadCausalStepForThisPoolOrNull();
        if (causalAuthority != null) {
            // The enclosing step/permit already pins this generation.  Its volatile
            // W read is the admission point; an action admitted before a later W
            // remains mandatory exact work and observes that same W at completion.
            runAcceptedCausalBody(causalAuthority, commit);
            return;
        }
        Throwable failure;
        boolean selectedHere = false;
        synchronized (lifecycleLock) {
            failure = fatalFailure;
            if (failure == null) {
                try {
                    commit.run();
                    return;
                } catch (Throwable thrown) {
                    // The success action and fatal selection share this monitor, so
                    // no peer can install a later candidate between the throwing
                    // commit edge and publication of this exact winner.
                    fatalFailure = thrown;
                    accepting = false;
                    signalLifecycleWaiters();
                    failure = thrown;
                    selectedHere = true;
                }
            }
        }
        if (selectedHere) {
            for (Thread worker : workers) if (worker != null) LockSupport.unpark(worker);
        }
        RegionWorkerPool.<RuntimeException>rethrow(failure);
    }

    /**
     * Linearize entry of the one serial player actor against this generation's
     * fatal slot while physical Main retains the exact-turn fence.  This is not
     * an external obligation: the owner is synchronously parked on the actor's
     * receipt and cannot resume the pool before that receipt becomes terminal.
     */
    void commitPausedExactActorEntryIfHealthy(Thread physicalOwner, Thread actor) {
        if (physicalOwner == null) throw new NullPointerException("physicalOwner");
        if (actor == null) throw new NullPointerException("actor");
        commitSuccessIfHealthy(() -> {
            if (!running || !accepting || stopping || terminated
                    || !exactTurnPaused || exactTurnRequested || exactReplayClosing
                    || exactTurnOwner != physicalOwner
                    || actor == physicalOwner
                    || Thread.currentThread() != actor
                    || !RegionalPlayerEntityTicksV2.exactActorHandoffMatches(
                            physicalOwner, actor)) {
                throw new IllegalStateException(
                        "exact player actor lost its paused physical-owner handoff");
            }
        });
    }

    /**
     * Atomically admits one fallible success-only body against this generation's
     * fatal cut, then runs it outside {@link #lifecycleLock}.  A body which won
     * admission may finish its own finally chain; a fatal which won first prevents
     * entry altogether.  The external permit's terminal edge also observes a peer
     * fatal selected while the admitted body was running, so its caller cannot move
     * on to the next success stage.
     */
    void runSuccessBodyIfHealthy(Runnable body) {
        if (body == null) throw new NullPointerException("body");
        StepToken causalAuthority = currentThreadCausalStepForThisPoolOrNull();
        if (causalAuthority != null) {
            runAcceptedCausalBody(causalAuthority, body);
            return;
        }
        ExternalPermit permit = reserveSuccessBodyPermit(false);

        runAcceptedExternalBody(permit, body);
    }

    /**
     * Generation-owned admission for the autonomous pacer.  Unlike an ordinary
     * unrelated synchronous root, a pacer tail which reaches an exact-turn cut is
     * still the cadence authority of an already accepted island attempt.  It must
     * neither become a deferred simulation replay nor select a fatal refusal merely
     * because Main temporarily owns the pool.
     *
     * <p>The wait owns no {@link ExternalPermit}, so an exact-turn requester can
     * reach its zero-obligation paused cut.  Fence release and permit reservation
     * share {@link #lifecycleLock}; therefore either the pacer pins the generation
     * first and the requester waits for its terminal edge, or the requester wins
     * and the pacer remains body-free until resume.  There is deliberately no
     * timeout.  Interruption escapes to the pacer loop, where the exact normal-stop
     * thread identity distinguishes teardown from an unexpected fatal interrupt.
     *
     * @return false only when this exact pool generation closed normally before
     *         admission; a selected W is always rethrown
     */
    boolean runPacerBodyIfHealthy(Runnable body) throws InterruptedException {
        if (body == null) throw new NullPointerException("body");
        ExternalPermit permit;
        synchronized (lifecycleLock) {
            while (fatalFailure == null
                    && running && accepting && !stopping && !terminated
                    && !rootAdmissionClosed
                    && (exactTurnRequested || exactTurnPaused
                            || exactReplayClosing)) {
                awaitLifecycleChange();
            }
            Throwable failure = fatalFailure;
            if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
            if (!running || !accepting || stopping || terminated
                    || rootAdmissionClosed) {
                return false;
            }
            // Re-entrant acquisition is intentional: reserveSuccessBodyPermit's
            // state test and publication happen before this outer monitor cut can
            // be crossed by a fresh exact-turn request.
            permit = reserveSuccessBodyPermit(false);
        }
        runAcceptedExternalBody(permit, body);
        return true;
    }

    /**
     * Run a nested success stage whose complete lifetime is already represented by
     * its enclosing worker step or foreign-handoff permit. Entry and completion still
     * take distinct first-W cuts, but no second external obligation is manufactured.
     */
    private void runAcceptedCausalBody(StepToken authority, Runnable body) {
        beginCausalSuccessBodyIfHealthy(authority);
        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        failure = finishCausalSuccessBody(authority, failure);
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
    }

    /** Run one already-accepted external obligation and always terminalize its permit. */
    private void runAcceptedExternalBody(ExternalPermit permit, Runnable body) {

        Throwable failure = null;
        boolean entered = false;
        StepToken previousAuthority = null;
        boolean authorityScopeAttempted = false;
        try {
            entered = permit.beginMainSync();
            if (!entered) {
                throw new IllegalStateException(
                        "success-only body lost its accepted generation permit");
            }
            previousAuthority = CAUSAL_STEP.get();
            authorityScopeAttempted = true;
            installExternalCausalAuthority(permit, previousAuthority);
            body.run();
        } catch (Throwable thrown) {
            // The body is either still behind its permit or has entered.  In both
            // cases select the exact generation W before terminal permit cleanup.
            failure = publishAndSelectFatal(thrown);
        } finally {
            if (authorityScopeAttempted) {
                try {
                    restoreCausalAuthority(previousAuthority);
                } catch (Throwable cleanup) {
                    failure = publishAndSelectFatal(
                            mergeFailure(failure, cleanup));
                }
            }
            if (entered) {
                failure = permit.finish(failure);
                if (failure != null) failure = publishAndSelectFatal(failure);
            } else if (permit.state.get() != ExternalPermit.TERMINAL) {
                failure = permit.cancelPublication(failure);
                if (failure != null) failure = publishAndSelectFatal(failure);
            }
        }
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
    }

    /** Boolean-returning, allocation-free form used by hot publication paths. */
    boolean callSuccessBooleanIfHealthy(java.util.function.BooleanSupplier body) {
        if (body == null) throw new NullPointerException("body");
        StepToken causalAuthority = currentThreadCausalStepForThisPoolOrNull();
        if (causalAuthority != null) {
            return callAcceptedCausalBoolean(causalAuthority, body);
        }
        ExternalPermit permit = reserveSuccessBodyPermit(false);
        return callAcceptedExternalBoolean(permit, body);
    }

    private boolean callAcceptedExternalBoolean(
            ExternalPermit permit, java.util.function.BooleanSupplier body) {

        Throwable failure = null;
        boolean entered = false;
        boolean result = false;
        StepToken previousAuthority = null;
        boolean authorityScopeAttempted = false;
        try {
            entered = permit.beginMainSync();
            if (!entered) {
                throw new IllegalStateException(
                        "success-only body lost its accepted generation permit");
            }
            previousAuthority = CAUSAL_STEP.get();
            authorityScopeAttempted = true;
            installExternalCausalAuthority(permit, previousAuthority);
            result = body.getAsBoolean();
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        } finally {
            if (authorityScopeAttempted) {
                try {
                    restoreCausalAuthority(previousAuthority);
                } catch (Throwable cleanup) {
                    failure = publishAndSelectFatal(
                            mergeFailure(failure, cleanup));
                }
            }
            if (entered) {
                failure = permit.finish(failure);
                if (failure != null) failure = publishAndSelectFatal(failure);
            } else if (permit.state.get() != ExternalPermit.TERMINAL) {
                failure = permit.cancelPublication(failure);
                if (failure != null) failure = publishAndSelectFatal(failure);
            }
        }
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        return result;
    }

    /** Boolean form of {@link #runAcceptedCausalBody(StepToken, Runnable)}. */
    private boolean callAcceptedCausalBoolean(
            StepToken authority, java.util.function.BooleanSupplier body) {
        beginCausalSuccessBodyIfHealthy(authority);
        Throwable failure = null;
        boolean result = false;
        try {
            result = body.getAsBoolean();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        failure = finishCausalSuccessBody(authority, failure);
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        return result;
    }

    /** Atomic fatal-vs-entry cut for a body already owned by one live causal step. */
    private void beginCausalSuccessBodyIfHealthy(StepToken authority) {
        if (authority == null || authority.owner != this || !authority.active
                || CAUSAL_STEP.get() != authority) {
            RegionWorkerPool.<RuntimeException>rethrow(publishAndSelectFatal(
                    new IllegalStateException(
                            "causal success-only body lost its enclosing pool step")));
        }
        // This volatile read is the exact admission linearization.  A W which is
        // already published refuses entry; a later W cannot revoke work which the
        // enclosing exact step has already admitted.
        Throwable failure = fatalFailure;
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
    }

    /**
     * Atomic terminal observation for a causal success body. A throwing body selects
     * the generation's first W here; a clean body observes any peer W which won while
     * it was running. The enclosing step remains the sole lifetime obligation.
     */
    private Throwable finishCausalSuccessBody(StepToken authority, Throwable primary) {
        Throwable result = primary;
        if (authority == null || authority.owner != this || !authority.active
                || CAUSAL_STEP.get() != authority) {
            result = mergeFailure(result, new IllegalStateException(
                    "causal success-only body escaped its enclosing pool step"));
        }
        // A local Throwable competes for the exact W through the canonical
        // first-wins publisher.  A clean completion needs only the second volatile
        // observation: if it sees null, its terminal edge precedes any later W.
        return result == null ? fatalFailure : publishAndSelectFatal(result);
    }

    /** Install one permit-owned token for the complete synchronous external body. */
    private void installExternalCausalAuthority(
            ExternalPermit permit, StepToken previousAuthority) {
        StepToken authority = permit.causalAuthority;
        if (authority == null || authority.owner != this || !authority.active) {
            throw new IllegalStateException(
                    "synchronous success body lost its permit causal authority");
        }
        if (previousAuthority != null && previousAuthority != authority
                && previousAuthority.active && previousAuthority.owner != this) {
            throw new IllegalStateException(
                    "synchronous success body crossed active pool generations");
        }
        CAUSAL_STEP.set(authority);
    }

    private static void restoreCausalAuthority(StepToken previousAuthority) {
        if (previousAuthority == null) CAUSAL_STEP.remove();
        else CAUSAL_STEP.set(previousAuthority);
    }

    boolean isTerminated() {
        synchronized (lifecycleLock) {
            return terminated;
        }
    }

    /**
     * Linearizable worker-exclusion fence used by the exact EntityTickList pass.
     * There is no timeout: returning while an old step is live would let it overlap
     * the logical-server actor.  The progress callback may service only causal
     * chunk-main work and runs outside {@link #lifecycleLock}.
     */
    void pauseExactTurnAndAwait(Runnable progress) {
        pauseExactTurnAndAwait(progress, false);
    }

    /**
     * Exclude every live body without advancing retained next-unit region work.
     * Unlike the canonical entity pass, preparation must not await that future unit.
     * Routed commands and native controls yield to their existing Main service venue.
     * False leaves no pause ownership and replays every accepted deferred root.
     */
    boolean pauseMainPreparationAndAwait(Runnable progress) {
        return pauseExactTurnAndAwait(progress, true);
    }

    private boolean pauseExactTurnAndAwait(Runnable progress, boolean retainQueuedRegions) {
        if (progress == null) throw new NullPointerException("progress");
        if (isOwnWorker(Thread.currentThread())) {
            throw new IllegalStateException("a region worker cannot pause its own pool");
        }
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            if (fatalFailure != null) {
                RegionWorkerPool.<RuntimeException>rethrow(fatalFailure);
            }
            if (!running || !accepting || stopping || terminated) {
                throw new IllegalStateException(
                        "exact player turn cannot pause an inactive/draining region pool");
            }
            if (exactTurnPaused || exactTurnRequested) {
                throw new IllegalStateException(exactTurnOwner == Thread.currentThread()
                        ? "exact player turn was requested twice by its owner"
                        : "exact player turn already owned by another thread");
            }
            if (retainQueuedRegions && IslandCommands.hasRoutedPending()) return false;
            exactTurnRequested = true;
            exactTurnOwner = Thread.currentThread();
        }

        boolean validatingPausedCut = false;
        Throwable fatalDuringPause = null;
        for (;;) {
            boolean yieldForRoutedCommand = false;
            synchronized (lifecycleLock) {
                if (fatalFailure != null) {
                    fatalDuringPause = fatalFailure;
                    exactTurnPaused = false;
                    exactTurnRequested = false;
                    exactTurnOwner = null;
                    exactReplayClosing = false;
                    signalLifecycleWaiters();
                }
                if (fatalDuringPause == null && retainQueuedRegions
                        && IslandCommands.hasRoutedPending()) {
                    yieldForRoutedCommand = true;
                }
                // Same fixed-point cut as shutdown, without closing admission: every
                // task accepted before this monitor edge either finished, remains an
                // explicitly deferred post-turn publication, or is represented by an
                // external permit. Setting PAUSED under this exact monitor prevents a
                // worker from incrementing activeSteps after the cut.
                if (fatalDuringPause == null
                        && !yieldForRoutedCommand && !validatingPausedCut
                        && exactTurnQuiescentLocked(retainQueuedRegions)) {
                    exactTurnPaused = true;
                    exactTurnRequested = false;
                    validatingPausedCut = true;
                } else if (fatalDuringPause == null
                        && !yieldForRoutedCommand && !validatingPausedCut) {
                    try {
                        awaitLifecycleChange(1L);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
            if (fatalDuringPause != null) break;
            if (yieldForRoutedCommand) {
                try {
                    // Accepted Main work still needs its own venue: replay publications
                    // without pretending that Main acquired its exclusive turn.
                    resumeExactTurn(!validatingPausedCut);
                    throwIfFailed();
                    return false;
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            try {
                throwIfFailed();
                progress.run();
                throwIfFailed();
            } catch (Throwable failure) {
                // This callback services Main-owned simulation obligations.
                // Select W before reopening the exact-turn fence and never call
                // the progress body again after a partial Main operation.
                Throwable winner = publishAndSelectFatal(failure);
                synchronized (lifecycleLock) {
                    exactTurnPaused = false;
                    exactTurnRequested = false;
                    exactTurnOwner = null;
                    exactReplayClosing = false;
                    signalLifecycleWaiters();
                }
                for (Thread worker : workers) {
                    if (worker != null) LockSupport.unpark(worker);
                }
                if (interrupted) Thread.currentThread().interrupt();
                RegionWorkerPool.<RuntimeException>rethrow(winner);
            }
            if (validatingPausedCut) {
                synchronized (lifecycleLock) {
                    // The final progress pass runs with workers fenced. Scheduler
                    // calls made by the physical owner execute inline; a raw pool
                    // publication is visible here and forces one more drain cycle.
                    if (exactTurnQuiescentLocked(retainQueuedRegions)
                            && (!retainQueuedRegions || !IslandCommands.hasRoutedPending())) {
                        break;
                    }
                    exactTurnPaused = false;
                    exactTurnRequested = true;
                    validatingPausedCut = false;
                    signalLifecycleWaiters();
                }
            }
            for (Thread worker : workers) if (worker != null) LockSupport.unpark(worker);
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (fatalDuringPause != null) {
            RegionWorkerPool.<RuntimeException>rethrow(fatalDuringPause);
        }
        return true;
    }

    private boolean exactTurnQuiescentLocked(boolean retainQueuedRegions) {
        return activeSteps.get() == 0 && ordinaryQueuesEmpty()
                && exactRootAdmissions == 0L
                && externalObligations == (retainQueuedRegions ? queuedRegionalObligations : 0L);
    }

    void resumeExactTurn() {
        resumeExactTurn(false);
    }

    private void resumeExactTurn(boolean abortRequested) {
        Throwable failure = null;
        ArrayList<Runnable> head;
        synchronized (lifecycleLock) {
            requireExactTurnOwnerLocked(abortRequested);
            head = takeDeferredRootsLocked();
        }
        failure = replayExactRoots(head, failure);

        boolean interrupted = false;
        ArrayList<Runnable> tail;
        synchronized (lifecycleLock) {
            requireExactTurnOwnerLocked(abortRequested);
            exactReplayClosing = true;
            while (exactRootAdmissions != 0L) {
                try {
                    awaitLifecycleChange();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            tail = takeDeferredRootsLocked();
        }
        failure = replayExactRoots(tail, failure);

        ArrayList<Runnable> stray = null;
        Throwable invariantFailure = null;
        synchronized (lifecycleLock) {
            requireExactTurnOwnerLocked(abortRequested);
            // Abort keeps live causal publication legal. Such a producer can enter
            // after the tail cut, but cannot add to the unrelated deferred FIFO.
            if (!exactDeferredRoots.isEmpty()
                    || (!abortRequested && exactRootAdmissions != 0L)) {
                invariantFailure = new IllegalStateException(
                        "exact root appeared after replay admission was closed");
                stray = takeDeferredRootsLocked();
            }
        }
        if (invariantFailure != null) {
            failure = publishAndSelectFatal(
                    mergeFailure(failure, invariantFailure));
            failure = replayExactRoots(stray, failure);
        }

        synchronized (lifecycleLock) {
            requireExactTurnOwnerLocked(abortRequested);
            exactTurnPaused = false;
            exactTurnRequested = false;
            exactTurnOwner = null;
            exactReplayClosing = false;
            signalLifecycleWaiters();
        }
        for (Thread worker : workers) if (worker != null) LockSupport.unpark(worker);
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
    }

    private Throwable replayExactRoots(ArrayList<Runnable> roots, Throwable primary) {
        Throwable failure = primary;
        for (Runnable root : roots) {
            if (failure != null) {
                failure = publishAndSelectFatal(
                        cancelQueuedEntry(root, failure));
                continue;
            }
            try {
                root.run();
            } catch (Throwable replayFailure) {
                failure = publishAndSelectFatal(replayFailure);
            }
        }
        return failure;
    }

    private ArrayList<Runnable> takeDeferredRootsLocked() {
        ArrayList<Runnable> roots = new ArrayList<>(exactDeferredRoots.size());
        Runnable root;
        while ((root = exactDeferredRoots.poll()) != null) roots.add(root);
        return roots;
    }

    private void requireExactTurnOwnerLocked(boolean abortRequested) {
        boolean held = abortRequested ? exactTurnRequested && !exactTurnPaused : exactTurnPaused;
        if (!held || exactTurnOwner != Thread.currentThread()) {
            throw new IllegalStateException("exact player turn released by a non-owner");
        }
    }

    boolean ownsPausedExactTurn(Thread thread) {
        synchronized (lifecycleLock) {
            // W closes new body admission, not the already-transferred thread
            // identity.  The actor may still be unwinding mandatory vanilla
            // finally chains while physical Main retains this real fence.
            return exactTurnPaused && exactTurnOwner == thread;
        }
    }

    private void enterRootAdmission() {
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            while (exactReplayClosing && Thread.currentThread() != exactTurnOwner
                    && !(exactTurnRequested && hasLiveCausalStepLocked())) {
                try {
                    awaitLifecycleChange();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            exactRootAdmissions++;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void leaveRootAdmission() {
        synchronized (lifecycleLock) {
            if (exactRootAdmissions <= 0L) {
                throw new IllegalStateException("exact root admission completed without entry");
            }
            exactRootAdmissions--;
            signalLifecycleWaiters();
        }
    }

    /**
     * Close one root-publication admission without allowing a cleanup failure to
     * replace the Throwable which already closed the generation.  Callers select
     * their body/publication failure before entering this method.
     */
    private Throwable closeRootAdmission(Throwable primary,
                                         PublicationGate first,
                                         PublicationGate second,
                                         PublicationGate third) {
        Throwable failure;
        boolean selectedHere = false;
        synchronized (lifecycleLock) {
            failure = fatalFailure;
            if (primary != null) {
                if (failure == null) {
                    fatalFailure = primary;
                    accepting = false;
                    failure = primary;
                    selectedHere = true;
                } else if (failure != primary) {
                    failure = mergeFailure(failure, primary);
                }
            }
            if (exactRootAdmissions <= 0L) {
                IllegalStateException underflow = new IllegalStateException(
                        "exact root admission completed without entry");
                if (failure == null) {
                    fatalFailure = underflow;
                    accepting = false;
                    failure = underflow;
                    selectedHere = true;
                } else {
                    failure = mergeFailure(failure, underflow);
                }
            } else {
                exactRootAdmissions--;
            }
            if (failure == null) {
                try {
                    acceptAdmissionGate(first);
                    acceptAdmissionGate(second);
                    acceptAdmissionGate(third);
                } catch (Throwable commitFailure) {
                    fatalFailure = commitFailure;
                    accepting = false;
                    failure = commitFailure;
                    selectedHere = true;
                }
            }
            signalLifecycleWaiters();
        }
        if (selectedHere) {
            for (Thread worker : workers) if (worker != null) LockSupport.unpark(worker);
        }
        return failure;
    }

    /** Caller holds lifecycleLock; every queue node is already OFFERING. */
    private static void acceptAdmissionGate(PublicationGate gate) {
        if (gate != null && !gate.accept()) {
            throw new IllegalStateException(
                    "root admission lost an OFFERING publication receipt");
        }
    }

    /**
     * Capture the current pool step for an explicit handoff to another executor.
     * The token remains causal only until that exact parent worker step terminates;
     * a delayed callback is therefore classified as a post-turn root, never as an
     * unbounded bypass of the exact-turn admission cut.
     */
    Runnable wrapCausalHandoff(Runnable body) {
        if (body == null) throw new NullPointerException("body");
        StepToken token = CAUSAL_STEP.get();
        synchronized (lifecycleLock) {
            if (token == null || token.owner != this || !token.active) {
                throw new IllegalStateException(
                        "causal region handoff must be captured inside a live pool step");
            }
        }
        return () -> {
            StepToken previous = null;
            boolean previousRead = false;
            Throwable failure = null;
            try {
                previous = CAUSAL_STEP.get();
                previousRead = true;
                if (previous != null && previous != token) {
                    throw new IllegalStateException(
                            "nested causal handoff changed pool generation");
                }
                // The reservation classifies the captured parent, then installs
                // a fresh execution token whose lifetime is the wrapper's permit.
                // There is deliberately no deferred replay of this Runnable.
                runCausalHandoffBodyIfHealthy(token, body);
            } catch (Throwable thrown) {
                failure = publishAndSelectFatal(thrown);
            }
            if (previousRead) {
                try {
                    if (previous == null) CAUSAL_STEP.remove();
                    else CAUSAL_STEP.set(previous);
                } catch (Throwable cleanup) {
                    failure = publishAndSelectFatal(
                            mergeFailure(failure, cleanup));
                }
            }
            if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        };
    }

    /**
     * Admit a captured worker handoff by the token's state at this exact lifecycle
     * cut, never by authority inherited from the thread which happens to invoke it.
     * A live token remains part of its already-accepted parent step. An expired token
     * is an ordinary root and may enter only while the generation is fully open.
     */
    private void runCausalHandoffBodyIfHealthy(StepToken token, Runnable body) {
        ExternalPermit permit = reserveCausalHandoffPermit(token);
        Throwable failure = null;
        try {
            runAcceptedExternalBody(permit, body);
            return;
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        } finally {
            // Covers a ThreadLocal publication/setup failure before the common
            // body envelope could terminalize the permit. W already exists.
            if (permit.state.get() != ExternalPermit.TERMINAL) {
                failure = permit.cancelPublication(failure);
                if (failure != null) failure = publishAndSelectFatal(failure);
            }
        }
        RegionWorkerPool.<RuntimeException>rethrow(failure);
    }

    /**
     * Wrapper-specific admission. This method deliberately does not enter or wait on
     * exact root replay admission: an expired callback which reaches a closed cut is
     * refused before its body and is never deferred or replayed after that cut.
     */
    private ExternalPermit reserveCausalHandoffPermit(StepToken token) {
        ExternalPermit permit = null;
        Throwable failure = null;
        boolean selectedHere = false;
        try {
            synchronized (lifecycleLock) {
                failure = fatalFailure;
                if (failure == null) {
                    try {
                        boolean live = token != null
                                && token.owner == this && token.active;
                        boolean liveParentOpen = live && running && accepting
                                && !terminated && !exactTurnPaused
                                && (!exactReplayClosing || exactTurnRequested);
                        boolean ordinaryRootOpen = running && accepting && !stopping
                                && !terminated && !rootAdmissionClosed
                                && !exactTurnRequested && !exactTurnPaused
                                && !exactReplayClosing;
                        if (!liveParentOpen && !ordinaryRootOpen) {
                            throw new IllegalStateException(
                                    "causal handoff refused by a closed or exclusive "
                                            + "pool generation");
                        }

                        StepToken executionAuthority = new StepToken(this);
                        executionAuthority.active = true;
                        permit = new ExternalPermit(executionAuthority);
                        externalObligations++;
                        if (!permit.acceptPublication()) {
                            throw new IllegalStateException(
                                    "causal handoff lost its fresh publication receipt");
                        }
                    } catch (Throwable admissionFailure) {
                        // Classification, first-W selection, and the lifecycle cut
                        // are one monitor action. Resume/stop cannot reopen between
                        // refusing this wrapper and closing the generation.
                        fatalFailure = admissionFailure;
                        accepting = false;
                        failure = admissionFailure;
                        selectedHere = true;
                        signalLifecycleWaiters();
                    }
                }
            }
        } catch (Throwable boundaryFailure) {
            // Monitor-entry/exit failure did not expose an untracked body. Select W
            // before cancelling any permit which was nevertheless half-published.
            failure = publishAndSelectFatal(
                    mergeFailure(failure, boundaryFailure));
        }
        if (selectedHere) {
            for (Thread worker : workers) if (worker != null) LockSupport.unpark(worker);
        }
        if (failure != null) {
            if (permit != null) {
                failure = permit.cancelPublication(failure);
            }
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return permit;
    }

    /**
     * Wait for a lifecycle predicate to change. The caller holds lifecycleLock and
     * must re-check its predicate after return. Registration and Object.wait share
     * that monitor, so a conditional signal cannot pass between those two edges.
     */
    private void awaitLifecycleChange() throws InterruptedException {
        lifecycleWaiters++;
        try {
            lifecycleLock.wait();
        } finally {
            lifecycleWaiters--;
        }
    }

    /** Timed form of {@link #awaitLifecycleChange()} with identical registration. */
    private void awaitLifecycleChange(long timeoutMillis) throws InterruptedException {
        lifecycleWaiters++;
        try {
            lifecycleLock.wait(timeoutMillis);
        } finally {
            lifecycleWaiters--;
        }
    }

    /** Caller holds lifecycleLock; avoid a hot notifyAll when nobody can consume it. */
    private void signalLifecycleWaiters() {
        if (lifecycleWaiters != 0) lifecycleLock.notifyAll();
    }

    RegionWorkerPool(int size) {
        this.workers = new Thread[Math.max(1, size)];
    }

    void start() {
        synchronized (lifecycleLock) {
            if (running) return;
            if (stopping || terminated) {
                throw new IllegalStateException("RegionWorkerPool is one-shot and already stopping/stopped");
            }
            running = true;
            accepting = true;
            int priority = FabroliaConfig.getThreadPriority();
            for (int i = 0; i < workers.length; i++) {
                RegionWorkerThread t = new RegionWorkerThread(this::workerLoop,
                        "Fabrolia-Region-Worker-" + WORKER_COUNTER.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(priority);
                t.setUncaughtExceptionHandler((thr, ex) -> {
                    // Last-resort crash edge: an exception which escaped the normal
                    // worker-loop envelope must still close this generation before
                    // the remaining workers can enter another step. Logging is only
                    // diagnostics and is deliberately sequenced after fatal publication.
                    publishFatalFailure(ex);
                    try {
                        LOGGER.error("Uncaught in {}", thr.getName(), ex);
                    } catch (Throwable diagnosticFailure) {
                        mergeFailure(ex, diagnosticFailure);
                    }
                });
                ParallelProcessor.registerThread("Fabrolia-Region", t);
                workers[i] = t;
                t.start();
            }
        }
        LOGGER.info("RegionWorkerPool: started {} workers", workers.length);
    }

    void stop() {
        stop(() -> {});
    }

    /**
     * Linearizable scheduler STOPPING boundary.  Existing pool workers and the exact
     * lifecycle/main owner may still publish causal children; unrelated producers are
     * refused before the potentially long async-domain collapse begins.
     */
    void beginDraining(Thread owner) {
        if (owner == null) throw new NullPointerException("drain owner");
        synchronized (lifecycleLock) {
            if (!running || terminated) {
                throw new IllegalStateException("cannot close root admission on a stopped pool");
            }
            if (rootAdmissionClosed && drainOwner != owner) {
                throw new IllegalStateException("pool drain owner changed during one generation");
            }
            rootAdmissionClosed = true;
            drainOwner = owner;
        }
    }

    /**
     * Close admission, cancel every queued player task outside the pool lock, and wait for
     * every started worker without a timeout that could authorize teardown while it is live.
     * The progress hook lets main service the exact cold-chunk processors needed by a worker.
     */
    void stop(Runnable progress) {
        // Drain the pool to a linearizable fixed point. Ordinary submissions stay open
        // while the drain is in progress: this is intentional, because an already
        // accepted worker may synchronously publish a child task (including through a
        // main-thread bridge). The final cut uses the same monitor as every enqueue and
        // the generation-local external-obligation ledger below.
        if (Thread.currentThread() instanceof RegionWorkerThread) {
            throw new IllegalStateException("RegionWorkerPool.stop cannot join its own worker");
        }
        boolean interrupted = false;
        boolean failedGeneration = false;
        Throwable failure = null;
        synchronized (lifecycleLock) {
            while (stopping && !terminated) {
                if (stoppingOwner == Thread.currentThread()) {
                    throw new IllegalStateException(
                            "RegionWorkerPool.stop re-entered from its cancellation/progress callback");
                }
                try {
                    awaitLifecycleChange();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (terminated) {
                if (interrupted) Thread.currentThread().interrupt();
                if (terminationFailure != null) {
                    RegionWorkerPool.<RuntimeException>rethrow(terminationFailure);
                }
                return;
            }
            if (!running) {
                terminated = true;
                signalLifecycleWaiters();
                if (interrupted) Thread.currentThread().interrupt();
                return;
            }
            if (fatalFailure != null) {
                failedGeneration = true;
                failure = fatalFailure;
            }
            if (!rootAdmissionClosed) {
                rootAdmissionClosed = true;
                drainOwner = Thread.currentThread();
            } else if (drainOwner != Thread.currentThread()) {
                throw new IllegalStateException("RegionWorkerPool.stop called by a non-drain owner");
            }
            stopping = true;
            stoppingOwner = Thread.currentThread();
        }
        for (Thread t : workers) if (t != null) LockSupport.unpark(t);
        if (failedGeneration) {
            failure = finishFailedGenerationCancellation(progress, failure);
        } else {
            // Drain to a fixed point. Root admission is closed by `stopping=true`; only the
            // stopping/main progress thread and an already-running worker may publish causal
            // children. This preserves accepted task chains without allowing an unrelated
            // network/mod producer to keep shutdown open forever.
            long nextDrainDiagnostic = System.nanoTime() + 15_000_000_000L;
            boolean idleSnapshotObserved = false;
            long idleSnapshotActivityEpoch = 0L;
            for (;;) {
                Throwable observedFatal = fatalFailure;
                if (observedFatal != null) {
                    // A failure which raced stop changes the operation from a
                    // normal queue drain into fail-stop cleanup.  Queued bodies
                    // are not legal recovery work; only already-entered steps
                    // may reach their finally chains.
                    failure = mergeFailure(observedFatal, failure);
                    failure = finishFailedGenerationCancellation(progress, failure);
                    break;
                }
                try {
                    progress.run();
                } catch (Throwable progressFailure) {
                    failure = publishAndSelectFatal(
                            mergeFailure(failure, progressFailure));
                    // The progress body itself partially entered and failed.
                    // Transition directly to failed-generation cleanup and
                    // never invoke that body a second time.
                    failure = finishFailedGenerationCancellation(
                            progress, failure, false);
                    break;
                }
                boolean wake;
                synchronized (lifecycleLock) {
                    boolean queuePending = !ordinaryQueuesEmpty();
                    boolean externalPending = externalObligations != 0L;
                    boolean admissionPending = exactRootAdmissions != 0L;
                    if (!externalPending && !admissionPending && !queuePending
                            && activeSteps.get() == 0) {
                        long observedActivityEpoch = shutdownActivityEpoch;
                        if (idleSnapshotObserved
                                && idleSnapshotActivityEpoch == observedActivityEpoch) {
                            accepting = false;
                            running = false;
                            break;
                        }
                        // A retained result can become visible just after progress
                        // while its tracked producer terminalizes just before this
                        // zero-ledger cut. Root admission is already closed; record
                        // the completion epoch and require another progress pass plus
                        // an unchanged zero snapshot. A pass which itself re-arms and
                        // finishes a producer therefore cannot hide behind an ABA of
                        // the counters back to zero.
                        idleSnapshotObserved = true;
                        idleSnapshotActivityEpoch = observedActivityEpoch;
                    } else {
                        idleSnapshotObserved = false;
                    }
                    wake = externalPending || admissionPending || queuePending;
                }
                if (wake) for (Thread t : workers) if (t != null) LockSupport.unpark(t);
                long now = System.nanoTime();
                if (now >= nextDrainDiagnostic) {
                    long external;
                    long admissions;
                    long activityEpoch;
                    int steps;
                    int readySize;
                    int globalSize;
                    int mainOnlySize;
                    int mainSafeSize;
                    int unitSize;
                    synchronized (lifecycleLock) {
                        external = externalObligations;
                        admissions = exactRootAdmissions;
                        activityEpoch = shutdownActivityEpoch;
                        steps = activeSteps.get();
                        readySize = ready.size();
                        globalSize = globalTasks.size();
                        mainOnlySize = mainOnlyTasks.size();
                        mainSafeSize = mainSafeTasks.size();
                        unitSize = unitTasks.size();
                    }
                    try {
                        LOGGER.error("RegionWorkerPool: drain still waiting; teardown remains closed "
                                        + "(externalObligations={}, rootAdmissions={}, activityEpoch={}, "
                                        + "activeSteps={}, ready={}, global={}, "
                                        + "mainOnly={}, mainSafe={}, units={})",
                                external, admissions, activityEpoch, steps, readySize, globalSize,
                                mainOnlySize, mainSafeSize, unitSize);
                    } catch (Throwable diagnosticFailure) {
                        failure = mergeFailure(failure, diagnosticFailure);
                    }
                    nextDrainDiagnostic = now + 15_000_000_000L;
                }
                LockSupport.parkNanos(this, 100_000L);
            }
        }
        for (Thread t : workers) if (t != null) LockSupport.unpark(t);
        long nextWorkerDiagnostic = System.nanoTime() + 15_000_000_000L;
        for (Thread t : workers) {
            if (t == null) continue;
            while (t.isAlive()) {
                try {
                    t.join(100L);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                long now = System.nanoTime();
                if (t.isAlive() && now >= nextWorkerDiagnostic) {
                    try {
                        StringBuilder st = new StringBuilder();
                        for (StackTraceElement el : t.getStackTrace()) {
                            st.append("\n  at ").append(el);
                        }
                        LOGGER.error("RegionWorkerPool: still waiting for {} at stop; "
                                        + "teardown remains closed; stack:{}",
                                t.getName(), st);
                    } catch (Throwable diagnosticFailure) {
                        failure = mergeFailure(failure, diagnosticFailure);
                    }
                    nextWorkerDiagnostic = now + 15_000_000_000L;
                }
            }
        }
        try {
            LOGGER.info("RegionWorkerPool: stopped");
        } catch (Throwable diagnosticFailure) {
            failure = mergeFailure(failure, diagnosticFailure);
        }
        synchronized (lifecycleLock) {
            stoppingOwner = null;
            stopping = false;
            failure = mergeFailure(failure, publicationFailure);
            publicationFailure = null;
            terminationFailure = failure;
            terminated = true;
            signalLifecycleWaiters();
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
    }

    private Throwable finishFailedGenerationCancellation(
            Runnable progress, Throwable primary) {
        return finishFailedGenerationCancellation(progress, primary, true);
    }

    private Throwable finishFailedGenerationCancellation(
            Runnable progress, Throwable primary, boolean progressAllowed) {
        Throwable failure = primary;
        // No new simulation work is legal after the first failure. Service only
        // bounded causal Main progress until operations which had already entered
        // a step reach their own finally chains. Root admission is included in this
        // cut: an ExternalTask may reserve under lifecycleLock and publish immediately
        // afterwards, so queue cancellation must wait for that publisher to leave.
        while (!failedGenerationBodiesQuiescent()) {
            if (progressAllowed) {
                try {
                    progress.run();
                } catch (Throwable progressFailure) {
                    failure = publishAndSelectFatal(
                            mergeFailure(failure, progressFailure));
                    // A partial cleanup-progress pass is never replayed. From
                    // here only already-running bodies may reach their finally.
                    progressAllowed = false;
                }
            }
            for (Thread t : workers) if (t != null) LockSupport.unpark(t);
            LockSupport.parkNanos(this, 100_000L);
        }

        // Detach and terminalize every QUEUED obligation. Never invoke a root body:
        // only explicit CancellableQueueEntry cleanup and permit cancellation are
        // legal. A RUNNING external owner reaches its own finally; it is never forged
        // terminal by the stop thread.
        failure = cancelFailedGenerationQueues(failure);
        long nextCancellationDiagnostic = System.nanoTime() + 15_000_000_000L;
        while (true) {
            boolean obligationsClosed;
            synchronized (lifecycleLock) {
                obligationsClosed = activeSteps.get() == 0
                        && exactRootAdmissions == 0L
                        && externalObligations == 0L;
            }
            if (obligationsClosed
                    && failedGenerationOwnQueuesEmpty()
                    && RegionScheduler.failedGenerationQueuesEmpty(this)) {
                break;
            }
            // Re-sweep in case a publication already inside admission raced the first
            // detach. Such a publisher cannot enter its body after fatal publication.
            failure = cancelFailedGenerationQueues(failure);
            long now = System.nanoTime();
            if (now >= nextCancellationDiagnostic) {
                long external;
                long admissions;
                int steps;
                synchronized (lifecycleLock) {
                    external = externalObligations;
                    admissions = exactRootAdmissions;
                    steps = activeSteps.get();
                }
                try {
                    LOGGER.error("RegionWorkerPool: fail-stop cancellation still waiting "
                                    + "(externalObligations={}, rootAdmissions={}, activeSteps={})",
                            external, admissions, steps);
                } catch (Throwable diagnosticFailure) {
                    failure = mergeFailure(failure, diagnosticFailure);
                }
                nextCancellationDiagnostic = now + 15_000_000_000L;
            }
            LockSupport.parkNanos(this, 100_000L);
        }
        synchronized (lifecycleLock) {
            accepting = false;
            running = false;
        }
        return failure;
    }

    private boolean failedGenerationBodiesQuiescent() {
        synchronized (lifecycleLock) {
            return activeSteps.get() == 0 && exactRootAdmissions == 0L;
        }
    }

    /** Physical-stop thread only, after fatal admission has closed. */
    private Throwable cancelFailedGenerationQueues(Throwable primary) {
        Throwable failure = primary;
        Runnable queued;
        try {
            while ((queued = globalTasks.poll()) != null) {
                failure = cancelQueuedEntry(queued, failure);
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        try {
            while ((queued = mainOnlyTasks.poll()) != null) {
                failure = cancelQueuedEntry(queued, failure);
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        try {
            while ((queued = mainSafeTasks.poll()) != null) {
                failure = cancelQueuedEntry(queued, failure);
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        try {
            while ((queued = unitTasks.poll()) != null) {
                failure = cancelQueuedEntry(queued, failure);
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        try {
            ArrayList<Runnable> deferred;
            synchronized (lifecycleLock) {
                deferred = takeDeferredRootsLocked();
            }
            for (int i = 0, size = deferred.size(); i < size; i++) {
                failure = cancelQueuedEntry(deferred.get(i), failure);
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        // Ready entries are scheduling hints, not bodies/obligations. Their region and
        // help queues are detached by RegionScheduler below. A tagged hint nevertheless
        // owns an exact entity-publication receipt, so failed-generation cancellation
        // must retire every detached hint rather than bulk-clearing past its cleanup.
        try {
            ReadyEntry readyEntry;
            while ((readyEntry = ready.poll()) != null) {
                try {
                    readyEntry.cancelPublication();
                } catch (Throwable cleanup) {
                    failure = mergeFailure(failure, cleanup);
                }
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        try {
            return RegionScheduler.cancelFailedGenerationQueues(this, failure);
        } catch (Throwable cleanup) {
            return mergeFailure(failure, cleanup);
        }
    }

    private boolean failedGenerationOwnQueuesEmpty() {
        synchronized (lifecycleLock) {
            return globalTasks.isEmpty() && mainOnlyTasks.isEmpty()
                    && mainSafeTasks.isEmpty()
                    && unitTasks.isEmpty() && ready.isEmpty()
                    && exactDeferredRoots.isEmpty();
        }
    }

    /** Unpark all workers. Called when work lands in a region queue. */
    void wakeAll() {
        for (Thread t : workers) if (t != null) LockSupport.unpark(t);
    }

    /**
     * R5 targeted wake: unpark up to {@code k} PARKED workers instead of the whole pool.
     * Lost-wake-free (Dekker): the submitter publishes the task (volatile/CAS enqueue)
     * BEFORE scanning flags; the worker sets {@code parked=true} BEFORE its final lane
     * re-check. In any interleaving either the re-check sees the task or the scan sees
     * the flag. A stale unpark of a worker that already found work is a benign permit;
     * the 10ms park fallback backstops any residual hole.
     */
    void wake(int k) {
        if (k <= 0) return;
        for (Thread t : workers) {
            if (t instanceof RegionWorkerThread w && w.parked) {
                w.parked = false;
                LockSupport.unpark(w);
                if (--k == 0) return;
            }
        }
    }

    public int size() { return workers.length; }

    /**
     * Global ready-queue of regions with pending work. Submit paths push the region
     * here (always — duplicates are benign); workers poll O(1) instead of the old
     * linear scan over every region snapshot. The scan was the region-owned-ticking
     * killer: ~400 region tasks/tick × 30 workers × `synchronized snapshot()`
     * (ArrayList copy of the whole region map under the regionizer monitor) per
     * claim attempt = O(R²) copies and a serialized claim path (tick median 24 → 37ms,
     * live 2026-06-09). CLQ claim protocol below closes the stranded-task race:
     * whoever holds the claim re-arms the region at release if its queue refilled.
     */
    /**
     * A queue node is visible before its publishing admission is committed.  The
     * consumer may detach it, but cannot enter the represented body while the node
     * is OFFERING.  This closes both offer-then-throw and queue-offer followed by a
     * failing admission cleanup.
     */
    private static final class PublicationGate {
        private static final int OFFERING = 0;
        private static final int ACCEPTED = 1;
        private static final int CANCELLED = 2;

        private final AtomicInteger state;

        private PublicationGate() {
            this(false);
        }

        private PublicationGate(boolean accepted) {
            state = new AtomicInteger(accepted ? ACCEPTED : OFFERING);
        }

        private boolean accept() {
            return state.compareAndSet(OFFERING, ACCEPTED);
        }

        private void cancel() {
            state.compareAndSet(OFFERING, CANCELLED);
        }

        private boolean awaitAccepted() {
            int spins = 0;
            int observed;
            while ((observed = state.get()) == OFFERING) {
                if (spins++ < 64) Thread.onSpinWait();
                else LockSupport.parkNanos(this, 1_000L);
            }
            return observed == ACCEPTED;
        }
    }

    private static final class EntityReadyReceipt {
        private final EpochDomain.EntityDispatchPublication owner;
        private final long epoch;
        /** Guarded by the owning ReadyEntry monitor. */
        private boolean open = true;

        private EntityReadyReceipt(EpochDomain.EntityDispatchPublication owner,
                                   long epoch) {
            if (owner == null) throw new NullPointerException("owner");
            this.owner = owner;
            this.epoch = epoch;
            // Must precede the ReadyEntry's CLQ-linking CAS.
            owner.openReadyReceipt();
        }

        private void close() {
            if (!open) return;
            open = false;
            owner.closeReadyReceipt();
        }
    }

    private static final class ReadyEntry {
        private final Region region;
        final PublicationGate publication;
        private EntityReadyReceipt entityReceipt;
        /** Successful Region.tryClaim receipt. It remains open through the
         * complete body/successor tail and this entry's entity receipt close. */
        private boolean claimTransactionOpen;

        private ReadyEntry(Region region) {
            this(region, false, null, -1L);
        }

        private ReadyEntry(Region region, boolean accepted) {
            this(region, accepted, null, -1L);
        }

        private ReadyEntry(Region region, boolean accepted,
                           EpochDomain.EntityDispatchPublication entityPublication,
                           long entityEpoch) {
            if (region == null) throw new NullPointerException("region");
            this.region = region;
            this.publication = new PublicationGate(accepted);
            if (entityPublication != null) {
                this.entityReceipt = new EntityReadyReceipt(
                        entityPublication, entityEpoch);
            }
        }

        private boolean acceptPublication() {
            return publication.accept();
        }

        private void cancelPublication() {
            publication.cancel();
            closeEntityReceipt();
        }

        private boolean awaitPublication() {
            return publication.awaitAccepted();
        }

        /**
         * A benign duplicate from an older dispatch may be the entry which wins
         * the current claim. Transfer its liveness witness to the exact current
         * publication before that claim attempt; open-new precedes close-old, so
         * the watchdog can never observe a poll-to-claim hole.
         */
        private synchronized void ensureEntityReceipt(
                EpochDomain.EntityDispatchPublication current, long currentEpoch) {
            EntityReadyReceipt previous = entityReceipt;
            if (previous != null && previous.owner == current
                    && previous.epoch == currentEpoch) {
                return;
            }
            EntityReadyReceipt replacement =
                    new EntityReadyReceipt(current, currentEpoch);
            entityReceipt = replacement;
            if (previous != null) previous.close();
        }

        private synchronized void closeEntityReceipt() {
            EntityReadyReceipt receipt = entityReceipt;
            if (receipt != null) {
                entityReceipt = null;
                receipt.close();
            }
        }

        private synchronized void closeClaimTransaction() {
            if (!claimTransactionOpen) return;
            region.finishClaimTransaction();
            claimTransactionOpen = false;
        }
    }

    private final ConcurrentLinkedQueue<ReadyEntry> ready = new ConcurrentLinkedQueue<>();

    /** Non-null result meaning the live holder had no helper ready to detach. */
    private static final Runnable LIVE_CLAIM_WITHOUT_HELP = () -> {
        throw new IllegalStateException("empty live-claim help sentinel was executed");
    };

    private ReadyEntry offerReady(Region region) {
        EpochDomain domain = region.domain;
        long epoch = domain == null ? -1L : domain.epoch;
        EpochDomain.EntityDispatchPublication entityPublication =
                domain != null
                        && domain.entityBarrierArmedEpoch == epoch
                        && region.groupedEpoch == epoch
                        && region.entityEpoch < epoch
                        ? domain.entityDispatchPublication : null;
        return offerReady(region, entityPublication, epoch);
    }

    private ReadyEntry offerReady(
            Region region,
            EpochDomain.EntityDispatchPublication entityPublication,
            long entityEpoch) {
        ReadyEntry entry = new ReadyEntry(
                region, false, entityPublication, entityEpoch);
        try {
            ready.add(entry);
        } catch (Throwable publicationFailure) {
            Throwable failure = publishAndSelectFatal(
                    publicationFailure);
            entry.cancelPublication();
            RegionWorkerPool.<RuntimeException>rethrow(failure);
        }
        return entry;
    }

    private void publishReady(Region region) {
        throwIfFailed();
        ReadyEntry entry = null;
        try {
            entry = offerReady(region);
            ReadyEntry commitEntry = entry;
            commitSuccessIfHealthy(() -> {
                if (!commitEntry.acceptPublication()) {
                    throw new IllegalStateException(
                            "ready publication cancelled before commit");
                }
            });
        } catch (Throwable publicationFailure) {
            Throwable failure = publishAndSelectFatal(publicationFailure);
            if (entry != null) entry.cancelPublication();
            RegionWorkerPool.<RuntimeException>rethrow(failure);
        }
    }

    private ReadyEntry pollReady() {
        ReadyEntry entry;
        while ((entry = ready.poll()) != null) {
            if (entry.awaitPublication()) return entry;
            entry.closeEntityReceipt();
            throwIfFailed();
        }
        return null;
    }

    boolean enqueueReady(Region r) {
        return enqueueReady(r, null, -1L);
    }

    boolean enqueueEntityReady(
            Region r,
            EpochDomain.EntityDispatchPublication entityPublication,
            long entityEpoch) {
        if (entityPublication == null) throw new NullPointerException("entityPublication");
        return enqueueReady(r, entityPublication, entityEpoch);
    }

    private boolean enqueueReady(
            Region r,
            EpochDomain.EntityDispatchPublication entityPublication,
            long entityEpoch) {
        enterRootAdmission();
        ReadyEntry publication = null;
        DeferredRoot deferred = null;
        boolean accepted = false;
        Throwable failure = null;
        try {
            synchronized (r.admissionLock) {
                if (RegionScheduler.ownsRuntime(this, r)) {
                    synchronized (lifecycleLock) {
                        deferred = deferExactRootEntryLocked(() -> {
                        boolean replayed = entityPublication == null
                                ? enqueueReady(r)
                                : enqueueEntityReady(r, entityPublication, entityEpoch);
                        if (!replayed) {
                            throw new IllegalStateException(
                                    "deferred region-ready publication lost its runtime");
                        }
                        }, null);
                        if (deferred != null) {
                            accepted = true;
                        } else if (acceptSubmissionLocked()) {
                            if (entityPublication == null
                                    && r.deferReadyForDomainRebase()) {
                                // A ready node is only a duplicate-tolerant
                                // liveness nudge. The rebase owner publishes one
                                // fresh new-domain node after moving all post-cut
                                // tasks and reopening claim admission.
                                accepted = true;
                            } else {
                                if (entityPublication != null
                                        && r.claimRebaseClosed()) {
                                    throw new IllegalStateException(
                                            "entity-ready obligation crossed a domain rebase cut");
                                }
                                publication = entityPublication == null
                                        ? offerReady(r)
                                        : offerReady(r, entityPublication, entityEpoch);
                            }
                            accepted = true;
                        }
                    }
                }
            }
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null) {
            if (publication != null) publication.cancelPublication();
            if (deferred != null) failure = deferred.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                publication == null ? null : publication.publication,
                deferred == null ? null : deferred.publication,
                null);
        if (failure != null) {
            if (publication != null) publication.cancelPublication();
            if (deferred != null) failure = deferred.cancelPublication(failure);
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return accepted;
    }

    /** Atomically publish a region-queue task together with its ready-lane wakeup. */
    boolean enqueueRegionTask(Region region, Runnable task) {
        enterRootAdmission();
        ExternalTask tracked = null;
        ReadyEntry readyPublication = null;
        DeferredRoot deferred = null;
        boolean accepted = false;
        Throwable failure = null;
        try {
            synchronized (region.admissionLock) {
                if (RegionScheduler.ownsRuntime(this, region)) {
                    synchronized (lifecycleLock) {
                        deferred = deferExactRootEntryLocked(() -> {
                            if (!enqueueRegionTask(region, task)) {
                                throw new IllegalStateException(
                                        "deferred region task lost its runtime");
                            }
                        }, task);
                        if (deferred != null) {
                            accepted = true;
                        } else {
                            tracked = reserveExternalTaskLocked(task);
                        }
                    }
                    if (tracked != null) {
                        if (!region.deferTaskForDomainRebase(tracked)) {
                            RegionizedServer runtime = RegionScheduler.forLevel(
                                    region.level);
                            if (runtime == null || !runtime.ownsRegion(region)) {
                                throw new IllegalStateException(
                                        "region runtime changed during admitted task publication");
                            }
                            boolean mainSafePublished = false;
                            if (RegionScheduler.shouldRunMainSafe(runtime, region)) {
                                synchronized (lifecycleLock) {
                                    if (!rootAdmissionClosed) {
                                        EpochDomain bodyDomain = region.domain;
                                        long bodyEpoch = bodyDomain == null
                                                ? Long.MIN_VALUE : bodyDomain.epoch;
                                        if (!tracked.attachMainSafeBodyReceipt(
                                                region, bodyDomain, bodyEpoch)) {
                                            throw new IllegalStateException(
                                                    "main-safe body crossed a domain-rebase cut");
                                        }
                                        mainSafeTasks.add(tracked);
                                        mainSafePublished = true;
                                    }
                                }
                            }
                            if (!mainSafePublished) {
                                region.queue().submit(tracked);
                                readyPublication = offerReady(region);
                            }
                        }
                        accepted = true;
                    }
                }
            }
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null) {
            if (readyPublication != null) readyPublication.cancelPublication();
            if (tracked != null) failure = tracked.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                readyPublication == null ? null : readyPublication.publication,
                tracked == null ? null : tracked.permit.publication,
                deferred == null ? null : deferred.publication);
        if (failure != null) {
            if (readyPublication != null) readyPublication.cancelPublication();
            if (tracked != null) failure = tracked.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
            try {
                recordExternalPublicationFailure(failure);
            } catch (Throwable bookkeepingFailure) {
                failure = publishAndSelectFatal(
                        mergeFailure(failure, bookkeepingFailure));
            }
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return accepted;
    }

    /** Atomically publish a dense-region help batch and its ready-lane signal. */
    boolean enqueueHelpTask(Region region, Runnable task) {
        enterRootAdmission();
        ExternalTask tracked = null;
        ReadyEntry readyPublication = null;
        DeferredRoot deferred = null;
        boolean accepted = false;
        Throwable failure = null;
        try {
            synchronized (region.admissionLock) {
                if (RegionScheduler.ownsRuntime(this, region)) {
                    synchronized (lifecycleLock) {
                        deferred = deferExactRootEntryLocked(() -> {
                            if (!enqueueHelpTask(region, task)) {
                                throw new IllegalStateException(
                                        "deferred region helper lost its runtime");
                            }
                        }, task);
                        if (deferred != null) {
                            accepted = true;
                        } else {
                            tracked = reserveExternalTaskLocked(task);
                        }
                    }
                    if (tracked != null) {
                        region.helpQueue.add(tracked);
                        readyPublication = offerReady(region);
                        accepted = true;
                    }
                }
            }
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null) {
            if (readyPublication != null) readyPublication.cancelPublication();
            if (tracked != null) failure = tracked.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                readyPublication == null ? null : readyPublication.publication,
                tracked == null ? null : tracked.permit.publication,
                deferred == null ? null : deferred.publication);
        if (failure != null) {
            if (readyPublication != null) readyPublication.cancelPublication();
            if (tracked != null) failure = tracked.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
            try {
                recordExternalPublicationFailure(failure);
            } catch (Throwable bookkeepingFailure) {
                failure = publishAndSelectFatal(
                        mergeFailure(failure, bookkeepingFailure));
            }
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return accepted;
    }

    /**
     * Atomically reserve a synchronous body. Only an optional queue probe may
     * return empty on healthy refusal: it has not detached or promised any work.
     * Every mandatory body still selects terminal W at the refusal cut, and an
     * already selected W is rethrown for both kinds of caller.
     */
    private ExternalPermit reserveSuccessBodyPermit(boolean probe) {
        ExternalPermit permit = null;
        Throwable failure = null;
        boolean selectedHere = false;
        try {
            synchronized (lifecycleLock) {
                failure = fatalFailure;
                if (failure == null) {
                    try {
                        boolean replayOwner = !exactReplayClosing
                                || Thread.currentThread() == exactTurnOwner;
                        if (!replayOwner || !acceptSubmissionLocked()) {
                            if (probe) return null;
                            throw new IllegalStateException(
                                    "success-only body refused by a closed or exclusive "
                                            + "pool generation");
                        }
                        StepToken executionAuthority = new StepToken(this);
                        executionAuthority.active = true;
                        permit = new ExternalPermit(executionAuthority);
                        externalObligations++;
                        if (!permit.acceptPublication()) {
                            throw new IllegalStateException(
                                    "success-only body lost its fresh publication receipt");
                        }
                    } catch (Throwable admissionFailure) {
                        fatalFailure = admissionFailure;
                        accepting = false;
                        failure = admissionFailure;
                        selectedHere = true;
                        signalLifecycleWaiters();
                    }
                }
            }
        } catch (Throwable boundaryFailure) {
            failure = publishAndSelectFatal(
                    mergeFailure(failure, boundaryFailure));
        }
        if (selectedHere) {
            for (Thread worker : workers) if (worker != null) LockSupport.unpark(worker);
        }
        if (failure != null) {
            if (permit != null) {
                failure = permit.cancelPublication(failure);
            }
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return permit;
    }

    ExternalPermit reserveExternalPermit() {
        enterRootAdmission();
        ExternalPermit permit = null;
        Throwable failure = null;
        try {
            synchronized (lifecycleLock) {
                permit = reserveExternalPermitLocked();
            }
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null && permit != null) {
            failure = permit.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                permit == null ? null : permit.publication, null, null);
        if (failure != null) {
            if (permit != null) failure = permit.cancelPublication(failure);
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return permit;
    }

    /**
     * Publish a generation-owned external obligation (currently MAIN_SYNC) through
     * the same exact-turn root cut as every pool lane. The permit is reserved before
     * queue publication, so the pause fixed point cannot pass a partial entry. A
     * post-cut producer is accepted into the deferred FIFO without exposing an
     * untracked obligation.
     */
    boolean publishExternalRoot(java.util.function.Consumer<ExternalPermit> publication) {
        if (publication == null) throw new NullPointerException("publication");
        enterRootAdmission();
        ExternalPermit permit = null;
        DeferredRoot deferred = null;
        boolean accepted = false;
        Throwable failure = null;
        try {
            synchronized (lifecycleLock) {
                deferred = deferExactRootEntryLocked(() -> {
                    if (!publishExternalRoot(publication)) {
                        throw new IllegalStateException(
                                "deferred external root was rejected by its pool generation");
                    }
                }, null);
                if (deferred != null) {
                    accepted = true;
                } else {
                    permit = reserveExternalPermitLocked();
                }
            }
            if (permit != null) {
                publication.accept(permit);
                accepted = true;
            }
        } catch (Throwable thrown) {
            // The callback may have enqueued and then thrown.  Select W before
            // terminalizing its still-OFFERING permit, so the detached consumer
            // can never enter its body in the cleanup window.
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null) {
            if (permit != null) failure = permit.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                permit == null ? null : permit.publication,
                deferred == null ? null : deferred.publication,
                null);
        if (failure != null) {
            if (permit != null) failure = permit.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
            try {
                recordExternalPublicationFailure(failure);
            } catch (Throwable bookkeepingFailure) {
                failure = publishAndSelectFatal(
                        mergeFailure(failure, bookkeepingFailure));
            }
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return accepted;
    }

    /** Publish a tracked foreign-executor body without a deferred-root replay edge. */
    void executeTrackedExternal(Executor executor, Runnable body) {
        executeTrackedExternal(executor, body, null);
    }

    /**
     * Publish one success-only body to a foreign executor while retaining this
     * generation's external obligation from before {@code beforeExecutorVisibility}
     * and executor visibility through the exact body terminal. The optional callback
     * installs a retained-result marker under the same root admission as the task, so
     * shutdown cannot close between those two publications. Nested success cuts inherit
     * a live causal token during normal STOPPING drain. Refusal is fail-stop; this path
     * never defers, retries, or replays the logical body.
     */
    void executeTrackedExternal(Executor executor, Runnable body,
                                Runnable beforeExecutorVisibility) {
        if (executor == null) throw new NullPointerException("executor");
        if (body == null) throw new NullPointerException("body");
        // An Executor may run the wrapper before execute returns (direct/CallerRuns).
        // It cannot enter the body until executor publication commits; hand that
        // same-thread invocation back to this frame instead of waiting on itself.
        class ExecutorPublication implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
            final Thread publisher = Thread.currentThread();
            final ExternalTask target;
            boolean inlineRequested;
            boolean publishing = true;

            ExecutorPublication(ExternalTask target) {
                this.target = target;
            }

            @Override
            public void run() {
                if (Thread.currentThread() == publisher && publishing) {
                    if (inlineRequested) {
                        throw new IllegalStateException("external pool task executed more than once");
                    }
                    inlineRequested = true;
                } else {
                    target.run();
                }
            }

            @Override
            public void cancelQueuedBeforeStart() {
                target.cancelQueuedBeforeStart();
            }

            @Override
            public void cancelQueuedBeforeStartWithFailure(Throwable primary) {
                target.cancelQueuedBeforeStartWithFailure(primary);
            }
        }
        ExecutorPublication publication = null;
        enterRootAdmission();
        ExternalTask task = null;
        Throwable failure = null;
        try {
            synchronized (lifecycleLock) {
                if (acceptSubmissionLocked()) {
                    ExternalPermit permit = new ExternalPermit();
                    task = new ExternalTask(body, permit, true);
                    externalObligations++;
                } else {
                    failure = fatalFailure;
                    if (failure == null) {
                        failure = new IllegalStateException(
                                "tracked foreign executor body was refused by its pool generation");
                    }
                }
            }
            if (failure == null) {
                if (beforeExecutorVisibility != null) beforeExecutorVisibility.run();
                publication = new ExecutorPublication(task);
                executor.execute(publication);
            }
        } catch (Throwable publicationFailure) {
            failure = publishAndSelectFatal(mergeFailure(failure, publicationFailure));
        }
        if (publication != null) publication.publishing = false;
        if (failure != null) {
            failure = publishAndSelectFatal(failure);
            if (task != null) failure = task.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                task == null ? null : task.permit.publication, null, null);
        if (failure != null) {
            if (task != null) failure = task.cancelPublication(failure);
            try {
                recordExternalPublicationFailure(failure);
            } catch (Throwable bookkeepingFailure) {
                failure = publishAndSelectFatal(
                        mergeFailure(failure, bookkeepingFailure));
            }
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        if (publication != null && publication.inlineRequested) publication.target.run();
    }

    private ExternalPermit reserveExternalPermitLocked() {
        if (!acceptSubmissionLocked()) return null;
        ExternalPermit permit = new ExternalPermit();
        externalObligations++;
        return permit;
    }

    private ExternalTask reserveExternalTask(Runnable body) {
        enterRootAdmission();
        ExternalTask task = null;
        Throwable failure = null;
        try {
            synchronized (lifecycleLock) {
                task = reserveExternalTaskLocked(body);
            }
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null && task != null) {
            failure = task.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                task == null ? null : task.permit.publication, null, null);
        if (failure != null) {
            if (task != null) failure = task.cancelPublication(failure);
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return task;
    }

    private ExternalTask reserveExternalTaskLocked(Runnable body) {
        if (!acceptSubmissionLocked()) return null;
        ExternalPermit permit = new ExternalPermit(null, true);
        ExternalTask task = new ExternalTask(body, permit);
        externalObligations++;
        queuedRegionalObligations++;
        return task;
    }

    private void recordExternalPublicationFailure(Throwable failure) {
        synchronized (lifecycleLock) {
            publicationFailure = mergeFailure(publicationFailure, failure);
        }
        publishFatalFailure(failure);
    }

    final class ExternalPermit {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final PublicationGate publication = new PublicationGate();
        /** Non-null for every synchronous body whose permit pins this generation. */
        private final StepToken causalAuthority;
        private final boolean regionalQueue;

        ExternalPermit() {
            this(null);
        }

        private ExternalPermit(StepToken causalAuthority) {
            this(causalAuthority, false);
        }

        private ExternalPermit(StepToken causalAuthority, boolean regionalQueue) {
            this.causalAuthority = causalAuthority;
            this.regionalQueue = regionalQueue;
        }

        boolean acceptPublication() {
            if (publication.accept()) return true;
            // A publisher is allowed to consume/cancel its own permit inside the
            // publication callback.  That is already a terminal, body-free result.
            return state.get() == TERMINAL;
        }

        private boolean awaitPublication() {
            return publication.awaitAccepted();
        }

        Throwable cancelPublication(Throwable primary) {
            publication.cancel();
            if (state.get() == TERMINAL) return primary;
            Throwable failure = primary;
            try {
                if (!cancelBeforeRun()) {
                    failure = mergeFailure(failure, new IllegalStateException(
                            "external permit escaped before publication commit"));
                }
            } catch (Throwable cleanup) {
                failure = mergeFailure(failure, cleanup);
            }
            return failure;
        }

        boolean begin() {
            if (!awaitPublication()) return false;
            synchronized (lifecycleLock) {
                return beginLocked();
            }
        }

        /** QUEUED data becomes a live body at the same cut as Main's pause decision. */
        private boolean beginLocked() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return false;
            if (regionalQueue) queuedRegionalObligations--;
            return true;
        }

        /**
         * MAIN_SYNC may already be detached from its CLQ when another body publishes
         * fatal. Linearize its QUEUED -> RUNNING decision with that publication; if
         * fatal won, close the detached permit as cancellation and throw the exact
         * generation primary without entering admission, completion, or entity body.
         */
        boolean beginMainSync() {
            if (!awaitPublication()) {
                throwIfFailed();
                return false;
            }
            synchronized (lifecycleLock) {
                Throwable fatal = fatalFailure;
                if (fatal != null) {
                    Throwable failure = fatal;
                    try {
                        if (!cancelBeforeRun()) {
                            failure = mergeFailure(failure, new IllegalStateException(
                                    "detached MAIN_SYNC permit was not QUEUED at fatal cut"));
                        }
                    } catch (Throwable cleanup) {
                        failure = mergeFailure(failure, cleanup);
                    }
                    RegionWorkerPool.<RuntimeException>rethrow(failure);
                    return false;
                }
                return beginLocked();
            }
        }

        boolean cancelBeforeRun() {
            if (!state.compareAndSet(QUEUED, TERMINAL)) return false;
            closeOwnerObligation();
            return true;
        }

        Throwable finish(Throwable primary) {
            return finish(primary, false);
        }

        private Throwable finish(Throwable primary, boolean recordShutdownActivity) {
            Throwable result;
            boolean selectedHere = false;
            synchronized (lifecycleLock) {
                // The normal external terminal and global W share one cut. A
                // body/completion which returned locally cannot publish a
                // successful permit after a peer already selected W.
                result = fatalFailure;
                if (result == null) result = primary;
                else if (primary != null && primary != result) {
                    result = mergeFailure(result, primary);
                }
                boolean terminalized = state.compareAndSet(RUNNING, TERMINAL);
                if (!terminalized) {
                    result = mergeFailure(result, new IllegalStateException(
                            "external pool permit closed outside RUNNING"));
                }
                if (terminalized) {
                    if (externalObligations <= 0L) {
                        result = mergeFailure(result, new IllegalStateException(
                                "external pool obligation completed twice or without admission"));
                    } else {
                        externalObligations--;
                    }
                    if (recordShutdownActivity) shutdownActivityEpoch++;
                    if (causalAuthority != null) {
                        if (!causalAuthority.active) {
                            result = mergeFailure(result, new IllegalStateException(
                                    "causal execution authority completed twice"));
                        } else {
                            causalAuthority.active = false;
                        }
                    }
                }
                if (result != null && fatalFailure == null) {
                    fatalFailure = result;
                    accepting = false;
                    selectedHere = true;
                }
                signalLifecycleWaiters();
            }
            if (selectedHere) {
                for (Thread worker : workers) if (worker != null) LockSupport.unpark(worker);
            }
            return result;
        }

        private void closeOwnerObligation() {
            Throwable failure = null;
            synchronized (lifecycleLock) {
                if (externalObligations <= 0L) {
                    failure = new IllegalStateException(
                            "external pool obligation completed twice or without admission");
                } else {
                    externalObligations--;
                }
                if (regionalQueue) queuedRegionalObligations--;
                if (causalAuthority != null) {
                    if (!causalAuthority.active) {
                        failure = mergeFailure(failure, new IllegalStateException(
                                "causal execution authority completed twice"));
                    } else {
                        causalAuthority.active = false;
                    }
                }
                signalLifecycleWaiters();
            }
            if (failure != null) {
                RegionWorkerPool.<RuntimeException>rethrow(failure);
            }
        }
    }

    private final class ExternalTask implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        private final Runnable body;
        private final ExternalPermit permit;
        private final boolean causalExecution;
        /** Exact region whose topology cut this claim-free body pins until terminal. */
        private Region mainSafeBodyReceiptOwner;

        private ExternalTask(Runnable body, ExternalPermit permit) {
            this(body, permit, false);
        }

        private ExternalTask(Runnable body, ExternalPermit permit,
                             boolean causalExecution) {
            this.body = body;
            this.permit = permit;
            this.causalExecution = causalExecution;
        }

        private void cancelBeforeRun() {
            cancelQueuedBeforeStart();
        }

        private boolean acceptPublication() {
            return permit.acceptPublication();
        }

        /** Caller owns the target region's admissionLock and has not published this lane. */
        private synchronized boolean attachMainSafeBodyReceipt(
                Region region, EpochDomain expectedDomain, long expectedEpoch) {
            if (mainSafeBodyReceiptOwner != null) {
                throw new IllegalStateException(
                        "main-safe body received two region transaction receipts");
            }
            if (permit.state.get() != ExternalPermit.QUEUED) {
                throw new IllegalStateException(
                        "main-safe body receipt attached after body start/terminal");
            }
            if (!region.tryOpenMainSafeBodyTransaction(
                    expectedDomain, expectedEpoch)) {
                return false;
            }
            mainSafeBodyReceiptOwner = region;
            return true;
        }

        /**
         * Close only after the ExternalPermit terminal.  Returning the merged
         * failure preserves the generation's exact W; no cleanup failure is
         * swallowed or converted into permission to continue.
         */
        private synchronized Throwable closeMainSafeBodyReceipt(Throwable primary) {
            Region owner = mainSafeBodyReceiptOwner;
            if (owner == null) return primary;
            mainSafeBodyReceiptOwner = null;
            try {
                owner.finishClaimTransaction();
            } catch (Throwable cleanup) {
                return mergeFailure(primary, cleanup);
            }
            return primary;
        }

        private Throwable cancelPublication(Throwable primary) {
            permit.publication.cancel();
            return cancelBeforeStartInternal(primary);
        }

        @Override
        public void cancelQueuedBeforeStart() {
            Throwable failure = null;
            if (body instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                try {
                    cancellable.cancelQueuedBeforeStart();
                } catch (Throwable cleanup) {
                    failure = mergeFailure(failure, cleanup);
                }
            }
            try {
                if (!permit.cancelBeforeRun()) {
                    failure = mergeFailure(failure, new IllegalStateException(
                            "published external task could not be cancelled before run"));
                }
            } catch (Throwable cleanup) {
                failure = mergeFailure(failure, cleanup);
            }
            if (permit.state.get() == ExternalPermit.TERMINAL) {
                failure = closeMainSafeBodyReceipt(failure);
            }
            if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        }

        @Override
        public void cancelQueuedBeforeStartWithFailure(Throwable primary) {
            Throwable failure = cancelBeforeStartInternal(primary);
            if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        }

        private Throwable cancelBeforeStartInternal(Throwable primary) {
            if (permit.state.get() == ExternalPermit.TERMINAL) {
                return closeMainSafeBodyReceipt(primary);
            }
            Throwable failure = primary;
            // Dense fan-in helpers carry a second, nested obligation. Close it first;
            // invoking body.run() merely to reach that cancellation gate would count as
            // starting successor work after the primary failure.
            if (body instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                try {
                    cancellable.cancelQueuedBeforeStartWithFailure(primary);
                } catch (Throwable cleanup) {
                    failure = mergeFailure(failure, cleanup);
                }
            }
            try {
                if (!permit.cancelBeforeRun()) {
                    failure = mergeFailure(failure, new IllegalStateException(
                            "published external task could not be cancelled before run"));
                }
            } catch (Throwable cleanup) {
                failure = mergeFailure(failure, cleanup);
            }
            if (permit.state.get() == ExternalPermit.TERMINAL) {
                failure = closeMainSafeBodyReceipt(failure);
            }
            return failure;
        }

        @Override
        public void run() {
            if (!permit.awaitPublication()) {
                throwIfFailed();
                return;
            }
            Throwable fatal;
            boolean started = false;
            synchronized (lifecycleLock) {
                fatal = fatalFailure;
                if (fatal == null) started = permit.beginLocked();
            }
            if (fatal != null) {
                Throwable failure = cancelBeforeStartInternal(fatal);
                RegionWorkerPool.<RuntimeException>rethrow(
                        publishAndSelectFatal(failure));
                return;
            }
            if (!started) {
                throw new IllegalStateException("external pool task executed more than once");
            }
            Throwable failure = null;
            StepToken previous = null;
            StepToken executionAuthority = null;
            boolean authorityPublished = false;
            try {
                if (causalExecution) {
                    previous = CAUSAL_STEP.get();
                    if (previous != null && previous.owner != RegionWorkerPool.this) {
                        throw new IllegalStateException(
                                "tracked foreign executor crossed pool generations");
                    }
                    executionAuthority = new StepToken(RegionWorkerPool.this);
                    synchronized (lifecycleLock) {
                        executionAuthority.active = true;
                        authorityPublished = true;
                    }
                    CAUSAL_STEP.set(executionAuthority);
                }
                body.run();
                // RegionTaskFanIn records helper failure so its coordinator can
                // drain siblings.  The pool wrapper must still observe W before
                // its caller publishes metrics, re-arms a region, or continues a
                // queue drain after that failed helper body.
                throwIfFailed();
            } catch (Throwable thrown) {
                failure = publishAndSelectFatal(thrown);
            } finally {
                if (causalExecution) {
                    try {
                        if (previous == null) CAUSAL_STEP.remove();
                        else CAUSAL_STEP.set(previous);
                    } catch (Throwable cleanup) {
                        failure = publishAndSelectFatal(
                                mergeFailure(failure, cleanup));
                    }
                    if (authorityPublished) {
                        Throwable authorityFailure = null;
                        synchronized (lifecycleLock) {
                            if (!executionAuthority.active) {
                                authorityFailure = new IllegalStateException(
                                        "tracked foreign causal authority closed twice");
                            } else {
                                executionAuthority.active = false;
                            }
                            signalLifecycleWaiters();
                        }
                        if (authorityFailure != null) {
                            failure = publishAndSelectFatal(
                                    mergeFailure(failure, authorityFailure));
                        }
                    }
                }
                try {
                    failure = permit.finish(failure, causalExecution);
                } catch (Throwable terminalFailure) {
                    failure = publishAndSelectFatal(
                            mergeFailure(failure, terminalFailure));
                } finally {
                    // Even an invariant failure inside the permit terminal must
                    // not leave domain rebase waiting on an ownerless receipt.
                    failure = closeMainSafeBodyReceipt(failure);
                }
                if (failure != null) {
                    failure = publishAndSelectFatal(failure);
                }
            }
            if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        }
    }

    /**
     * Pool-owned wrapper for ordinary global/main-safe/unit roots. Its start CAS shares
     * the lifecycle monitor with fatal publication. A failure that wins first therefore
     * cancels the still-queued root without invoking {@code body.run()}; a root which won
     * RUNNING is already part of {@link #activeSteps} and reaches its own finally chain.
     */
    private class QueuedRootTask implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final Runnable body;
        private final Runnable cancellationTarget;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        final PublicationGate publication;

        private QueuedRootTask(Runnable body) {
            this(body, body, false);
        }

        private QueuedRootTask(Runnable body, Runnable cancellationTarget) {
            this(body, cancellationTarget, false);
        }

        private QueuedRootTask(Runnable body, Runnable cancellationTarget,
                               boolean accepted) {
            if (body == null) throw new NullPointerException("body");
            this.body = body;
            this.cancellationTarget = cancellationTarget;
            this.publication = new PublicationGate(accepted);
        }

        final boolean acceptPublication() {
            return publication.accept();
        }

        final Throwable cancelPublication(Throwable primary) {
            publication.cancel();
            return cancelBeforeStartInternal(primary);
        }

        @Override
        public void run() {
            if (!publication.awaitAccepted()) {
                throwIfFailed();
                return;
            }
            Throwable fatal;
            boolean started = false;
            synchronized (lifecycleLock) {
                fatal = fatalFailure;
                if (fatal == null) started = state.compareAndSet(QUEUED, RUNNING);
            }
            if (fatal != null) {
                Throwable failure = cancelBeforeStartInternal(fatal);
                RegionWorkerPool.<RuntimeException>rethrow(
                        publishAndSelectFatal(failure));
                return;
            }
            if (!started) {
                if (state.get() == CANCELLED) return;
                throw new IllegalStateException("pool root executed more than once");
            }
            Throwable failure = null;
            try {
                body.run();
                // A peer may have selected the generation winner while this
                // body was running.  Do not publish a normal root terminal in
                // that generation: surface the exact shared W first.
                throwIfFailed();
            } catch (Throwable thrown) {
                failure = publishAndSelectFatal(thrown);
            }
            try {
                state.set(TERMINAL);
            } catch (Throwable cleanup) {
                failure = publishAndSelectFatal(
                        mergeFailure(failure, cleanup));
            }
            if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        }

        @Override
        public void cancelQueuedBeforeStart() {
            Throwable failure = cancelBeforeStartInternal(null);
            if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        }

        @Override
        public void cancelQueuedBeforeStartWithFailure(Throwable primary) {
            cancelBeforeStartInternal(primary);
        }

        private Throwable cancelBeforeStartInternal(Throwable primary) {
            publication.cancel();
            if (!state.compareAndSet(QUEUED, CANCELLED)) return primary;
            return cancelNestedEntry(cancellationTarget, primary);
        }
    }

    /** Replay publication held outside the ordinary queues during an exact Main turn. */
    private final class DeferredRoot extends QueuedRootTask {
        private DeferredRoot(Runnable replay, Runnable cancellationTarget) {
            super(replay, cancellationTarget, false);
        }
    }

    private Throwable cancelNestedEntry(Runnable entry, Throwable primary) {
        Throwable failure = primary;
        if (entry instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
            try {
                cancellable.cancelQueuedBeforeStartWithFailure(primary);
            } catch (Throwable cleanup) {
                failure = mergeFailure(failure, cleanup);
            }
        }
        return failure;
    }

    /** Cancel one detached queue entry without entering its simulation body. */
    Throwable cancelQueuedEntry(Runnable entry, Throwable primary) {
        return cancelNestedEntry(entry, primary);
    }

    /** Cancel one queued external permit (MAIN_SYNC) without evaluating admission/body. */
    Throwable cancelQueuedPermit(ExternalPermit permit, Throwable primary) {
        if (permit.state.get() == ExternalPermit.TERMINAL) return primary;
        Throwable failure = primary;
        try {
            if (!permit.cancelBeforeRun()) {
                failure = mergeFailure(failure, new IllegalStateException(
                        "queued external permit was already running during fail-stop sweep"));
            }
        } catch (Throwable cleanup) {
            failure = mergeFailure(failure, cleanup);
        }
        return failure;
    }

    Throwable mergeCancellationFailure(Throwable primary, Throwable cleanup) {
        return mergeFailure(primary, cleanup);
    }

    void recordCancellationFailure(Throwable cleanup) {
        synchronized (lifecycleLock) {
            publicationFailure = mergeFailure(publicationFailure, cleanup);
        }
    }

    /**
     * Pool-level task lane for region-GROUP work units (dispatch coalescing): one task
     * processes several regions back-to-back (spin-claim → drain queue → tick entities →
     * release per region), so a tick costs ~2×workers tasks instead of 3×regionCount.
     * Group tasks manage currentRegion themselves per region segment.
     */
    private final ConcurrentLinkedQueue<Runnable> globalTasks = new ConcurrentLinkedQueue<>();

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (secondary == null) return primary;
        if (primary == null) return secondary;
        if (primary != secondary) {
            try {
                if (primary.getSuppressed().length < 8) primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Suppression is diagnostic. Never let an allocation failure skip a
                // permit close, replay tail, or exact-turn fence release.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    boolean enqueueGlobal(Runnable task) {
        enterRootAdmission();
        QueuedRootTask queued = null;
        DeferredRoot deferred = null;
        boolean accepted = false;
        Throwable failure = null;
        try {
            synchronized (lifecycleLock) {
                deferred = deferExactRootEntryLocked(() -> {
                    if (!enqueueGlobal(task)) {
                        throw new IllegalStateException("deferred global task was rejected");
                    }
                }, task);
                if (deferred != null) {
                    accepted = true;
                } else if (acceptSubmissionLocked()) {
                    queued = new QueuedRootTask(task);
                    globalTasks.add(queued);
                    accepted = true;
                }
            }
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null) {
            if (queued != null) failure = queued.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                queued == null ? null : queued.publication,
                deferred == null ? null : deferred.publication,
                null);
        if (failure != null) {
            if (queued != null) failure = queued.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return accepted;
    }

    /**
     * Physical-Main-only lane.  These bodies mutate vanilla structures whose
     * single-writer authority is the real server thread (ticket storage, raid maps,
     * portal tickets).  Region workers must never poll this queue: a routed wrapper
     * normally re-enters itself and proves Main before invoking vanilla, so executing
     * it on a worker would publish the same body back into this lane forever.
     *
     * <p>The queue still owns an ordinary root receipt.  Exact-turn pause, normal
     * shutdown and failed-generation cancellation therefore retain the same
     * linearizable/fail-stop contract as every other pool lane; only physical Main's
     * drain/steal paths may enter its bodies.
     */
    private final ConcurrentLinkedQueue<Runnable> mainOnlyTasks =
            new ConcurrentLinkedQueue<>();

    /**
     * Main-safe global lane: tasks with NO unbounded claim-spins inside (chunk grouping
     * jobs). The barrier pump may steal from here, and ordinary region workers may also
     * execute it; entity GROUP tasks stay in globalTasks only — main parked in a group's
     * claim-spin while the claim holder waits on mainThreadProcessor (which only main
     * drains) would be a hard cycle.
     */
    private final ConcurrentLinkedQueue<Runnable> mainSafeTasks =
            new ConcurrentLinkedQueue<>();

    boolean enqueueGlobalMainOnly(Runnable task) {
        enterRootAdmission();
        QueuedRootTask queued = null;
        DeferredRoot deferred = null;
        boolean accepted = false;
        Throwable failure = null;
        try {
            synchronized (lifecycleLock) {
                deferred = deferExactRootEntryLocked(() -> {
                    if (!enqueueGlobalMainOnly(task)) {
                        throw new IllegalStateException("deferred main-only task was rejected");
                    }
                }, task);
                if (deferred != null) {
                    accepted = true;
                } else if (acceptSubmissionLocked()) {
                    queued = new QueuedRootTask(task);
                    mainOnlyTasks.add(queued);
                    accepted = true;
                }
            }
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null) {
            if (queued != null) failure = queued.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                queued == null ? null : queued.publication,
                deferred == null ? null : deferred.publication,
                null);
        if (failure != null) {
            if (queued != null) failure = queued.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return accepted;
    }

    boolean enqueueGlobalMainSafe(Runnable task) {
        enterRootAdmission();
        QueuedRootTask queued = null;
        DeferredRoot deferred = null;
        boolean accepted = false;
        Throwable failure = null;
        try {
            synchronized (lifecycleLock) {
                deferred = deferExactRootEntryLocked(() -> {
                    if (!enqueueGlobalMainSafe(task)) {
                        throw new IllegalStateException("deferred main-safe task was rejected");
                    }
                }, task);
                if (deferred != null) {
                    accepted = true;
                } else if (acceptSubmissionLocked()) {
                    queued = new QueuedRootTask(task);
                    mainSafeTasks.add(queued);
                    accepted = true;
                }
            }
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null) {
            if (queued != null) failure = queued.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                queued == null ? null : queued.publication,
                deferred == null ? null : deferred.publication,
                null);
        if (failure != null) {
            if (queued != null) failure = queued.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return accepted;
    }

    /** Drain the caller-safe lane to empty. Physical Main additionally owns the
     *  Main-only lane and drains both to a joint fixed point, including tasks one
     *  lane publishes into the other while executing. */
    void drainMainSafeAll(boolean physicalMain) {
        Runnable body = () -> drainMainSafeBody(physicalMain);
        StepToken causalAuthority = currentThreadCausalStepForThisPoolOrNull();
        if (causalAuthority != null) {
            runAcceptedCausalBody(causalAuthority, body);
            return;
        }
        ExternalPermit permit = reserveSuccessBodyPermit(true);
        if (permit != null) runAcceptedExternalBody(permit, body);
    }

    private void drainMainSafeBody(boolean physicalMain) {
        throwIfFailed();
        for (;;) {
            Runnable g = physicalMain ? mainOnlyTasks.poll() : null;
            if (g == null) g = mainSafeTasks.poll();
            if (g == null) {
                if (!physicalMain || mainOnlyTasks.peek() == null) return;
                continue;
            }
            g.run();
        }
    }

    /**
     * One non-blocking barrier-pump step. Physical Main consumes only its two
     * explicit Main lanes; autonomous ready/help work remains visible to region
     * workers. A non-Main helper may additionally consume one ready region or one
     * dense-region help batch.
     *
     * @return true if any work was executed.
     */
    /** /fabrolia regions top: cumulative main-thread help-batch steals. */
    public static final java.util.concurrent.atomic.AtomicLong MAIN_HELP_BATCHES =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong MAIN_HELP_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    boolean stealOne(boolean physicalMain) {
        java.util.function.BooleanSupplier body = () -> stealOneBody(physicalMain);
        StepToken causalAuthority = currentThreadCausalStepForThisPoolOrNull();
        if (causalAuthority != null)
            return callAcceptedCausalBoolean(causalAuthority, body);
        ExternalPermit permit = reserveSuccessBodyPermit(true);
        return permit != null && callAcceptedExternalBoolean(permit, body);
    }

    private boolean stealOneBody(boolean physicalMain) {
        throwIfFailed();
        Runnable g = physicalMain ? mainOnlyTasks.poll() : null;
        if (g == null) g = mainSafeTasks.poll();
        if (g != null) {
            g.run();
            return true;
        }
        // A ready entry is the workers' autonomous progress witness. Detaching it
        // on physical Main made every barrier wait compete with island execution:
        // Main could claim/drain a long region or run its help tail while the owner
        // unit was already the authoritative continuation. Leave the entry queued;
        // no work is dropped, deferred, or acknowledged here.
        if (physicalMain) return false;
        ReadyEntry readyEntry = pollReady();
        if (readyEntry == null) {
            return false;
        }
        return stealReadyEntry(readyEntry);
    }

    private boolean stealReadyEntry(ReadyEntry readyEntry) {
        boolean worked = false;
        Throwable failure = null;
        try {
            worked = stealReadyEntryBody(readyEntry);
        } catch (Throwable thrown) {
            // Select W before retiring the only exact ready/tail witness.
            failure = publishAndSelectFatal(thrown);
        }
        try {
            readyEntry.closeEntityReceipt();
        } catch (Throwable receiptFailure) {
            failure = publishAndSelectFatal(
                    mergeFailure(failure, receiptFailure));
        }
        try {
            readyEntry.closeClaimTransaction();
        } catch (Throwable transactionFailure) {
            failure = publishAndSelectFatal(
                    mergeFailure(failure, transactionFailure));
        }
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        return worked;
    }

    private boolean stealReadyEntryBody(ReadyEntry readyEntry) {
        Region r = readyEntry.region;
        EpochDomain readyDomain = r.domain;
        if (readyDomain != null) {
            EpochDomain.EntityDispatchPublication readyPublication =
                    readyDomain.entityDispatchPublication;
            if (!readyPublication.awaitAccepted()) {
                throwIfFailed();
                return false;
            }
            long readyEpoch = readyDomain.epoch;
            if (readyDomain.entityDispatchPublication == readyPublication
                    && r.groupedEpoch == readyEpoch
                    && r.entityEpoch < readyEpoch) {
                readyEntry.ensureEntityReceipt(readyPublication, readyEpoch);
            }
        }
        if (r.state() != RegionState.ACTIVE) {
            return false;
        }
        RegionizedServer rsPeek = RegionScheduler.forLevel(r.level);
        EpochDomain claimDomain = r.domain;
        long claimEpoch = claimDomain != null
                ? claimDomain.epoch : Long.MIN_VALUE;
        // R4 color gate: dropping the entry is safe — the blocker's
        // done-mark re-arms dependents after its volatile write.
        if (!com.axalotl.fabrolia.common.regions.ColorGate.eligible(r, rsPeek)
                || r.domain != claimDomain
                || (claimDomain != null && claimDomain.epoch != claimEpoch)) {
            return false;
        }
        // R5 LIVELOCK GUARD (live stall 2026-07-02, "[event-sched] barrier stalled 13s"):
        // main must NEVER claim a region that owes an entity phase. It cannot run the
        // phase itself (mixed-monitor hazard), and the old claim→drain→re-arm epilogue
        // ping-ponged the region between main's tight pump loop and the woken worker —
        // main kept winning the ready.poll race, the phase never ran, the barrier hung,
        // and heal (idle-only back then) starved. Hand the entry straight to a worker
        // and report "no work" so main's pump goes through its heal + park path.
        if (claimDomain != null && claimDomain.isEventEntityEpoch(claimEpoch) && rsPeek != null
                && rsPeek.owesEntityPhase(r)) {
            // Dense fan-out unlock (2026-07-12): while the claim holder is mid-phase it
            // publishes intra-region help batches — main may run those WITHOUT claiming
            // (see the doc above: batches are main-legal; item-pickup exclusion keys by
            // the ITEM's region, not the executing thread). The 2026-07-02 livelock
            // guard below only forbids main CLAIMING an owing region; returning before
            // this poll left main parked next to a stealable long pole (steal yielded
            // 0.17 ms/tick against a 17 ms barrier park on the 2026-07-11 profiles).
            // DONE_SEAL and help admission use this same monitor. If the first
            // owed read predates a phase commit, this block runs either before
            // the seal (and sees its old admitted help) or after it (and refuses
            // next-epoch help); it cannot detach across the boundary.
            Runnable owedHelp = detachHelpForLiveClaim(
                    r, rsPeek, true, claimDomain, claimEpoch);
            if (owedHelp != null) {
                if (owedHelp != LIVE_CLAIM_WITHOUT_HELP) {
                    long ht0 = System.nanoTime();
                    // The detached ExternalTask owns the atomic W-vs-entry decision.
                    // A separate pre-check here could throw after poll and strand its permit.
                    owedHelp.run();
                    throwIfFailed();
                    long elapsed = System.nanoTime() - ht0;
                    commitMainHelpMetrics(elapsed);
                    if (!r.helpQueue.isEmpty() || !r.queue().isEmpty()) {
                        publishReady(r);
                    }
                    return true;
                }
                publishReady(r);
                wake(1);
                return false;
            }
        }
        if (!r.tryClaim(Thread.currentThread(), claimDomain, claimEpoch)) {
            // false also means this stale claimant self-released after seeing
            // sealedDoneEpoch, not only that another live worker is BUSY.
            // Never detach a next-epoch help published after that seal.
            if (r.domain != claimDomain
                    || (claimDomain != null && claimDomain.epoch != claimEpoch)) {
                return false;
            }
            Runnable help = detachHelpForLiveClaim(
                    r, rsPeek, false, claimDomain, claimEpoch);
            if (help != null && help != LIVE_CLAIM_WITHOUT_HELP) {
                long ht0 = System.nanoTime();
                // Run the accepted receipt immediately after detach; it closes itself
                // without entering the body when the generation W won first.
                help.run();
                throwIfFailed();
                long elapsed = System.nanoTime() - ht0;
                commitMainHelpMetrics(elapsed);
                if (!r.helpQueue.isEmpty() || !r.queue().isEmpty()) {
                    publishReady(r);
                }
                return true;
            }
            return false;
        }
        // tryClaim opened the per-region transaction receipt.  The enclosing
        // ReadyEntry closes it only after this complete successor tail AND its
        // exact entity-ready receipt have terminated.
        readyEntry.claimTransactionOpen = true;
        // CLAIM-LEAK FIX (2026-07-13): same shape as workOnce — nothing runs
        // between a successful tryClaim and the try/finally that releases.
        boolean claimStamped = false;
        Throwable failure = null;
        try {
            throwIfFailed();
            RegionizedServer claimedServer = RegionScheduler.forLevel(r.level);
            boolean postClaimEligible = com.axalotl.fabrolia.common.regions.ColorGate
                    .eligible(r, claimedServer);
            if (postClaimEligible && claimDomain != null && claimDomain.isEventEntityEpoch(claimEpoch)
                    && claimedServer != null && claimedServer.owesEntityPhase(r)) {
                postClaimEligible = false;
            }
            // Recheck after CAS: DONE_SEAL may have published after the first
            // eligibility read, or dispatch may have made this a worker-only
            // entity obligation. A stale Main claim releases without entering
            // queue/help/portal work.
            if (postClaimEligible) {
                r.stampAcquire("stealOne");
                claimStamped = true;
                // A help batch normally runs while another thread holds this claim. If its
                // coordinator failed/released after publication, however, the next successful
                // claimant is the only remaining consumer. Drain those accepted obligations
                // before rerouting/ordinary work; otherwise stop re-arms the same orphan forever.
                drainOrphanHelp(r);
                if (!rerouteIfMainSafe(r, Thread.currentThread())) {
                    r.queue().drain(Integer.MAX_VALUE);
                }
            }
        } catch (Throwable thrown) {
            // Close the shared generation gate at the exact body edge. Releasing
            // the claim first would let Main/a peer enter another simulation root
            // after this one had already failed.
            failure = publishAndSelectFatal(thrown);
        } finally {
            if (claimStamped) {
                try {
                    r.stampRelease("stealOne");
                } catch (Throwable cleanupFailure) {
                    failure = publishAndSelectFatal(
                            mergeFailure(failure, cleanupFailure));
                }
            }
            try {
                r.release(Thread.currentThread());
            } catch (Throwable cleanupFailure) {
                failure = publishAndSelectFatal(
                        mergeFailure(failure, cleanupFailure));
            }
            if (failure == null) failure = fatalFailure;
            if (failure == null) {
                try {
                    // Re-arm and completion stamps are success publication,
                    // never cleanup after a partially applied queue task.
                    runSuccessBodyIfHealthy(() -> {
                        RegionizedServer rsSteal = RegionScheduler.forLevel(r.level);
                        EpochDomain releasedDomain = r.domain;
                        boolean eventSched = releasedDomain != null
                                && releasedDomain.isEventEntityEpoch(releasedDomain.epoch);
                        if (!r.queue().isEmpty() || !r.helpQueue.isEmpty()
                                || (eventSched && rsSteal != null
                                    && rsSteal.owesEntityPhase(r))) {
                            publishReady(r);
                            wake(1);
                        }
                        com.axalotl.fabrolia.common.regions.ColorGate
                                .tryMarkDone(r, rsSteal);
                        if (eventSched && rsSteal != null) {
                            rsSteal.contributeEntityBarrier(r);
                        }
                    });
                } catch (Throwable completionFailure) {
                    failure = publishAndSelectFatal(
                            mergeFailure(failure, completionFailure));
                }
            }
        }
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        return true;
    }

    private void commitMainHelpMetrics(long elapsedNanos) {
        try {
            commitSuccessIfHealthy(() -> {
                MAIN_HELP_BATCHES.incrementAndGet();
                MAIN_HELP_NANOS.addAndGet(elapsedNanos);
            });
        } catch (Throwable completionFailure) {
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(completionFailure));
        }
    }

    /**
     * R4: a region outside the epoch's ordered participant set must not run
     * region work. A DONE region can have higher-color dependents ticking right
     * now; a post-dispatch orphan was never seen by the color DAG. Tasks can
     * still land in its queue through submit/dispatch TOCTOU windows, so move
     * them to the main-safe lane (global-lock fallback semantics, same as the
     * executeAt redirect). Queue-FIFO is preserved.
     *
     * <p>MUST be called with the claim held — the claim serializes reroutes
     * (two concurrent pollers would interleave the FIFO).  Each detached body
     * opens its own region transaction before mainSafeTasks visibility; the
     * holder's transaction remains independently owned by the caller.
     *
     * @return true if the queue was rerouted, or deliberately retained behind
     *         an already-published domain-rebase cut.  In either case the caller
     *         must not drain it under this claim.
     */
    private boolean rerouteIfMainSafe(Region r, Thread holder) {
        synchronized (r.admissionLock) {
            if (r.currentWorker() != holder) {
                throw new IllegalStateException(
                        "main-safe reroute without the exact live region claim");
            }
            if (r.claimRebaseClosed()) {
                return true;
            }
            RegionizedServer rs = RegionScheduler.forLevel(r.level);
            if (rs == null || !rs.ownsRegion(r)
                    || !RegionScheduler.shouldRunMainSafe(rs, r)) {
                return false;
            }
            EpochDomain bodyDomain = r.domain;
            long bodyEpoch = bodyDomain == null
                    ? Long.MIN_VALUE : bodyDomain.epoch;
            Runnable task;
            synchronized (lifecycleLock) {
                // Keep beginDraining from splitting one FIFO publication between
                // the ordinary mixed-consumer lane and the shutdown Main lane.
                while ((task = r.queue().poll()) != null) {
                    transferToMainSafe(r, task, bodyDomain, bodyEpoch);
                }
            }
            return true;
        }
    }

    /**
     * Shutdown has no next epoch to consume a sealed global region's retained
     * work. Only the drain owner may transfer it, after the complete old claim
     * tail has settled. The normal epoch/claim gates remain closed.
     */
    boolean rerouteClosedRegionForShutdown(Region r) {
        throwIfFailed();
        synchronized (lifecycleLock) {
            if (!rootAdmissionClosed || drainOwner != Thread.currentThread()) {
                throw new IllegalStateException(
                        "shutdown region reroute without the exact drain owner");
            }
        }
        synchronized (r.admissionLock) {
            if (r.hasClaimOrDoneSeal() || r.claimRebaseClosed()
                    || r.activeClaimTransactions() != 0) {
                return false;
            }
            RegionizedServer rs = RegionScheduler.forLevel(r.level);
            EpochDomain domain = r.domain;
            if (rs == null || !rs.ownsRegion(r) || rs.asyncActive
                    || domain == null || domain.owner != null
                    || !RegionScheduler.shouldRunMainSafe(rs, r)) {
                return false;
            }
            long epoch = domain.epoch;
            Runnable task;
            while ((task = r.queue().poll()) != null) {
                transferToMainSafe(r, task, domain, epoch);
            }
            while ((task = r.helpQueue.poll()) != null) {
                transferToMainSafe(r, task, domain, epoch);
            }
            return true;
        }
    }

    /** Caller owns region admission and the right to detach this FIFO entry. */
    private void transferToMainSafe(
            Region r, Runnable task, EpochDomain domain, long epoch) {
        try {
            if (!(task instanceof ExternalTask external)) {
                throw new IllegalStateException(
                        "untracked task reached the production region queue");
            }
            if (!external.attachMainSafeBodyReceipt(r, domain, epoch)) {
                throw new IllegalStateException(
                        "main-safe reroute crossed a domain-rebase cut");
            }
            synchronized (lifecycleLock) {
                // A region FIFO has one consumer. During stop, physical Main
                // alone consumes its retained bodies and any causal suffix.
                if (rootAdmissionClosed) mainOnlyTasks.add(external);
                else mainSafeTasks.add(external);
            }
        } catch (Throwable transferFailure) {
            Throwable failure = publishAndSelectFatal(transferFailure);
            failure = cancelQueuedEntry(task, failure);
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
    }

    /** A2 unit lane: island tick-unit coordinator tasks. Separate from
     *  globalTasks so a coordinator's help-pump ({@link #workOnce} with
     *  {@code allowUnits=false}) can NEVER pick up another unit and nest —
     *  nesting would stack unit latencies and coordinator stacks arbitrarily
     *  deep. Only the top-level worker loop starts units. */
    private final ConcurrentLinkedQueue<Runnable> unitTasks = new ConcurrentLinkedQueue<>();

    boolean enqueueUnit(Runnable task) {
        enterRootAdmission();
        QueuedRootTask queued = null;
        DeferredRoot deferred = null;
        boolean accepted = false;
        Throwable failure = null;
        try {
            synchronized (lifecycleLock) {
                deferred = deferExactRootEntryLocked(() -> {
                    if (!enqueueUnit(task)) {
                        throw new IllegalStateException("deferred unit task was rejected");
                    }
                }, task);
                if (deferred != null) {
                    accepted = true;
                } else if (acceptSubmissionLocked()) {
                    queued = new QueuedRootTask(task);
                    unitTasks.add(queued);
                    accepted = true;
                }
            }
        } catch (Throwable thrown) {
            failure = publishAndSelectFatal(thrown);
        }
        if (failure != null) {
            if (queued != null) failure = queued.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
        }
        failure = closeRootAdmission(failure,
                queued == null ? null : queued.publication,
                deferred == null ? null : deferred.publication,
                null);
        if (failure != null) {
            if (queued != null) failure = queued.cancelPublication(failure);
            if (deferred != null) failure = deferred.cancelPublication(failure);
            RegionWorkerPool.<RuntimeException>rethrow(
                    publishAndSelectFatal(failure));
        }
        return accepted;
    }

    private void workerLoop() {
        RegionWorkerThread me = (RegionWorkerThread) Thread.currentThread();
        while (true) {
            Throwable stepFailure = null;
            StepToken step;
            // Dequeue and the active-step receipt share the shutdown/admission cut.
            synchronized (lifecycleLock) {
                while (running && exactTurnPaused && fatalFailure == null) {
                    try {
                        awaitLifecycleChange();
                    } catch (InterruptedException ignored) {
                        // A wake-up cannot bypass the exact/global admission cut.
                    }
                }
                if (!running || fatalFailure != null) break;
                step = new StepToken(this);
                step.active = true;
                activeSteps.incrementAndGet();
            }
            CAUSAL_STEP.set(step);
            // Held packets leave when the step ends, before its step receipt retires.
            me.outbound.open();
            boolean worked;
            try {
                worked = workOnce(me, true);
                me.outbound.close();
            } catch (Throwable thrown) {
                me.outbound.closeAfter(thrown);
                stepFailure = thrown;
                worked = false;
                publishFatalFailure(thrown);
            } finally {
                synchronized (lifecycleLock) {
                    step.active = false;
                    activeSteps.decrementAndGet();
                    signalLifecycleWaiters();
                }
                CAUSAL_STEP.remove();
            }
            if (stepFailure != null) return;
            if (worked) continue;
            me.parked = true;
            if (ready.peek() == null && globalTasks.peek() == null
                    && mainSafeTasks.peek() == null && unitTasks.peek() == null) {
                LockSupport.parkNanos(this, 10_000_000L);
            }
            me.parked = false;
        }
    }

    boolean isOwnWorker(Thread thread) {
        if (!(thread instanceof RegionWorkerThread)) return false;
        for (Thread worker : workers) if (worker == thread) return true;
        return false;
    }

    private boolean acceptSubmissionLocked() {
        if (!running || !accepting) return false;
        if (exactTurnPaused) return Thread.currentThread() == exactTurnOwner;
        if (exactTurnRequested) {
            Thread caller = Thread.currentThread();
            return caller == exactTurnOwner || isOwnWorker(caller)
                    || hasLiveCausalStepLocked();
        }
        if (!rootAdmissionClosed) return true;
        Thread caller = Thread.currentThread();
        return caller == drainOwner || isOwnWorker(caller)
                || hasLiveCausalStepLocked();
    }

    /**
     * Linearization point for unrelated roots that arrive after an exact-turn
     * request. They are accepted into the same pool generation, but deliberately
     * do not participate in the pre-turn fixed point and cannot starve it.
     */
    private boolean deferExactRootLocked(Runnable replay) {
        // Keep the original causal decision explicit in this compatibility seam.
        // Production publishers pass a cancellation target to the overload below;
        // reflective/model callers still exercise the same live-parent rule here.
        if (hasLiveCausalStepLocked()) return false;
        return deferExactRootLocked(replay, null);
    }

    private boolean deferExactRootLocked(Runnable replay, Runnable cancellationTarget) {
        DeferredRoot deferred = deferExactRootEntryLocked(replay, cancellationTarget);
        if (deferred == null) return false;
        if (!deferred.acceptPublication()) {
            throw new IllegalStateException("deferred root publication cancelled before commit");
        }
        return true;
    }

    /** Caller keeps the returned receipt OFFERING until its admission closes. */
    private DeferredRoot deferExactRootEntryLocked(
            Runnable replay, Runnable cancellationTarget) {
        // Fatal publication closes every admission shape, including the
        // pre-cut deferred FIFO.  Otherwise an unrelated producer could land
        // new simulation after accepting=false merely because an exact turn
        // happened to be paused at the failure edge.
        if (fatalFailure != null) return null;
        if (!exactTurnRequested && !exactTurnPaused) return null;
        Thread caller = Thread.currentThread();
        if (caller == exactTurnOwner || isOwnWorker(caller)
                || hasLiveCausalStepLocked()) return null;
        DeferredRoot deferred = new DeferredRoot(replay, cancellationTarget);
        // CLQ constructs its node before the linking CAS and performs no
        // allocation after visibility.  ArrayDeque grows only after advancing
        // its tail, so an OOME there would leave an ambiguous visible root.
        exactDeferredRoots.add(deferred);
        return deferred;
    }

    private boolean hasLiveCausalStepLocked() {
        StepToken token = CAUSAL_STEP.get();
        return token != null && token.owner == this && token.active;
    }

    /**
     * Cold-path proof that the calling thread is executing an accepted step of
     * some still-live pool generation.  This includes the fresh execution token
     * installed by {@link #wrapCausalHandoff(Runnable)} on a foreign executor;
     * thread class alone therefore cannot replace this check.
     */
    static boolean currentThreadHasLiveCausalStep() {
        StepToken token = CAUSAL_STEP.get();
        if (token == null) return false;
        synchronized (token.owner.lifecycleLock) {
            return token.active;
        }
    }

    /**
     * Hot-path owner lookup for scheduler success bodies and commits reached from
     * an already accepted causal body. This deliberately takes no scheduler or
     * pool lock. The enclosing worker step or synchronous external permit already
     * pins the owner generation while its exact body is live.
     *
     * <p>The ThreadLocal slot yields an execution token installed for this thread;
     * foreign handoffs install a fresh token rather than the captured parent's token.
     * While installed, that same execution deactivates it after the body.  A worker
     * token remains represented by {@link #activeSteps}; a foreign-executor token
     * remains represented by its running {@link ExternalPermit} and
     * {@link #externalObligations}.  Pool stop cannot reach its fixed point, and
     * RegionScheduler therefore cannot detach or replace this owner generation,
     * until that representation and this token retire.
     */
    static RegionWorkerPool currentThreadCausalOwnerOrNull() {
        StepToken token = CAUSAL_STEP.get();
        return token != null && token.active ? token.owner : null;
    }

    /** Finish the entered unit, then yield for admission cuts or metadata backlog. */
    static boolean currentUnitChainMustYield() {
        RegionWorkerPool owner = currentThreadCausalOwnerOrNull();
        if (owner == null) return false;
        synchronized (owner.lifecycleLock) {
            return owner.exactTurnRequested || owner.exactTurnPaused;
        }
    }

    /** Same-thread lookup used by the pool methods after scheduler causal routing. */
    private StepToken currentThreadCausalStepForThisPoolOrNull() {
        StepToken token = CAUSAL_STEP.get();
        return token != null && token.owner == this && token.active ? token : null;
    }

    private static final class StepToken {
        final RegionWorkerPool owner;
        boolean active;

        StepToken(RegionWorkerPool owner) {
            this.owner = owner;
        }
    }

    private boolean ordinaryQueuesEmpty() {
        return ready.isEmpty() && globalTasks.isEmpty()
                && mainOnlyTasks.isEmpty() && mainSafeTasks.isEmpty()
                && unitTasks.isEmpty();
    }

    /** Detached accepted data waits while ordinary roots drain into an exact turn. */
    boolean mayServiceDetachedTransfers() {
        synchronized (lifecycleLock) {
            return running && fatalFailure == null && !exactTurnRequested && !exactTurnPaused;
        }
    }

    /**
     * One work step on a WORKER thread: a ready-region claim (queue drain +
     * owed entity phase), a help batch, or a unit/global/main-safe task;
     * ordinary pool consumers may omit unit roots with {@code allowUnits=false}.
     *
     * @return true if any work was executed or attempted (caller should
     *         re-poll immediately); false = all selected lanes empty.
     */
    boolean workOnce(RegionWorkerThread me, boolean allowUnits) {
        return workOnce(me, allowUnits, true);
    }

    /** Dependencies may unblock an own member; unrelated ready bodies may not. */
    boolean workBarrierDependenciesOnce(RegionWorkerThread me) {
        return workOnce(me, false, false);
    }

    private boolean workOnce(RegionWorkerThread me, boolean allowUnits, boolean allowReady) {
        boolean worked = workOnceBody(me, allowUnits, allowReady);
        // Before the shutdown cut, activeSteps already owns all lifecycle ordering
        // and no fixed-point observer exists, so serializing every successful step
        // on lifecycleLock only turns the worker pool into one contended lane.  The
        // flag is monotonic for this one-shot generation.  If a step reads false,
        // its result was published before beginDraining's volatile write; the first
        // shutdown progress pass necessarily follows that write.  A step which
        // finishes after the cut records the epoch under the fixed-point monitor.
        if (worked && rootAdmissionClosed) {
            synchronized (lifecycleLock) {
                shutdownActivityEpoch++;
                signalLifecycleWaiters();
            }
        }
        return worked;
    }

    private boolean workOnceBody(RegionWorkerThread me, boolean allowUnits, boolean allowReady) {
            throwIfFailed();
            if (allowUnits && NetherPortalTransfers.serviceOne()) return true;
            ReadyEntry readyEntry = allowReady ? pollReady() : null;
            if (readyEntry == null) {
                Runnable global = allowUnits ? unitTasks.poll() : null;
                if (global == null) {
                    global = globalTasks.poll();
                }
                if (global == null) {
                    global = mainSafeTasks.poll();
                }
                if (global != null) {
                    global.run();
                    return true;
                }
                return false;
            }
            return workReadyEntry(me, readyEntry);
    }

    private boolean workReadyEntry(RegionWorkerThread me, ReadyEntry readyEntry) {
        boolean worked = false;
        Throwable failure = null;
        try {
            worked = workReadyEntryBody(me, readyEntry);
        } catch (Throwable thrown) {
            // Select W before retiring the only exact ready/tail witness.
            failure = publishAndSelectFatal(thrown);
        }
        try {
            readyEntry.closeEntityReceipt();
        } catch (Throwable receiptFailure) {
            failure = publishAndSelectFatal(
                    mergeFailure(failure, receiptFailure));
        }
        try {
            readyEntry.closeClaimTransaction();
        } catch (Throwable transactionFailure) {
            failure = publishAndSelectFatal(
                    mergeFailure(failure, transactionFailure));
        }
        if (failure != null) RegionWorkerPool.<RuntimeException>rethrow(failure);
        return worked;
    }

    /**
     * Consume an already-published entity obligation for the barrier owned by
     * the calling island coordinator.  The normal ready node remains in the
     * global CLQ as a benign duplicate; whichever claimant wins runs the exact
     * phase and the later loser observes {@code entityEpoch >= epoch}.
     *
     * <p>The synthetic entry carries its own receipt from before this helper
     * can be descheduled through the complete claim/commit/contribution tail.
     * It is therefore the same fail-stop/liveness transaction as a global
     * ready consumer, not an alternate entity-tick implementation.</p>
     */
    boolean helpAcceptedEntityRegion(RegionWorkerThread me, Region region,
                                     EpochDomain.EntityDispatchPublication publication,
                                     long epoch) {
        EpochDomain domain = region.domain;
        if (domain == null || domain.epoch != epoch
                || domain.entityDispatchPublication != publication
                || !publication.awaitAccepted()) {
            throw new IllegalStateException(
                    "island barrier helper lost its accepted dispatch generation");
        }
        ReadyEntry direct = new ReadyEntry(region, true, publication, epoch);
        return workReadyEntry(me, direct);
    }

    private boolean workReadyEntryBody(RegionWorkerThread me, ReadyEntry readyEntry) {
            Region claimed = readyEntry.region;
            EpochDomain readyDomain = claimed.domain;
            if (readyDomain != null) {
                EpochDomain.EntityDispatchPublication readyPublication =
                        readyDomain.entityDispatchPublication;
                if (!readyPublication.awaitAccepted()) {
                    throwIfFailed();
                    return true;
                }
                long readyEpoch = readyDomain.epoch;
                if (readyDomain.entityDispatchPublication == readyPublication
                        && claimed.groupedEpoch == readyEpoch
                        && claimed.entityEpoch < readyEpoch) {
                    readyEntry.ensureEntityReceipt(readyPublication, readyEpoch);
                }
            }
            if (claimed.state() != RegionState.ACTIVE) return true;
            EpochDomain claimDomain = claimed.domain;
            long claimEpoch = claimDomain != null
                    ? claimDomain.epoch : Long.MIN_VALUE;
            // R4 color gate (see stealOne note on liveness).
            if (!com.axalotl.fabrolia.common.regions.ColorGate.eligible(claimed,
                    RegionScheduler.forLevel(claimed.level))
                    || claimed.domain != claimDomain
                    || (claimDomain != null && claimDomain.epoch != claimEpoch)) {
                return true;
            }
            // COHERENT PUBLICATION PROTOCOL (2026-07-13 production audit):
            // publish BEFORE claim, release BEFORE unpublish. With volatile
            // ordering this makes the state (holder == T && T.currentRegion
            // != region) IMPOSSIBLE to observe for any legitimately held
            // claim: a reader that sees the claim CAS must also see the
            // earlier publication, and a reader that still sees the old
            // holder during release sees the not-yet-cleared publication.
            // The heal forensic therefore never needs to guess — any
            // mismatch it observes twice for one claim GENERATION is a real
            // retention, with the acquire path and age in the ledger.
            Region prevPublished = me.currentRegion;
            if (prevPublished != null) {
                // Nested-claim invariant (user directive 2026-07-13): a thread
                // must never take a region claim while still publishing (and
                // thus potentially holding) another region. No legal path
                // does. Logging and continuing would let this worker mutate two
                // regions without either ownership proof.
                throw new IllegalStateException(
                        "nested region claim: " + me.getName() + " entering "
                                + Long.toHexString(claimed.gridKey) + " while publishing "
                                + Long.toHexString(prevPublished.gridKey));
            }
            me.currentRegion = claimed;
            if (!claimed.tryClaim(me, claimDomain, claimEpoch)) {
                me.currentRegion = prevPublished;
                // Another worker holds the claim. If the holder published intra-region
                // sub-batches (dense fan-out), help with ONE of them under this region's
                // lock discipline, then re-arm if more work remains. Otherwise drop the
                // entry — the holder's release re-check re-arms the region if its task
                // queue refilled.
                if (claimed.domain != claimDomain
                        || (claimDomain != null && claimDomain.epoch != claimEpoch)) {
                    return true;
                }
                RegionizedServer failedClaimServer = RegionScheduler.forLevel(
                        claimed.level);
                Runnable help = detachHelpForLiveClaim(
                        claimed, failedClaimServer, false,
                        claimDomain, claimEpoch);
                if (help != null && help != LIVE_CLAIM_WITHOUT_HELP) {
                    Region prev = me.currentRegion;
                    me.currentRegion = claimed;
                    try {
                        // ExternalTask linearizes fatal selection against body entry.
                        help.run();
                        throwIfFailed();
                    } finally {
                        me.currentRegion = prev;
                    }
                    if (!claimed.helpQueue.isEmpty() || !claimed.queue().isEmpty()) {
                        publishReady(claimed);
                    }
                }
                return true;
            }
            // Kept through release -> publishReady -> ColorGate -> barrier and
            // the outer ReadyEntry.closeEntityReceipt terminal.
            readyEntry.claimTransactionOpen = true;
            // CLAIM-LEAK FIX (2026-07-13 "SUSPECT LEAKED CLAIM"): everything
            // after a successful tryClaim runs under try/finally — the old
            // shape left rerouteIfMainSafe + forLevel outside it, so any throw
            // there leaked the claim permanently (and the worker died, see
            // workerLoop fail-stop edge). release() is a CAS(me→null), so the
            // reroute now moves only the queue; this outer transaction retains
            // the claim through frame close and owns the one release.
            boolean claimStamped = false;
            boolean entityPhaseRan = false;
            Throwable phaseFailure = null;
            try {
                throwIfFailed();
                RegionizedServer claimedServer = RegionScheduler.forLevel(claimed.level);
                // Required post-CAS half of DONE_SEAL: a ready consumer which
                // read eligibility before sealedDoneEpoch was published must
                // release without entering queue/help/entity/portal work.
                if (com.axalotl.fabrolia.common.regions.ColorGate
                        .eligible(claimed, claimedServer)) {
                    claimed.stampAcquire("workOnce");
                    claimStamped = true;
                    // Same orphan-help recovery as stealOne's successful-claim path. This
                    // worker publishes currentRegion and holds the exact region claim.
                    drainOrphanHelp(claimed);
                    if (!rerouteIfMainSafe(claimed, me)) {
                        claimed.queue().drain(Integer.MAX_VALUE);
                        // R5 event scheduler: the claim path IS the entity-phase executor —
                        // preserves the per-region [spawn][random][entities] FIFO exactly like
                        // the legacy group task (drain first, then tick). The callee
                        // checks the accepted epoch protocol, owing and ColorGate;
                        // a desired config change cannot revoke this obligation.
                        if (claimedServer != null) {
                            entityPhaseRan = claimedServer.runOwedEntityPhase(claimed);
                        }
                    }
                }
            } catch (Throwable thrown) {
                // The entered vanilla/Fabric phase is terminal on any Throwable.
                // Publish before claim/frame cleanup so no successor can pass the
                // global start gate in that cleanup window.
                phaseFailure = publishAndSelectFatal(thrown);
            } finally {
                if (phaseFailure == null) phaseFailure = fatalFailure;
                if (phaseFailure == null) {
                    try {
                        final RegionizedServer commitServer = entityPhaseRan
                                ? RegionScheduler.forLevel(claimed.level)
                                : null;
                        if (entityPhaseRan && commitServer == null) {
                            throw new IllegalStateException(
                                    "missing RegionizedServer while committing entity phase");
                        }
                        final boolean commitEntityPhase = entityPhaseRan;
                        commitSuccessIfHealthy(() -> {
                            if (commitEntityPhase) commitServer.commitEntityPhase(claimed);
                        });
                    } catch (Throwable completionFailure) {
                        phaseFailure = publishAndSelectFatal(
                                completionFailure);
                    }
                }
                if (claimStamped) {
                    try {
                        claimed.stampRelease("workOnce");
                    } catch (Throwable cleanupFailure) {
                        phaseFailure = publishAndSelectFatal(
                                mergeFailure(phaseFailure, cleanupFailure));
                    }
                }
                try {
                    claimed.release(me);
                } catch (Throwable cleanupFailure) {
                    phaseFailure = publishAndSelectFatal(
                            mergeFailure(phaseFailure, cleanupFailure));
                }
                if (phaseFailure == null) phaseFailure = fatalFailure;
                // A failed phase has no legal successor/re-arm/barrier
                // completion. Those are success publications, not cleanup.
                if (phaseFailure == null) {
                    try {
                        final Region releasedRegion = claimed;
                        runSuccessBodyIfHealthy(() -> {
                            RegionizedServer rs = RegionScheduler.forLevel(
                                    releasedRegion.level);
                            EpochDomain releasedDomain = releasedRegion.domain;
                            boolean eventSched = releasedDomain.isEventEntityEpoch(releasedDomain.epoch);
                            if (!releasedRegion.queue().isEmpty()
                                    || !releasedRegion.helpQueue.isEmpty()
                                    || (eventSched && rs != null
                                        && rs.owesEntityPhase(releasedRegion))) {
                                publishReady(releasedRegion);
                            }
                            com.axalotl.fabrolia.common.regions.ColorGate
                                    .tryMarkDone(releasedRegion, rs);
                            if (eventSched && rs != null) {
                                rs.contributeEntityBarrier(releasedRegion);
                            }
                        });
                    } catch (Throwable completionFailure) {
                        phaseFailure = publishAndSelectFatal(
                                mergeFailure(phaseFailure, completionFailure));
                    }
                }
                me.currentRegion = prevPublished;
            }
            if (phaseFailure != null) {
                RegionWorkerPool.<RuntimeException>rethrow(phaseFailure);
            }
            return true;
    }

    /**
     * Detach at most one dense helper without crossing an epoch's done seal.
     * Help publication and DONE_SEAL both serialize on admissionLock.  A
     * non-null holder is also required: the dense fan-in owner retains the
     * region claim until every detached ExternalTask reaches its terminal, so
     * after this atomic detach the task remains part of that old claim even
     * though it executes outside the monitor.  A null holder means BUSY was
     * actually DONE_SEAL/stale-self-refusal; next-epoch help stays queued.
     */
    private Runnable detachHelpForLiveClaim(
            Region region, RegionizedServer runtime, boolean requireOwedPhase,
            EpochDomain expectedDomain, long expectedEpoch) {
        synchronized (region.admissionLock) {
            Thread holder = region.currentWorker();
            if (region.domain != expectedDomain
                    || (expectedDomain != null
                        && expectedDomain.epoch != expectedEpoch)
                    || holder == null || !holder.isAlive()
                    || !com.axalotl.fabrolia.common.regions.ColorGate
                            .eligible(region, runtime)
                    || (requireOwedPhase && !runtime.owesEntityPhase(region))) {
                return null;
            }
            Runnable help = region.helpQueue.poll();
            return help != null ? help : LIVE_CLAIM_WITHOUT_HELP;
        }
    }

    /** Execute every accepted helper left without its original claim coordinator. */
    private void drainOrphanHelp(Region region) {
        Runnable help;
        while ((help = region.helpQueue.poll()) != null) {
            // Never check between detach and the receipt's own fatal-cut admission.
            help.run();
            throwIfFailed();
        }
    }
}
