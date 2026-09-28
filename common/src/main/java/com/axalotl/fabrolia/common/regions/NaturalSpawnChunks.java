package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

/**
 * Vanilla's natural-spawn chunk set, expressed per lane.
 *
 * <p>{@code DistanceManager.naturalSpawnChunkCounter} is a
 * {@code FixedPlayerDistanceChunkTracker(8)}: a chunk is a spawn candidate
 * (and counts toward {@code getNaturalSpawnChunkCount}, the global-cap
 * denominator) iff it lies within Chebyshev distance {@link #RADIUS} of the
 * chunk of a counted player — loaded or not. Islands are separated by at
 * least two empty grid cells (16 chunks), so the squares of players on
 * different islands never overlap: the per-lane sets partition vanilla's
 * level-wide set exactly, and their sizes add up to vanilla's count.
 */
public final class NaturalSpawnChunks {

    /** {@code FixedPlayerDistanceChunkTracker(8)}: 17×17 = 289 chunks per player. */
    public static final int RADIUS = 8;

    private NaturalSpawnChunks() {}

    /** Union of the radius-8 squares around {@code playerChunkKeys}. */
    public static LongOpenHashSet candidates(LongIterator playerChunkKeys) {
        LongOpenHashSet out = new LongOpenHashSet(289);
        while (playerChunkKeys.hasNext()) {
            long key = playerChunkKeys.nextLong();
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    out.add(ChunkPos.pack(cx + dx, cz + dz));
                }
            }
        }
        return out;
    }
}
