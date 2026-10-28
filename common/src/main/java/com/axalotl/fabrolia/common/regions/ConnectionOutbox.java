package com.axalotl.fabrolia.common.regions;

import io.netty.util.concurrent.AbstractEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * One connection's ordered off-loop transport FIFO in front of its Netty event loop.
 *
 * <p>Vanilla posts one {@code EventLoop.execute} per off-loop packet; each can write the
 * loop's eventfd. Here every off-loop transport operation of the connection (send,
 * flush, Fabrolia's ordered tasks) is appended to one FIFO, and drain tasks run the
 * appended operations on the loop thread in append order. The per-connection order of
 * these operations is therefore exactly the order of the appends, whichever thread
 * made them; which drain task runs an operation never matters.
 *
 * <p>A region worker step may hold its own listener-free sends and flushes: they are
 * appended immediately (their FIFO position is fixed at the send) but the drain is
 * scheduled when the step ends, so one step costs one wakeup per connection it touched
 * instead of one per packet. This is vanilla's own shape for Main, whose tick suspends
 * flushing and releases a player's packets when the tick ends; an island step releases
 * its packets at the end of its own unit. Any other off-loop operation on that
 * connection schedules a drain immediately and so carries the held operations out with
 * it.
 *
 * <p>{@code state} only coalesces posts: once a drain is posted (or being posted) with
 * the needed wakeup, later appenders rely on it; every drain re-checks the FIFO after
 * clearing the state, so nothing appended is ever left without a drain.
 */
public final class ConnectionOutbox {

    /** Netty's own task logger: an operation that throws behaves as a failed loop task. */
    private static final InternalLogger TASK_LOGGER =
            InternalLoggerFactory.getInstance(AbstractEventExecutor.class);

    private static final int IDLE = 0;
    /** A drain is posted (or being posted) without a wakeup: only {@code flush=false}
     *  sends are waiting, like VMP's lazy execute of such a send. */
    private static final int LAZY = 1;
    /** A drain is posted (or being posted) with a wakeup. */
    private static final int EAGER = 2;

    /** Queue segment size: one segment covers a busy step's packets to one player. */
    private static final int OPERATION_CHUNK = 256;
    /** The largest capacity Netty's MPSC queue accepts (its own task queues use it). */
    private static final int OPERATION_CAPACITY = 1 << 30;

    private static final VarHandle STATE;

    static {
        try {
            STATE = MethodHandles.lookup().findVarHandle(ConnectionOutbox.class, "state", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Netty's own MPSC task-queue type: any thread appends, only the loop thread polls. */
    private final Queue<Runnable> operations =
            PlatformDependent.newMpscQueue(OPERATION_CHUNK, OPERATION_CAPACITY);
    private final Runnable drain = this::drain;
    private volatile int state;
    /** Step token of the last worker step that registered this outbox; see {@link WorkerScope}. */
    private Object heldBy;

    /**
     * Appends one off-loop transport operation. {@code wake} is the operation's need to
     * wake the loop (a flush, or a send with {@code flush=true}); without it the drain is
     * queued lazily, like VMP's lazy execute of a non-flushing send. A {@code holdable}
     * operation issued inside a region worker step is published when the step ends.
     * A refused post (the loop is shutting down) propagates; the refused operation stays
     * appended and may or may not still run.
     */
    public void offer(Executor loop, Runnable operation, boolean wake, boolean holdable) {
        append(operation);
        WorkerScope scope = holdable ? WorkerScope.current() : null;
        if (scope != null) {
            scope.hold(this, loop, wake);
        } else {
            schedule(loop, wake);
        }
    }

    /** Ordered task (Fabrolia transport publication): visible to the loop now. */
    public void execute(Executor loop, Runnable task) {
        append(task);
        schedule(loop, true);
    }

    private void append(Runnable operation) {
        // Like Netty's own task queue: a full queue rejects, it never drops silently.
        if (!operations.offer(operation)) {
            throw new java.util.concurrent.RejectedExecutionException(
                    "connection outbox is full (" + OPERATION_CAPACITY + " operations)");
        }
    }

    /**
     * Called right before a Channel operation that posts itself to the loop outside this
     * FIFO (close, protocol switch, Connection.tick's flush): a drain is queued now, so
     * every operation appended before this call, held ones included, runs before that
     * operation. It queues its own drain rather than trusting {@code state}: a concurrent
     * scheduler may have won the state but not have posted yet. The drain is queued
     * lazily; the direct operation behind it wakes the loop (VMP may redirect the tick
     * flush away, and then nothing here needs a wakeup).
     *
     * <p>An operation appended after this call may still be run by a drain queued ahead of
     * the direct operation. That never inverts a causal pair at the three callers: a
     * protocol switch is synchronous (its caller waits for the loop); after a close the
     * Connection reports itself disconnected, so a later send, direct or from an ordered
     * task, goes to its pending queue instead of the channel; and a flush that writes
     * more than vanilla's flush did only puts bytes on the wire earlier.
     */
    public void publishBeforeDirect(Executor loop) {
        if (operations.isEmpty()) return;
        if (loop instanceof AbstractEventExecutor lazyLoop) {
            lazyLoop.lazyExecute(drain);
        } else {
            loop.execute(drain);
        }
    }

    private void schedule(Executor loop, boolean wake) {
        while (true) {
            int observed = state;
            if (observed == EAGER || (observed == LAZY && !wake)) return;
            int next = wake ? EAGER : LAZY;
            if (!STATE.compareAndSet(this, observed, next)) continue;
            boolean posted = false;
            try {
                // LAZY -> EAGER posts a second, waking drain: the lazy one may not be
                // queued yet (its scheduler can still be between CAS and post).
                if (wake || !(loop instanceof AbstractEventExecutor lazyLoop)) {
                    loop.execute(drain);
                } else {
                    lazyLoop.lazyExecute(drain);
                }
                posted = true;
            } finally {
                // A refused post must not leave appenders waiting on a drain that never
                // comes (IDLE only costs a redundant post); the refusal itself propagates.
                if (!posted) state = IDLE;
            }
            return;
        }
    }

    private void drain() {
        boolean retired = false;
        try {
            while (true) {
                Runnable operation;
                while ((operation = operations.poll()) != null) {
                    try {
                        operation.run();
                    } catch (Throwable failure) {
                        // Exactly what the loop does for a failing task (safeExecute): the
                        // following operations of the connection still run in order.
                        TASK_LOGGER.warn("A task raised an exception. Task: {}", operation, failure);
                    }
                }
                // Dekker pair with offer(): append, then read state / write IDLE, then
                // re-read the FIFO. Both sides must stay sequentially consistent
                // (volatile), never release/opaque.
                state = IDLE;
                retired = true;
                // An operation appended after the last poll whose appender still saw this
                // drain outstanding: keep draining unless a new drain was already posted.
                if (operations.isEmpty() || !STATE.compareAndSet(this, IDLE, EAGER)) return;
                retired = false;
            }
        } finally {
            // Abnormal exit (the failure log itself threw): the next appender posts again.
            if (!retired) state = IDLE;
        }
    }

    /**
     * The holding window of one region worker thread: opened around each worker step by
     * {@link RegionWorkerPool}, thread-confined.
     */
    public static final class WorkerScope {
        private final ArrayList<ConnectionOutbox> held = new ArrayList<>();
        private final ArrayList<Executor> loops = new ArrayList<>();
        /** Unique per step that held something; written into each outbox it registered. */
        private Object token = new Object();
        private boolean wake;
        private boolean open;

        static WorkerScope current() {
            return Thread.currentThread() instanceof RegionWorkerThread worker
                    && worker.outbound.open ? worker.outbound : null;
        }

        void open() {
            open = true;
        }

        /** Publishes everything this step held; a failure is rethrown after all were tried. */
        void close() {
            open = false;
            Throwable failure = publishHeld();
            if (failure != null) FailStops.<RuntimeException>rethrow(failure);
        }

        /** Step failed: still publish what it held (already-sent packets), after {@code primary}. */
        void closeAfter(Throwable primary) {
            open = false;
            Throwable failure = publishHeld();
            if (failure != null && failure != primary) primary.addSuppressed(failure);
        }

        private void hold(ConnectionOutbox outbox, Executor loop, boolean wake) {
            this.wake |= wake;
            // Racy plain field: another worker's token only causes a duplicate entry.
            // This step's token is in the field only if this step added the outbox.
            if (outbox.heldBy == token) return;
            outbox.heldBy = token;
            held.add(outbox);
            loops.add(loop);
        }

        private Throwable publishHeld() {
            int size = held.size();
            if (size == 0) return null;
            Throwable failure = null;
            for (int i = 0; i < size; i++) {
                ConnectionOutbox outbox = held.get(i);
                // Empty: another thread's operation already carried them out.
                if (outbox.operations.isEmpty()) continue;
                try {
                    outbox.schedule(loops.get(i), wake);
                } catch (Throwable thrown) {
                    if (failure == null) failure = thrown;
                    else failure.addSuppressed(thrown);
                }
            }
            held.clear();
            loops.clear();
            wake = false;
            token = new Object();
            return failure;
        }
    }
}
