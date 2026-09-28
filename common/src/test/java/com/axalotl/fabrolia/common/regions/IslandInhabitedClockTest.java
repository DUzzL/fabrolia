package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The island chunk pass credits {@code inhabitedTime} with island-L since its
 * previous pass. That clock must never be re-anchored to wall time behind a
 * lagging island's L, or the next pass would credit a negative delta.
 */
class IslandInhabitedClockTest {

    private static LongArrayList cells(long... keys) {
        LongArrayList list = new LongArrayList(keys.length);
        for (long k : keys) list.add(k);
        return list;
    }

    @Test
    void bornIslandStartsAtItsOwnClock() {
        IslandRegistry registry = new IslandRegistry();
        registry.applyRebuildCells(List.of(cells(1L, 2L)), 5000L);
        IslandRegistry.PIsland island = registry.byCell(1L);
        assertEquals(island.logicalTick, island.lastInhabitedUpdateL);
        // First unit runs at L+1: exactly one tick of inhabited time.
        assertEquals(1L, (island.logicalTick + 1) - island.lastInhabitedUpdateL);
    }

    @Test
    void splitFragmentInheritsTheLaggingSourceClockNotWallTime() {
        IslandRegistry registry = new IslandRegistry();
        registry.applyRebuildCells(List.of(cells(1L, 2L, 30L, 31L)), 1000L);
        IslandRegistry.PIsland source = registry.byCell(1L);
        // The source island fell 400 ticks behind wall and last credited its
        // chunks at its own L (the coordinator writes both after each pass).
        source.logicalTick = 1600L;
        source.lastInhabitedUpdateL = 1600L;

        registry.applyRebuildCells(List.of(cells(1L, 2L), cells(30L, 31L)), 2000L);
        IslandRegistry.PIsland keeper = registry.byCell(1L);
        IslandRegistry.PIsland fragment = registry.byCell(30L);
        assertTrue(keeper != fragment);
        for (IslandRegistry.PIsland p : List.of(keeper, fragment)) {
            assertEquals(1600L, p.logicalTick, "fragments inherit the source clock");
            long timeDiff = (p.logicalTick + 1) - p.lastInhabitedUpdateL;
            assertEquals(1L, timeDiff, "next pass credits one island tick, never wall skew");
        }
    }
}
