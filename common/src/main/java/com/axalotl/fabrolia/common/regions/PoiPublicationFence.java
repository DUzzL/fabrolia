package com.axalotl.fabrolia.common.regions;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Completion, not queue acceptance, for the POI callbacks of a level.
 * Zero certifies completion of earlier receipts, not exclusion of future worldgen
 * publications. Readers still need ownership of the block/chunk state they use.
 * This fence never drains or changes the order of the executor's queue.
 */
public final class PoiPublicationFence {
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final UnaryOperator<Throwable> selectFailure;

    public PoiPublicationFence(UnaryOperator<Throwable> selectFailure) {
        this.selectFailure = Objects.requireNonNull(selectFailure);
    }

    public boolean isPublished() {
        boolean published = pending.get() == 0;
        throwIfFailed();
        return published;
    }

    public void submit(Runnable body, Consumer<Runnable> executor) {
        Objects.requireNonNull(body);
        Objects.requireNonNull(executor);
        throwIfFailed();
        Publication publication = new Publication(body);
        pending.incrementAndGet();
        try {
            executor.accept(publication);
        } catch (Throwable thrown) {
            Throwable winner = fail(thrown);
            // An executor may publish and then throw, or execute inline and
            // throw from that body. Only an unentered receipt can be cancelled.
            publication.cancelBeforeStart();
            FailStops.<RuntimeException>rethrow(winner);
        }
    }

    private Throwable fail(Throwable thrown) {
        Throwable existing = failure.get();
        if (existing != null) return existing;
        Throwable selected;
        try {
            selected = Objects.requireNonNull(selectFailure.apply(thrown));
        } catch (Throwable selectionFailure) {
            selected = thrown;
            if (selectionFailure != thrown) {
                try {
                    thrown.addSuppressed(selectionFailure);
                } catch (Throwable ignored) {
                    // Diagnostics cannot strand a publication or erase failure.
                }
            }
        }
        failure.compareAndSet(null, selected);
        return failure.get();
    }

    private void throwIfFailed() {
        Throwable thrown = failure.get();
        if (thrown != null) FailStops.<RuntimeException>rethrow(thrown);
    }

    private final class Publication implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final Runnable body;

        private Publication(Runnable body) {
            this.body = body;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return;
            try {
                throwIfFailed();
                body.run();
            } catch (Throwable thrown) {
                FailStops.<RuntimeException>rethrow(fail(thrown));
            } finally {
                // Failure is selected before a reader can observe readiness.
                state.set(TERMINAL);
                pending.decrementAndGet();
            }
        }

        private void cancelBeforeStart() {
            if (state.compareAndSet(QUEUED, TERMINAL)) pending.decrementAndGet();
        }
    }
}
