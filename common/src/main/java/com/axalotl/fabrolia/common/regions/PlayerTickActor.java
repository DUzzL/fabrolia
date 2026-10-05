package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * One serial physical thread used for exact-ordinal player entity bodies.
 *
 * <p>The server thread never advances to the next EntityTickList ordinal while a
 * job is running.  Completion is a receipt hand-off, so every body write happens-
 * before the waiting server thread resumes.  There is deliberately no timeout and
 * no retry-after-entry: either the exact body returns, or its exact Throwable is
 * rethrown at the original vanilla call site.
 */
final class PlayerTickActor {
    /** Admission failed before the supplied body could start. */
    static final class Rejected extends IllegalStateException {
        private Rejected(String message) {
            super(message);
        }

        private Rejected(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Object lock = new Object();
    private final ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>();
    private final String threadName;
    private Thread thread;
    private Job runningJob;
    private boolean closing;
    private boolean terminated;
    private Throwable terminalFailure;

    PlayerTickActor(String threadName) {
        this.threadName = threadName;
    }

    void executeAndAwait(Runnable body) {
        executeAndAwait(body, null);
    }

    /**
     * Submit the one production job whose vanilla body is authorized by an
     * already-held exact-turn fence.  Its body installs the logical-server turn
     * and takes the dedicated paused-actor cut immediately before the vanilla
     * consumer; an ordinary external permit would self-refuse because physical
     * Main, not this actor thread, owns the pause.
     */
    void executeExactAndAwait(Thread physicalOwner, Runnable body) {
        if (physicalOwner == null) throw new NullPointerException("physicalOwner");
        if (Thread.currentThread() != physicalOwner) {
            throw new Rejected("exact player actor submitted by a non-owner thread");
        }
        executeAndAwait(body, physicalOwner);
    }

    private void executeAndAwait(Runnable body, Thread exactPhysicalOwner) {
        if (body == null) throw new NullPointerException("body");
        if (Thread.currentThread() == thread) {
            throw new Rejected("nested exact player actor submission");
        }
        Job job;
        synchronized (lock) {
            if (closing || terminated) {
                if (terminalFailure != null) {
                    PlayerTickActor.<RuntimeException>rethrow(terminalFailure);
                }
                throw new Rejected("exact player actor is stopping/stopped");
            }
            // Read and rethrow the sticky body failure before allocating a new
            // admission record. Even an OOME here must not replace the exact
            // Throwable which already terminated this actor.
            job = new Job(body, Thread.currentThread(), RegionScheduler.isActive(),
                    exactPhysicalOwner);
            try {
                ensureStartedLocked();
                // CLQ allocates its node before the linking CAS.  A failed
                // allocation is therefore a definite pre-publication refusal;
                // ArrayDeque could advance its tail and then fail while growing,
                // making Main fallback ambiguous with an actor-visible job.
                queue.add(job);
            } catch (Rejected rejected) {
                throw rejected;
            } catch (Throwable admissionFailure) {
                throw new Rejected(
                        "exact player actor admission failed before body entry",
                        admissionFailure);
            }
            lock.notifyAll();
        }
        job.awaitAndRethrow();
    }

    void shutdownAndAwait() {
        Thread target;
        synchronized (lock) {
            if (terminated && (thread == null || !thread.isAlive())) return;
            closing = true;
            target = thread;
            lock.notifyAll();
            if (target == null) {
                terminated = true;
                return;
            }
        }
        if (Thread.currentThread() == target) {
            throw new IllegalStateException("exact player actor cannot join itself");
        }
        boolean interrupted = false;
        while (target.isAlive()) {
            try {
                target.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        synchronized (lock) {
            terminated = true;
            lock.notifyAll();
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    Thread thread() {
        synchronized (lock) {
            return thread;
        }
    }

    /** False after a terminal start/loop failure or orderly server shutdown. */
    boolean available() {
        synchronized (lock) {
            return !closing && !terminated;
        }
    }

    Throwable terminalFailure() {
        synchronized (lock) {
            return terminalFailure;
        }
    }

    private void ensureStartedLocked() {
        if (thread != null) return;
        try {
            Thread created = new Thread(ParallelProcessor.FABROLIA_GROUP,
                    this::runLoop, threadName);
            created.setDaemon(true);
            created.setPriority(FabroliaConfig.getThreadPriority());
            created.setUncaughtExceptionHandler((failed, thrown) -> {
                Throwable winner = publishActorFatal(thrown);
                synchronized (lock) {
                    closing = true;
                    terminated = true;
                    if (terminalFailure == null) terminalFailure = winner;
                    Throwable fatal = terminalFailure;
                    if (runningJob != null) {
                        runningJob.finish(fatal);
                        runningJob = null;
                    }
                    Job pending;
                    while ((pending = queue.poll()) != null) {
                        pending.finish(fatal);
                    }
                    lock.notifyAll();
                }
            });
            created.start();
            thread = created;
        } catch (Throwable startFailure) {
            closing = true;
            terminated = true;
            lock.notifyAll();
            throw new Rejected("exact player actor could not start", startFailure);
        }
    }

    private void runLoop() {
        for (;;) {
            Job job;
            synchronized (lock) {
                while ((job = queue.poll()) == null && !closing) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                        // Interruption is only a wakeup.  An accepted job is never
                        // abandoned and shutdown still waits for exact completion.
                    }
                }
                if (job == null) {
                    terminated = true;
                    lock.notifyAll();
                    return;
                }
                runningJob = job;
            }
            Throwable failure = null;
            try {
                if (job.exactPhysicalOwner != null) {
                    // runExactActorTurn owns the dedicated W-vs-vanilla-body cut.
                    // Do not reserve an ExternalPermit here: exactTurnPaused only
                    // admits its physical Main owner, so the actor would reject
                    // every legitimate production job before its logical turn.
                    job.body.run();
                } else if (job.schedulerGeneration) {
                    // Standalone/integration uses outside the production exact
                    // handoff remain ordinary external success-only bodies.
                    RegionScheduler.runSuccessBodyIfHealthy(job.body);
                } else {
                    // Focused actor tests run without a scheduler generation.
                    job.body.run();
                }
                commitActorSuccess(job);
            } catch (Throwable thrown) {
                failure = publishActorFatal(thrown);
            }
            if (failure != null) {
                // The actor completion wakes physical Main. Publish into the
                // generation-wide first-wins slot before that notification so no
                // unrelated worker can enter a successor in between.
                synchronized (lock) {
                    // First body failure is terminal for this physical actor.
                    // Close admission before waking the submitting Main thread,
                    // and fail every already-accepted but unstarted obligation
                    // with that exact Throwable. No later ordinal body may run.
                    closing = true;
                    if (terminalFailure == null) terminalFailure = failure;
                    Throwable fatal = terminalFailure;
                    if (runningJob == job) runningJob = null;
                    job.finish(fatal);
                    Job pending;
                    while ((pending = queue.poll()) != null) {
                        pending.finish(fatal);
                    }
                    terminated = true;
                    lock.notifyAll();
                }
                return;
            }
        }
    }

    private void commitActorSuccess(Job job) {
        // The generation cut may only accept the already-offered receipt.  In
        // particular it must not take the actor/Job monitors or wake physical
        // Main while a peer body-W publisher is waiting for lifecycleLock.
        if (job.schedulerGeneration) {
            RegionScheduler.commitSuccessIfHealthy(job::acceptSuccess);
        } else {
            // Focused standalone actor tests deliberately have no scheduler
            // generation; their local serial monitor remains the only cut.
            job.acceptSuccess();
        }

        // Consume the accepted receipt only after commitSuccessIfHealthy has
        // released lifecycleLock.  This is the sole normal actor terminal and
        // the sole normal wakeup of the submitting Main thread.
        synchronized (lock) {
            if (runningJob != job) {
                throw new IllegalStateException(
                        "player actor lost its running-job success receipt");
            }
            if (!job.successAccepted()) {
                throw new IllegalStateException(
                        "player actor success receipt was not accepted");
            }
            runningJob = null;
            job.finishAcceptedSuccess();
        }
    }

    /** Standalone actor tests have no scheduler generation; production actors do. */
    private static Throwable publishActorFatal(Throwable failure) {
        return RegionScheduler.isActive()
                ? RegionScheduler.publishFatalAndSelectWinner(failure)
                : failure;
    }

    private static final class Job {
        private static final int SUCCESS_OFFERING = 0;
        private static final int SUCCESS_ACCEPTED = 1;

        private final Runnable body;
        private final Thread waiter;
        private final boolean schedulerGeneration;
        private final Thread exactPhysicalOwner;
        /** Single-writer fixed receipt accepted by the generation cut. */
        private volatile int successReceipt = SUCCESS_OFFERING;
        private volatile boolean done;
        private Throwable failure;

        private Job(Runnable body, Thread waiter, boolean schedulerGeneration,
                    Thread exactPhysicalOwner) {
            this.body = body;
            this.waiter = waiter;
            this.schedulerGeneration = schedulerGeneration;
            this.exactPhysicalOwner = exactPhysicalOwner;
        }

        /** Fixed non-blocking write: the only operation legal in the success cut. */
        private void acceptSuccess() {
            successReceipt = SUCCESS_ACCEPTED;
        }

        private boolean successAccepted() {
            return successReceipt == SUCCESS_ACCEPTED;
        }

        private void finishAcceptedSuccess() {
            if (!successAccepted()) {
                throw new IllegalStateException(
                        "player actor completed an unaccepted success receipt");
            }
            if (done) {
                throw new IllegalStateException(
                        "player actor success waiter completed twice");
            }
            try {
                done = true;
            } finally {
                LockSupport.unpark(waiter);
            }
        }

        private void finish(Throwable thrown) {
            // The uncaught-exception backstop can race the normal terminal path
            // only after this caller has already received its authoritative
            // result. Never allocate diagnostics or replace that first result.
            if (done) return;
            failure = thrown;
            try {
                done = true;
            } finally {
                LockSupport.unpark(waiter);
            }
        }

        private void awaitAndRethrow() {
            boolean interrupted = false;
            while (!done) {
                LockSupport.park(this);
                if (Thread.interrupted()) interrupted = true;
            }
            Throwable thrown = failure;
            if (interrupted) Thread.currentThread().interrupt();
            if (thrown != null) PlayerTickActor.<RuntimeException>rethrow(thrown);
            // Do not hold the Job monitor while consulting the scheduler cut.
            // A peer generation W selected after local receipt acceptance is
            // still the authoritative result at the original vanilla callsite.
            if (schedulerGeneration) RegionScheduler.throwWorkerFailureIfAny();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable thrown) throws T {
        throw (T) thrown;
    }
}
