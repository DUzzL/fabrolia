package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
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

@Isolated
@Timeout(20)
final class AdvancementFenceTransitionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void queuedRewardsLeaveTheProducerMonitorAndClaimBeforeRunningInFifoOrder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> effects = new ArrayList<>();
            Object monitor = new Object();
            Constructor<IslandRegistry.PIsland> constructor =
                    IslandRegistry.PIsland.class.getDeclaredConstructor(long.class, long.class);
            constructor.setAccessible(true);
            IslandRegistry.PIsland owner = constructor.newInstance(730L, 0L);
            IslandCommands.mainClaim(owner);
            try {
                synchronized (monitor) {
                    AdvancementBuffer.deferDirect(() -> {
                        effects.add(Thread.holdsLock(monitor) || IslandCommands.mainHolds(owner)
                                ? "reward-inside-producer" : "first-reward");
                        AdvancementBuffer.deferDirect(() -> effects.add("cascade"));
                    });
                    AdvancementBuffer.deferDirect(() -> effects.add("second-reward"));
                    effects.add("packet-completed");
                }
            } finally {
                IslandCommands.mainRelease(owner);
            }
            assertEquals(List.of("packet-completed"), effects,
                    "publishing from physical Main must not execute a reward inside its producer");
            fixture.drain();
            assertEquals(List.of("packet-completed", "first-reward", "second-reward", "cascade"), effects);
            assertFalse(AdvancementBatchScope.active());
        }
    }

    @Test
    void transitionPumpRetainsRewardsUntilTheMainBeltHasFenceOwnership() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> effects = new ArrayList<>();
            Class<?> frameType = Class.forName(IslandCommands.class.getName() + "$GlobalFenceFrame");
            Constructor<?> constructor = frameType.getDeclaredConstructor(Thread.class, IslandRegistry.PIsland.class);
            constructor.setAccessible(true);
            Object frame = constructor.newInstance(Thread.currentThread(), null);
            set(IslandCommands.class, null, "ACTIVE_FENCE", frame);

            AdvancementBuffer.deferDirect(() -> effects.add("first-reward"));
            fixture.drain();
            AdvancementBuffer.deferDirect(() -> effects.add("second-reward"));
            fixture.drain();
            assertEquals(List.of(), effects, "a reentrant acquisition pump must not enter reward bodies");

            Field phase = frameType.getDeclaredField("phase");
            phase.setAccessible(true);
            phase.set(frame, enumConstant(phase.getType(), "HELD"));
            AdvancementBuffer.drainMainFrameless();
            fixture.drain();
            assertEquals(List.of("first-reward", "second-reward"), effects,
                    "the unconditional Main belt must preserve both deferred rewards and their order");
            assertFalse(AdvancementBatchScope.active());
        }
    }

    /** Real Minecraft schedule/managedBlock semantics, without starting a world. */
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
        private final AtomicBoolean scheduled;
        private final boolean previouslyScheduled;

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
            scheduled = (AtomicBoolean) get(AdvancementBuffer.class, null, "MAIN_DRAIN_SCHEDULED");
            previouslyScheduled = scheduled.get();
            if (!direct.isEmpty()) throw new IllegalStateException("another test retained advancement work");
            levels.clear();
            scheduled.set(false);
            set(ParallelProcessor.class, null, "server", server);
            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            set(IslandCommands.class, null, "ACTIVE_FENCE", null);
        }

        void drain() {
            server.managedBlock(tasks::isEmpty);
        }

        @Override
        public void close() {
            try {
                if (AdvancementBatchScope.active()) AdvancementBatchScope.end();
            } finally {
                tasks.clear();
                direct.clear();
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }
}
