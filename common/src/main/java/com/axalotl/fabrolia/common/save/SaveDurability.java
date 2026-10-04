package com.axalotl.fabrolia.common.save;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * When a save handed to {@link AsyncSaveWriter} must be on disk.
 *
 * <p>Default (no scope): before the saving call returns, exactly like vanilla's
 * synchronous write. Inside {@link #runDeferred} (autosave/plain save-all player
 * pass, reload, the world save pass) the call returns after the snapshot; the
 * {@code save-all flush} and shutdown barriers ({@link AsyncSaveWriter#awaitAll})
 * restore vanilla durability there. A {@link Batch} (one {@code PlayerList.save})
 * moves the wait to its end so it happens after the owner claim is released.
 */
public final class SaveDurability {

    private static final ThreadLocal<int[]> DEFERRED = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<Batch> BATCH = new ThreadLocal<>();

    private SaveDurability() {}

    public static void runDeferred(Runnable body) {
        int[] depth = DEFERRED.get();
        depth[0]++;
        try {
            body.run();
        } finally {
            depth[0]--;
        }
    }

    public static boolean callDeferred(BooleanSupplier body) {
        int[] depth = DEFERRED.get();
        depth[0]++;
        try {
            return body.getAsBoolean();
        } finally {
            depth[0]--;
        }
    }

    public static boolean deferred() {
        return DEFERRED.get()[0] > 0;
    }

    /** Called by every save hook right after it queued its write. */
    public static void afterSubmit(AsyncSaveWriter writer, AsyncSaveWriter.Ticket ticket) {
        Batch batch = BATCH.get();
        if (batch != null) {
            batch.add(writer, ticket);
        } else if (!deferred()) {
            writer.await(ticket);
        }
    }

    /**
     * Opens the batch of one {@code PlayerList.save}. {@code leaving}: the player
     * is being removed, so the writer drops its unchanged-skip memory for these
     * files once they are written.
     */
    public static Batch openBatch(boolean leaving) {
        Batch batch = new Batch(BATCH.get(), leaving, deferred());
        BATCH.set(batch);
        return batch;
    }

    public static final class Batch implements AutoCloseable {
        private final Batch previous;
        private final boolean leaving;
        private final boolean deferred;
        private final List<AsyncSaveWriter> writers = new ArrayList<>(3);
        private final List<AsyncSaveWriter.Ticket> tickets = new ArrayList<>(3);
        private boolean closed;

        private Batch(Batch previous, boolean leaving, boolean deferred) {
            this.previous = previous;
            this.leaving = leaving;
            this.deferred = deferred;
        }

        private void add(AsyncSaveWriter writer, AsyncSaveWriter.Ticket ticket) {
            writers.add(writer);
            tickets.add(ticket);
            if (leaving) writer.forgetAfter(ticket);
        }

        /** Detaches the batch from the thread; does not wait. */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (BATCH.get() == this) {
                if (previous == null) BATCH.remove();
                else BATCH.set(previous);
            }
        }

        /** Vanilla durability for a save outside a deferred scope. */
        public void awaitDurable() {
            if (deferred) return;
            RuntimeException first = null;
            for (int i = 0; i < tickets.size(); i++) {
                try {
                    writers.get(i).await(tickets.get(i));
                } catch (RuntimeException failure) {
                    if (first == null) first = failure;
                    else if (failure != first) first.addSuppressed(failure);
                }
            }
            if (first != null) throw first;
        }
    }
}
