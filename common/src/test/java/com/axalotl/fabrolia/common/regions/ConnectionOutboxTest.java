package com.axalotl.fabrolia.common.regions;

import io.netty.util.concurrent.AbstractEventExecutor;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-connection transport order through {@link ConnectionOutbox}: an operation issued
 * after another on the same connection (same thread, or ordered across threads) must
 * reach the event loop after it, whether either one was held by a region worker step,
 * sent by Main, enqueued as an ordered task, or is a Channel operation posted directly.
 */
class ConnectionOutboxTest {

    @Test
    @Timeout(30)
    void mainSendAfterHeldWorkerSendsCannotOvertakeThem() throws Exception {
        ManualLoop loop = new ManualLoop();
        ConnectionOutbox outbox = new ConnectionOutbox();
        List<String> wire = new ArrayList<>();
        try (WorkerDriver worker = new WorkerDriver()) {
            worker.call(() -> {
                worker.scope().open();
                outbox.offer(loop, () -> wire.add("w1"), true, true);
                outbox.offer(loop, () -> wire.add("w2"), true, true);
            });
            assertEquals(0, loop.posted(), "a step's listener-free sends are held");

            outbox.offer(loop, () -> wire.add("m1"), true, false);
            worker.call(() -> {
                outbox.offer(loop, () -> wire.add("w3"), true, true);
                worker.scope().close();
            });
        }
        loop.runAll();
        assertEquals(List.of("w1", "w2", "m1", "w3"), wire);
    }

    @Test
    @Timeout(30)
    void orderedTaskRunsBehindHeldSendsAndAheadOfLaterOnes() throws Exception {
        ManualLoop loop = new ManualLoop();
        ConnectionOutbox outbox = new ConnectionOutbox();
        List<String> wire = new ArrayList<>();
        try (WorkerDriver worker = new WorkerDriver()) {
            worker.call(() -> {
                worker.scope().open();
                outbox.offer(loop, () -> wire.add("w1"), true, true);
            });
            outbox.execute(loop, () -> wire.add("forget"));
            assertTrue(loop.lastWasEager(), "an ordered task is visible to the loop now");
            worker.call(() -> {
                outbox.offer(loop, () -> wire.add("w2"), true, true);
                worker.scope().close();
            });
        }
        loop.runAll();
        assertEquals(List.of("w1", "forget", "w2"), wire);
    }

    @Test
    @Timeout(30)
    void directChannelOperationQueuesHeldSendsAheadOfItself() throws Exception {
        ManualLoop loop = new ManualLoop();
        ConnectionOutbox outbox = new ConnectionOutbox();
        List<String> wire = new ArrayList<>();
        try (WorkerDriver worker = new WorkerDriver()) {
            worker.call(() -> {
                worker.scope().open();
                outbox.offer(loop, () -> wire.add("w1"), false, true);
            });
            outbox.publishBeforeDirect(loop);
            loop.execute(() -> wire.add("close"));
            worker.call(() -> worker.scope().close());
        }
        loop.runAll();
        assertEquals(List.of("w1", "close"), wire);
    }

    @Test
    @Timeout(30)
    void flushingSendBehindALazilyQueuedDrainWakesTheLoop() {
        ManualLoop loop = new ManualLoop();
        ConnectionOutbox outbox = new ConnectionOutbox();
        List<String> wire = new ArrayList<>();
        outbox.offer(loop, () -> wire.add("lazy1"), false, false);
        outbox.offer(loop, () -> wire.add("lazy2"), false, false);
        assertEquals(1, loop.posted(), "non-flushing sends share one queued drain");
        assertFalse(loop.lastWasEager(), "a non-flushing send does not wake the loop");

        outbox.offer(loop, () -> wire.add("flush"), true, false);
        assertTrue(loop.lastWasEager(),
                "a flushing send may not wait for an unrelated wakeup of the loop");
        loop.runAll();
        assertEquals(List.of("lazy1", "lazy2", "flush"), wire);
    }

    @Test
    @Timeout(30)
    void heldStepWakesTheLoopOnlyWhenItHeldAFlush() throws Exception {
        ManualLoop loop = new ManualLoop();
        ConnectionOutbox quiet = new ConnectionOutbox();
        ConnectionOutbox flushing = new ConnectionOutbox();
        List<String> wire = new ArrayList<>();
        try (WorkerDriver worker = new WorkerDriver()) {
            worker.call(() -> {
                worker.scope().open();
                quiet.offer(loop, () -> wire.add("q1"), false, true);
                quiet.offer(loop, () -> wire.add("q2"), false, true);
                worker.scope().close();
            });
            assertEquals(1, loop.posted(), "one drain per connection and step");
            assertFalse(loop.lastWasEager());

            worker.call(() -> {
                worker.scope().open();
                flushing.offer(loop, () -> wire.add("f1"), false, true);
                flushing.offer(loop, () -> wire.add("f2"), true, true);
                worker.scope().close();
            });
            assertEquals(2, loop.posted());
            assertTrue(loop.lastWasEager());
        }
        loop.runAll();
        assertEquals(List.of("q1", "q2", "f1", "f2"), wire);
    }

    @Test
    @Timeout(30)
    void everyStepPublishesWhatItHeldOnTheSameConnection() throws Exception {
        ManualLoop loop = new ManualLoop();
        ConnectionOutbox outbox = new ConnectionOutbox();
        List<String> wire = new ArrayList<>();
        try (WorkerDriver worker = new WorkerDriver()) {
            for (int step = 0; step < 3; step++) {
                String name = "s" + step;
                worker.call(() -> {
                    worker.scope().open();
                    outbox.offer(loop, () -> wire.add(name), true, true);
                    worker.scope().close();
                });
                loop.runAll();
                assertEquals(name, wire.getLast(), "step " + step + " left at its own end");
            }
        }
    }

    @Test
    @Timeout(30)
    void listenerBearingSendIsNeverHeld() throws Exception {
        ManualLoop loop = new ManualLoop();
        ConnectionOutbox outbox = new ConnectionOutbox();
        List<String> wire = new ArrayList<>();
        try (WorkerDriver worker = new WorkerDriver()) {
            worker.call(() -> {
                worker.scope().open();
                outbox.offer(loop, () -> wire.add("held"), true, true);
                outbox.offer(loop, () -> wire.add("with-listener"), true, false);
            });
            assertEquals(1, loop.posted(), "the listener-bearing send publishes now");
            loop.runAll();
            assertEquals(List.of("held", "with-listener"), wire);
            worker.call(() -> worker.scope().close());
        }
    }

    @Test
    @Timeout(30)
    void failingOperationBehavesLikeAFailedLoopTask() {
        ManualLoop loop = new ManualLoop();
        ConnectionOutbox outbox = new ConnectionOutbox();
        List<String> wire = new ArrayList<>();
        outbox.offer(loop, () -> {
            throw new IllegalStateException("encoder blew up");
        }, true, false);
        outbox.offer(loop, () -> wire.add("next"), true, false);
        loop.runAll();
        assertEquals(List.of("next"), wire);

        outbox.offer(loop, () -> wire.add("after"), true, false);
        assertEquals(2, loop.posted(), "the drain retired; a new operation posts a new one");
        loop.runAll();
        assertEquals(List.of("next", "after"), wire);
    }

    @Test
    @Timeout(30)
    void refusedPostSurfacesAndLeavesTheOutboxSchedulable() {
        ConnectionOutbox outbox = new ConnectionOutbox();
        AtomicReference<Boolean> refuse = new AtomicReference<>(true);
        List<String> wire = new ArrayList<>();
        java.util.concurrent.Executor loop = task -> {
            if (refuse.get()) throw new java.util.concurrent.RejectedExecutionException("down");
            task.run();
        };
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> outbox.offer(loop, () -> wire.add("a"), true, false));
        refuse.set(false);
        outbox.offer(loop, () -> wire.add("b"), true, false);
        assertTrue(wire.contains("b"), "a refusal must not leave later sends without a drain");
    }

    /**
     * A sender that won the drain reservation but has not posted it yet must not let a
     * direct Channel operation overtake an operation appended before that direct one.
     */
    @Test
    @Timeout(30)
    void directOperationCannotOvertakeWhileAnotherSenderIsStillPostingItsDrain() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread[] stalled = new Thread[1];
        ManualLoop loop = new ManualLoop() {
            @Override
            public void execute(Runnable task) {
                if (Thread.currentThread() == stalled[0]) {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                super.execute(task);
            }
        };
        ConnectionOutbox outbox = new ConnectionOutbox();
        List<String> wire = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread sender = new Thread(() -> outbox.offer(loop, () -> wire.add("other"), true, false));
        stalled[0] = sender;
        sender.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        outbox.offer(loop, () -> wire.add("before"), true, false);
        outbox.publishBeforeDirect(loop);
        loop.execute(() -> wire.add("direct"));
        release.countDown();
        sender.join(10_000);
        loop.runAll();
        assertTrue(wire.indexOf("before") >= 0 && wire.indexOf("before") < wire.indexOf("direct"),
                "order on the wire: " + wire);
    }

    /**
     * Real loop, real races: four worker steps and Main issue sends, flushes, ordered
     * tasks and synchronous direct operations. Every operation issued
     * under {@code causalLock} is ordered after the previous one (happens-before through
     * the monitor); the loop must observe them in exactly that order, and every thread's
     * own operations in its program order.
     */
    @Test
    @Timeout(120)
    void concurrentSendersKeepProgramAndCausalOrder() throws Exception {
        DefaultEventExecutor loop = new DefaultEventExecutor();
        ConnectionOutbox outbox = new ConnectionOutbox();
        List<long[]> wire = new ArrayList<>();
        Object causalLock = new Object();
        long[] causal = {0};
        int threads = 5;
        int steps = 1500;
        List<Thread> senders = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            boolean worker = t < threads - 1;
            Runnable body = () -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                long seq = 0;
                try {
                    for (int step = 0; step < steps; step++) {
                        if (worker) ((RegionWorkerThread) Thread.currentThread()).outbound.open();
                        int ops = 1 + random.nextInt(8);
                        for (int i = 0; i < ops; i++) {
                            long mine = ++seq;
                            int kind = random.nextInt(10);
                            boolean ordered = random.nextInt(3) == 0;
                            if (ordered) {
                                synchronized (causalLock) {
                                    long c = ++causal[0];
                                    issue(loop, outbox, wire, kind, id, mine, c);
                                }
                            } else {
                                issue(loop, outbox, wire, kind, id, mine, 0);
                            }
                        }
                        if (worker) ((RegionWorkerThread) Thread.currentThread()).outbound.close();
                        if (random.nextInt(16) == 0) Thread.yield();
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            };
            senders.add(worker ? new RegionWorkerThread(body, "outbox-worker-" + t)
                    : new Thread(body, "outbox-main"));
        }
        senders.forEach(Thread::start);
        for (Thread sender : senders) sender.join();
        assertNull(failure.get());
        // Everything issued was published by its sender or its step end.
        loop.submit(() -> { }).sync();
        loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();

        Map<Long, Long> lastSeq = new HashMap<>();
        long lastCausal = 0;
        int count = 0;
        for (long[] op : wire) {
            count++;
            long previous = lastSeq.getOrDefault(op[0], 0L);
            assertTrue(op[1] > previous, "thread " + op[0] + " op " + op[1] + " after " + previous);
            lastSeq.put(op[0], op[1]);
            if (op[2] != 0) {
                assertTrue(op[2] > lastCausal, "causal op " + op[2] + " after " + lastCausal);
                lastCausal = op[2];
            }
        }
        assertEquals(lastCausal, causal[0], "every ordered operation reached the loop");
        long issued = 0;
        for (long value : lastSeq.values()) issued += value;
        assertEquals(issued, count, "no operation lost or duplicated");
    }

    private static void issue(DefaultEventExecutor loop, ConnectionOutbox outbox, List<long[]> wire,
                              int kind, long thread, long seq, long causal) throws Exception {
        long[] op = {thread, seq, causal};
        Runnable record = () -> wire.add(op);
        switch (kind) {
            case 0 -> outbox.execute(loop, record);
            case 1 -> {
                // A synchronous direct Channel operation (protocol switch): the caller
                // waits for it, so nothing it issues later can be drained before it.
                outbox.publishBeforeDirect(loop);
                Future<?> direct = loop.submit(record);
                direct.sync();
            }
            case 2 -> outbox.offer(loop, record, true, false);
            case 3 -> outbox.offer(loop, record, false, true);
            default -> outbox.offer(loop, record, true, true);
        }
    }

    /** A Netty executor that runs nothing until told to; records eager vs lazy posts. */
    private static class ManualLoop extends AbstractEventExecutor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private int posted;
        private boolean lastEager;

        @Override
        public void execute(Runnable task) {
            post(task, true);
        }

        @Override
        public void lazyExecute(Runnable task) {
            post(task, false);
        }

        private synchronized void post(Runnable task, boolean eager) {
            tasks.add(task);
            posted++;
            lastEager = eager;
        }

        synchronized int posted() {
            return posted;
        }

        synchronized boolean lastWasEager() {
            return lastEager;
        }

        void runOne() {
            Runnable task;
            synchronized (this) {
                task = tasks.poll();
            }
            if (task != null) task.run();
        }

        void runAll() {
            while (true) {
                Runnable task;
                synchronized (this) {
                    task = tasks.poll();
                }
                if (task == null) return;
                task.run();
            }
        }

        @Override public boolean inEventLoop(Thread thread) { return false; }
        @Override public boolean isShuttingDown() { return false; }
        @Override public Future<?> shutdownGracefully(long quiet, long timeout, TimeUnit unit) {
            return terminationFuture();
        }
        @Override public Future<?> terminationFuture() {
            return ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
        }
        @Override @Deprecated public void shutdown() { }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    /** A real region worker thread that runs the test's actions in order, one at a time. */
    private static final class WorkerDriver implements AutoCloseable {
        private final BlockingQueue<Runnable> actions = new LinkedBlockingQueue<>();
        private final RegionWorkerThread thread;

        WorkerDriver() {
            thread = new RegionWorkerThread(() -> {
                while (true) {
                    Runnable action;
                    try {
                        action = actions.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    action.run();
                }
            }, "outbox-test-worker");
            thread.setDaemon(true);
            thread.start();
        }

        ConnectionOutbox.WorkerScope scope() {
            return thread.outbound;
        }

        void call(Runnable action) throws Exception {
            CompletableFuture<Void> done = new CompletableFuture<>();
            actions.add(() -> {
                try {
                    action.run();
                    done.complete(null);
                } catch (Throwable thrown) {
                    done.completeExceptionally(thrown);
                }
            });
            done.get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws InterruptedException {
            thread.interrupt();
            thread.join(10_000);
        }
    }
}
