package com.axalotl.fabrolia.common.regions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Small synchronized backlog with a hard retention bound.
 *
 * <p>An overflow drops the old snapshot and keeps the newest value.  The
 * caller can use the {@code true} return from {@link #offer(Object)} to mark
 * its derived index invalid and rebuild it from the authoritative source.
 * Keeping the newest value closes the registration-vs-rebuild seam without
 * allowing a cold derived index to retain an unbounded number of values.
 */
final class BoundedPendingQueue<T> {
    private final int limit;
    private final ArrayDeque<T> queue;

    BoundedPendingQueue(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
        this.queue = new ArrayDeque<>(Math.min(limit, 64));
    }

    /**
     * @return true when old backlog entries had to be discarded
     */
    synchronized boolean offer(T value) {
        Objects.requireNonNull(value, "value");
        boolean overflow = queue.size() >= limit;
        if (overflow) queue.clear();
        queue.addLast(value);
        return overflow;
    }

    synchronized List<T> drain() {
        if (queue.isEmpty()) return List.of();
        ArrayList<T> snapshot = new ArrayList<>(queue);
        queue.clear();
        return snapshot;
    }

    synchronized void clear() {
        queue.clear();
    }

    synchronized int size() {
        return queue.size();
    }
}
