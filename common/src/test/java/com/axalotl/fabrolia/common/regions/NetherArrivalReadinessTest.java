package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.network.ConnectionIslandPlayerMixin;
import com.axalotl.fabrolia.common.mixin.world.ServerLevelMixin;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.waypoints.Waypoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises native tick and send wrappers across metadata drain and source retirement. */
@Isolated
class NetherArrivalReadinessTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rootAndPassengerStayUntickedUntilPreviousSourceRetires() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger bodies = new AtomicInteger();
            fixture.capture(fixture.arrival);
            fixture.tickRoot(fixture.arrival, bodies);
            fixture.tickPassenger(fixture.arrival, fixture.rider, bodies);
            fixture.tickPassenger(fixture.rider, fixture.arrival, bodies);
            assertEquals(0, bodies.get(), "unsettled arrival must not run either native body");

            assertTrue(fixture.reservation.deferMetadata(() -> bodies.addAndGet(10)));
            fixture.invokeReservation("drainOutbox");
            fixture.tickRoot(fixture.arrival, bodies);
            fixture.tickPassenger(fixture.arrival, fixture.rider, bodies);
            assertEquals(10, bodies.get(), "draining callbacks alone must not enable simulation");

            field(NetherPortalPlayerAccess.Reservation.class, "releaseSource").set(fixture.reservation,
                    (Runnable) () -> {
                        try {
                            fixture.tickRoot(fixture.arrival, bodies);
                            assertEquals(10, bodies.get(), "source UUID is still reserved inside retirement");
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    });
            fixture.invokeReservation("finish");
            fixture.tickRoot(fixture.arrival, bodies);
            fixture.tickPassenger(fixture.arrival, fixture.rider, bodies);
            assertEquals(12, bodies.get(), "normal root and rider bodies resume after retirement");
        }
    }

    @Test
    void sourceWaypointUpdateMustNotMoveBehindItsDeferredRemoval() throws Exception {
        try (Fixture fixture = new Fixture(); var sender = Executors.newSingleThreadExecutor()) {
            GuardedPlayer player = fixture.player();
            TestConnection connection = new TestConnection();
            List<Packet<?>> sent = new ArrayList<>();
            UUID waypoint = new UUID(0, 1);
            connection.send(ClientboundTrackedWaypointPacket.addWaypointAzimuth(
                    waypoint, new Waypoint.Icon(), 0), sent);
            fixture.capture(player);
            fixture.reservation.deferMetadata(() -> {
                try {
                    connection.send(ClientboundTrackedWaypointPacket.removeWaypoint(waypoint), sent);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            sender.submit(() -> {
                connection.send(ClientboundTrackedWaypointPacket.updateWaypointAzimuth(
                        waypoint, new Waypoint.Icon(), 0.1f), sent);
                return null;
            }).get(5, TimeUnit.SECONDS);
            fixture.drainOutbox();

            ClientWaypointManager client = new ClientWaypointManager();
            for (Packet<?> packet : sent) ((ClientboundTrackedWaypointPacket) packet).apply(client);
            assertFalse(client.hasWaypoints(), "source waypoints must remain removed after the transfer");
        }
    }

    @Test
    void publicationWaitsThroughMetadataDrainAndSourceRetirement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> events = new ArrayList<>();
            fixture.capture(fixture.arrival);
            Runnable published = () -> {
                fixture.assertOutsideCustodyLocks();
                assertFalse(fixture.arrival.lease.isArrivalPending());
                events.add("published");
            };
            Runnable unexpectedCancel = () -> fail("healthy publication was canceled");
            assertTrue(NetherPortalPlayerAccess.deferArrivalPublication(
                    fixture.arrival, publication(published, unexpectedCancel)));
            field(NetherPortalPlayerAccess.Reservation.class, "entered").setBoolean(fixture.reservation, true);
            fixture.reservation.complete(() -> {
                assertEquals(List.of("drained"), events, "no publication may precede source retirement");
                assertTrue(fixture.arrival.lease.isArrivalPending());
                assertTrue(NetherPortalPlayerAccess.deferArrivalPublication(
                        fixture.arrival, publication(published, unexpectedCancel)));
                events.add("retired");
            });
            assertTrue(events.isEmpty(), "an arrival without players or builders must not finish under cohort claims");
            assertTrue(fixture.reservation.deferMetadata(() -> events.add("drained")));
            fixture.drainOutbox();
            assertEquals(List.of("drained"), events);
            assertTrue(NetherPortalPlayerAccess.deferArrivalPublication(
                    fixture.arrival, publication(published, unexpectedCancel)),
                    "closing the builder outbox must not close publication custody");

            fixture.invokeReservation("completeMetadata");
            assertEquals(List.of("drained", "retired", "published", "published", "published"), events);
            assertFalse(NetherPortalPlayerAccess.deferArrivalPublication(
                    fixture.arrival, publication(published, unexpectedCancel)));
            NetherPortalPlayerAccess.drainMetadata();
        }
    }

    @Test
    void cancellationReleasesEveryPublicationOutsideCustodyLocksDespiteCleanupFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.capture(fixture.arrival);
            List<String> events = new ArrayList<>();
            RuntimeException primary = new RuntimeException("arrival failed");
            Runnable unexpectedPublish = () -> fail("canceled arrival was published");
            assertTrue(NetherPortalPlayerAccess.deferArrivalPublication(fixture.arrival,
                    publication(unexpectedPublish, () -> {
                        fixture.assertOutsideCustodyLocks();
                        assertSame(primary, RegionScheduler.workerFailureOrNull());
                        events.add("first released");
                        throw new IllegalStateException("first cleanup failed");
                    })));
            assertTrue(NetherPortalPlayerAccess.deferArrivalPublication(fixture.arrival,
                    publication(unexpectedPublish, () -> {
                        fixture.assertOutsideCustodyLocks();
                        assertSame(primary, RegionScheduler.workerFailureOrNull());
                        events.add("second released");
                    })));

            fixture.withTransfer(false, () -> {
                fixture.reservation.cancel(primary);
                assertTrue(events.isEmpty(), "an active worker still owns the pending arrival");
                var constructor = NetherPortalPlayerAccess.Scope.class.getDeclaredConstructor(
                        NetherPortalPlayerAccess.Reservation.class);
                constructor.setAccessible(true);
                constructor.newInstance(fixture.reservation).close();
                return null;
            });
            assertEquals(List.of("first released", "second released"), events);
            assertFalse(fixture.arrival.lease.isArrivalPending());
            fixture.reservation.cancel(primary);
            assertEquals(List.of("first released", "second released"), events, "cleanup must not release twice");
            assertSame(primary, RegionScheduler.workerFailureOrNull());
        }
    }

    @Test
    void publicationFailureCancelsWholeDetachedBatchAndPreventsRemainingSuccess() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.capture(fixture.arrival);
            List<String> events = new ArrayList<>();
            RuntimeException primary = new RuntimeException("scheduler retained task then rejected offer");
            for (int index = 0; index < 3; index++) {
                int member = index;
                assertTrue(NetherPortalPlayerAccess.deferArrivalPublication(fixture.arrival,
                        publication(() -> {
                            fixture.assertOutsideCustodyLocks();
                            assertFalse(fixture.arrival.lease.isArrivalPending());
                            events.add("publish " + member);
                            if (member == 1) throw primary;
                        }, () -> {
                            fixture.assertOutsideCustodyLocks();
                            assertSame(primary, RegionScheduler.workerFailureOrNull());
                            events.add("cancel " + member);
                            if (member == 0) throw new IllegalStateException("cleanup also failed");
                        })));
            }
            field(NetherPortalPlayerAccess.Reservation.class, "releaseSource").set(
                    fixture.reservation, (Runnable) () -> events.add("retired"));
            fixture.drainOutbox();

            assertSame(primary, assertThrows(RuntimeException.class,
                    () -> fixture.invokeReservation("completeMetadata")));
            assertEquals(List.of("retired", "publish 0", "publish 1", "cancel 0", "cancel 1", "cancel 2"), events);
            fixture.reservation.cancel(primary);
            assertEquals(List.of("retired", "publish 0", "publish 1", "cancel 0", "cancel 1", "cancel 2"), events);
            assertSame(primary, RegionScheduler.workerFailureOrNull());
        }
    }

    private static NetherPortalPlayerAccess.ArrivalPublication publication(Runnable publish, Runnable cancel) {
        return new NetherPortalPlayerAccess.ArrivalPublication() {
            @Override public void publish() { publish.run(); }
            @Override public void cancel() { cancel.run(); }
        };
    }

    private static final class TestConnection extends ConnectionIslandPlayerMixin {
        void send(Packet<?> packet, List<Packet<?>> sent) throws Exception {
            Method method = ConnectionIslandPlayerMixin.class.getDeclaredMethod(
                    "fabrolia$netherOrderedSend", Packet.class,
                    io.netty.channel.ChannelFutureListener.class, boolean.class, Operation.class);
            invoke(method, this, packet, null, true, (Operation<Void>) args -> {
                sent.add((Packet<?>) args[0]);
                return null;
            });
        }
    }

    private static final class GuardedPlayer extends ServerPlayer implements ParallelProcessor.TickGuard {
        EntityTickLease lease;
        private GuardedPlayer() { super(null, null, null, null); }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class GuardedItem extends ItemEntity implements ParallelProcessor.TickGuard, SyncItemPickup.Guarded {
        EntityTickLease lease;
        SyncItemPickup.Guard consumption;
        private GuardedItem() { super(null, null); }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return consumption; }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final Object previousFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final GuardedItem source = entity();
        final GuardedItem arrival = entity();
        final GuardedItem rider = entity();
        final Object transfer;
        final NetherPortalPlayerAccess.Reservation reservation;
        final Object nativeWrapper;
        final Object cameras = field(NetherPortalPlayerAccess.class, "CAMERAS").get(null);

        Fixture() throws Exception {
            Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName()+"$Transfer");
            var constructor = transferType.getDeclaredConstructor(Entity.class, ServerLevel.class, EpochDomain.class, long.class);
            constructor.setAccessible(true);
            transfer = constructor.newInstance(source, null, null, 0L);
            var reserve = NetherPortalPlayerAccess.Reservation.class.getDeclaredConstructor(Entity.class, Object.class);
            reserve.setAccessible(true);
            reservation = reserve.newInstance(source, transfer);
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "ArrivalNativeWrapper", null,
                    ServerLevelMixin.class.getName().replace('.', '/'), null);
            writer.visitEnd();
            class Loader extends ClassLoader {
                Loader() { super(ServerLevelMixin.class.getClassLoader()); }
                Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
            }
            nativeWrapper = unsafe.allocateInstance(new Loader().define(writer.toByteArray()));
        }

        GuardedItem entity() throws Exception {
            GuardedItem entity = (GuardedItem) unsafe.allocateInstance(GuardedItem.class);
            entity.lease = new EntityTickLease();
            entity.consumption = new SyncItemPickup.Guard();
            field(Entity.class, "passengers").set(entity, ImmutableList.of());
            return entity;
        }

        GuardedPlayer player() throws Exception {
            GuardedPlayer player = (GuardedPlayer) unsafe.allocateInstance(GuardedPlayer.class);
            player.lease = new EntityTickLease();
            return player;
        }

        void capture(Entity entity) throws Exception {
            withTransfer(false, () -> {
                NetherPortalPlayerAccess.captureArrivalMetadata(entity);
                return null;
            });
        }

        void drainOutbox() throws Exception {
            withTransfer(true, () -> { invokeReservation("drainOutbox"); return null; });
        }

        @SuppressWarnings("unchecked")
        void withTransfer(boolean metadata, Callable<Void> body) throws Exception {
            ThreadLocal<Object> current = (ThreadLocal<Object>) field(NetherPortalPlayerAccess.class, "CURRENT").get(null);
            ThreadLocal<Object> active = (ThreadLocal<Object>) field(NetherPortalTransfers.class, "ACTIVE").get(null);
            Object oldCurrent = current.get(), oldActive = active.get();
            Class<?> scope = Class.forName(NetherPortalTransfers.class.getName()+"$Scope");
            var constructor = scope.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            boolean oldMetadata = field(NetherPortalPlayerAccess.Reservation.class, "metadata").getBoolean(reservation);
            boolean oldExecuting = field(NetherPortalPlayerAccess.Reservation.class, "active").getBoolean(reservation);
            field(NetherPortalPlayerAccess.Reservation.class, "active").setBoolean(reservation, true);
            field(NetherPortalPlayerAccess.Reservation.class, "metadata").setBoolean(reservation, metadata);
            try {
                current.set(reservation);
                active.set(constructor.newInstance(transfer, null));
                body.call();
            } finally {
                current.set(oldCurrent);
                active.set(oldActive);
                field(NetherPortalPlayerAccess.Reservation.class, "metadata").setBoolean(reservation, oldMetadata);
                field(NetherPortalPlayerAccess.Reservation.class, "active").setBoolean(reservation, oldExecuting);
            }
        }

        void tickRoot(Entity entity, AtomicInteger bodies) throws Exception {
            Method method = ServerLevelMixin.class.getDeclaredMethod("fabrolia$guardTickNonPassenger", Entity.class, Operation.class);
            invoke(method, nativeWrapper, entity, (Operation<Void>) args -> { bodies.incrementAndGet(); return null; });
        }

        void tickPassenger(Entity vehicle, Entity passenger, AtomicInteger bodies) throws Exception {
            Method method = ServerLevelMixin.class.getDeclaredMethod("fabrolia$guardTickPassenger", Entity.class, Entity.class, Operation.class);
            invoke(method, nativeWrapper, vehicle, passenger, (Operation<Void>) args -> { bodies.incrementAndGet(); return null; });
        }

        void assertOutsideCustodyLocks() {
            assertFalse(Thread.holdsLock(reservation), "callback must not hold reservation while entering a tracker");
            assertFalse(Thread.holdsLock(cameras), "callback must not hold camera registry while entering a tracker");
        }

        void invokeReservation(String name) throws Exception {
            invoke(NetherPortalPlayerAccess.Reservation.class.getDeclaredMethod(name), reservation);
        }

        @Override public void close() throws Exception {
            try { reservation.cancel(null); }
            finally { field(RegionScheduler.class, "orphanFatalFailure").set(null, previousFailure); }
        }
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
