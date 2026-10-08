package com.axalotl.fabrolia.common.regions;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Carpet fake player's connection is an {@link EmbeddedChannel}: its event loop runs a
 * queued task only when the channel is next operated on (vanilla: resumeFlushing on Main
 * at the end of tickChildren). A chunk-forget publication made between two ticks (bot
 * /tp from RCON) used to keep the next exact-turn pause waiting forever for a pool permit
 * that only the pausing thread could run.
 */
class PassiveConnectionLoopsTest {

    /** The shape of Connection.fabrolia$enqueueEventLoopTask for a fake player. */
    private static void publish(RegionWorkerPool pool, EmbeddedChannel channel,
                                ConnectionOutbox outbox, AtomicInteger bodies) {
        try {
            pool.executeTrackedExternal(queued -> outbox.execute(channel.eventLoop(), queued),
                    bodies::incrementAndGet);
        } finally {
            PassiveConnectionLoops.noteQueued(channel);
        }
    }

    @Test
    void exactTurnCannotPauseWhileAFakePlayerTransportTaskIsQueued() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            EmbeddedChannel channel = new EmbeddedChannel();
            AtomicInteger bodies = new AtomicInteger();
            pool.start();
            try {
                publish(pool, channel, new ConnectionOutbox(), bodies);
                assertEquals(0, bodies.get(), "an EmbeddedEventLoop never runs a task by itself");

                CompletableFuture<Void> paused = new CompletableFuture<>();
                CountDownLatch release = new CountDownLatch(1);
                Thread owner = Thread.ofPlatform().name("passive-loop-pause-owner").start(() -> {
                    try {
                        pool.pauseExactTurnAndAwait(() -> {});
                        paused.complete(null);
                        release.await();
                        pool.resumeExactTurn();
                    } catch (Throwable thrown) {
                        paused.completeExceptionally(thrown);
                    }
                });
                assertThrows(TimeoutException.class,
                        () -> paused.get(300, TimeUnit.MILLISECONDS),
                        "the fixed point must wait for the accepted transport permit");
                // What vanilla's next resumeFlushing would do; only this thread touches it.
                PassiveConnectionLoops.runQueued();
                paused.get(3, TimeUnit.SECONDS);
                assertEquals(1, bodies.get());
                release.countDown();
                owner.join();
            } finally {
                pool.stop();
                channel.finishAndReleaseAll();
            }
        });
    }

    @Test
    void exactTurnPauseRunsQueuedFakePlayerTransportOnce() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            EmbeddedChannel channel = new EmbeddedChannel();
            AtomicInteger bodies = new AtomicInteger();
            pool.start();
            try {
                publish(pool, channel, new ConnectionOutbox(), bodies);
                pool.pauseExactTurnAndAwait(PassiveConnectionLoops::runQueued);
                assertEquals(1, bodies.get());
                assertTrue(PassiveConnectionLoops.isEmpty());
                pool.resumeExactTurn();
                channel.runPendingTasks();
                assertEquals(1, bodies.get(), "the transport body ran exactly once");
            } finally {
                pool.stop();
                channel.finishAndReleaseAll();
            }
        });
    }

    @Test
    void mainPreparationPauseRunsQueuedFakePlayerTransport() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            EmbeddedChannel channel = new EmbeddedChannel();
            AtomicInteger bodies = new AtomicInteger();
            pool.start();
            try {
                publish(pool, channel, new ConnectionOutbox(), bodies);
                assertTrue(pool.pauseMainPreparationAndAwait(PassiveConnectionLoops::runQueued));
                assertEquals(1, bodies.get());
                pool.resumeExactTurn();
            } finally {
                pool.stop();
                channel.finishAndReleaseAll();
            }
        });
    }

    @Test
    void failStopCancelsQueuedFakePlayerTransportWithoutItsBody() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Field schedulerPool = RegionScheduler.class.getDeclaredField("pool");
            schedulerPool.setAccessible(true);
            assertNull(schedulerPool.get(null), "test requires no live scheduler generation");
            RegionWorkerPool pool = new RegionWorkerPool(1);
            EmbeddedChannel channel = new EmbeddedChannel();
            AtomicInteger bodies = new AtomicInteger();
            RuntimeException winner = new RuntimeException("generation fatal");
            pool.start();
            schedulerPool.set(null, pool);
            try {
                publish(pool, channel, new ConnectionOutbox(), bodies);
                pool.publishFatalFailure(winner);
                // The failed-generation cut never calls progress for a QUEUED permit; its
                // queue sweep must reach the passive loop or stop waits forever.
                assertSame(winner, assertThrows(RuntimeException.class, () -> pool.stop(() -> {})));
                assertEquals(0, bodies.get());
                assertTrue(PassiveConnectionLoops.isEmpty());
            } finally {
                schedulerPool.set(null, null);
                channel.finishAndReleaseAll();
            }
        });
    }

    @Test
    void everyMainFixedPointServicesPassiveLoops() throws Exception {
        String scheduler = Files.readString(Path.of(
                "src/main/java/com/axalotl/fabrolia/common/regions/RegionScheduler.java"));
        String pause = section(scheduler,
                "private static boolean pauseExactPlayerTurn(boolean retainQueuedRegions)",
                "public static void resumeExactPlayerTurn()");
        assertOrdered(pause, "PassiveConnectionLoops.runQueued();", "throwWorkerFailureIfAny();");
        String stop = section(scheduler, "Runnable drainProgress = () -> {",
                "stoppingPool.stop(drainProgress);");
        assertOrdered(stop, "PassiveConnectionLoops.runQueued();",
                "AdvancementBuffer.drainShutdownPassIfPending();");
        assertTrue(section(scheduler, "static Throwable cancelFailedGenerationQueues(",
                "static boolean failedGenerationQueuesEmpty(")
                .contains("PassiveConnectionLoops.runQueued();"));
        assertTrue(section(scheduler, "static boolean failedGenerationQueuesEmpty(",
                "} catch (Throwable cleanup) {")
                .contains("!PassiveConnectionLoops.isEmpty()"));

        String connection = Files.readString(Path.of(
                "src/main/java/com/axalotl/fabrolia/common/mixin/network/ConnectionIslandPlayerMixin.java"));
        String enqueue = section(connection,
                "public void fabrolia$enqueueEventLoopTask(Runnable task)",
                "private void fabrolia$orderedOffLoopSend(");
        assertOrdered(enqueue, "RegionScheduler.executeTrackedExternal(", "} finally {");
        assertOrdered(enqueue, "} finally {", "PassiveConnectionLoops.noteQueued(current);");
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue(from >= 0, "missing " + start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(to > from, "missing " + end + " after " + start);
        return source.substring(from, to);
    }

    private static void assertOrdered(String section, String first, String second) {
        int a = section.indexOf(first);
        int b = section.indexOf(second, Math.max(a, 0));
        assertTrue(a >= 0 && b > a, first + " must precede " + second);
    }
}
