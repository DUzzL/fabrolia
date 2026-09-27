package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.server.ChunkMapTrackedEntityMixin;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the production mixin wrapper, pending queue, token storage and MainTrackingTask.
 * Unsafe substitutes world construction; the original Operation records native-body entry
 * and viewer membership, not vanilla range checks, spawn packets or a transformed VMP runtime.
 */
@Isolated
class ArrivalViewerPairingTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void arrivingViewerCannotEnterOriginalWhileReadyViewerAndStatePublicationProceed() throws Exception {
        try (Fixture f = new Fixture()) {
            Arrival arrival = f.arrive(f.viewer);
            f.tracker.updatePlayer(f.viewer);
            assertTrue(f.tracker.nativeEntries.isEmpty());
            assertFalse(f.tracker.fabrolia$hasIslandTrackingViewers());
            f.tracker.updatePlayer(f.ready);
            assertEquals(List.of(f.ready), f.tracker.nativeEntries);
            assertTrue(f.publish(), "viewer readiness must not block the entity state publisher");
            assertEquals(List.of(f.ready.connection), f.tracker.stateRecipients);
            assertTrue(f.tracker.fabrolia$hasPendingIslandPairings());
            arrival.call("drainOutbox");
            f.tracker.updatePlayer(f.viewer);
            assertEquals(List.of(f.ready), f.tracker.nativeEntries,
                    "metadata drain alone does not release source retirement/readiness");
            arrival.settle();
            assertEquals(List.of(f.ready), f.tracker.nativeEntries, "settlement must not pair inline");
            assertEquals(1, f.tasks.size());
            f.tasks.remove().run();
            assertEquals(List.of(f.ready, f.viewer), f.tracker.nativeEntries);
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
            assertTrue(f.tasks.isEmpty());
        }
    }

    @Test
    void mixedBulkAndDetachedPerPlayerCommitsRetainOneBlockedPairing() throws Exception {
        try (Fixture f = new Fixture()) {
            Arrival arrival = f.arrive(f.viewer);
            assertTrue(f.tracker.fabrolia$queueIslandPairing(f.viewer));
            assertTrue(f.tracker.fabrolia$queueIslandBulkPairingRefresh());
            f.tracker.fabrolia$commitIslandPairings();
            assertEquals(List.of(f.ready), f.tracker.nativeEntries);
            assertTrue(f.tracker.fabrolia$hasPendingIslandPairings(),
                    "per-player commit detached its record before the gate; refusal must retain it");
            for (int i = 0; i < 32; i++) {
                f.tracker.updatePlayer(f.viewer);
                f.tracker.fabrolia$commitIslandPairings();
            }
            assertEquals(1, arrival.publications().size(), "one wait per exact tracker/viewer/listener");
            assertTrue(f.tasks.isEmpty());
            arrival.settle();
            assertEquals(1, f.tasks.size());
            f.tasks.remove().run();
            assertEquals(List.of(f.ready, f.viewer), f.tracker.nativeEntries);
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
            assertTrue(f.tasks.isEmpty());
        }
    }

    @Test
    void settlementSharesAnAlreadyQueuedMainTrackingTaskRatherThanOfferingAnother() throws Exception {
        try (Fixture f = new Fixture()) {
            Arrival arrival = f.arrive(f.viewer);
            f.tracker.updatePlayer(f.viewer);
            f.schedule();
            TickTask original = f.tasks.element();
            arrival.settle();
            assertEquals(1, f.tasks.size());
            assertSame(original, f.tasks.remove());
            original.run();
            assertEquals(List.of(f.viewer), f.tracker.nativeEntries);
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
            assertTrue(f.tasks.isEmpty());
        }
    }

    @Test
    void interveningArrivalReceivesTheSameWaitWithoutPrematureTaskOrNativeEntry() throws Exception {
        try (Fixture f = new Fixture()) {
            Arrival first = f.arrive(f.viewer);
            Arrival[] successor = new Arrival[1];
            assertTrue(NetherPortalPlayerAccess.deferArrivalPublication(f.viewer,
                    new NetherPortalPlayerAccess.ArrivalPublication() {
                        @Override public void publish() {
                            try { successor[0] = f.arrive(f.viewer); }
                            catch (Exception failure) { throw new AssertionError(failure); }
                        }
                        @Override public void cancel() {}
                    }));
            f.tracker.updatePlayer(f.viewer);
            var originalWait = first.publications().get(1);
            first.settle();
            assertNotNull(successor[0]);
            assertTrue(f.tasks.isEmpty());
            assertTrue(f.tracker.nativeEntries.isEmpty());
            assertEquals(List.of(originalWait), successor[0].publications());
            successor[0].settle();
            assertEquals(1, f.tasks.size());
            f.tasks.remove().run();
            assertEquals(List.of(f.viewer), f.tracker.nativeEntries);
        }
    }

    @Test
    void canceledOldWaitCannotRemoveReboundListenerWaitOrItsPendingPairing() throws Exception {
        try (Fixture f = new Fixture()) {
            Arrival arrival = f.arrive(f.viewer);
            f.tracker.updatePlayer(f.viewer);
            var oldWait = arrival.publications().getFirst();
            ServerGamePacketListenerImpl oldListener = f.viewer.connection;
            f.viewer.connection = f.listener(f.viewer);
            f.tracker.updatePlayer(f.viewer);
            oldWait.cancel();
            oldWait.publish();
            assertTrue(f.tasks.isEmpty(), "stale listener callback must not publish a successor");
            arrival.settle();
            assertEquals(1, f.tasks.size());
            f.tasks.remove().run();
            assertEquals(List.of(f.viewer), f.tracker.nativeEntries);
            assertFalse(f.tracker.viewers().contains(oldListener));
            assertTrue(f.tracker.viewers().contains(f.viewer.connection));
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
        }
    }

    @Test
    void listenerChangedAfterSettlementCannotConsumeOldPairing() throws Exception {
        try (Fixture f = new Fixture()) {
            Arrival arrival = f.arrive(f.viewer);
            f.tracker.updatePlayer(f.viewer);
            arrival.settle();
            f.viewer.connection = f.listener(f.viewer);
            f.tasks.remove().run();
            assertTrue(f.tracker.nativeEntries.isEmpty());
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
        }
    }

    @Test
    void retiredTrackerRejectsHeldAndAlreadyQueuedArrivalSuccessors() throws Exception {
        try (Fixture f = new Fixture()) {
            Arrival arrival = f.arrive(f.viewer);
            f.tracker.updatePlayer(f.viewer);
            var oldWait = arrival.publications().getFirst();
            f.schedule();
            f.tracker.retire();
            arrival.settle();
            oldWait.publish();
            oldWait.cancel();
            assertEquals(1, f.tasks.size(), "retired callback must not offer another task");
            f.tasks.remove().run();
            f.tracker.updatePlayer(f.viewer);
            assertTrue(f.tracker.nativeEntries.isEmpty());
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
            assertFalse(f.tracker.fabrolia$hasIslandTrackingViewers());
        }
    }

    @Test
    void cancelingArrivalWaitCannotEraseAnAcceptedRemoval() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tracker.updatePlayer(f.viewer);
            Arrival arrival = f.arrive(f.viewer);
            f.tracker.updatePlayer(f.viewer);
            var oldWait = arrival.publications().getFirst();
            assertTrue(f.tracker.fabrolia$queueIslandRemoval(f.viewer));
            oldWait.cancel();
            f.schedule();
            f.tasks.remove().run();
            assertFalse(f.tracker.fabrolia$hasIslandTrackingViewers(),
                    "canceling a REVALIDATE wait must not discard a newer REMOVE");
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
            arrival.settle();
            assertTrue(f.tasks.isEmpty());
            assertEquals(List.of(f.viewer), f.tracker.nativeEntries);
        }
    }

    @Test
    void unseenRemovalCannotInventStatePublicationToExistingViewers() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tracker.updatePlayer(f.ready);
            Arrival arrival = f.arrive(f.viewer);
            f.tracker.updatePlayer(f.viewer);
            assertTrue(f.tracker.fabrolia$queueIslandRemoval(f.viewer));
            arrival.settle();
            while (!f.tasks.isEmpty()) f.tasks.remove().run();
            assertEquals(List.of(f.ready), f.tracker.nativeEntries);
            assertTrue(f.tracker.stateRecipients.isEmpty(),
                    "a withdrawn unseen pairing has no remaining publication obligation");
        }
    }

    private static final class GuardedFrame extends ItemFrame implements ParallelProcessor.TickGuard {
        EntityTickLease lease;
        private GuardedFrame() { super(null, null); }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class GuardedPlayer extends ServerPlayer implements ParallelProcessor.TickGuard {
        EntityTickLease lease;
        private GuardedPlayer() { super(null, null, null, null); }
        @Override public boolean hasDisconnected() { return false; }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class Tracker extends ChunkMapTrackedEntityMixin {
        final List<ServerPlayer> nativeEntries = new ArrayList<>();
        final List<ServerGamePacketListenerImpl> stateRecipients = new ArrayList<>();

        Tracker(Entity entity) throws Exception {
            this.entity = entity;
            invoke(ChunkMapTrackedEntityMixin.class.getDeclaredMethod(
                    "fabrolia$initializeIslandTrackingState", CallbackInfo.class), this,
                    new CallbackInfo("<init>", false));
        }

        @Override public void updatePlayer(ServerPlayer player) {
            try {
                invoke(ChunkMapTrackedEntityMixin.class.getDeclaredMethod(
                        "updatePlayer", ServerPlayer.class, Operation.class), this, player,
                        (Operation<Void>) args -> {
                            ServerPlayer viewer = (ServerPlayer) args[0];
                            nativeEntries.add(viewer);
                            viewers().add(viewer.connection);
                            return null;
                        });
            } catch (Exception failure) { throw new AssertionError(failure); }
        }

        @Override public void updatePlayers(List<ServerPlayer> players) {
            for (ServerPlayer player : players) updatePlayer(player);
        }

        @Override public void removePlayer(ServerPlayer player) {
            viewers().remove(player.connection);
        }

        @Override public synchronized boolean fabrolia$sendIslandChanges() {
            stateRecipients.addAll(viewers());
            fabrolia$markIslandTrackingPublished();
            return true;
        }

        @SuppressWarnings("unchecked")
        Set<ServerGamePacketListenerImpl> viewers() {
            try { return (Set<ServerGamePacketListenerImpl>) field(
                    ChunkMapTrackedEntityMixin.class, "seenBy").get(this); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }

        void retire() throws Exception {
            invoke(ChunkMapTrackedEntityMixin.class.getDeclaredMethod(
                    "fabrolia$broadcastRemoved", Operation.class), this,
                    (Operation<Void>) ignored -> null);
        }
    }

    private static final class Arrival {
        final GuardedPlayer player;
        final Object transfer;
        final NetherPortalPlayerAccess.Reservation reservation;

        Arrival(GuardedPlayer player, ServerLevel level) throws Exception {
            this.player = player;
            Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");
            var transferConstructor = transferType.getDeclaredConstructor(
                    Entity.class, ServerLevel.class, EpochDomain.class, long.class);
            transferConstructor.setAccessible(true);
            transfer = transferConstructor.newInstance(player, level, null, 0L);
            var constructor = NetherPortalPlayerAccess.Reservation.class.getDeclaredConstructor(Entity.class, Object.class);
            constructor.setAccessible(true);
            reservation = constructor.newInstance(player, transfer);
        }

        @SuppressWarnings("unchecked")
        void capture() throws Exception {
            ThreadLocal<Object> current = (ThreadLocal<Object>) field(NetherPortalPlayerAccess.class, "CURRENT").get(null);
            ThreadLocal<Object> active = (ThreadLocal<Object>) field(NetherPortalTransfers.class, "ACTIVE").get(null);
            Object oldCurrent = current.get(), oldActive = active.get();
            Class<?> scope = Class.forName(NetherPortalTransfers.class.getName() + "$Scope");
            var constructor = scope.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            try {
                current.set(reservation);
                active.set(constructor.newInstance(transfer, null));
                field(NetherPortalPlayerAccess.Reservation.class, "active").setBoolean(reservation, true);
                NetherPortalPlayerAccess.captureArrivalMetadata(player);
            } finally {
                current.set(oldCurrent);
                active.set(oldActive);
                field(NetherPortalPlayerAccess.Reservation.class, "active").setBoolean(reservation, false);
            }
        }

        @SuppressWarnings("unchecked")
        List<NetherPortalPlayerAccess.ArrivalPublication> publications() throws Exception {
            return (List<NetherPortalPlayerAccess.ArrivalPublication>) field(
                    NetherPortalPlayerAccess.Reservation.class, "arrivalPublications").get(reservation);
        }

        void call(String name) throws Exception {
            invoke(NetherPortalPlayerAccess.Reservation.class.getDeclaredMethod(name), reservation);
        }

        void settle() throws Exception {
            field(NetherPortalPlayerAccess.Reservation.class, "releaseSource").set(reservation,
                    (Runnable) () -> assertTrue(player.lease.isArrivalPending()));
            call("completeMetadata");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final Object priorPool = field(RegionScheduler.class, "pool").get(null);
        final Object priorActive = field(RegionScheduler.class, "active").get(null);
        final Object priorServer = field(RegionScheduler.class, "server").get(null);
        final Object priorFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final ConcurrentLinkedQueue<TickTask> tasks = new ConcurrentLinkedQueue<>();
        final List<Arrival> arrivals = new ArrayList<>();
        final MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
        final ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        final GuardedFrame entity = (GuardedFrame) unsafe.allocateInstance(GuardedFrame.class);
        final GuardedPlayer viewer;
        final GuardedPlayer ready;
        final Tracker tracker;

        Fixture() throws Exception {
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(MinecraftServer.class, "levels").set(server, Map.of());
            field(BlockableEventLoop.class, "pendingRunnables").set(server, tasks);
            field(ServerLevel.class, "server").set(level, server);
            field(Entity.class, "level").set(entity, level);
            field(Entity.class, "passengers").set(entity, ImmutableList.of());
            entity.lease = new EntityTickLease();
            viewer = player();
            ready = player();
            field(ServerLevel.class, "players").set(level, List.of(viewer, ready));
            field(RegionScheduler.class, "pool").set(null, null);
            field(RegionScheduler.class, "active").set(null, false);
            field(RegionScheduler.class, "server").set(null, server);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            tracker = new Tracker(entity);
        }

        GuardedPlayer player() throws Exception {
            GuardedPlayer player = (GuardedPlayer) unsafe.allocateInstance(GuardedPlayer.class);
            field(Entity.class, "level").set(player, level);
            field(Entity.class, "passengers").set(player, ImmutableList.of());
            player.lease = new EntityTickLease();
            player.connection = listener(player);
            return player;
        }

        ServerGamePacketListenerImpl listener(ServerPlayer player) throws Exception {
            var listener = (ServerGamePacketListenerImpl) unsafe.allocateInstance(ServerGamePacketListenerImpl.class);
            listener.player = player;
            return listener;
        }

        Arrival arrive(GuardedPlayer player) throws Exception {
            Arrival arrival = new Arrival(player, level);
            arrivals.add(arrival);
            arrival.capture();
            return arrival;
        }

        void schedule() throws Exception {
            synchronized (tracker) {
                invoke(IslandEntityTracking.class.getDeclaredMethod(
                        "scheduleMainPublication", IslandTrackedEntityAccess.class), null, tracker);
            }
        }

        boolean publish() throws Exception {
            synchronized (tracker) {
                return (boolean) invoke(IslandEntityTracking.class.getDeclaredMethod(
                        "publishAndPairUnderTrackerLock", IslandTrackedEntityAccess.class), null, tracker);
            }
        }

        @Override public void close() throws Exception {
            try {
                tracker.retire();
                for (Arrival arrival : arrivals) arrival.reservation.cancel(null);
            } finally {
                field(RegionScheduler.class, "pool").set(null, priorPool);
                field(RegionScheduler.class, "active").set(null, priorActive);
                field(RegionScheduler.class, "server").set(null, priorServer);
                field(RegionScheduler.class, "orphanFatalFailure").set(null, priorFailure);
            }
        }
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapper) {
            if (wrapper.getCause() instanceof Exception failure) throw failure;
            if (wrapper.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(wrapper.getCause());
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
