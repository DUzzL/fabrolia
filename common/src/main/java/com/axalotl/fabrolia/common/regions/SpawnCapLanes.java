package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;

import java.util.List;

/**
 * Level-wide global mob-cap aggregate for async islands.
 *
 * <p>Vanilla evaluates {@code SpawnState.canSpawnForCategoryGlobal} once per
 * level tick against the whole level's census and the whole level's
 * spawnable-chunk count. Under async islands every lane (each island, plus
 * Main's global remainder lane) runs its own chunk pass at its own cadence
 * and takes its own census of its own chunks. Each lane publishes that census
 * as one immutable {@link #slot slot}; a lane's cap check then reads the SUM
 * of every slot — the same numerator and denominator vanilla would see, with
 * every other lane's contribution at most one of that lane's periods old
 * (vanilla's census is itself one tick old by the time spawning runs). A
 * lane never waits for another lane or for Main.
 *
 * <p>Slot layout: {@code [ordinal]} census count per {@link MobCategory},
 * {@code [CATEGORIES]} the lane's spawnable-chunk count. Slots are replaced
 * whole, never mutated after publication.
 */
public final class SpawnCapLanes {

    private static final MobCategory[] CATEGORY_VALUES = MobCategory.values();
    public static final int CATEGORIES = CATEGORY_VALUES.length;
    /** Index of the spawnable-chunk count inside a slot. */
    public static final int CHUNKS = CATEGORIES;

    /** Main's global remainder lane. Null until Main has published once. */
    private volatile int[] globalLane;

    public static int[] slot(Object2IntMap<MobCategory> counts, int spawnableChunks) {
        int[] slot = new int[CATEGORIES + 1];
        for (int i = 0; i < CATEGORIES; i++) {
            slot[i] = counts.getInt(CATEGORY_VALUES[i]);
        }
        slot[CHUNKS] = spawnableChunks;
        return slot;
    }

    public static int[] slot(java.util.concurrent.atomic.AtomicIntegerArray counts,
                             int spawnableChunks) {
        int[] slot = new int[CATEGORIES + 1];
        for (int i = 0; i < CATEGORIES; i++) {
            slot[i] = counts.get(i);
        }
        slot[CHUNKS] = spawnableChunks;
        return slot;
    }

    public void publishGlobalLane(int[] slot) {
        this.globalLane = slot;
    }

    public int[] globalLane() {
        return globalLane;
    }

    /** Sum of every island slot except {@code exclude}'s (the caller adds its
     *  own fresh census), plus the global lane when requested. */
    public int[] sum(List<IslandRegistry.PIsland> islands, IslandRegistry.PIsland exclude,
                     boolean includeGlobalLane) {
        int[] total = new int[CATEGORIES + 1];
        for (int i = 0, n = islands.size(); i < n; i++) {
            IslandRegistry.PIsland island = islands.get(i);
            if (island == exclude || island.retired) continue;
            add(total, island.spawnCapSlot);
        }
        if (includeGlobalLane) {
            add(total, globalLane);
        }
        return total;
    }

    public static void add(int[] total, int[] slot) {
        if (slot == null) return;
        for (int i = 0; i <= CATEGORIES; i++) {
            total[i] += slot[i];
        }
    }

    /** Category map view of a summed slot, in the shape {@code SpawnState} takes. */
    public static Object2IntOpenHashMap<MobCategory> counts(int[] slot) {
        Object2IntOpenHashMap<MobCategory> counts = new Object2IntOpenHashMap<>(CATEGORIES);
        for (int i = 0; i < CATEGORIES; i++) {
            if (slot[i] != 0) counts.put(CATEGORY_VALUES[i], slot[i]);
        }
        return counts;
    }
}
