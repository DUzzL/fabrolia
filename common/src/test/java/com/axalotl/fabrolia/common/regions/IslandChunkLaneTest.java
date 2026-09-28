package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Main's chunk-phase lane decision is a pure ownership test: an island-owned
 * region is never armed by Main (its unit runs the chunk pass itself), so no
 * per-tick reservation or stamp exists that a lagging island could owe.
 */
class IslandChunkLaneTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void mainSkipsIslandOwnedRegionsOnEveryTickWithoutReservingAnything() throws Exception {
        Field unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
        TestLevel level = (TestLevel) ((sun.misc.Unsafe) unsafeField.get(null))
                .allocateInstance(TestLevel.class);
        RegionizedServer rs = new RegionizedServer(level, new RegionGrid(3));
        RegionGrid grid = rs.regionizer().grid;
        long islandCell = grid.keyFor(ChunkPos.pack(5, 5));
        long gapCell = grid.keyFor(ChunkPos.pack(40, 40));
        Region islandRegion = rs.regionizer().regionForGridKey(islandCell);
        Region gapRegion = rs.regionizer().regionForGridKey(gapCell);
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(7L, 100L);
        island.level = level;
        islandRegion.domain = island.domain;

        // Sync mode: everything is Main's (pre-A2 arm + await).
        rs.asyncActive = false;
        assertFalse(rs.islandOwned(islandRegion));
        assertFalse(rs.islandOwned(gapRegion));

        // Async: three Main ticks with no island unit in between — Main skips
        // the island region every time; nothing accumulates, nothing is owed.
        rs.asyncActive = true;
        for (int mainTick = 0; mainTick < 3; mainTick++) {
            level.wall++;
            assertTrue(rs.islandOwned(islandRegion));
            assertFalse(rs.islandOwned(gapRegion), "global remainder lane stays Main's");
        }
        assertSame(island, rs.islandOwnerOfCell(islandCell));
        assertSame(island, rs.islandOwnerOfChunk(ChunkPos.pack(5, 5)));
        assertNull(rs.islandOwnerOfChunk(ChunkPos.pack(40, 40)));
        assertNull(island.spawnCapSlot, "the island publishes its census only from its own pass");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class TestLevel extends ServerLevel {
        long wall;
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public long getGameTime() { return wall; }
        @Override public LevelData getLevelData() { return null; }
        @Override public MinecraftServer getServer() { return null; }
        @Override public net.minecraft.resources.ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }
}
