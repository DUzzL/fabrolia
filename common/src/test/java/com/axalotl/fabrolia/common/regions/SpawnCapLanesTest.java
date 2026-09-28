package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The summed lane slots reproduce vanilla's level-wide global cap check. */
class SpawnCapLanesTest {

    private static final int MAGIC_NUMBER = 17 * 17;

    /** Vanilla {@code SpawnState.canSpawnForCategoryGlobal}. */
    private static boolean vanillaGlobalCap(int[] slot, MobCategory category) {
        int max = category.getMaxInstancesPerChunk() * slot[SpawnCapLanes.CHUNKS] / MAGIC_NUMBER;
        return slot[category.ordinal()] < max;
    }

    private static int[] islandSlot(int monsters, int chunks) {
        Object2IntOpenHashMap<MobCategory> counts = new Object2IntOpenHashMap<>();
        counts.put(MobCategory.MONSTER, monsters);
        return SpawnCapLanes.slot(counts, chunks);
    }

    @Test
    void oneSaturatedIslandBlocksTheOtherExactlyLikeVanillasLevelTotal() {
        IslandRegistry.PIsland a = new IslandRegistry.PIsland(1L, 0L);
        IslandRegistry.PIsland b = new IslandRegistry.PIsland(2L, 0L);
        int cap = MobCategory.MONSTER.getMaxInstancesPerChunk(); // 70 per 289 chunks
        // A alone holds the whole level's monster budget for two players' worth
        // of spawnable chunks; B has nothing near it.
        a.spawnCapSlot = islandSlot(2 * cap, MAGIC_NUMBER);
        b.spawnCapSlot = islandSlot(0, MAGIC_NUMBER);
        SpawnCapLanes lanes = new SpawnCapLanes();
        List<IslandRegistry.PIsland> islands = List.of(a, b);

        // B's own census alone would spawn (0 < 70)...
        assertTrue(vanillaGlobalCap(b.spawnCapSlot, MobCategory.MONSTER));
        // ...but vanilla sees 140 monsters against 578 chunks → max 140 → blocked.
        int[] bView = lanes.sum(islands, b, true);
        SpawnCapLanes.add(bView, b.spawnCapSlot);
        assertEquals(2 * cap, bView[MobCategory.MONSTER.ordinal()]);
        assertEquals(2 * MAGIC_NUMBER, bView[SpawnCapLanes.CHUNKS]);
        assertFalse(vanillaGlobalCap(bView, MobCategory.MONSTER));
        // A's view is the same level total.
        int[] aView = lanes.sum(islands, a, true);
        SpawnCapLanes.add(aView, a.spawnCapSlot);
        assertFalse(vanillaGlobalCap(aView, MobCategory.MONSTER));

        // Once A's mobs are gone, B's next census sees the budget reopen.
        a.spawnCapSlot = islandSlot(cap - 1, MAGIC_NUMBER);
        bView = lanes.sum(islands, b, true);
        SpawnCapLanes.add(bView, b.spawnCapSlot);
        assertTrue(vanillaGlobalCap(bView, MobCategory.MONSTER));
    }

    @Test
    void sumUsesTheCallersFreshSlotAndSkipsRetiredAndUnpublishedIslands() {
        IslandRegistry.PIsland self = new IslandRegistry.PIsland(1L, 0L);
        IslandRegistry.PIsland other = new IslandRegistry.PIsland(2L, 0L);
        IslandRegistry.PIsland retired = new IslandRegistry.PIsland(3L, 0L);
        IslandRegistry.PIsland unpublished = new IslandRegistry.PIsland(4L, 0L);
        self.spawnCapSlot = islandSlot(50, 289);   // stale: caller replaces it
        other.spawnCapSlot = islandSlot(5, 100);
        retired.spawnCapSlot = islandSlot(1000, 1000);
        retired.retired = true;
        SpawnCapLanes lanes = new SpawnCapLanes();
        lanes.publishGlobalLane(islandSlot(2, 10));

        int[] fresh = islandSlot(7, 289);
        int[] total = lanes.sum(List.of(self, other, retired, unpublished), self, true);
        SpawnCapLanes.add(total, fresh);
        assertEquals(7 + 5 + 2, total[MobCategory.MONSTER.ordinal()]);
        assertEquals(289 + 100 + 10, total[SpawnCapLanes.CHUNKS]);

        int[] islandsOnly = lanes.sum(List.of(self, other, retired, unpublished), null, false);
        assertEquals(50 + 5, islandsOnly[MobCategory.MONSTER.ordinal()]);
        assertEquals(289 + 100, islandsOnly[SpawnCapLanes.CHUNKS]);
        assertEquals(0, islandsOnly[MobCategory.CREATURE.ordinal()]);
    }
}
