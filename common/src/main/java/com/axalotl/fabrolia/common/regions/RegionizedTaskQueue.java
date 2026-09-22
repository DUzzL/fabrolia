package com.axalotl.fabrolia.common.regions;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-{@link Region} task FIFO. Multiple producers (any thread can submit cross-region
 * work), single consumer at a time (whichever worker holds the region's claim).
 *
 * <p>CLD-backed because: (1) producers are uncontended common-case (one player's
 * actions go to one region) so a lock-free deque outperforms a synchronized
 * ArrayDeque; (2) the size counter is a cheap volatile that lets the worker
 * dispatcher skip empty regions without polling head.
 */
public final class RegionizedTaskQueue {
    private final ConcurrentLinkedDeque<Runnable> tasks = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();

    public void submit(Runnable r) {
        tasks.add(r);
        size.incrementAndGet();
    }

    public Runnable poll() {
        Runnable r = tasks.poll();
        if (r != null) size.decrementAndGet();
        return r;
    }

    /**
     * Drain up to {@code maxTasks} from the head, running each on the caller thread.
     * The first task failure propagates unchanged.  Later tasks remain queued: a
     * failed vanilla mutation is a failed server tick, not permission to skip that
     * mutation and continue the region against partially-applied state.
     *
     * @return number of tasks actually executed
     */
    public int drain(int maxTasks) {
        int n = 0;
        for (; n < maxTasks; n++) {
            Runnable r = poll();
            if (r == null) return n;
            r.run();
        }
        return n;
    }

    public boolean isEmpty() { return size.get() <= 0; }
    public int size() { return Math.max(0, size.get()); }
}
