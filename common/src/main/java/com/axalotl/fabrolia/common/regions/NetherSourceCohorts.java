package com.axalotl.fabrolia.common.regions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Source-owned staging and ordered, retryable transport; acquiring a cohort never waits. */
public final class NetherSourceCohorts<T> {
    private final Object monitor = new Object();
    private final BiConsumer<T, Throwable> cancellation;
    private final ArrayDeque<Prepared<T>> queue = new ArrayDeque<>();
    private List<T> stage = new ArrayList<>();
    private final Prepared<T> empty = new Prepared<>(this, List.of());

    public NetherSourceCohorts(BiConsumer<T, Throwable> cancellation) {
        this.cancellation = Objects.requireNonNull(cancellation);
    }

    public void stage(T item) {
        Objects.requireNonNull(item);
        synchronized (monitor) {
            stage.add(item);
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
            } catch (Throwable thrown) {
                failure = thrown;
            }
        }
        if (failure != null) NetherSourceCohorts.<RuntimeException>rethrow(prepared.cancel(failure));
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

    /** A finite FIFO snapshot bound, including any cancelled receipts behind the current head. */
    public int pendingCohorts() {
        synchronized (monitor) {
            headAfterCancelled();
            return queue.size();
        }
    }

    /** Reads accepted head items without taking ownership, including an already-acquired head. */
    public List<T> acceptedHeadItems() {
        synchronized (monitor) {
            Prepared<T> head = headAfterCancelled();
            return head != null && head.state == State.ACCEPTED ? head.items : List.of();
        }
    }

    public Cohort<T> tryAcquireHead() {
        synchronized (monitor) {
            Prepared<T> head = headAfterCancelled();
            if (head == null || head.state != State.ACCEPTED || head.acquired != null) return null;
            Cohort<T> cohort = new Cohort<>(head);
            head.acquired = cohort;
            return cohort;
        }
    }

    /** Called only while holding the monitor; cancellation callbacks already belong to the receipt. */
    private Prepared<T> headAfterCancelled() {
        Prepared<T> head;
        while ((head = queue.peekFirst()) != null && head.state == State.CANCELLED) {
            queue.removeFirst();
        }
        return head;
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
            synchronized (prepared.owner.monitor) {
                if (released) return;
                prepared.owner.queue.removeFirst();
                prepared.state = State.COMPLETE;
                prepared.acquired = null;
                released = true;
            }
        }

        /** Gives back the attempt, not its FIFO position or accepted items. */
        @Override
        public void close() {
            synchronized (prepared.owner.monitor) {
                if (released) return;
                prepared.acquired = null;
                released = true;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
