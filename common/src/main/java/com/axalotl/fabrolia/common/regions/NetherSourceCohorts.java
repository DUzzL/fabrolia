package com.axalotl.fabrolia.common.regions;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Source-owned staging and ordered, retryable transport; acquiring a cohort never waits. */
public final class NetherSourceCohorts<T> {
    /**
     * Observes the transported queue becoming non-empty / empty. Invoked while
     * holding this source's monitor, so one source's notifications are totally
     * ordered and always match {@code queued}; implementations must not block.
     */
    public interface Activity {
        Activity NONE = nonEmpty -> { };

        void changed(boolean nonEmpty);
    }

    private static final VarHandle ATTEMPT;

    static {
        try {
            ATTEMPT = MethodHandles.lookup().findVarHandle(NetherSourceCohorts.class, "attempt", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Object monitor = new Object();
    private final BiConsumer<T, Throwable> cancellation;
    private final Activity activity;
    private final ArrayDeque<Prepared<T>> queue = new ArrayDeque<>();
    private List<T> stage = new ArrayList<>();
    private final Prepared<T> empty = new Prepared<>(this, List.of());
    /**
     * {@code queue.size()}, republished under the monitor after every change.
     * Every idle region worker may poll a source between units; with the
     * queue almost always empty, monitor traffic there was pure contention.
     * A read racing a concurrent prepare() may miss it once: the next pass sees it.
     */
    private volatile int queued;
    /**
     * Single-attempt gate (0 free, 1 taken). Only the head can ever be handed
     * out and only to one attempt at a time; deciding that BEFORE the monitor
     * lets every other poller back out with one failed CAS instead of
     * convoying on the monitor (2026-10-03 production: 35 s blocked). Taken
     * only by {@link #tryAcquireHead}, released exactly once by the winning
     * call's own refusal or by its cohort's first {@code complete/close}.
     */
    private volatile int attempt;

    public NetherSourceCohorts(BiConsumer<T, Throwable> cancellation) {
        this(cancellation, Activity.NONE);
    }

    public NetherSourceCohorts(BiConsumer<T, Throwable> cancellation, Activity activity) {
        this.cancellation = Objects.requireNonNull(cancellation);
        this.activity = Objects.requireNonNull(activity);
    }

    public void stage(T item) {
        Objects.requireNonNull(item);
        synchronized (monitor) {
            stage.add(item);
        }
    }

    /** True while at least one detached cohort (offering, accepted or cancelled) is queued. */
    public boolean hasQueued() {
        return queued != 0;
    }

    /** Nothing staged and nothing queued (no offered, accepted or acquired cohort). */
    public boolean isIdle() {
        synchronized (monitor) {
            return stage.isEmpty() && queue.isEmpty();
        }
    }

    /** Detaches the stage after the source phase has closed its writers, without accepting it. */
    public Prepared<T> prepare() {
        Prepared<T> prepared;
        Throwable failure = null;
        synchronized (monitor) {
            if (stage.isEmpty()) return empty;
            List<T> nextStage = new ArrayList<>();
            prepared = new Prepared<>(this, Collections.unmodifiableList(stage));
            stage = nextStage;
            try {
                queue.addLast(prepared);
                publishQueuedSize();
            } catch (Throwable thrown) {
                failure = thrown;
            }
        }
        if (failure != null) FailStops.<RuntimeException>rethrow(prepared.cancel(failure));
        return prepared;
    }

    /** Cancels only the still-open stage; detached stages belong to their preparation receipts. */
    public void cancelOpen(Throwable failure) {
        Objects.requireNonNull(failure);
        List<T> cancelled;
        synchronized (monitor) {
            if (stage.isEmpty()) return;
            cancelled = stage;
            stage = new ArrayList<>();
        }
        cancelItems(cancelled, failure);
    }

    public Cohort<T> tryAcquireHead() {
        // Read before CAS: losers back out without taking the line exclusive.
        if (queued == 0 || attempt != 0 || !ATTEMPT.compareAndSet(this, 0, 1)) return null;
        boolean handedOut = false;
        try {
            synchronized (monitor) {
                Prepared<T> head = headAfterCancelled();
                if (head == null || head.state != State.ACCEPTED) return null;
                if (head.acquired != null) {
                    throw new IllegalStateException("Nether cohort head acquired outside its attempt gate");
                }
                Cohort<T> cohort = new Cohort<>(head);
                head.acquired = cohort;
                handedOut = true;
                return cohort;
            }
        } finally {
            if (!handedOut) ATTEMPT.setRelease(this, 0);
        }
    }

    /** Called only while holding the monitor; cancellation callbacks already belong to the receipt. */
    private Prepared<T> headAfterCancelled() {
        Prepared<T> head;
        boolean removed = false;
        while ((head = queue.peekFirst()) != null && head.state == State.CANCELLED) {
            queue.removeFirst();
            removed = true;
        }
        if (removed) publishQueuedSize();
        return head;
    }

    /** Called only while holding the monitor, after every queue mutation. */
    private void publishQueuedSize() {
        int before = queued;
        int after = queue.size();
        queued = after;
        if ((before == 0) != (after == 0)) activity.changed(after != 0);
    }

    private Throwable cancelItems(List<T> items, Throwable failure) {
        for (T item : items) {
            try {
                cancellation.accept(item, failure);
            } catch (Throwable cleanup) {
                if (cleanup != failure) {
                    try {
                        failure.addSuppressed(cleanup);
                    } catch (Throwable ignored) {
                        // Diagnostics must not strand the remaining cancellations.
                    }
                }
            }
        }
        return failure;
    }

    private enum State { OFFERING, ACCEPTED, CANCELLED, COMPLETE }

    public static final class Prepared<T> {
        private final NetherSourceCohorts<T> owner;
        private final List<T> items;
        private State state = State.OFFERING;
        private Cohort<T> acquired;

        private Prepared(NetherSourceCohorts<T> owner, List<T> items) {
            this.owner = owner;
            this.items = items;
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        /** The sole success cut: after commit, source failure cannot retract accepted transport. */
        public void commit() {
            synchronized (owner.monitor) {
                if (state == State.OFFERING) state = State.ACCEPTED;
            }
        }

        /** Cancels an uncommitted offer once, including an offer whose publisher subsequently threw. */
        public Throwable cancel(Throwable failure) {
            Objects.requireNonNull(failure);
            synchronized (owner.monitor) {
                if (state != State.OFFERING) return failure;
                state = State.CANCELLED;
            }
            return owner.cancelItems(items, failure);
        }
    }

    public static final class Cohort<T> implements AutoCloseable {
        private final Prepared<T> prepared;
        private boolean released;

        private Cohort(Prepared<T> prepared) {
            this.prepared = prepared;
        }

        public List<T> items() {
            return prepared.items;
        }

        /** Consumes exactly this acquired head; a previously released attempt cannot consume a retry. */
        public void complete() {
            NetherSourceCohorts<T> owner = prepared.owner;
            synchronized (owner.monitor) {
                if (released) return;
                owner.queue.removeFirst();
                owner.publishQueuedSize();
                prepared.state = State.COMPLETE;
                prepared.acquired = null;
                released = true;
            }
            ATTEMPT.setRelease(owner, 0);
        }

        /** Gives back the attempt, not its FIFO position or accepted items. */
        @Override
        public void close() {
            NetherSourceCohorts<T> owner = prepared.owner;
            synchronized (owner.monitor) {
                if (released) return;
                prepared.acquired = null;
                released = true;
            }
            ATTEMPT.setRelease(owner, 0);
        }
    }
}
