package com.axalotl.fabrolia.common.regions;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-{@code LevelChunk} publication fence between mutable chunk writes and an
 * immutable full-chunk packet enqueue.
 *
 * <p>The generation check in {@link ChunkDelivery} is necessary but is not a
 * commit by itself: a writer can otherwise start changing the chunk after
 * Main's last check and enqueue its authoritative block correction before Main
 * enqueues the older full snapshot. The client would then apply {@code AIR}
 * first and the stale full chunk second. This gate supplies the missing
 * writer-start linearization point:
 *
 * <ul>
 *   <li>a chunk write briefly owns the gate, bumps the generation, and releases
 *       it <em>before</em> invoking any {@code LevelChunk} body or callback;</li>
 *   <li>Main never waits for a writer.  It takes the fair gate only if
 *       immediately available, rechecks the expected generation, and holds it
 *       only through a non-blocking connection event-loop task offer;</li>
 *   <li>a writer which arrives after that commit waits only until the old send
 *       task has entered the same event-loop FIFO used by later connection
 *       sends, then bumps, releases, mutates, and publishes its newer update.
 *       A writer which arrived first either makes Main's try fail or changes
 *       the generation before Main can commit.</li>
 * </ul>
 *
 * <p>No arbitrary world method runs under this gate. Nested/cross-chunk writes
 * therefore never retain fence A while acquiring fence B, and block callbacks
 * cannot create a fence-to-world reverse edge. This is deliberately not an
 * island claim and Main never blocks on it. Fair zero-time admission also
 * prevents repeated player sends from barging ahead of a queued writer.
 */
public final class ChunkWriteEnqueueFence {

    private final ReentrantLock gate = new ReentrantLock(true);
    private volatile long generation;

    /** Gate-owned invariant state; only Main retains the lock across two calls. */
    private boolean enqueueReservation;

    /** Current stable write generation. */
    public long generation() {
        return generation;
    }

    /**
     * Linearizes the start of one write. Writers may wait only behind another
     * short generation bump or Main's bounded event-loop task publication; the
     * gate is always released before the caller invokes its mutation body.
     * A throwing body deliberately leaves this generation invalidated.
     */
    public void markWriteStart() {
        gate.lock();
        try {
            if (enqueueReservation) {
                throw new IllegalStateException(
                        "chunk write re-entered a full-chunk enqueue reservation");
            }
            if (generation == Long.MAX_VALUE) {
                throw new IllegalStateException("chunk write generation exhausted");
            }
            generation++;
        } finally {
            gate.unlock();
        }
    }

    /** One generation bump for callers which do not wrap a larger mutation. */
    public void bump() {
        markWriteStart();
    }

    /**
     * Non-blocking Main-side commit reservation.  The timed-zero form honors
     * this fair lock's queued-writer order, unlike {@link ReentrantLock#tryLock()}.
     */
    public boolean tryReserveEnqueue(long expectedGeneration) {
        if (generation != expectedGeneration) {
            return false;
        }
        final boolean acquired;
        try {
            acquired = gate.tryLock(0L, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!acquired) {
            return false;
        }
        if (enqueueReservation || generation != expectedGeneration) {
            gate.unlock();
            return false;
        }
        enqueueReservation = true;
        return true;
    }

    /** Releases the exact successful enqueue reservation. */
    public void releaseEnqueue(long expectedGeneration) {
        if (!gate.isHeldByCurrentThread() || !enqueueReservation
                || generation != expectedGeneration) {
            throw new IllegalStateException(
                    "full-chunk enqueue fence released without exact generation ownership");
        }
        enqueueReservation = false;
        gate.unlock();
    }
}
