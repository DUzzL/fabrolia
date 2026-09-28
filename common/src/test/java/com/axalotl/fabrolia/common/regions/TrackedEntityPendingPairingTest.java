package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.server.ChunkMapTrackedEntityMixin;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Commit order and per-identity coalescing of the tracker's staged viewer
 * obligations. Unsafe-allocated players all carry entity id 0, so any
 * Entity.equals-keyed structure would silently merge distinct viewers.
 */
@Isolated
class TrackedEntityPendingPairingTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void removalCoalescesUnseenAddWithoutDisturbingOtherViewersOrder() throws Exception {
        try (Fixture f = new Fixture()) {
            GuardedPlayer a = f.player(), b = f.player(), c = f.player();
            assertTrue(f.tracker.fabrolia$queueIslandPairing(a));
            assertTrue(f.tracker.fabrolia$queueIslandPairing(b));
            assertTrue(f.tracker.fabrolia$queueIslandPairing(c));
            assertTrue(f.tracker.fabrolia$queueIslandRemoval(b),
                    "an unseen viewer's removal is accepted as a withdrawal");
            assertTrue(f.tracker.fabrolia$hasPendingIslandPairings());
            f.tracker.fabrolia$commitIslandPairings();
            assertEquals(List.of(a, c), f.tracker.paired);
            assertTrue(f.tracker.removed.isEmpty(), "nothing to remove for a never-paired viewer");
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
        }
    }

    @Test
    void seenViewerRemovalMovesToTailWhileIntentChangeKeepsPosition() throws Exception {
        try (Fixture f = new Fixture()) {
            GuardedPlayer a = f.player(), b = f.player(), c = f.player();
            f.tracker.viewers().add(a.connection);
            assertTrue(f.tracker.fabrolia$queueIslandPairing(a));
            assertTrue(f.tracker.fabrolia$queueIslandPairing(b));
            assertTrue(f.tracker.fabrolia$queueIslandPairing(c));
            // b becomes visible before its ADD commits: REVALIDATE replaces in place.
            f.tracker.viewers().add(b.connection);
            assertTrue(f.tracker.fabrolia$queueIslandPairing(b));
            // a's desired-ABSENT supersedes its REVALIDATE and is ordered last.
            assertTrue(f.tracker.fabrolia$queueIslandRemoval(a));
            f.tracker.fabrolia$commitIslandPairings();
            assertEquals(List.of(b, c), f.tracker.paired);
            assertEquals(List.of(a), f.tracker.removed);
            assertFalse(f.tracker.viewers().contains(a.connection));
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
        }
    }

    @Test
    void cancelledPairingLeavesNoObligation() throws Exception {
        try (Fixture f = new Fixture()) {
            GuardedPlayer a = f.player(), b = f.player();
            assertTrue(f.tracker.fabrolia$queueIslandPairing(a));
            assertTrue(f.tracker.fabrolia$queueIslandPairing(b));
            f.tracker.fabrolia$cancelIslandPairing(a);
            assertTrue(f.tracker.fabrolia$hasPendingIslandPairings());
            f.tracker.fabrolia$commitIslandPairings();
            assertEquals(List.of(b), f.tracker.paired);
        }
    }

    @Test
    void respawnedSuccessorSharingListenerAndIdDoesNotReplacePredecessorRemoval() throws Exception {
        try (Fixture f = new Fixture()) {
            GuardedPlayer old = f.player();
            old.setId(7);
            f.tracker.viewers().add(old.connection);
            assertTrue(f.tracker.fabrolia$queueIslandRemoval(old));
            // Vanilla respawn: new ServerPlayer instance, same listener, same entity id.
            GuardedPlayer successor = f.player();
            successor.setId(7);
            successor.connection = old.connection;
            successor.connection.player = successor;
            assertEquals(old, successor, "Entity.equals conflates the respawn pair");
            assertTrue(f.tracker.fabrolia$queueIslandPairing(successor));
            f.tracker.fabrolia$commitIslandPairings();
            assertEquals(List.of(old), f.tracker.removed,
                    "the predecessor's desired-ABSENT must survive the successor's staging");
            assertEquals(List.of(successor), f.tracker.paired);
            assertTrue(f.tracker.viewers().contains(successor.connection));
        }
    }

    @Test
    void retiredTrackerRefusesAndDropsStagedWork() throws Exception {
        try (Fixture f = new Fixture()) {
            GuardedPlayer a = f.player();
            assertTrue(f.tracker.fabrolia$queueIslandPairing(a));
            f.tracker.retire();
            assertFalse(f.tracker.fabrolia$hasPendingIslandPairings());
            assertFalse(f.tracker.fabrolia$queueIslandPairing(a));
            assertFalse(f.tracker.fabrolia$queueIslandRemoval(a));
            f.tracker.fabrolia$commitIslandPairings();
            assertTrue(f.tracker.paired.isEmpty());
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

    /** The transformed bodies only record membership; commit order is the subject. */
    private static final class Tracker extends ChunkMapTrackedEntityMixin {
        final List<ServerPlayer> paired = new ArrayList<>();
        final List<ServerPlayer> removed = new ArrayList<>();
        boolean retired;

        Tracker(Entity entity) throws Exception {
            this.entity = entity;
            invoke(ChunkMapTrackedEntityMixin.class.getDeclaredMethod(
                    "fabrolia$initializeIslandTrackingState", CallbackInfo.class), this,
                    new CallbackInfo("<init>", false));
        }

        @Override public void updatePlayer(ServerPlayer player) {
            paired.add(player);
            viewers().add(player.connection);
        }

        @Override public void updatePlayers(List<ServerPlayer> players) {
            for (ServerPlayer player : players) updatePlayer(player);
        }

        @Override public void removePlayer(ServerPlayer player) {
            removed.add(player);
            viewers().remove(player.connection);
        }

        @SuppressWarnings("unchecked")
        Set<ServerGamePacketListenerImpl> viewers() {
            try { return (Set<ServerGamePacketListenerImpl>) field(
                    ChunkMapTrackedEntityMixin.class, "seenBy").get(this); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }

        void retire() throws Exception {
            if (retired) return;
            retired = true;
            invoke(ChunkMapTrackedEntityMixin.class.getDeclaredMethod(
                    "fabrolia$broadcastRemoved",
                    com.llamalad7.mixinextras.injector.wrapoperation.Operation.class), this,
                    (com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void>) ignored -> null);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        final Object priorPool = field(RegionScheduler.class, "pool").get(null);
        final Object priorActive = field(RegionScheduler.class, "active").get(null);
        final Object priorServer = field(RegionScheduler.class, "server").get(null);
        final Object priorFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(TestDedicatedServer.class);
        final ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        final GuardedFrame entity = (GuardedFrame) unsafe.allocateInstance(GuardedFrame.class);
        final Tracker tracker;

        Fixture() throws Exception {
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(MinecraftServer.class, "levels").set(server, Map.of());
            field(ServerLevel.class, "server").set(level, server);
            field(Entity.class, "level").set(entity, level);
            field(Entity.class, "passengers").set(entity, ImmutableList.of());
            entity.lease = new EntityTickLease();
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
            var listener = (ServerGamePacketListenerImpl) unsafe.allocateInstance(ServerGamePacketListenerImpl.class);
            listener.player = player;
            player.connection = listener;
            return player;
        }

        @Override public void close() throws Exception {
            try {
                tracker.retire();
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
