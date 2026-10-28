package com.axalotl.fabrolia.common.regions;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/**
 * Failure-preserving fan-in for helper work executed under one Region claim.
 *
 * <p>A helper failure must not let the coordinator release that claim while sibling
 * helpers are still running. Every registered task therefore owns one obligation until
 * its body returns, or until the submitter proves that it never started. The first
 * failure closes the start gate atomically: already-running bodies are awaited, while
 * queued bodies are cancelled without being entered. The exact first failure is then
 * rethrown; failures from already-running bodies are retained only as bounded suppressed
 * diagnostics.
 */
public final class RegionTaskFanIn {
    private static final int NEW = 0;
    private static final int RUNNING = 1;
    private static final int TERMINAL = 2;
    private static final int CANCELLED = 3;
    private static final int MAX_SUPPRESSED = 8;

    private final AtomicInteger pending = new AtomicInteger();
    private final ArrayList<RegisteredTask> registered = new ArrayList<>();
    private Throwable firstFailure;
    private int suppressedFailures;

    /**
     * Queue wrappers use this hook to close their own publication obligation and the
     * nested fan-in obligation without invoking a helper body after fail-stop closed.
     */
    public interface CancellableQueueEntry {
        void cancelQueuedBeforeStart();

        /**
         * Failure-aware shutdown cut. Implementations which own a future/fan-in may
         * terminalize it with the generation's exact primary; simple resource-only
         * entries retain the no-arg cleanup contract.
         */
        default void cancelQueuedBeforeStartWithFailure(Throwable primary) {
            cancelQueuedBeforeStart();
        }
    }

    /** Register before publication so a fast helper cannot escape the fan-in. */
    public RegisteredTask register(Runnable body) {
        RegisteredTask task = new RegisteredTask(Objects.requireNonNull(body, "body"));
        synchronized (this) {
            registered.add(task);
            pending.incrementAndGet();
            if (firstFailure != null) task.cancelNewLocked();
        }
        return task;
    }

    /**
     * Register and publish one helper as a single lexical obligation. Every failure after
     * registration, including rejection-message allocation, terminalizes the task unless
     * its body already won RUNNING. A caller therefore never has to retain a half-published
     * task across an exceptional expression.
     */
    public boolean submit(Runnable body, Predicate<Runnable> publisher,
                          String rejectionMessage) {
        Objects.requireNonNull(publisher, "publisher");
        RegisteredTask task = register(body);
        if (task.isCancelled()) return false;
        boolean accepted = false;
        Throwable failure = null;
        try {
            accepted = publisher.test(task);
            if (!accepted && !hasFailure()) {
                failure = new IllegalStateException(rejectionMessage);
            }
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            if (!accepted) {
                // A publisher may throw after making the wrapper visible. Close
                // the generation gate before any cancellation/permit cleanup.
                if (failure != null) recordFailure(failure);
                task.cancelBeforeStart(null);
            }
        }
        // A sibling may have failed while the publisher was accepting this wrapper.
        // Its RegisteredTask is already CANCELLED by the synchronized start gate; tell
        // the coordinator to stop publishing the rest of the batch as well.
        return accepted && !task.isCancelled();
    }

    /**
     * Drain until empty. Before failure, helpers may start normally. Once the first
     * failure closes the start gate, remaining queue entries are cancelled through
     * {@link CancellableQueueEntry}; no later helper body is entered. Registered entries
     * account for their own completion or cancellation.
     */
    public void drain(Queue<Runnable> queue) {
        Runnable helper;
        while ((helper = queue.poll()) != null) {
            if (hasFailure()) {
                cancelQueued(helper);
                continue;
            }
            try {
                helper.run();
            } catch (Throwable failure) {
                recordFailure(failure);
            }
        }
    }

    /** Drain and then wait even if queue traversal itself fails. */
    public void drainAndAwait(Queue<Runnable> queue) {
        try {
            drain(queue);
        } catch (Throwable failure) {
            recordFailure(failure);
        }
        awaitAndRethrow();
    }

    /** Retain a coordinator/publication failure without opening pending obligations. */
    public void fail(Throwable failure) {
        recordFailure(failure);
    }

    /** Wait without a force-open timeout, then rethrow the exact first failure. */
    public void awaitAndRethrow() {
        boolean interrupted = false;
        long spinUntil = System.nanoTime() + 30_000L;
        while (pending.get() != 0) {
            if (Thread.interrupted()) interrupted = true;
            if (System.nanoTime() < spinUntil) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(25_000L);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Throwable failure;
        synchronized (this) {
            failure = firstFailure;
        }
        if (failure != null) FailStops.<RuntimeException>rethrow(failure);
    }

    private synchronized boolean hasFailure() {
        return firstFailure != null;
    }

    private void cancelQueued(Runnable helper) {
        if (!(helper instanceof CancellableQueueEntry cancellable)) {
            // An untracked Runnable owns no fan-in obligation. After fail-stop it is
            // successor work, so removing it from the queue is the complete action.
            return;
        }
        try {
            cancellable.cancelQueuedBeforeStart();
        } catch (Throwable cleanup) {
            recordFailure(cleanup);
        }
    }

    private void recordFailure(Throwable failure) {
        if (failure == null) return;
        Throwable selected = RegionScheduler.isActive()
                ? RegionScheduler.publishFatalAndSelectWinner(failure)
                : failure;
        synchronized (this) {
            recordSelectedFailureLocked(selected);
        }
    }

    /** Caller holds this monitor; W was selected before acquiring it. */
    private void recordSelectedFailureLocked(Throwable failure) {
        if (firstFailure == null) {
            firstFailure = failure;
            // run() takes this same monitor before NEW -> RUNNING. Once the primary
            // is visible, every task still NEW is therefore provably not started and
            // may be cancelled immediately; pending then counts running bodies only.
            // Index iteration is allocation-free on the primary failure edge.
            for (int i = 0, size = registered.size(); i < size; i++) {
                registered.get(i).cancelNewLocked();
            }
        } else if (firstFailure != failure && suppressedFailures < MAX_SUPPRESSED) {
            try {
                firstFailure.addSuppressed(failure);
                suppressedFailures++;
            } catch (Throwable ignored) {
                // Diagnostics must never replace the exact primary failure or open the fan-in.
            }
        }
    }

    public final class RegisteredTask implements Runnable, CancellableQueueEntry {
        private final Runnable body;
        private final AtomicInteger state = new AtomicInteger(NEW);

        private RegisteredTask(Runnable body) {
            this.body = body;
        }

        @Override
        public void run() {
            boolean started = false;
            Throwable duplicateFailure = null;
            synchronized (RegionTaskFanIn.this) {
                int current = state.get();
                if (current == NEW) {
                    if (firstFailure == null) {
                        started = state.compareAndSet(NEW, RUNNING);
                    } else {
                        cancelNewLocked();
                    }
                } else if (current != CANCELLED) {
                    // A duplicate real execution is an invariant failure retained by
                    // the same coordinator and closes the remaining start gate.
                    duplicateFailure = new IllegalStateException(
                            "region helper executed more than once");
                }
            }
            if (duplicateFailure != null) recordFailure(duplicateFailure);
            if (!started) return;
            Throwable failure = null;
            try {
                // NEW -> RUNNING is protected by the fan-in monitor, not by
                // the scheduler lifecycle lock.  Atomically admit the actual
                // helper body against global W as well; this is required for
                // the coordinator's direct run and help-queue self-drains,
                // even when an ordinary worker queue adds an outer wrapper.
                RegionScheduler.runSuccessBodyIfHealthy(body);
            } catch (Throwable thrown) {
                recordFailure(thrown);
                failure = thrown;
            }
            if (failure == null) {
                try {
                    // A normal helper terminal is observable through pending==0. Make
                    // that success edge share the scheduler's exact success-vs-W cut.
                    RegionScheduler.commitSuccessIfHealthy(() -> {
                        state.set(TERMINAL);
                        pending.decrementAndGet();
                    });
                    return;
                } catch (Throwable selectedFailure) {
                    recordFailure(selectedFailure);
                    failure = selectedFailure;
                }
            }
            // W was selected above. Retiring the already-entered obligation is
            // mandatory failure cleanup, never a normal helper completion.
            if (failure != null) {
                state.set(TERMINAL);
                pending.decrementAndGet();
            }
        }

        /**
         * Cancel only while the body has not started. If it raced into RUNNING, its own
         * finally remains responsible for the obligation. The supplied failure is still
         * retained in either case.
         */
        public boolean cancelBeforeStart(Throwable failure) {
            Throwable selected = failure == null ? null
                    : (RegionScheduler.isActive()
                            ? RegionScheduler.publishFatalAndSelectWinner(failure)
                            : failure);
            synchronized (RegionTaskFanIn.this) {
                boolean cancelled = cancelNewLocked();
                if (selected != null) recordSelectedFailureLocked(selected);
                return cancelled;
            }
        }

        @Override
        public void cancelQueuedBeforeStart() {
            cancelBeforeStart(null);
        }

        @Override
        public void cancelQueuedBeforeStartWithFailure(Throwable primary) {
            cancelBeforeStart(primary);
        }

        private boolean isCancelled() {
            return state.get() == CANCELLED;
        }

        /** Caller holds RegionTaskFanIn.this. */
        private boolean cancelNewLocked() {
            if (!state.compareAndSet(NEW, CANCELLED)) return false;
            pending.decrementAndGet();
            return true;
        }
    }
}
