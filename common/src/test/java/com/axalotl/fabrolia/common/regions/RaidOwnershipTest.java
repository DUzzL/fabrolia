package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Synchronous region threading: only physical Main may own a raid, even during a paused turn. */
@Isolated
@Timeout(20)
class RaidOwnershipTest {
    private static final BlockPos CENTER = new BlockPos(3 * 16, 64, 3 * 16);

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void aForeignThreadNeverOwnsASyncRaidEvenDuringAPausedTurn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.runtime.asyncActive = false;
            fixture.onForeignThread();
            fixture.pool.pauseExactTurnAndAwait(() -> {});
            try {
                fixture.onForeignThread();
            } finally {
                fixture.pool.resumeExactTurn();
            }
            fixture.onForeignThread();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RegionWorkerPool pool = new RegionWorkerPool(1);
        final IslandRegistry.PIsland island = new IslandRegistry.PIsland(71, 0);
        final TestLevel level;
        final RegionizedServer runtime;
        final TestPlayer player;
        final Region source;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object oldPool = get(RegionScheduler.class, null, "pool");
        final Object oldServer = get(RegionScheduler.class, null, "server");
        final Object oldActive = get(RegionScheduler.class, null, "active");
        final boolean oldThreading = FabroliaConfig.enableRegionThreading;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            assertNull(oldPool);
            MinecraftServer server = (MinecraftServer) unsafe().allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
            level.server = server;
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            island.level = level;
            island.domain.epoch = 8;
            island.domain.entityDispatchCutEpoch = 7;
            Long2ObjectOpenHashMap<EpochDomain> domains = new Long2ObjectOpenHashMap<>();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    domains.put(runtime.regionizer().grid.keyFor(x * 8, z * 8), island.domain);
                }
            }
            set(RegionizedServer.class, runtime, "cellDomainSnapshot", domains);
            for (long key : domains.keySet()) {
                runtime.regionizer().regionForGridKey(key).groupedEpoch = 8;
            }
            source = runtime.regionizer().peekRegionForChunk(ChunkPos.pack(0, 0));
            island.domain.entityDispatchCutEpoch = island.domain.dispatchedEpoch = 8;
            player = (TestPlayer) unsafe().allocateInstance(TestPlayer.class);
            player.localLevel = level;
            player.state = new AsyncIslandPlayerTicks.PlayerState();
            player.connection = (ServerGamePacketListenerImpl) unsafe().allocateInstance(ServerGamePacketListenerImpl.class);
            player.connection.player = player;
            IslandPlayerTurnSession.OwnerStamp owner = new IslandPlayerTurnSession.OwnerStamp(
                    player, player.connection, island, island.id, island.gen, island.domain,
                    0, 1, IslandPlayerTurnSession.PlayerKind.REAL);
            assertTrue(player.state.session.attach(owner));
            set(Entity.class, player, "chunkPosition", new ChunkPos(3, 3));
            set(Entity.class, player, "blockPosition", CENTER);
            player.indexedKey = source.gridKey;
            levels = (Map<ServerLevel, RegionizedServer>) get(RegionScheduler.class, null, "PER_LEVEL");
            pool.start();
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "server", server);
            set(RegionScheduler.class, null, "active", true);
            FabroliaConfig.enableRegionThreading = true;
            levels.put(level, runtime);
        }

        void onForeignThread() throws Exception {
            FutureTask<Void> task = new FutureTask<>(() -> {
                assertFalse(RaidOwnership.canCreate(player, CENTER));
                assertFalse(RaidOwnership.ownsRaid(level, CENTER));
                return null;
            });
            Thread foreign = new Thread(task, "raid-foreign-thread-regression");
            foreign.start();
            try { task.get(5, TimeUnit.SECONDS); }
            finally { foreign.join(5000); }
            assertFalse(foreign.isAlive());
        }

        @Override public void close() throws Exception {
            try { pool.stop(); }
            finally {
                levels.remove(level, runtime);
                FabroliaConfig.enableRegionThreading = oldThreading;
                set(RegionScheduler.class, null, "active", oldActive);
                set(RegionScheduler.class, null, "server", oldServer);
                set(RegionScheduler.class, null, "pool", oldPool);
            }
        }
    }

    private static final class TestLevel extends ServerLevel {
        MinecraftServer server;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static final class TestPlayer extends ServerPlayer implements AsyncIslandPlayerAccess, RegionKeyed {
        AsyncIslandPlayerTicks.PlayerState state;
        ServerLevel localLevel;
        long indexedKey;
        private TestPlayer() { super(null, null, null, null); }
        @Override public ServerLevel level() { return localLevel; }
        @Override public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return state; }
        @Override public long fabrolia$getRegionKey() { return indexedKey; }
        @Override public void fabrolia$setRegionKey(long key) { indexedKey = key; }
        @Override public boolean fabrolia$tryMarkRegionTick(long token) { throw new AssertionError("not an entity tick"); }
        @Override public boolean fabrolia$carryRegionTickMark(long from, long to) { throw new AssertionError("not a migration"); }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        return (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
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
