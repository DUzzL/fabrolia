package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class IslandSprintPreparationPauseTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void preparationStopsLocalSprintAndResumeConsumesOnlyItsRemainingUnits() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.enqueue();
            await(f.firstEntered);
            f.retainNextUnit("first");
            f.retainNextUnit("second");

            assertTrue(f.pool.pauseMainPreparationAndAwait(f.releaseFirst::countDown));
            assertTrue(f.pool.ownsPausedExactTurn(Thread.currentThread()));
            assertFalse(f.island.unitClaim.get(), "preparation must wait for the real claim release");
            assertEquals(1L, f.island.unitsCompleted,
                    "a preparation request must not wait for the entire local sprint");
            assertEquals(Fixture.SPRINT_UNITS - 1, f.sprint.sprintRemaining.get());
            assertEquals(List.of(), f.effects,
                    "queued next-unit obligations must remain untouched during preparation");

            f.pool.resumeExactTurn();
            f.enqueue();
            await(f.finished);
            f.pool.pauseExactTurnAndAwait(() -> {});
            assertEquals(Fixture.SPRINT_UNITS, f.island.logicalTick);
            assertEquals(Fixture.SPRINT_UNITS, f.island.unitsCompleted);
            assertEquals(0L, f.sprint.sprintRemaining.get());
            assertEquals(List.of("first", "second"), f.effects,
                    "the resumed full unit must drain the retained FIFO exactly once");
        }
    }

    @Test
    void successorDecidedBeforeRequestDrainsButCannotExtendTheSprintIndefinitely() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.enqueue();
            await(f.firstEntered);
            assertTrue(f.pool.pauseMainPreparationAndAwait(f.releaseFirst::countDown));
            assertTrue(f.pool.ownsPausedExactTurn(Thread.currentThread()));
            assertFalse(f.island.unitClaim.get());
            assertEquals(2L, f.island.unitsCompleted,
                    "the already-decided whole successor is owed, but its successor must yield");
            assertEquals(Fixture.SPRINT_UNITS - 2, f.sprint.sprintRemaining.get());

            f.pool.resumeExactTurn();
            f.enqueue();
            await(f.finished);
            f.pool.pauseExactTurnAndAwait(() -> {});
            assertEquals(Fixture.SPRINT_UNITS, f.island.logicalTick);
            assertEquals(0L, f.sprint.sprintRemaining.get());
        }
    }

    /**
     * Substitute phase bodies only: the real completion plan commits L and the
     * sprint debit under an actual island claim, and its chaining decision feeds
     * the real pool unit admission. One worker exposes the queue-or-active
     * starvation without any timing-dependent attempt to catch an idle instant.
     */
    private static final class Fixture implements AutoCloseable {
        static final long SPRINT_UNITS = 16L;
        final RegionWorkerPool pool = new RegionWorkerPool(1);
        final IslandRegistry.PIsland island = new IslandRegistry.PIsland(1L, 0L);
        final TemporalControl.Session sprint = new TemporalControl.Session("preparation sprint");
        final RegionizedTaskQueue nextUnit = new RegionizedTaskQueue();
        final List<String> effects = new ArrayList<>();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
        final boolean decisionBeforeRequest;
        final ServerLevel level;
        final RegionizedServer runtime;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object oldPool = field(RegionScheduler.class, null, "pool");
        final Object oldActive = field(RegionScheduler.class, null, "active");
        final Object oldServer = field(RegionScheduler.class, null, "server");
        final Object oldFailure = field(RegionScheduler.class, null, "orphanFatalFailure");
        final Method prepare = method(IslandTickUnit.class, "prepareCompletion",
                IslandRegistry.PIsland.class, long.class);
        final Method commit = method(prepare.getReturnType(), "commit");
        final Method chain = method(prepare.getReturnType(), "chain");
        final Method reserve = method(RegionWorkerPool.class, "reserveExternalTask", Runnable.class);
        final Thread watchdog;

        @SuppressWarnings("unchecked")
        Fixture(boolean decisionBeforeRequest) throws Exception {
            this.decisionBeforeRequest = decisionBeforeRequest;
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, null, "theUnsafe");
            level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            set(RegionizedServer.class, runtime, "level", level);
            runtime.asyncActive = true;
            island.level = level;
            island.cells.add(0L);
            sprint.cells.add(0L);
            sprint.sprintRemaining.set(SPRINT_UNITS);
            set(RegionizedServer.class, runtime, "temporalSessions",
                    new CopyOnWriteArrayList<>(List.of(sprint)));
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, null, "PER_LEVEL");
            levels.put(level, runtime);
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "active", true);
            set(RegionScheduler.class, null, "server", null);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            watchdog = new Thread(() -> {
                try {
                    if (!closed.await(5, TimeUnit.SECONDS)) {
                        AssertionError failure = new AssertionError("local sprint failed to reach preparation quiescence");
                        backgroundFailure.compareAndSet(null, failure);
                        pool.publishFatalFailure(failure);
                        releaseFirst.countDown();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "sprint-preparation-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            pool.start();
        }

        void enqueue() {
            assertTrue(pool.enqueueUnit(this::runUnit));
            pool.wake(1);
        }

        void retainNextUnit(String effect) {
            nextUnit.submit((Runnable) invoke(reserve, pool, (Runnable) () -> effects.add(effect)));
        }

        void runUnit() {
            assertTrue(island.unitClaim.compareAndSet(false, true));
            try {
                nextUnit.drain(Integer.MAX_VALUE);
                boolean first = island.unitsCompleted == 0;
                Object plan = first && decisionBeforeRequest
                        ? invoke(prepare, null, island, System.nanoTime()) : null;
                if (first) {
                    firstEntered.countDown();
                    await(releaseFirst);
                }
                if (plan == null) plan = invoke(prepare, null, island, System.nanoTime());
                // This mirrors the real offer-before-commit/release ordering. A
                // plan captured before REQUESTED deliberately admits one owed
                // successor, proving that the next real completion breaks it.
                if ((boolean) invoke(chain, plan)) enqueue();
                Object completed = plan;
                pool.commitSuccessIfHealthy(() -> invoke(commit, completed));
                if (sprint.sprintRemaining.get() == 0) finished.countDown();
            } finally {
                island.unitClaim.set(false);
            }
        }

        @Override
        public void close() throws Exception {
            releaseFirst.countDown();
            try {
                if (pool.ownsPausedExactTurn(Thread.currentThread())) pool.resumeExactTurn();
                nextUnit.drain(Integer.MAX_VALUE);
                pool.stop();
                assertNull(backgroundFailure.get());
            } finally {
                closed.countDown();
                watchdog.join(2_000L);
                assertFalse(watchdog.isAlive());
                levels.remove(level, runtime);
                set(RegionScheduler.class, null, "pool", oldPool);
                set(RegionScheduler.class, null, "active", oldActive);
                set(RegionScheduler.class, null, "server", oldServer);
                set(RegionScheduler.class, null, "orphanFatalFailure", oldFailure);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "worker missed its completion boundary");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object field(Class<?> owner, Object target, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void set(Class<?> owner, Object target, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
