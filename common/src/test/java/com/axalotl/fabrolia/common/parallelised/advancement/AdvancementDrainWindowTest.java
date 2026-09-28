package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drain-point policy with an async level published: the worker lane waits for the
 * tick-head window unless a batch already holds the fence, and the window applies
 * every lane in order under one scope.
 */
@Isolated
@Timeout(20)
final class AdvancementDrainWindowTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void batchEndLeavesTheWorkerLaneUntilAFenceIsHeld() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publishAsyncLevel();
            fixture.publishWorkerPending();
            assertTrue(IslandCommands.requiresMainFence());
            assertFalse(IslandCommands.fenceWindowHeld());

            AdvancementBuffer.applyDeferred();
            assertEquals(1, fixture.deferred.size(),
                    "no held fence: the lane must wait for the tick-head window, not quiesce here");
            assertFalse(AdvancementBatchScope.active());

            fixture.holdAllClaims();
            assertTrue(IslandCommands.fenceWindowHeld());
            AdvancementBuffer.applyDeferred();
            assertTrue(fixture.deferred.isEmpty(), "a batch that already holds the fence drains the lane");
            assertFalse(AdvancementBatchScope.active());
        }
    }

    @Test
    void tickHeadWindowAppliesLanesInOrderAndReleasesOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publishAsyncLevel();
            fixture.holdAllClaims();
            List<String> effects = new ArrayList<>();
            long headDrains = AdvancementDiag.HEAD_DRAINS.sum();
            AdvancementBuffer.deferDirect(() -> effects.add("direct"));
            fixture.publishWorkerPending();

            assertTrue(AdvancementBuffer.beginTickHeadWindow(false), "the head owns the window");
            assertTrue(AdvancementBatchScope.active(), "the window stays open for the tick functions");
            assertTrue(fixture.deferred.isEmpty(), "the worker lane applied at the head");
            assertEquals(List.of("direct"), effects);
            assertEquals(headDrains + 1, AdvancementDiag.HEAD_DRAINS.sum());
            assertFalse(AdvancementBuffer.beginTickHeadWindow(false), "a nested entry never owns the window");

            // Raised by a tick function inside the window: settles before release.
            AdvancementBuffer.deferDirect(() -> effects.add("function-cascade"));
            assertNull(AdvancementBuffer.endTickHeadWindow(null));
            assertEquals(List.of("direct", "function-cascade"), effects);
            assertFalse(AdvancementBatchScope.active());
        }
    }

    @Test
    void tickHeadWindowKeepsTheFunctionPrimaryAndStillCloses() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publishAsyncLevel();
            fixture.holdAllClaims();
            List<String> effects = new ArrayList<>();
            assertTrue(AdvancementBuffer.beginTickHeadWindow(false));
            AdvancementBuffer.deferDirect(() -> effects.add("cascade"));
            IllegalStateException primary = new IllegalStateException("tick function failed");
            Throwable reported = AdvancementBuffer.endTickHeadWindow(primary);
            assertTrue(reported == primary, "the entered function failure stays primary");
            assertEquals(List.of(), effects, "a failed function body owns no cascade tail");
            assertFalse(AdvancementBatchScope.active());
        }
    }

    @Test
    void standaloneFramelessDrainWaitsForAHeldFenceWhileAsyncIsLive() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publishAsyncLevel();
            List<String> effects = new ArrayList<>();
            AdvancementBuffer.deferDirect(() -> effects.add("replay"));

            AdvancementBuffer.drainMainFrameless();
            assertEquals(List.of(), effects,
                    "no held fence: the scheduled belt leaves the replay to the tick-head window");
            assertFalse(AdvancementBatchScope.active());

            fixture.holdAllClaims();
            AdvancementBuffer.drainMainFrameless();
            assertEquals(List.of("replay"), effects, "a free fence drains the belt as before");
            assertFalse(AdvancementBatchScope.active());
        }
    }

    /** Real Minecraft schedule semantics without a world; one fake async level. */
    private static final class Fixture implements AutoCloseable {
        private final MinecraftServer server;
        private final Queue<TickTask> tasks = new ConcurrentLinkedQueue<>();
        private final Object previousServer = get(ParallelProcessor.class, null, "server");
        private final Object previousPool = get(RegionScheduler.class, null, "pool");
        private final Object previousFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
        private final Object previousFence = get(IslandCommands.class, null, "ACTIVE_FENCE");
        private final Map<Object, Object> levels;
        private final Map<Object, Object> previousLevels;
        private final Queue<Runnable> direct;
        private final Queue<Object[]> deferred;
        private final AtomicBoolean scheduled;
        private final boolean previouslyScheduled;
        private RegionizedServer runtime;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            set(MinecraftServer.class, server, "levels", Map.of());
            set(BlockableEventLoop.class, server, "pendingRunnables", tasks);
            levels = (Map<Object, Object>) get(RegionScheduler.class, null, "PER_LEVEL");
            previousLevels = new HashMap<>(levels);
            direct = (Queue<Runnable>) get(AdvancementBuffer.class, null, "DIRECT_MAIN");
            deferred = (Queue<Object[]>) get(AdvancementBuffer.class, null, "DEFERRED_PENDING");
            scheduled = (AtomicBoolean) get(AdvancementBuffer.class, null, "MAIN_DRAIN_SCHEDULED");
            previouslyScheduled = scheduled.get();
            if (!direct.isEmpty() || !deferred.isEmpty()) {
                throw new IllegalStateException("another test retained advancement work");
            }
            levels.clear();
            scheduled.set(false);
            set(ParallelProcessor.class, null, "server", server);
            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            set(IslandCommands.class, null, "ACTIVE_FENCE", null);
        }

        /** An async level whose fence classification is live; no claims held yet. */
        void publishAsyncLevel() throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            runtime.asyncActive = true;
            levels.put(new Object(), runtime);
        }

        /** Physical Main owns every claim of the fake level: the fence window is held. */
        void holdAllClaims() throws Exception {
            Class<?> ownership = Class.forName(RegionizedServer.class.getName() + "$MainAllClaimsOwnership");
            Constructor<?> constructor = ownership.getDeclaredConstructor(Object.class, Thread.class, boolean.class);
            constructor.setAccessible(true);
            set(RegionizedServer.class, runtime, "mainAllClaimsOwnership",
                    constructor.newInstance(new Object(), Thread.currentThread(), false));
        }

        /** One worker-lane publication with no surviving listener: applies without a player. */
        void publishWorkerPending() throws Exception {
            Class<?> pendingType = Class.forName(AdvancementBuffer.class.getName() + "$Pending");
            Constructor<?> constructor = pendingType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object pending = constructor.newInstance(null, null,
                    new net.minecraft.server.PlayerAdvancements.TriggerInstanceKey[0], new Object[0]);
            Object[] publication = (Object[]) java.lang.reflect.Array.newInstance(pendingType, 1);
            publication[0] = pending;
            deferred.add(publication);
        }

        @Override
        public void close() {
            try {
                if (AdvancementBatchScope.active()) AdvancementBatchScope.end();
            } finally {
                tasks.clear();
                direct.clear();
                deferred.clear();
                scheduled.set(previouslyScheduled);
                levels.clear();
                levels.putAll(previousLevels);
                set(IslandCommands.class, null, "ACTIVE_FENCE", previousFence);
                set(RegionScheduler.class, null, "pool", previousPool);
                set(RegionScheduler.class, null, "orphanFatalFailure", previousFailure);
                set(ParallelProcessor.class, null, "server", previousServer);
            }
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
}
