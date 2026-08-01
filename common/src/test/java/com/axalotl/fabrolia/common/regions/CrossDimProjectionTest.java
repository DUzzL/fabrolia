package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static com.axalotl.fabrolia.common.regions.CrossDimProjection.cellKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #42 — geometry of Overworld↔Nether island linking, tested by feeding cell
 * sets straight into the real projection code.
 *
 * <p>Covers requirements 1–3 of the feature request: intersecting projections
 * link, non-intersecting ones do not, and the 8:1 transform is exact at its
 * boundaries (including negative coordinates, where vanilla floors toward
 * negative infinity and a naive {@code /8} would not).
 */
class CrossDimProjectionTest {

    private static final int SHIFT = RegionGrid.DEFAULT_SHIFT;   // 3 → 128-block cells
    private static final double OW_TO_NETHER = 0.125;            // getTeleportationScale(OW, Nether)
    private static final double NETHER_TO_OW = 8.0;

    private static LongOpenHashSet cells(long... keys) {
        LongOpenHashSet s = new LongOpenHashSet(Math.max(8, keys.length));
        for (long k : keys) s.add(k);
        return s;
    }

    private static LongOpenHashSet project(LongOpenHashSet src, double scale, int dilation) {
        LongOpenHashSet out = new LongOpenHashSet(8);
        CrossDimProjection.project(src, SHIFT, scale, dilation, out);
        return out;
    }

    // ------------------------------------------------------------ 3. the 8:1 transform

    /**
     * A cell covers 128 blocks; ÷8 makes that 16 blocks, so eight consecutive
     * Overworld cells collapse into one Nether cell and the ninth starts the
     * next. If this were off by one the whole link table would be shifted.
     */
    @Test
    void eightOverworldCellsCollapseIntoOneNetherCell() {
        for (int c = 0; c <= 7; c++) {
            assertEquals(cells(cellKey(0, 0)), project(cells(cellKey(c, 0)), OW_TO_NETHER, 0),
                    "overworld cell " + c + " must land in nether cell 0");
        }
        assertEquals(cells(cellKey(1, 0)), project(cells(cellKey(8, 0)), OW_TO_NETHER, 0),
                "overworld cell 8 starts nether cell 1");
    }

    /**
     * Vanilla scales with {@code Mth.floor(x * scale)}, which rounds toward
     * negative infinity. Integer division would round toward zero and put cell
     * -1 in nether cell 0 — silently linking the wrong islands across the axis.
     */
    @Test
    void negativeCoordinatesFloorTowardNegativeInfinity() {
        assertEquals(cells(cellKey(-1, 0)), project(cells(cellKey(-1, 0)), OW_TO_NETHER, 0),
                "overworld cell -1 is in nether cell -1, not 0");
        assertEquals(cells(cellKey(-1, 0)), project(cells(cellKey(-8, 0)), OW_TO_NETHER, 0),
                "overworld cell -8 is the far edge of nether cell -1");
        assertEquals(cells(cellKey(-2, 0)), project(cells(cellKey(-9, 0)), OW_TO_NETHER, 0),
                "overworld cell -9 crosses into nether cell -2");
    }

    /** Both axes must transform, not just X. */
    @Test
    void bothAxesTransform() {
        assertEquals(cells(cellKey(2, -3)),
                project(cells(cellKey(16, -20)), OW_TO_NETHER, 0),
                "16>>3 = 2 and -20>>3 = -3");
    }

    /**
     * Up-projection is the exact preimage of down-projection: one Nether cell
     * covers an aligned 8×8 block of Overworld cells. This is why the production
     * test may project DOWN only — materialising the ×64 up-projection is pure
     * waste.
     */
    @Test
    void netherCellPreimageIsTheAlignedEightByEightBlockOfOverworldCells() {
        LongOpenHashSet up = project(cells(cellKey(0, 0)), NETHER_TO_OW, 0);
        assertEquals(64, up.size(), "one nether cell covers 8x8 overworld cells");
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                assertTrue(up.contains(cellKey(x, z)), "missing overworld cell " + x + "," + z);
            }
        }
        // ...and every one of them projects back to where it came from.
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                assertEquals(cells(cellKey(0, 0)), project(cells(cellKey(x, z)), OW_TO_NETHER, 0),
                        "round trip failed for " + x + "," + z);
            }
        }
    }

    /** Same, on the negative side, where the alignment is easiest to get wrong. */
    @Test
    void negativeNetherCellPreimageIsAlignedToo() {
        LongOpenHashSet up = project(cells(cellKey(-1, 0)), NETHER_TO_OW, 0);
        assertEquals(64, up.size());
        for (int x = -8; x <= -1; x++) {
            assertTrue(up.contains(cellKey(x, 0)), "missing overworld cell " + x);
        }
        assertFalse(up.contains(cellKey(0, 0)), "cell 0 belongs to nether cell 0, not -1");
    }

    /**
     * The whole point of projecting down rather than up: the projected set never
     * grows. A 4x4 overworld island collapses to a single nether key.
     */
    @Test
    void downProjectionCollapsesRatherThanExpands() {
        LongOpenHashSet island = new LongOpenHashSet(16);
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) island.add(cellKey(x, z));
        }
        assertEquals(16, island.size());
        assertEquals(1, project(island, OW_TO_NETHER, 0).size(),
                "16 overworld cells inside one nether cell collapse to one key");
    }

    // ------------------------------------------------------------ 1 & 2. linking

    /** Requirement 1: a farm at the matching coordinates links. */
    @Test
    void intersectingProjectionsLink() {
        LongOpenHashSet overworld = cells(cellKey(16, 16), cellKey(17, 16));
        LongOpenHashSet nether = cells(cellKey(2, 2));            // 16>>3 = 2
        assertTrue(CrossDimProjection.linked(overworld, nether, SHIFT, OW_TO_NETHER, 0),
                "overworld island over nether cell (2,2) must link to it");
    }

    /** Requirement 2: unrelated islands stay independent. */
    @Test
    void nonIntersectingProjectionsDoNotLink() {
        LongOpenHashSet overworld = cells(cellKey(16, 16));       // → nether (2,2)
        LongOpenHashSet nether = cells(cellKey(40, 40));          // nowhere near
        assertFalse(CrossDimProjection.linked(overworld, nether, SHIFT, OW_TO_NETHER, 0),
                "distant islands must not be coupled");
    }

    /**
     * The test is symmetric: asking "does the overworld island's projection hit
     * the nether island" and "does the nether island's projection hit the
     * overworld island" must always agree, otherwise the two levels' rebuild
     * passes could disagree about whether a bridge exists.
     */
    @Test
    void theLinkTestIsSymmetricInBothDirections() {
        LongOpenHashSet overworld = cells(cellKey(16, 16), cellKey(23, 23));
        LongOpenHashSet nether = cells(cellKey(2, 2));

        boolean down = CrossDimProjection.linked(overworld, nether, SHIFT, OW_TO_NETHER, 0);
        boolean up = CrossDimProjection.linked(nether, overworld, SHIFT, NETHER_TO_OW, 0);
        assertTrue(down, "sanity: these do intersect");
        assertEquals(down, up, "both directions must answer identically");

        LongOpenHashSet farNether = cells(cellKey(99, 99));
        assertEquals(CrossDimProjection.linked(overworld, farNether, SHIFT, OW_TO_NETHER, 0),
                CrossDimProjection.linked(farNether, overworld, SHIFT, NETHER_TO_OW, 0),
                "and identically when they do NOT intersect");
    }

    // ------------------------------------------------------------ dilation

    /**
     * The scaled point is only vanilla's SEARCH ORIGIN — it then looks for an
     * exit portal within 16 blocks (Nether space). A footprint sitting on a cell
     * edge can therefore genuinely pair with the neighbouring cell, and the
     * dilation must reach it — but no further, since 16 &lt; 128.
     */
    @Test
    void dilationReachesTheAdjacentCellAndNoFurther() {
        // Overworld cell 0 projects to nether blocks [0,15] — hard against the
        // lower edge of nether cell 0, so cell -1 is within pairing reach.
        LongOpenHashSet dilated = project(cells(cellKey(0, 0)), OW_TO_NETHER,
                CrossDimProjection.NETHER_PORTAL_RADIUS);
        assertTrue(dilated.contains(cellKey(0, 0)), "own cell");
        assertTrue(dilated.contains(cellKey(-1, 0)), "the cell 16 blocks away is reachable");
        assertTrue(dilated.contains(cellKey(0, -1)), "and on the other axis");
        assertFalse(dilated.contains(cellKey(1, 0)),
                "the far side is 113 blocks away — beyond vanilla's pairing radius");
        assertFalse(dilated.contains(cellKey(-2, 0)), "never two cells out");
    }

    /** With no dilation the same input stays inside a single cell. */
    @Test
    void withoutDilationTheFootprintDoesNotLeakIntoNeighbours() {
        assertEquals(cells(cellKey(0, 0)), project(cells(cellKey(0, 0)), OW_TO_NETHER, 0));
    }

    // ------------------------------------------------------------ scale handling

    /**
     * {@code coordinate_scale} is a datapack field, so the transform must come
     * from the dimension pair and not from a hardcoded 8. Identity scale must be
     * an identity projection.
     */
    @Test
    void identityScaleIsAnIdentityProjection() {
        LongOpenHashSet src = cells(cellKey(5, -7), cellKey(0, 0));
        assertEquals(src, project(src, 1.0, 0), "scale 1 must not move anything");
    }

    /**
     * A non-power-of-two scale takes the generic rectangle path. A cell may then
     * straddle two destination cells, which the set representation absorbs.
     */
    @Test
    void nonPowerOfTwoScaleUsesTheGenericPathAndStillFloorsCorrectly() {
        // scale 1/3: overworld cell 0 covers blocks [0,127] → dest [0, 42].
        LongOpenHashSet out = project(cells(cellKey(0, 0)), 1.0 / 3.0, 0);
        assertEquals(cells(cellKey(0, 0)), out, "0..42 is still inside dest cell 0");

        // overworld cell 3 covers [384,511] → dest [128,170] → cells 1 only.
        assertEquals(cells(cellKey(1, 0)), project(cells(cellKey(3, 0)), 1.0 / 3.0, 0));

        // A cell that genuinely straddles: [341,384] spans dest cells 2 and 3
        // at scale 1.0 — use scale 1 with a dilation to force a straddle instead.
        LongOpenHashSet straddle = project(cells(cellKey(1, 0)), 1.0, 1);
        assertTrue(straddle.contains(cellKey(1, 0)));
        assertTrue(straddle.contains(cellKey(0, 0)), "dilation pulls in the lower neighbour");
    }

    @Test
    void rejectsNonsenseScales() {
        LongOpenHashSet src = cells(cellKey(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> project(src, 0.0, 0), "zero scale");
        assertThrows(IllegalArgumentException.class,
                () -> project(src, -8.0, 0), "negative scale");
        assertThrows(IllegalArgumentException.class,
                () -> project(src, Double.NaN, 0), "NaN scale");
    }

    @Test
    void exactLog2RecognisesPowersOfTwoIncludingReciprocals() {
        assertEquals(-3, CrossDimProjection.exactLog2(0.125));
        assertEquals(3, CrossDimProjection.exactLog2(8.0));
        assertEquals(0, CrossDimProjection.exactLog2(1.0));
        assertEquals(Integer.MIN_VALUE, CrossDimProjection.exactLog2(3.0));
        assertEquals(Integer.MIN_VALUE, CrossDimProjection.exactLog2(1.0 / 3.0));
    }

    // ------------------------------------------------------------ hygiene

    @Test
    void emptyInputProducesNothing() {
        assertTrue(project(new LongOpenHashSet(), OW_TO_NETHER, 0).isEmpty());
        assertFalse(CrossDimProjection.intersects(new LongOpenHashSet(), cells(cellKey(0, 0))));
        assertFalse(CrossDimProjection.intersects(cells(cellKey(0, 0)), new LongOpenHashSet()));
    }

    @Test
    void intersectsIteratesTheSmallerSide() {
        LongOpenHashSet big = new LongOpenHashSet(256);
        for (int i = 0; i < 200; i++) big.add(cellKey(i, 0));
        assertTrue(CrossDimProjection.intersects(cells(cellKey(199, 0)), big));
        assertTrue(CrossDimProjection.intersects(big, cells(cellKey(199, 0))));
        assertFalse(CrossDimProjection.intersects(big, cells(cellKey(0, 1))));
    }
}
