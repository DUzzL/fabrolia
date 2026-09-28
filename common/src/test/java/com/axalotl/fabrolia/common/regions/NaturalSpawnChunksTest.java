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

    @Test
    void islandEdgePlayerCountsChunksBeyondTheIslandExactlyLikeVanilla() {
        // One player standing on the last loaded chunk of an island: vanilla's
        // naturalSpawnChunkCounter still counts the full 17×17 square, including
        // the unloaded gap chunks past the island edge.
        LongOpenHashSet set = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(7, 3)).iterator());
        assertEquals(MAGIC_NUMBER, set.size());
        assertTrue(set.contains(ChunkPos.pack(15, 11)), "corner of the square");
        assertTrue(set.contains(ChunkPos.pack(-1, -5)));
        assertFalse(set.contains(ChunkPos.pack(16, 3)), "distance 9 is outside the tracker");
        assertFalse(set.contains(ChunkPos.pack(7, 12)));
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
        // Islands are ≥ 2 empty grid cells (16 chunks) apart: the nearest two
        // players are 17 chunks apart and their squares are disjoint.
        LongOpenHashSet a = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(7, 0)).iterator());
        LongOpenHashSet b = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(24, 0)).iterator());
        LongOpenHashSet level = NaturalSpawnChunks.candidates(
                LongArrayList.of(ChunkPos.pack(7, 0), ChunkPos.pack(24, 0)).iterator());
        LongOpenHashSet overlap = new LongOpenHashSet(a);
        overlap.retainAll(b);
        assertTrue(overlap.isEmpty());
        assertEquals(level.size(), a.size() + b.size());
        assertEquals(2 * MAGIC_NUMBER, level.size());
    }
}
