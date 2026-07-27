package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.level.ChunkPos;

/**
 * Fixed-grid region key. Each grid cell covers (2^shift)×(2^shift) chunks.
 * Default shift=3 → 8×8-chunk regions = 128×128 blocks each.
 *
 * <p>Trade-off vs Folia's ticket-driven dynamic regionizer: fixed grid is
 * dramatically simpler (no merge/split state machine, no overlap arbitration)
 * at the cost of arbitrary cell boundaries. Two players in adjacent grid cells
 * end up in different regions even if they're 1 chunk apart. For Phase 2/3
 * "lite" this is acceptable because: (a) entity ticks in the same region
 * serialize cleanly; (b) cross-region entity interaction is rare relative to
 * within-region; (c) the worst case (region boundary between two players)
 * still parallelizes across N regions, beating single-threaded vanilla.
 *
 * <p>Chunk packing matches vanilla {@code ChunkPos.pack}: low 32 bits = x,
 * high 32 bits = z. We unpack with raw bit ops to avoid coupling to whatever
 * accessor variant (x() vs x field) the current yarn mappings expose.
 */
public final class RegionGrid {
    public static final int DEFAULT_SHIFT = 3;
    public final int shift;

    public RegionGrid(int shift) {
        if (shift < 0 || shift > 10) {
            throw new IllegalArgumentException("shift out of range: " + shift);
        }
        this.shift = shift;
    }

    public long keyFor(int chunkX, int chunkZ) {
        long rx = (chunkX >> shift);
        long rz = (chunkZ >> shift);
        return (rx & 0xFFFFFFFFL) | ((rz & 0xFFFFFFFFL) << 32);
    }

    public long keyFor(long packedChunkPos) {
        int cx = (int) packedChunkPos;
        int cz = (int) (packedChunkPos >> 32);
        return keyFor(cx, cz);
    }

    public long keyFor(ChunkPos pos) {
        return keyFor(pos.pack());
    }

    public int regionX(long regionKey) { return (int) regionKey; }
    public int regionZ(long regionKey) { return (int) (regionKey >> 32); }
}
