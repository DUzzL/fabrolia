package com.axalotl.fabrolia.common.regions;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production disconnect producer, ordinal admission and queued teardown. */
@Isolated
@Timeout(20)
class AsyncIslandPendingDisconnectTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void deferredOwnerDisconnectWaitsForChannelCloseBeforePublishingItsTerminalReceipt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel();
            try {
                set(Connection.class, connection, "channel", channel);
                set(Connection.class, connection, "packetListener", fixture.listener);
                fixture.listener.physicalDisconnect = () ->
                        AsyncIslandPlayerTicks.runPhysicalMainDisconnect(
                                fixture.listener, fixture.server,
                                () -> fixture.bodies.add("deferred-disconnect"));

                // Vanilla queues this exact callable after sending the disconnect
                // packet; its send-completion callback may not have closed yet.
                fixture.deferFromLate(true, connection::handleDisconnection);
                assertTrue(fixture.state.mainDisconnectPending);
                fixture.pumpMain();

                assertTrue(connection.isConnected());
                assertEquals(List.of(), fixture.bodies);
                assertTrue(fixture.state.mainDisconnectPending,
                        "an open channel has not delivered onDisconnect yet");
                assertNotEquals(IslandPlayerTurnSession.Phase.DISCONNECTED,
                        fixture.state.session.view().phase());
                assertTrue(fixture.consumeOrdinal(),
                        "the pending disconnect must still exclude another player body");

                // The packet-send callback closes the channel; the next ordinary
                // connection-shell pass delivers onDisconnect on physical Main.
                connection.disconnect(new DisconnectionDetails(Component.literal("Timed out")));
                connection.handleDisconnection();
                assertEquals(List.of("deferred-disconnect"), fixture.bodies);
                assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED,
                        fixture.state.session.view().phase());
                assertSame(fixture.player, fixture.state.terminalDisconnectPlayer);
                assertSame(fixture.listener, fixture.state.terminalDisconnectListener);
                assertFalse(fixture.state.mainDisconnectPending);
                assertNull(fixture.state.mainDisconnectOrigin);
                assertTrue(fixture.consumeOrdinal(),
                        "only the exact clean terminal receipt may consume a stale ordinal");

                fixture.pumpMain();
                connection.handleDisconnection();
                assertEquals(List.of("deferred-disconnect"), fixture.bodies,
                        "neither a stale owner task nor another connection pass may repeat teardown");
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void channelClosingAfterTheDeferredAttemptStillWaitsForThePhysicalCallback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel();
            try {
                set(Connection.class, connection, "channel", channel);
                set(Connection.class, connection, "packetListener", fixture.listener);
                fixture.listener.physicalDisconnect = () ->
                        AsyncIslandPlayerTicks.runPhysicalMainDisconnect(
                                fixture.listener, fixture.server,
                                () -> fixture.bodies.add("deferred-disconnect"));
                fixture.deferFromLate(true, () -> {
                    connection.handleDisconnection();
                    // The send-completion callback may close after vanilla's
                    // open-channel return but before Fabrolia checks its receipt.
                    connection.disconnect(new DisconnectionDetails(Component.literal("Timed out")));
                });
                fixture.pumpMain();

                assertFalse(connection.isConnected());
                assertTrue(fixture.state.mainDisconnectPending);
                assertEquals(List.of(), fixture.bodies);
                assertTrue(fixture.consumeOrdinal());

                connection.handleDisconnection();
                assertEquals(List.of("deferred-disconnect"), fixture.bodies);
                assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED,
                        fixture.state.session.view().phase());
                assertSame(fixture.player, fixture.state.terminalDisconnectPlayer);
                assertSame(fixture.listener, fixture.state.terminalDisconnectListener);
                assertFalse(fixture.state.mainDisconnectPending);
                assertNull(fixture.state.mainDisconnectOrigin);
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void failedDeferredAttemptPreservesItsFailureAndReleasesItsOwnFence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            IllegalStateException failure = new IllegalStateException("disconnect attempt failed");
            fixture.deferFromLate(true, () -> { throw failure; });
            assertFalse(IslandCommands.fenceWindowHeld());

            assertSame(failure, assertThrows(IllegalStateException.class, fixture::pumpMain));

            assertFalse(IslandCommands.fenceWindowHeld());
            assertTrue(fixture.state.mainDisconnectPending);
            assertNotNull(fixture.state.mainDisconnectOrigin);
            assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY,
                    fixture.state.session.view().phase());
            assertTrue(fixture.consumeOrdinal(),
                    "the valid pending receipt must not permit a later entity body");
            assertEquals(List.of(), fixture.bodies);
        }
    }

    @Test
    void failedDeferredAttemptCannotReleaseItsCallersFence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            IllegalStateException failure = new IllegalStateException("disconnect attempt failed");
            fixture.deferFromLate(true, () -> { throw failure; });
            assertTrue(IslandCommands.quiesceAllExcept(null));
            try {
                assertSame(failure, assertThrows(IllegalStateException.class, fixture::pumpMain));
                assertTrue(IslandCommands.fenceWindowHeld(),
                        "only the caller may release the borrowed outer fence");
                assertTrue(fixture.state.mainDisconnectPending);
                assertTrue(fixture.consumeOrdinal());
                assertEquals(List.of(), fixture.bodies);
            } finally {
                assertSame(failure, IslandCommands.releaseAllExcept(failure));
            }
            assertFalse(IslandCommands.fenceWindowHeld());
        }
    }

    @Test
    void completedLateDisconnectConsumesLaterOrdinalsWithoutReplayingThePlayer() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.deferFromLate(true);
            assertTrue(fixture.consumeOrdinal());
            assertTrue(fixture.consumeOrdinal());
            assertEquals(List.of(), fixture.bodies);
            fixture.pumpMain();
            assertEquals(List.of("deferred-disconnect"), fixture.bodies);
            assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED,
                    fixture.state.session.view().phase());
            assertTrue(fixture.consumeOrdinal());
            fixture.pumpMain();
            assertEquals(List.of("deferred-disconnect"), fixture.bodies);
        }
    }

    @Test
    void packetDisconnectPreservesItsReservedSuffixUntilTheQueuedTeardown() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.state.session.enqueuePacket(fixture.owner,
                    new AsyncIslandPlayerTicks.InboundPacket(
                            new ServerboundKeepAlivePacket(1L), fixture.listener)).orElseThrow();
            fixture.state.session.enqueuePacket(fixture.owner,
                    new AsyncIslandPlayerTicks.InboundPacket(
                            new ServerboundKeepAlivePacket(2L), fixture.listener)).orElseThrow();
            var permit = fixture.state.session.tryReserveEarly(fixture.owner, 1L).orElseThrow();
            var packets = fixture.state.session.takeIslandHeadPackets(permit).orElseThrow();
            assertTrue(fixture.state.session.enterIslandPacketBody(permit));
            fixture.defer(permit, false);
            assertTrue(fixture.state.session.restoreIslandHeadSuffix(permit, packets.subList(1, 2)));
            var before = fixture.state.session.view();
            assertTrue(fixture.consumeOrdinal());
            assertEquals(before, fixture.state.session.view(),
                    "consuming a later ordinal must not complete or replace the reserved turn");
            fixture.pumpMain();
            assertEquals(List.of("deferred-disconnect"), fixture.bodies);
            assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED,
                    fixture.state.session.view().phase());
        }
    }

    @Test
    void pendingDisconnectDoesNotAuthorizeAnOverlappingEnteredEntityBody() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.deferFromLate(false);
            assertThrows(IllegalStateException.class, fixture::consumeOrdinal);
            assertEquals(IslandPlayerTurnSession.Phase.LATE_RUNNING,
                    fixture.state.session.view().phase());
            assertEquals(List.of(), fixture.bodies);
        }
    }

    @Test
    void pendingDisconnectDoesNotAuthorizeAReplacementPlayerOnTheSameListener() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.deferFromLate(true);
            TestPlayer replacement = fixture.allocate(TestPlayer.class);
            replacement.localState = new AsyncIslandPlayerTicks.PlayerState();
            replacement.connection = fixture.listener;
            fixture.listener.player = replacement;
            assertThrows(IllegalStateException.class, () -> invoke("markMainFallback",
                    new Class<?>[]{ServerPlayer.class, boolean.class, String.class},
                    replacement, true, "test"));
            assertEquals(List.of(), fixture.bodies);
        }
    }

    @Test
    void pendingFlagWithoutAnOwnedDisconnectCannotConsumeAnOrdinal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.state.mainDisconnectPending = true;
            assertThrows(IllegalStateException.class, fixture::consumeOrdinal);
            assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY,
                    fixture.state.session.view().phase());
        }
    }

    @Test
    void physicalDisconnectWinningFirstDoesNotRunTheDeferredBodyAgain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.deferFromLate(true);
            AsyncIslandPlayerTicks.runPhysicalMainDisconnect(fixture.listener, fixture.server,
                    () -> fixture.bodies.add("physical-disconnect"));
            fixture.pumpMain();
            assertEquals(List.of("physical-disconnect"), fixture.bodies);
            assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED,
                    fixture.state.session.view().phase());
        }
    }

    @Test
    void physicalDisconnectLeavesItsExistingHandoffAsTheOnlyCloser() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var handoff = fixture.state.session.beginMainHandoff(fixture.owner).orElseThrow();
            AsyncIslandPlayerTicks.runPhysicalMainDisconnect(fixture.listener, fixture.server,
                    () -> fixture.bodies.add("physical-disconnect"));
            assertTrue(fixture.consumeOrdinal());
            fixture.state.session.completeMainHandoff(handoff).orElseThrow();
            assertTrue(fixture.consumeOrdinal());
            assertTrue(fixture.state.session.finishMainHandoff(handoff));
            invoke("finishPhysicalMainDisconnect",
                    new Class<?>[]{AsyncIslandPlayerTicks.PlayerState.class,
                            ServerGamePacketListenerImpl.class}, fixture.state, fixture.listener);
            assertEquals(List.of("physical-disconnect"), fixture.bodies);
            assertTrue(fixture.consumeOrdinal());
            assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED,
                    fixture.state.session.view().phase());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe;
        final AsyncIslandPlayerTicks.PlayerState state = new AsyncIslandPlayerTicks.PlayerState();
        final IslandRegistry.PIsland island = new IslandRegistry.PIsland(701L, 0L);
        final TestPlayer player;
        final TestListener listener;
        final MinecraftServer server;
        final ServerLevel level;
        final RegionizedServer runtime;
        final IslandPlayerTurnSession.OwnerStamp owner;
        final Queue<TickTask> tasks = new ConcurrentLinkedQueue<>();
        final List<String> bodies = new ArrayList<>();
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousActive;
        final Object previousEnabled;
        final Object previousPool;
        final Object previousFailure;
        final ThreadLocal<Object> turn;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            player = allocate(TestPlayer.class);
            listener = allocate(TestListener.class);
            server = allocate(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            level = allocate(ServerLevel.class);
            runtime = allocate(RegionizedServer.class);
            runtime.asyncActive = true;
            set(RegionizedServer.class, runtime, "islandRegistry", new IslandRegistry());
            set(RegionizedServer.class, runtime, "level", level);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            set(BlockableEventLoop.class, server, "pendingRunnables", tasks);
            player.localState = state;
            player.connection = listener;
            listener.player = player;
            listener.binding = new AtomicReference<>(state);
            state.listenerGate = listener;
            owner = new IslandPlayerTurnSession.OwnerStamp(player, listener,
                    island, island.id, island.gen, new EpochDomain(), 0L, 1L,
                    IslandPlayerTurnSession.PlayerKind.REAL);
            assertTrue(state.session.attach(owner));
            turn = (ThreadLocal<Object>) get(AsyncIslandPlayerTicks.class, null, "TURN");
            assertNull(turn.get());
            levels = (Map<ServerLevel, RegionizedServer>) get(
                    RegionScheduler.class, null, "PER_LEVEL");
            previousActive = get(RegionScheduler.class, null, "active");
            previousEnabled = get(RegionalPlayerEntityTicksV2.class, null, "enabled");
            previousPool = get(RegionScheduler.class, null, "pool");
            previousFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
            levels.put(level, runtime);
            set(RegionScheduler.class, null, "active", true);
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", true);
            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
        }

        <T> T allocate(Class<T> type) throws InstantiationException {
            return type.cast(unsafe.allocateInstance(type));
        }

        void deferFromLate(boolean complete) throws Exception {
            deferFromLate(complete, () ->
                    AsyncIslandPlayerTicks.runPhysicalMainDisconnect(listener, server,
                            () -> bodies.add("deferred-disconnect")));
        }

        void deferFromLate(boolean complete, Runnable disconnect) throws Exception {
            var permit = state.session.tryReserveEarly(owner, 1L).orElseThrow();
            assertTrue(state.session.enterIslandBody(permit));
            assertTrue(state.session.completeRealEarly(permit));
            assertTrue(state.session.beginRealLate(permit));
            defer(permit, true, disconnect);
            if (complete) assertTrue(state.session.completeRealLate(permit));
        }

        void defer(IslandPlayerTurnSession.EarlyPermit<AsyncIslandPlayerTicks.InboundPacket> permit,
                   boolean late) throws Exception {
            defer(permit, late, () ->
                    AsyncIslandPlayerTicks.runPhysicalMainDisconnect(listener, server,
                            () -> bodies.add("deferred-disconnect")));
        }

        void defer(IslandPlayerTurnSession.EarlyPermit<AsyncIslandPlayerTicks.InboundPacket> permit,
                   boolean late, Runnable disconnect) throws Exception {
            Class<?> contextType = Class.forName(AsyncIslandPlayerTicks.class.getName() + "$TurnContext");
            Constructor<?> constructor = contextType.getDeclaredConstructor(
                    AsyncIslandPlayerTicks.PlayerState.class, ServerPlayer.class,
                    IslandRegistry.PIsland.class, IslandPlayerTurnSession.OwnerStamp.class,
                    IslandPlayerTurnSession.EarlyPermit.class, boolean.class);
            constructor.setAccessible(true);
            turn.set(constructor.newInstance(state, player, island, owner, permit, late));
            try {
                assertTrue(AsyncIslandPlayerTicks.deferBlockingDisconnect(listener, server, disconnect));
            } finally {
                turn.remove();
            }
        }

        boolean consumeOrdinal() throws Exception {
            return (boolean) invoke("markMainFallback",
                    new Class<?>[]{ServerPlayer.class, boolean.class, String.class},
                    player, true, "test");
        }

        void pumpMain() {
            for (TickTask task; (task = tasks.poll()) != null;) task.run();
        }

        @Override
        public void close() throws Exception {
            try {
                IslandCommands.releaseAllExcept();
            } finally {
                turn.remove();
                tasks.clear();
                levels.remove(level, runtime);
                set(RegionScheduler.class, null, "pool", previousPool);
                set(RegionScheduler.class, null, "orphanFatalFailure", previousFailure);
                set(RegionScheduler.class, null, "active", previousActive);
                set(RegionalPlayerEntityTicksV2.class, null, "enabled", previousEnabled);
            }
        }
    }

    // Mixin storage and the vanilla teardown body are substituted; production
    // coordinator methods and session transitions run unchanged.
    // Unsafe avoids constructing an unrelated game world.
    private static final class TestPlayer extends ServerPlayer implements AsyncIslandPlayerAccess {
        AsyncIslandPlayerTicks.PlayerState localState;

        private TestPlayer() { super(null, null, null, null); }

        @Override
        public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return localState; }
    }

    private static final class TestListener extends ServerGamePacketListenerImpl
            implements AsyncIslandListenerAccess {
        AtomicReference<AsyncIslandPlayerTicks.PlayerState> binding;
        Runnable physicalDisconnect;

        private TestListener() { super(null, null, null, null); }

        @Override
        public void onDisconnect(DisconnectionDetails details) {
            physicalDisconnect.run();
        }

        @Override
        public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return binding.get(); }

        @Override
        public boolean fabrolia$bindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            return binding.compareAndSet(null, expected);
        }

        @Override
        public boolean fabrolia$unbindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            return binding.compareAndSet(expected, null);
        }
    }

    private static Object invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = AsyncIslandPlayerTicks.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new AssertionError(failure.getCause());
        }
    }

    private static Object get(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
