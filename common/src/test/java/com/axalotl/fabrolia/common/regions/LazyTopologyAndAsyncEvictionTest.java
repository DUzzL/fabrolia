package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Population boundaries of committed island core cells are lazy topology drift
 * (no all-island quiesce), everything else stays an urgent rebuild; async
 * reclamation removes only idle global-domain regions.
 */
final class LazyTopologyAndAsyncEvictionTest {

    private static final int SHIFT = 3;

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void committedCoreCellDepopulationIsLazyAndBounded() throws Exception {
        Fixture f = new Fixture();
        long core = cellKey(2, 3);
        f.commitCores(core);
        long chunk = chunkIn(core);

        f.runtime.onChunkFullTransition(chunk, true, null);
        assertFalse(f.dirty(), "repopulating a committed core cell reproduces the committed shape");

        f.setGameTime(10L);
        f.runtime.onChunkFullTransition(chunk, false, null);
        assertFalse(f.dirty(), "a committed core cell losing its last FULL chunk must not force a quiesce");
        assertFalse(f.lazyDue(), "lazy drift is not due before its staleness bound");

        f.setGameTime(RegionizedServer.LAZY_TOPOLOGY_TICKS - 1L);
        assertFalse(f.lazyDue());
        f.setGameTime(RegionizedServer.LAZY_TOPOLOGY_TICKS);
        assertTrue(f.lazyDue(), "pending lazy drift is folded in after the staleness bound");
    }

    @Test
    void repopulationBeforeTheBoundCancelsLazyDrift() throws Exception {
        Fixture f = new Fixture();
        long core = cellKey(-4, 9);
        f.commitCores(core);
        long chunk = chunkIn(core);
        f.runtime.onChunkFullTransition(chunk, true, null);

        f.runtime.onChunkFullTransition(chunk, false, null);
        f.runtime.onChunkFullTransition(chunk, true, null);
        f.setGameTime(10L * RegionizedServer.LAZY_TOPOLOGY_TICKS);
        assertFalse(f.dirty());
        assertFalse(f.lazyDue(), "a depopulate/repopulate pair leaves no topology drift behind");
    }

    @Test
    void boundariesOutsideTheCommittedCoreSetStayUrgent() throws Exception {
        Fixture f = new Fixture();
        long core = cellKey(0, 0);
        f.commitCores(core);

        long halo = cellKey(1, 0);
        f.runtime.onChunkFullTransition(chunkIn(halo), true, null);
        assertTrue(f.dirty(), "a newly populated non-core cell may merge islands: urgent rebuild");

        f.setDirty(false);
        f.runtime.onChunkFullTransition(chunkIn(halo), false, null);
        assertTrue(f.dirty(), "an unadopted cell losing population is itself an urgent change");
    }

    @Test
    void boundaryDuringTheRebuildWindowIsUrgent() throws Exception {
        Fixture f = new Fixture();
        long core = cellKey(5, 5);
        f.commitCores(core);
        long chunk = chunkIn(core);
        f.runtime.onChunkFullTransition(chunk, true, null);

        set(RegionizedServer.class, f.runtime, "topologyRebuildInFlight", true);
        f.runtime.onChunkFullTransition(chunk, false, null);
        assertTrue(f.dirty(),
                "between the BFS read and the registry commit no boundary may be judged lazy");
    }

    @Test
    void asyncReclamationKeepsIslandRegionsAndHonoursTheGrace() throws Exception {
        Fixture f = new Fixture();
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(1L, 0L);
        island.domain.epoch = f.global.epoch;

        Region idleGlobal = f.regionizer.regionForGridKey(cellKey(10, 10));
        Region busyGlobal = f.regionizer.regionForGridKey(cellKey(20, 20));
        busyGlobal.entityCount.set(1L);
        Region idleIsland = f.regionizer.regionForGridKey(cellKey(30, 30));
        idleIsland.domain = island.domain;
        assertSame(f.global, idleGlobal.domain);

        assertEquals(0, f.regionizer.evictIdle(f.runtime, f.global, 0L));
        assertEquals(0, f.regionizer.evictIdle(f.runtime, f.global,
                ThreadedRegionizer.EVICT_GRACE_TICKS - 1L));
        assertEquals(1, f.regionizer.evictIdle(f.runtime, f.global,
                ThreadedRegionizer.EVICT_GRACE_TICKS));

        assertNull(f.regionizer.peekByGridKey(idleGlobal.gridKey));
        assertEquals(RegionState.STOPPED, idleGlobal.state());
        assertSame(busyGlobal, f.regionizer.peekByGridKey(busyGlobal.gridKey));
        assertSame(idleIsland, f.regionizer.peekByGridKey(idleIsland.gridKey));

        assertEquals(0, f.regionizer.evictIdle(f.runtime, f.global,
                100L * ThreadedRegionizer.EVICT_GRACE_TICKS),
                "an island-domain region is never an async reclamation candidate");
        assertSame(idleIsland, f.regionizer.peekByGridKey(idleIsland.gridKey));
        assertNotNull(f.regionizer.regionForGridKey(idleGlobal.gridKey),
                "a reclaimed cell is reinstalled on its next lookup");
    }

    @Test
    void activityRestartsTheGrace() throws Exception {
        Fixture f = new Fixture();
        Region region = f.regionizer.regionForGridKey(cellKey(-7, 2));

        assertEquals(0, f.regionizer.evictIdle(f.runtime, f.global, 0L));
        region.entityCount.set(1L);
        assertEquals(0, f.regionizer.evictIdle(f.runtime, f.global, 50L));
        region.entityCount.set(0L);
        assertEquals(0, f.regionizer.evictIdle(f.runtime, f.global, 60L));
        assertEquals(0, f.regionizer.evictIdle(f.runtime, f.global,
                59L + ThreadedRegionizer.EVICT_GRACE_TICKS));
        assertEquals(1, f.regionizer.evictIdle(f.runtime, f.global,
                60L + ThreadedRegionizer.EVICT_GRACE_TICKS));
    }

    private static long cellKey(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32);
    }

    private static long chunkIn(long cellKey) {
        return ChunkPos.pack(((int) cellKey) << SHIFT, ((int) (cellKey >>> 32)) << SHIFT);
    }

    private static final class Fixture {
        final ServerLevel level;
        final PrimaryLevelData levelData;
        final RegionizedServer runtime;
        final ThreadedRegionizer regionizer;
        final EpochDomain global = new EpochDomain();
        final IslandRegistry registry = new IslandRegistry();

        Fixture() throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            levelData = (PrimaryLevelData) unsafe.allocateInstance(PrimaryLevelData.class);
            set(net.minecraft.world.level.Level.class, level, "levelData", levelData);
            set(ServerLevel.class, level, "serverLevelData", levelData);
            MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(TestDedicatedServer.class);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            set(ServerLevel.class, level, "server", server);

            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            regionizer = new ThreadedRegionizer(level, new RegionGrid(SHIFT));
            regionizer.owner = runtime;
            set(RegionizedServer.class, runtime, "level", level);
            set(RegionizedServer.class, runtime, "globalDomain", global);
            set(RegionizedServer.class, runtime, "regionizer", regionizer);
            set(RegionizedServer.class, runtime, "islandRegistry", registry);
            set(RegionizedServer.class, runtime, "lazyDepopulatedCells", new LongOpenHashSet());
            set(RegionizedServer.class, runtime, "scheduledTickDiscoveryCells", new LongOpenHashSet());
            set(RegionizedServer.class, runtime, "popIgnoredAdds",
                    new java.util.concurrent.atomic.AtomicLong());
            set(RegionizedServer.class, runtime, "popIgnoredRemoves",
                    new java.util.concurrent.atomic.AtomicLong());
            setDirty(false);
        }

        void commitCores(long... cells) {
            registry.applyRebuildCells(List.of(LongArrayList.wrap(cells)), 0L);
            for (long cell : cells) assertNotNull(registry.byCell(cell));
        }

        void setGameTime(long time) {
            levelData.setGameTime(time);
        }

        boolean dirty() throws Exception {
            return (boolean) get(RegionizedServer.class, runtime, "islandsDirty");
        }

        void setDirty(boolean dirty) throws Exception {
            set(RegionizedServer.class, runtime, "islandsDirty", dirty);
        }

        boolean lazyDue() throws Exception {
            Method due = RegionizedServer.class.getDeclaredMethod("lazyTopologyRefreshDue");
            due.setAccessible(true);
            return (boolean) due.invoke(runtime);
        }
    }

    private static Object get(Class<?> owner, Object target, String name)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> owner, Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
