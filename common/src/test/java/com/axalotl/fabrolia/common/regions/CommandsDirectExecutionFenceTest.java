package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.commands.CommandsMixin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual command handoff and vanilla task pump; transformed overloads are exercised in runtime. */
@Isolated
class CommandsDirectExecutionFenceTest {
    @Test
    void foreignCallerWaitsForPhysicalMainEvenWithTheSchedulerDisabled() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            AtomicInteger effects = new AtomicInteger();
            AtomicReference<Thread> venue = new AtomicReference<>();
            fixture.start(() -> {
                assertEquals(1L, fixture.finished.getCount(), "caller returned before its command body");
                venue.set(Thread.currentThread());
                effects.incrementAndGet();
            }, false);
            await(fixture.published);
            assertEquals(0, effects.get());
            assertEquals(1L, fixture.finished.getCount());
            fixture.drain();
            fixture.join();
            assertNull(fixture.failure.get());
            assertSame(Thread.currentThread(), venue.get());
            assertEquals(1, effects.get());
        }
    }

    @Test
    void anEscapingCompletionExceptionRemainsTheExactCommandFailure() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            CompletionException original = new CompletionException("command", new IllegalArgumentException("cause"));
            AtomicInteger effects = new AtomicInteger();
            fixture.start(() -> {
                effects.incrementAndGet();
                throw original;
            }, false);
            await(fixture.published);
            fixture.drain();
            fixture.join();
            assertEquals(1, effects.get());
            assertSame(original, fixture.failure.get(), "transport unwrapped or replaced the command failure");
            assertSame(original, fixture.pool.fatalFailureOrNull());
        }
    }

    @Test
    void failureBeforeMainDequeueCancelsTheCommandAndItsLateCallback() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            IllegalStateException original = new IllegalStateException("peer failure");
            AtomicInteger effects = new AtomicInteger();
            fixture.start(effects::incrementAndGet, false);
            await(fixture.published);
            fixture.pool.publishFatalFailure(original);
            fixture.join();
            assertSame(original, fixture.failure.get());
            assertEquals(0, effects.get());
            // The real server pollTask guard refuses dequeue after W. A late
            // holder of the already-published TickTask must also remain inert.
            fixture.tasks.remove().run();
            assertEquals(0, effects.get());
            assertSame(original, fixture.pool.fatalFailureOrNull());
        }
    }

    @Test
    void terminationBeforeDequeueReleasesTheWaiterWithoutEnteringTheCommand() throws Exception {
        CountDownLatch terminate = new CountDownLatch(1);
        Thread owner = new Thread(() -> await(terminate), "terminating-command-main");
        owner.setDaemon(true);
        owner.start();
        try (Fixture fixture = new Fixture(false)) {
            set(MinecraftServer.class, fixture.server, "serverThread", owner);
            AtomicInteger effects = new AtomicInteger();
            fixture.start(effects::incrementAndGet, false);
            await(fixture.published);
            terminate.countDown();
            owner.join(5_000L);
            assertFalse(owner.isAlive());
            fixture.join();
            assertInstanceOf(RejectedExecutionException.class, fixture.failure.get());
            assertEquals(0, effects.get());
            fixture.tasks.remove().run();
            assertEquals(0, effects.get(), "a rejected command entered after its caller continued");
        } finally {
            terminate.countDown();
            owner.join(5_000L);
        }
    }

    @Test
    void anEnteredCommandMayStopTheServerWithoutLosingItsSuccessfulResult() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            AtomicInteger effects = new AtomicInteger();
            fixture.start(() -> {
                effects.incrementAndGet();
                set(MinecraftServer.class, fixture.server, "stopped", true);
            }, false);
            await(fixture.published);
            fixture.drain();
            fixture.join();
            assertTrue(fixture.server.isStopped());
            assertEquals(1, effects.get());
            assertNull(fixture.failure.get(), "a completed command was retrospectively rejected");
        }
    }

    @Test
    void aCausalForeignCallerCannotWaitBehindItsOwnMainDependency() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            AtomicInteger effects = new AtomicInteger();
            fixture.start(effects::incrementAndGet, true);
            fixture.join();
            assertInstanceOf(IllegalStateException.class, fixture.failure.get());
            assertSame(fixture.failure.get(), fixture.pool.fatalFailureOrNull());
            assertEquals(0, effects.get());
            assertTrue(fixture.tasks.isEmpty(), "causal caller published a blocking Main command");
        }
    }

    @Test
    void enqueueThenThrowCancelsThePublishedUnenteredCommand() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            IllegalStateException original = new IllegalStateException("failure after queue insertion");
            fixture.afterInsertionFailure = original;
            AtomicInteger effects = new AtomicInteger();
            fixture.start(effects::incrementAndGet, false);
            await(fixture.published);
            fixture.join();
            assertSame(original, fixture.failure.get());
            fixture.tasks.remove().run();
            assertEquals(0, effects.get());
            assertSame(original, fixture.pool.fatalFailureOrNull());
        }
    }

    @Test
    void publicationWinnerSurvivesAnEarlierStopCancellationTerminal() throws Exception {
        CountDownLatch releasePublication = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(true)) {
            try {
                IllegalStateException original = new IllegalStateException("publication failure after cancellation");
                fixture.afterInsertion = () -> await(releasePublication);
                fixture.afterInsertionFailure = original;
                AtomicInteger effects = new AtomicInteger();
                fixture.start(effects::incrementAndGet, false);
                await(fixture.published);
                set(MinecraftServer.class, fixture.server, "stopped", true);
                // Main cancels the queued root while the original publisher is
                // still inside schedule; schedule then throws and selects W.
                fixture.tasks.remove().run();
                releasePublication.countDown();
                fixture.join();
                assertEquals(0, effects.get());
                assertSame(original, fixture.pool.fatalFailureOrNull());
                assertSame(original, fixture.failure.get(),
                        "the local stop rejection replaced the canonical publication failure");
            } finally {
                releasePublication.countDown();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CountDownLatch published = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile RuntimeException afterInsertionFailure;
        volatile Runnable afterInsertion;
        final ConcurrentLinkedQueue<TickTask> tasks = new ConcurrentLinkedQueue<>() {
            @Override
            public boolean add(TickTask task) {
                boolean added = super.add(task);
                published.countDown();
                if (afterInsertion != null) afterInsertion.run();
                if (afterInsertionFailure != null) throw afterInsertionFailure;
                return added;
            }
        };
        final MinecraftServer server;
        final RegionWorkerPool pool;
        final Object priorPool = get(RegionScheduler.class, null, "pool");
        final Object priorActive = get(RegionScheduler.class, null, "active");
        final Object priorServer = get(RegionScheduler.class, null, "server");
        final Object priorFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
        Thread caller;

        Fixture(boolean active) throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            set(MinecraftServer.class, server, "levels", Map.of());
            set(BlockableEventLoop.class, server, "pendingRunnables", tasks);
            pool = active ? new RegionWorkerPool(1) : null;
            if (pool != null) {
                set(RegionWorkerPool.class, pool, "running", true);
                set(RegionWorkerPool.class, pool, "accepting", true);
            }
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "active", active);
            set(RegionScheduler.class, null, "server", server);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
        }

        void start(Runnable body, boolean causal) {
            caller = new Thread(() -> {
                try {
                    Runnable entry = () -> dispatch(server, body);
                    if (causal) RegionScheduler.runSuccessBodyIfHealthy(entry);
                    else entry.run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    finished.countDown();
                }
            }, "foreign-command-caller");
            caller.setDaemon(true);
            caller.start();
        }

        void drain() {
            server.managedBlock(tasks::isEmpty);
        }

        void join() throws Exception {
            caller.join(5_000L);
            assertFalse(caller.isAlive(), "foreign command waiter did not reach its terminal");
        }

        @Override
        public void close() throws Exception {
            try {
                if (caller != null && caller.isAlive()) {
                    set(MinecraftServer.class, server, "stopped", true);
                    set(MinecraftServer.class, server, "serverThread", new Thread());
                    join();
                }
                tasks.clear();
                if (pool != null) {
                    Throwable cleanup = pool.fatalFailureOrNull();
                    if (cleanup == null) cleanup = new IllegalStateException("fixture teardown");
                    pool.publishFatalFailure(cleanup);
                    try {
                        pool.stop(() -> {});
                    } catch (Throwable thrown) {
                        if (thrown != cleanup) throw thrown;
                    }
                }
            } finally {
                set(RegionScheduler.class, null, "pool", priorPool);
                set(RegionScheduler.class, null, "active", priorActive);
                set(RegionScheduler.class, null, "server", priorServer);
                set(RegionScheduler.class, null, "orphanFatalFailure", priorFailure);
            }
        }
    }

    private static void dispatch(MinecraftServer server, Runnable body) {
        try {
            Method needsMain = CommandsMixin.class.getDeclaredMethod("fabrolia$isForeignCommand", MinecraftServer.class);
            needsMain.setAccessible(true);
            if ((boolean) needsMain.invoke(null, server)) {
                Method handoff = CommandsMixin.class.getDeclaredMethod(
                        "fabrolia$runForeignCommand", MinecraftServer.class, Runnable.class);
                handoff.setAccessible(true);
                handoff.invoke(null, server, body);
            } else {
                body.run();
            }
        } catch (InvocationTargetException wrapped) {
            CommandsDirectExecutionFenceTest.<RuntimeException>rethrow(wrapped.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "command scheduling boundary timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Object get(Class<?> type, Object target, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void set(Class<?> type, Object target, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
