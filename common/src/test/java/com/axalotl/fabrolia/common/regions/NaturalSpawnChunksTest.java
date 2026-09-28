package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-lane natural-spawn candidate sets partition vanilla's level-wide set. */
class NaturalSpawnChunksTest {

    private static final int MAGIC_NUMBER = 17 * 17; // NaturalSpawner.MAGIC_NUMBER
    /** Chunks per grid cell side at the production shift. */
    private static final int CELL = 1 << RegionGrid.DEFAULT_SHIFT;
    /** Closest two players on different islands can stand: the last chunk of
     *  one populated cell and the first chunk of a populated cell
     *  {@code ISLAND_BFS_RADIUS + 1} cells away. */
    private static final int MIN_CROSS_ISLAND_PLAYER_DISTANCE =
            RegionizedServer.ISLAND_BFS_RADIUS * CELL + 1;

    @Test
    void islandEdgePlayerCountsChunksBeyondTheIslandExactlyLikeVanilla() {
        // One player standing on the last loaded chunk of an island: vanilla's
        // naturalSpawnChunkCounter still counts the full 17×17 square, including
        // the unloaded gap chunks past the island edge.
        int edge = CELL - 1;
        LongOpenHashSet set = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(edge, 3)).iterator());
        assertEquals(MAGIC_NUMBER, set.size());
        int r = NaturalSpawnChunks.RADIUS;
        assertTrue(set.contains(ChunkPos.pack(edge + r, 3 + r)), "corner of the square");
        assertTrue(set.contains(ChunkPos.pack(edge - r, 3 - r)));
        assertFalse(set.contains(ChunkPos.pack(edge + r + 1, 3)), "distance 9 is outside the tracker");
        assertFalse(set.contains(ChunkPos.pack(edge, 3 + r + 1)));
    }

    @Test
    void overlappingPlayersOnOneIslandCountEachChunkOnce() {
        LongOpenHashSet set = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(0, 0), ChunkPos.pack(4, 0)).iterator());
        // 17 rows × (17 + 4) columns
        assertEquals(17 * 21, set.size());
    }

    @Test
    void playersOnSeparatedIslandsPartitionVanillasCount() {
        // The partition is exact only because the island separation exceeds the
        // tracker's reach: two radius-8 squares are disjoint iff their centres
        // are more than 16 chunks apart.
        assertTrue(MIN_CROSS_ISLAND_PLAYER_DISTANCE > 2 * NaturalSpawnChunks.RADIUS,
                "grid shift " + RegionGrid.DEFAULT_SHIFT + " with BFS radius "
                        + RegionizedServer.ISLAND_BFS_RADIUS + " cannot separate spawn squares");
        int a = CELL - 1;
        int b = a + MIN_CROSS_ISLAND_PLAYER_DISTANCE;
        LongOpenHashSet setA = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(a, 0)).iterator());
        LongOpenHashSet setB = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(b, 0)).iterator());
        LongOpenHashSet level = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(a, 0), ChunkPos.pack(b, 0)).iterator());
        LongOpenHashSet overlap = new LongOpenHashSet(setA);
        overlap.retainAll(setB);
        assertTrue(overlap.isEmpty());
        assertEquals(level.size(), setA.size() + setB.size());
        assertEquals(2 * MAGIC_NUMBER, level.size());
        // One chunk closer and the squares would touch: the bound is tight.
        LongOpenHashSet closer = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(b - 1, 0)).iterator());
        closer.retainAll(setA);
        assertFalse(closer.isEmpty());
    }
}
