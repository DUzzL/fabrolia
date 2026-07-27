package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link RegionGrid}'s packing to vanilla {@link ChunkPos}, with emphasis on
 * NEGATIVE coordinates — a region key computed with truncating instead of
 * flooring division silently assigns entities near the origin-negative side to
 * the wrong region, which under region threading means the wrong owner and the
 * wrong lock. Verified against the real vanilla implementation rather than a
 * copy of it.
 */
class RegionGridTest {

    private static final RegionGrid GRID = new RegionGrid(RegionGrid.DEFAULT_SHIFT);

    @Test
    void packingConventionMatchesVanillaChunkPos() {
        // Vanilla: pack(x,z) = x&0xFFFFFFFF | (z&0xFFFFFFFF)<<32 — low 32 = x.
        for (int x : new int[]{0, 1, -1, 31, -31, 4096, -4096, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            for (int z : new int[]{0, 1, -1, 31, -31, 4096, -4096, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
                long packed = ChunkPos.pack(x, z);
                assertEquals(x, ChunkPos.getX(packed), "vanilla getX round-trip");
                assertEquals(z, ChunkPos.getZ(packed), "vanilla getZ round-trip");
                assertEquals(GRID.keyFor(x, z), GRID.keyFor(packed),
                        "keyFor(packed) must unpack exactly as vanilla packs, at x=" + x + " z=" + z);
                assertEquals(GRID.keyFor(x, z), GRID.keyFor(new ChunkPos(x, z)),
                        "keyFor(ChunkPos) must agree, at x=" + x + " z=" + z);
            }
        }
    }

    @Test
    void regionCoordsUseFlooringNotTruncation() {
        int size = 1 << RegionGrid.DEFAULT_SHIFT;
        for (int c = -64; c <= 64; c++) {
            long key = GRID.keyFor(c, c);
            assertEquals(Math.floorDiv(c, size), GRID.regionX(key),
                    "regionX must floor-divide (truncation splits the negative side wrongly) at c=" + c);
            assertEquals(Math.floorDiv(c, size), GRID.regionZ(key),
                    "regionZ must floor-divide at c=" + c);
        }
    }

    @Test
    void adjacentChunksOnANegativeBoundaryLandInDistinctRegions() {
        int size = 1 << RegionGrid.DEFAULT_SHIFT;
        // -size and -size-1 straddle a region boundary on the negative side,
        // which is exactly where truncating division collapses two regions into one.
        long a = GRID.keyFor(-size, 0);
        long b = GRID.keyFor(-size - 1, 0);
        assertEquals(-1, GRID.regionX(a));
        assertEquals(-2, GRID.regionX(b));
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b,
                "chunks either side of a negative region boundary must not share a region key");
    }

    @Test
    void realWorldNegativeCoordinatesAreStable() {
        // The operator's failing portal sat near OW (37017, -4428) / Nether (4626, -554).
        // Region assignment there must be self-consistent and floor-based.
        for (long packed : new long[]{
                ChunkPos.pack(37017 >> 4, -4428 >> 4),
                ChunkPos.pack(4626 >> 4, -554 >> 4)}) {
            long key = GRID.keyFor(packed);
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            assertEquals(Math.floorDiv(cx, 1 << RegionGrid.DEFAULT_SHIFT), GRID.regionX(key));
            assertEquals(Math.floorDiv(cz, 1 << RegionGrid.DEFAULT_SHIFT), GRID.regionZ(key));
        }
    }
}
