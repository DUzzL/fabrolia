package com.axalotl.fabrolia.common.parallelised.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Queue;
import java.util.Set;

/**
 * task #26 — thread-confined staging for hand-off work that must not become
 * visible to another thread until the tick frame that produced it has finished.
 *
 * <p>The portal case: a cross-dimension transfer is submitted from deep inside
 * the source entity's tick, on an island worker. Main does not await island
 * units, so the moment the transfer landed in a shared queue main could execute
 * the whole teleport concurrently with the remainder of that worker tick —
 * reading a pose the worker was still writing. Staging the task here and
 * releasing it only when the tick returns removes the concurrency itself: the
 * consumer cannot observe the work until every write that tick could make has
 * happened, whether or not we enumerated the writers.
 *
 * <p>Deliberately free of Minecraft types so the contract is unit-testable — see
 * {@code TransferVisibilityBarrierTest}, which asserts the barrier property
 * under real concurrent producer/consumer threads.
 *
 * <p>Not thread-safe per instance in the sense of sharing a frame across
 * threads: each thread stages into its own list and publishes its own list. The
 * destination queue must be thread-safe.
 */
public final class TransferVisibilityBarrier {

    private final Queue<Runnable> published;
    private final ThreadLocal<ArrayList<Runnable>> staged = new ThreadLocal<>();
    /** audit11: a frame can now span a whole region-claim drain (many entities),
     *  so a single Object slot is wrong — the second departure of the frame
     *  overwrote the first, un-freezing an entity vanilla had already removed.
     *  Identity set, thread-confined, cleared in {@link #publishStaged}. */
    private final ThreadLocal<Set<Object>> departed = new ThreadLocal<>();

    public TransferVisibilityBarrier(Queue<Runnable> published) {
        this.published = published;
    }

    /**
     * @param owner     the object whose tick frame produced this work; may be null
     * @param task      the work to hand to the consumer
     * @param insideTick whether a tick frame is currently open on this thread. When
     *                   false there is no in-progress mutation to wait for and the
     *                   task is published immediately.
     */
    public void submit(Object owner, Runnable task, boolean insideTick) {
        if (!insideTick) {
            published.offer(task);
            return;
        }
        if (owner != null) {
            Set<Object> dep = departed.get();
            if (dep == null) {
                dep = Collections.newSetFromMap(new IdentityHashMap<>(4));
                departed.set(dep);
            }
            dep.add(owner);
        }
        ArrayList<Runnable> list = staged.get();
        if (list == null) {
            list = new ArrayList<>(2);
            staged.set(list);
        }
        list.add(task);
    }

    /**
     * True iff {@code owner} submitted work earlier in the tick frame currently
     * running on THIS thread. Written and read by the producing thread alone, so
     * unlike a shared flag the consumer cannot clear it underneath a tick that is
     * still executing.
     */
    public boolean hasDeparted(Object owner) {
        if (owner == null) return false;
        Set<Object> dep = departed.get();
        return dep != null && dep.contains(owner);
    }

    /** Release this thread's staged work to the consumer — as ONE queue element.
     *  Call at the outermost frame boundary, from a finally, so a throwing tick
     *  still publishes.
     *
     *  <p>audit11: offering each staged task individually let a concurrent
     *  {@code drainPending()} poll loop bisect the frame — the drainer could
     *  execute a prefix of a portal activation this wall tick and the rest next
     *  wall tick, publishing the two halves at different destination epochs.
     *  A single composite offer is atomic against poll: the consumer sees the
     *  whole frame or none of it. The composite runs the staged transfers in
     *  submission order; a throwing transfer never swallows the rest of its
     *  frame (each is contained, the first throwable is rethrown at the end so
     *  the drain's error handling still observes it). */
    public void publishStaged() {
        Set<Object> dep = departed.get();
        if (dep != null) dep.clear();
        ArrayList<Runnable> list = staged.get();
        if (list == null || list.isEmpty()) return;
        if (list.size() == 1) {
            published.offer(list.get(0)); // a single task is already atomic
        } else {
            final Runnable[] frame = list.toArray(new Runnable[0]);
            published.offer(() -> {
                Throwable first = null;
                for (Runnable task : frame) {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        if (first == null) first = t;
                        else first.addSuppressed(t);
                    }
                }
                if (first != null) {
                    if (first instanceof RuntimeException re) throw re;
                    if (first instanceof Error err) throw err;
                    throw new RuntimeException("staged transfer failed", first);
                }
            });
        }
        list.clear();
    }

    /** Staged-but-unpublished count on this thread (diagnostics/tests). */
    public int stagedOnThisThread() {
        ArrayList<Runnable> list = staged.get();
        return list == null ? 0 : list.size();
    }
}
