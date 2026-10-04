package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.SharedConstants;
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
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player ownership follows the A5 turn session, not geometry: physical Main owns a
 * player only while no island session holds it (or Main holds that island's claim or
 * the fence); the player's own turn owns it; foreign threads never do.
 */
@Isolated
@Timeout(20)
final class PlayerOwnershipTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ownershipFollowsTheIslandSessionAndTheExactTurn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(IslandCommands.requiresMainFence());
            assertTrue(PlayerOwnership.currentThreadOwns(fixture.player),
                    "no island session holds the player: physical Main owns it");
            assertFalse(fixture.onForeignThread(),
                    "a thread that is neither Main nor the player's island never owns it");

            assertTrue(fixture.state.session.attach(fixture.owner));
            assertFalse(PlayerOwnership.currentThreadOwns(fixture.player),
                    "Main without the attached island's claim must not mutate an island player");
            assertTrue(fixture.inOwnTurn(), "the player's own A5 turn owns it");
            assertFalse(fixture.onForeignThread());

            fixture.holdAllClaims();
            assertTrue(PlayerOwnership.currentThreadOwns(fixture.player),
                    "under the held fence Main owns every player");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final sun.misc.Unsafe unsafe;
        final AsyncIslandPlayerTicks.PlayerState state = new AsyncIslandPlayerTicks.PlayerState();
        final IslandRegistry.PIsland island = new IslandRegistry.PIsland(702L, 0L);
        final TestPlayer player;
        final TestListener listener;
        final MinecraftServer server;
        final ServerLevel level;
        final RegionizedServer runtime;
        final IslandPlayerTurnSession.OwnerStamp owner;
        final Queue<TickTask> tasks = new ConcurrentLinkedQueue<>();
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousServer;
        final Object previousPool;
        final Object previousFailure;
        final Object previousFence;
        final ThreadLocal<Object> turn;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            player = (TestPlayer) unsafe.allocateInstance(TestPlayer.class);
            listener = (TestListener) unsafe.allocateInstance(TestListener.class);
            server = (MinecraftServer) unsafe.allocateInstance(TestDedicatedServer.class);
            level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            runtime.asyncActive = true;
            set(RegionizedServer.class, runtime, "islandRegistry", new IslandRegistry());
            set(RegionizedServer.class, runtime, "level", level);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            set(BlockableEventLoop.class, server, "pendingRunnables", tasks);
            player.localState = state;
            player.connection = listener;
            listener.player = player;
            listener.binding = new AtomicReference<>(state);
            owner = new IslandPlayerTurnSession.OwnerStamp(player, listener,
                    island, island.id, island.gen, new EpochDomain(), 0L, 1L,
                    IslandPlayerTurnSession.PlayerKind.REAL);
            turn = (ThreadLocal<Object>) get(AsyncIslandPlayerTicks.class, null, "TURN");
            assertNull(turn.get());
            levels = (Map<ServerLevel, RegionizedServer>) get(RegionScheduler.class, null, "PER_LEVEL");
            previousServer = get(ParallelProcessor.class, null, "server");
            previousPool = get(RegionScheduler.class, null, "pool");
            previousFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
            previousFence = get(IslandCommands.class, null, "ACTIVE_FENCE");
            levels.put(level, runtime);
            set(ParallelProcessor.class, null, "server", server);
            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            set(IslandCommands.class, null, "ACTIVE_FENCE", null);
        }

        boolean onForeignThread() throws InterruptedException {
            AtomicBoolean owns = new AtomicBoolean(true);
            Thread foreign = new Thread(() -> owns.set(PlayerOwnership.currentThreadOwns(player)),
                    "foreign-producer");
            foreign.start();
            foreign.join();
            return owns.get();
        }

        boolean inOwnTurn() throws Exception {
            var permit = state.session.tryReserveEarly(owner, 1L).orElseThrow();
            Class<?> contextType = Class.forName(AsyncIslandPlayerTicks.class.getName() + "$TurnContext");
            Constructor<?> constructor = contextType.getDeclaredConstructor(
                    AsyncIslandPlayerTicks.PlayerState.class, ServerPlayer.class,
                    IslandRegistry.PIsland.class, IslandPlayerTurnSession.OwnerStamp.class,
                    IslandPlayerTurnSession.EarlyPermit.class, boolean.class);
            constructor.setAccessible(true);
            turn.set(constructor.newInstance(state, player, island, owner, permit, false));
            try {
                return PlayerOwnership.currentThreadOwns(player);
            } finally {
                turn.remove();
            }
        }

        void holdAllClaims() throws Exception {
            Class<?> ownership = Class.forName(RegionizedServer.class.getName() + "$MainAllClaimsOwnership");
            Constructor<?> constructor = ownership.getDeclaredConstructor(Object.class, Thread.class, boolean.class);
            constructor.setAccessible(true);
            set(RegionizedServer.class, runtime, "mainAllClaimsOwnership",
                    constructor.newInstance(new Object(), Thread.currentThread(), false));
        }

        @Override
        public void close() throws Exception {
            turn.remove();
            tasks.clear();
            levels.remove(level, runtime);
            set(IslandCommands.class, null, "ACTIVE_FENCE", previousFence);
            set(RegionScheduler.class, null, "pool", previousPool);
            set(RegionScheduler.class, null, "orphanFatalFailure", previousFailure);
            set(ParallelProcessor.class, null, "server", previousServer);
        }
    }

    // Unsafe-allocated: mixin storage substituted, no world constructed.
    private static final class TestPlayer extends ServerPlayer implements AsyncIslandPlayerAccess {
        AsyncIslandPlayerTicks.PlayerState localState;

        private TestPlayer() { super(null, null, null, null); }

        @Override
        public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return localState; }
    }

    private static final class TestListener extends ServerGamePacketListenerImpl
            implements AsyncIslandListenerAccess {
        AtomicReference<AsyncIslandPlayerTicks.PlayerState> binding;

        private TestListener() { super(null, null, null, null); }

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
