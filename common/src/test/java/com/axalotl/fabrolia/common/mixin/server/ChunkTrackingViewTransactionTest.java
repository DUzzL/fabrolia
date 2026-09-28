package com.axalotl.fabrolia.common.mixin.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.Isolated
final class ChunkTrackingViewTransactionTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private final java.util.Map<String, Object> savedGate = new java.util.HashMap<>();

    @org.junit.jupiter.api.BeforeEach
    void isolateGate() throws Exception {
        for (String name : java.util.List.of("pool", "transitionPool", "orphanFatalFailure")) {
            var field = schedulerField(name);
            savedGate.put(name, field.get(null));
            field.set(null, null);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void restoreGate() throws Exception {
        for (var entry : savedGate.entrySet()) schedulerField(entry.getKey()).set(null, entry.getValue());
    }

    private static Field schedulerField(String name) throws Exception {
        var field = com.axalotl.fabrolia.common.regions.RegionScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void periodicPrefixCannotEraseChunksAfterTheReturnMoveHasReaddedThem() throws Exception {
        ViewMap map = allocate(ViewMap.class);
        ChunkTrackingView home = ChunkTrackingView.of(new ChunkPos(0, 0), 2);
        ChunkTrackingView away = ChunkTrackingView.of(new ChunkPos(8, 0), 2);
        AtomicReference<ChunkTrackingView> published = new AtomicReference<>(home);
        Set<Long> delivered = ConcurrentHashMap.newKeySet();
        home.forEach(pos -> delivered.add(pos.pack()));
        CountDownLatch prefixRead = new CountDownLatch(1);
        CountDownLatch finishPrefix = new CountDownLatch(1);
        CountDownLatch outwardFinished = new CountDownLatch(1);
        CountDownLatch returnAdded = new CountDownLatch(1);
        CountDownLatch publishReturn = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread prefix = new Thread(() -> run(failure, () -> update(map, () -> {
            ChunkTrackingView old = published.get();
            prefixRead.countDown();
            await(finishPrefix);
            applyDifference(old, away, delivered);
            published.set(away);
        })), "periodic-view-prefix");
        Thread movement = new Thread(() -> run(failure, () -> {
            apply(map, away, () -> {
                applyDifference(published.get(), away, delivered);
                published.set(away);
            });
            outwardFinished.countDown();
            apply(map, home, () -> {
                applyDifference(published.get(), home, delivered);
                returnAdded.countDown();
                await(publishReturn);
                published.set(home);
            });
        }), "outward-and-return-move");

        prefix.start();
        try {
            assertTrue(prefixRead.await(5, TimeUnit.SECONDS));
            movement.start();
            // At the broken cut the outward move and return's additions both
            // finish while the old Main prefix is paused. With the actual
            // wrapper the competing call instead waits before reading a view.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (movement.getState() != Thread.State.BLOCKED
                    && returnAdded.getCount() != 0 && failure.get() == null
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            finishPrefix.countDown();
            prefix.join(5000);
            assertTrue(outwardFinished.await(5, TimeUnit.SECONDS));
            assertTrue(returnAdded.await(5, TimeUnit.SECONDS));
        } finally {
            finishPrefix.countDown();
            publishReturn.countDown();
            prefix.join(5000);
            movement.join(5000);
        }
        assertFalse(prefix.isAlive());
        assertFalse(movement.isAlive());
        assertNull(failure.get());
        assertSame(home, published.get());
        Set<Long> expected = ConcurrentHashMap.newKeySet();
        home.forEach(pos -> expected.add(pos.pack()));
        assertEquals(expected, delivered,
                "the committed home view must include every chunk that its next early-out assumes delivered");
    }

    private static void applyDifference(ChunkTrackingView old, ChunkTrackingView next, Set<Long> delivered) {
        ChunkTrackingView.difference(old, next,
                pos -> delivered.add(pos.pack()), pos -> delivered.remove(pos.pack()));
    }

    private static void update(ViewMap map, Runnable body) throws Exception {
        invoke("fabrolia$syncTrackingViewUpdate", map,
                new Class<?>[]{ServerPlayer.class, Operation.class},
                null, (Operation<Void>) args -> { body.run(); return null; });
    }

    private static void apply(ViewMap map, ChunkTrackingView view, Runnable body) throws Exception {
        invoke("fabrolia$syncTrackingViewApply", map,
                new Class<?>[]{ServerPlayer.class, ChunkTrackingView.class, Operation.class},
                null, view, (Operation<Void>) args -> { body.run(); return null; });
    }

    private static void invoke(String name, ViewMap map, Class<?>[] types, Object... args) throws Exception {
        Method method = ChunkMapMixin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            method.invoke(map, args);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("interleaving did not advance");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @FunctionalInterface
    private interface Body { void run() throws Exception; }

    private static void run(AtomicReference<Throwable> failure, Body body) {
        try { body.run(); } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
    }

    private static final class ViewMap extends ChunkMapMixin {
        private ViewMap() { super(null, null, null, false, null); }
        @Override protected boolean skipPlayer(ServerPlayer player) { return false; }
        @Override protected boolean playerIsCloseEnoughForSpawning(ServerPlayer player, ChunkPos pos) { return false; }
        @Override public java.util.List<ServerPlayer> getPlayers(ChunkPos pos, boolean borderOnly) {
            throw new AssertionError("view diff must not enumerate entity-tracking viewers");
        }
    }
}
