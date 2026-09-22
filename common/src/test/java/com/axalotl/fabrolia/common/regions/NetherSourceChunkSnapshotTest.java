package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual submit/capture and Main chunk consumer, with entity callbacks controlling the race. */
@Isolated
class NetherSourceChunkSnapshotTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void mainTraversalDoesNotReadThePassengerMapBeingCaptured() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch secondCaptured = new CountDownLatch(1);
            CountDownLatch continueCapture = new CountDownLatch(1);
            CountDownLatch thirdCaptured = new CountDownLatch(1);
            CountDownLatch finishCapture = new CountDownLatch(1);
            fixture.second.onPending = () -> { secondCaptured.countDown(); await(continueCapture); };
            fixture.third.onPending = () -> { thirdCaptured.countDown(); await(finishCapture); };
            Future<Boolean> submission = fixture.executor.submit(() ->
                    NetherPortalTransfers.submit(fixture.root, null, BlockPos.ZERO, fixture.level));
            try {
                await(secondCaptured);
                List<Long> observed = new ArrayList<>();
                NetherPortalTransfers.forEachPendingSourceChunk(fixture.level, chunk -> {
                    observed.add(chunk);
                    continueCapture.countDown();
                    await(thirdCaptured);
                });
                assertEquals(List.of(fixture.root.chunk()), observed,
                        "construction retains the root chunk, not a partial mutable passenger receipt");
                fixture.cancel();
                assertTrue(fixture.root.pending, "cancellation must retain construction custody");
                assertTrue(fixture.second.pending);
                assertTrue(fixture.third.pending);
                assertEquals(1, NetherPortalTransfers.pendingCount());
            } finally {
                // Let the real submit finally settle cancellation even if an assertion fails.
                fixture.cancel();
                continueCapture.countDown();
                finishCapture.countDown();
                assertFalse(submission.get(5, TimeUnit.SECONDS));
            }
            assertFalse(fixture.root.pending);
            assertFalse(fixture.second.pending);
            assertFalse(fixture.third.pending);
            assertEquals(0, NetherPortalTransfers.pendingCount());
            assertEquals(List.of(), fixture.chunks());
        }
    }

    @Test
    void completedReceiptSurvivesCancellationInsideTheConsumerWithoutHoldingTransferLock() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.capture();
            List<Long> observed = new ArrayList<>();
            NetherPortalTransfers.forEachPendingSourceChunk(fixture.level, chunk -> {
                observed.add(chunk);
                if (observed.size() == 1) {
                    Future<?> cancellation = fixture.executor.submit(() -> {
                        fixture.cancel();
                        return null;
                    });
                    try {
                        cancellation.get(5, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new AssertionError("Main sink held a transfer lock across cancellation", failure);
                    }
                }
            });
            assertEquals(new HashSet<>(List.of(fixture.root.chunk(), fixture.second.chunk(), fixture.third.chunk())),
                    new HashSet<>(observed));
            assertEquals(3, observed.size());
            assertEquals(List.of(), fixture.chunks());
            assertFalse(fixture.root.pending);
            assertFalse(fixture.second.pending);
            assertFalse(fixture.third.pending);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "capture barrier did not advance");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final TestLevel level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
        final TestEntity root = entity(0);
        final TestEntity second = entity(32);
        final TestEntity third = entity(64);
        final Map<UUID, Object> sources;
        final Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");
        final Object previousFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            assertFalse(RegionScheduler.isActive());
            assertNull(field(RegionScheduler.class, "pool").get(null));
            sources = (Map<UUID, Object>) field(NetherPortalTransfers.class, "SOURCES").get(null);
            assertTrue(sources.isEmpty());
            field(Entity.class, "passengers").set(root, ImmutableList.of(second, third));
        }

        private TestEntity entity(int x) throws Exception {
            TestEntity entity = (TestEntity) unsafe.allocateInstance(TestEntity.class);
            entity.guard = new SyncItemPickup.Guard();
            entity.location = new Vec3(x, 64, 0);
            field(Entity.class, "uuid").set(entity, UUID.randomUUID());
            field(Entity.class, "level").set(entity, level);
            field(Entity.class, "passengers").set(entity, ImmutableList.of());
            return entity;
        }

        void capture() throws Exception {
            Constructor<?> constructor = transferType.getDeclaredConstructor(
                    Entity.class, ServerLevel.class, EpochDomain.class, long.class);
            constructor.setAccessible(true);
            Object transfer = constructor.newInstance(root, level, null, 0L);
            assertNull(sources.putIfAbsent(root.getUUID(), transfer));
            invoke(transfer, "captureTree", new Class<?>[]{Entity.class}, root);
        }

        void cancel() throws Exception {
            Object transfer = sources.get(root.getUUID());
            if (transfer != null) invoke(transfer, "cancel", new Class<?>[]{Throwable.class}, (Object) null);
        }

        List<Long> chunks() {
            List<Long> chunks = new ArrayList<>();
            NetherPortalTransfers.forEachPendingSourceChunk(level, chunks::add);
            return chunks;
        }

        private void invoke(Object transfer, String name, Class<?>[] types, Object... arguments) throws Exception {
            Method method = transferType.getDeclaredMethod(name, types);
            method.setAccessible(true);
            try {
                method.invoke(transfer, arguments);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception exception) throw exception;
                if (failure.getCause() instanceof Error error) throw error;
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            try {
                cancel();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                sources.entrySet().removeIf(entry -> entry.getKey().equals(root.getUUID())
                        || entry.getKey().equals(second.getUUID()) || entry.getKey().equals(third.getUUID()));
                field(RegionScheduler.class, "orphanFatalFailure").set(null, previousFailure);
            }
        }
    }

    private static final class TestEntity extends ItemEntity implements ParallelProcessor.TickGuard, SyncItemPickup.Guarded {
        Vec3 location;
        Runnable onPending;
        volatile boolean pending;
        SyncItemPickup.Guard guard;
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        private TestEntity() { super(null, 0, 0, 0, null); }
        long chunk() { return ChunkPos.pack(blockPosition()); }
        @Override public Vec3 position() { return location; }
        @Override public BlockPos blockPosition() { return BlockPos.containing(location); }
        @Override public Vec3 getDeltaMovement() { return Vec3.ZERO; }
        @Override public Pose getPose() { return Pose.STANDING; }
        @Override public boolean fabrolia$isPortalPending() { return pending; }
        @Override public void fabrolia$setPortalPending(boolean value) {
            pending = value;
            if (value && onPending != null) onPending.run();
        }
        @Override public EntityTickLease fabrolia$tickLease() { throw new AssertionError("not ticking"); }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class TestLevel extends ServerLevel {
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public long getGameTime() { return 0L; }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
