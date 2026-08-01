package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Task #42 — the geometry half of Overworld↔Nether island linking.
 *
 * <p>Pure math over grid-cell keys: no world access, no chunks, no tickets, no
 * scheduler state. The "virtual regions" of the feature request are never
 * materialised as {@link Region} objects — a projected region would have to be
 * registered, claimed, colour-gated and drained, and it holds nothing to tick.
 * All that is ever needed is the QUESTION "does island A's footprint, mapped
 * through the portal transform, touch island B's footprint?", and that is a set
 * intersection over {@code long} keys.
 *
 * <h2>Project DOWN, never UP</h2>
 * Up-projection (Nether→Overworld, ×8) is the exact preimage of down-projection
 * (Overworld→Nether, ÷8), so both directions of the question are the SAME test
 * once both sides are expressed in the finer dimension's cell space:
 * <pre>{@code
 *   downProj(overworldIsland.cells) ∩ netherIsland.cells != ∅
 * }</pre>
 * That matters for cost: one Nether cell's preimage is an aligned 8×8 block of
 * 64 Overworld cells, and materialising the up-projection would inflate every
 * Nether island by ×64 for nothing. Down-projection instead COLLAPSES (up to 64
 * Overworld cells share one key), so the projected set is never larger than the
 * source and is usually far smaller.
 *
 * <h2>Exactness at cell granularity</h2>
 * With {@link RegionGrid#DEFAULT_SHIFT} = 3 a cell is 8×8 chunks = 128×128
 * blocks, and cell index {@code c} covers blocks {@code [128c, 128c+128)}.
 * Divided by 8 that is {@code [16c, 16c+16)}, whose Nether cell index is
 * {@code floor(16c/128) = c >> 3}, and {@code 16c+15 < 128(⌊c/8⌋+1)} always —
 * so one Overworld cell lands in exactly ONE Nether cell and no cell ever
 * straddles a boundary. Arithmetic {@code >>} floors toward negative infinity,
 * which is what vanilla's {@code Mth.floor(x * scale)} does, so negative
 * coordinates need no special case.
 *
 * <p>The scale is NOT hardcoded. Vanilla's {@code DimensionType.coordinateScale}
 * is a datapack field and {@code getTeleportationScale(from, to)} is a property
 * of the PAIR, so callers pass the live value; this class only assumes it is
 * positive. Powers of two take a shift fast path, anything else takes the
 * generic rectangle path, and both floor identically to vanilla.
 *
 * <h2>Dilation</h2>
 * The scaled point is only the SEARCH ORIGIN: vanilla then looks for an exit
 * portal within 16 blocks going into the Nether and 128 going into the
 * Overworld ({@code PortalForcer}). Both slacks are ±16 blocks once expressed in
 * Nether-block space (128 Overworld blocks ÷ 8 = 16), so a footprint within 16
 * blocks of a cell edge can genuinely exchange traffic with the neighbouring
 * cell. Dilating by exactly that is not safety margin — it is vanilla's real
 * pairing reach, so nothing linked by it is a false link, and 16 &lt; 128 means
 * the dilation only ever adds the immediately adjacent cell.
 */
public final class CrossDimProjection {

    private CrossDimProjection() {}

    /** Vanilla {@code PortalForcer} search radius going INTO the Nether. */
    public static final int NETHER_PORTAL_RADIUS = 16;
    /** Vanilla {@code PortalForcer} search radius going INTO the Overworld. */
    public static final int OVERWORLD_PORTAL_RADIUS = 128;

    /** Cell key layout, identical to {@link RegionGrid#keyFor(int, int)}. */
    public static long cellKey(int cellX, int cellZ) {
        return (cellX & 0xFFFFFFFFL) | ((cellZ & 0xFFFFFFFFL) << 32);
    }

    public static int cellX(long cellKey) {
        return (int) cellKey;
    }

    public static int cellZ(long cellKey) {
        return (int) (cellKey >> 32);
    }

    /** Blocks covered by one grid cell along one axis: {@code 2^shift} chunks. */
    public static int cellSizeBlocks(int gridShift) {
        return 1 << (gridShift + 4);
    }

    /**
     * The dilation to apply, in DESTINATION blocks, for a transfer whose
     * destination is the given dimension side.
     *
     * <p>Both of vanilla's radii are 16 blocks once measured in Nether space, so
     * the caller does not need to know which way the transfer goes — but the
     * value is derived rather than written as {@code 16} so a datapack that
     * changes the scale keeps the two consistent.
     */
    public static int dilationInDestBlocks(double scaleSrcToDest) {
        // Going into the Nether (scale < 1): vanilla searches NETHER_PORTAL_RADIUS
        // in destination blocks. Going into the Overworld (scale > 1): vanilla
        // searches OVERWORLD_PORTAL_RADIUS destination blocks, which is the same
        // distance measured in the coarser space.
        return scaleSrcToDest < 1.0
                ? NETHER_PORTAL_RADIUS
                : (int) Math.round(OVERWORLD_PORTAL_RADIUS * 1.0);
    }

    /**
     * Project {@code srcCells} into destination cell space, dilated by
     * {@code dilationDestBlocks}, appending into {@code out}.
     *
     * <p>Allocation discipline: primitive {@code long} end to end, no boxing, no
     * intermediate collections. {@code out} is expected to be a reused set that
     * the caller {@code clear()}s — island rebuild once had Long-key boxing as
     * its single largest allocation source, and this runs on the same path.
     *
     * @param scaleSrcToDest vanilla's {@code getTeleportationScale(src, dest)} —
     *                       0.125 for Overworld→Nether, 8.0 for Nether→Overworld
     */
    public static void project(LongSet srcCells, int gridShift, double scaleSrcToDest,
                               int dilationDestBlocks, LongSet out) {
        if (srcCells.isEmpty()) {
            return;
        }
        if (!(scaleSrcToDest > 0.0) || !Double.isFinite(scaleSrcToDest)) {
            throw new IllegalArgumentException("non-positive teleportation scale: " + scaleSrcToDest);
        }
        final int cellBlocks = cellSizeBlocks(gridShift);
        final int destCellShift = gridShift + 4;

        // Fast path: the scale is a power of two AND the dilation cannot pull in
        // more than the adjacent cell. Everything is integer shifts, exactly
        // matching Mth.floor including negatives.
        int log2 = exactLog2(scaleSrcToDest);
        boolean fastPath = log2 != Integer.MIN_VALUE;

        LongIterator it = srcCells.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int cx = cellX(key);
            int cz = cellZ(key);

            // Source cell footprint in source blocks: [c * cellBlocks, +cellBlocks).
            long sxMin = (long) cx * cellBlocks;
            long sxMax = sxMin + cellBlocks - 1;
            long szMin = (long) cz * cellBlocks;
            long szMax = szMin + cellBlocks - 1;

            long dxMin;
            long dxMax;
            long dzMin;
            long dzMax;
            if (fastPath) {
                dxMin = shiftScale(sxMin, log2);
                dxMax = shiftScale(sxMax, log2);
                dzMin = shiftScale(szMin, log2);
                dzMax = shiftScale(szMax, log2);
            } else {
                // Vanilla multiplies a double and Mth.floors it. Coordinates stay
                // far inside 2^53, so double math is exact here.
                dxMin = (long) Math.floor(sxMin * scaleSrcToDest);
                dxMax = (long) Math.floor(sxMax * scaleSrcToDest);
                dzMin = (long) Math.floor(szMin * scaleSrcToDest);
                dzMax = (long) Math.floor(szMax * scaleSrcToDest);
            }

            dxMin -= dilationDestBlocks;
            dxMax += dilationDestBlocks;
            dzMin -= dilationDestBlocks;
            dzMax += dilationDestBlocks;

            int cellXMin = (int) (dxMin >> destCellShift);
            int cellXMax = (int) (dxMax >> destCellShift);
            int cellZMin = (int) (dzMin >> destCellShift);
            int cellZMax = (int) (dzMax >> destCellShift);

            for (int dx = cellXMin; dx <= cellXMax; dx++) {
                for (int dz = cellZMin; dz <= cellZMax; dz++) {
                    out.add(cellKey(dx, dz));
                }
            }
        }
    }

    /** Arithmetic-shift scaling that floors toward negative infinity. */
    private static long shiftScale(long v, int log2) {
        return log2 >= 0 ? v << log2 : v >> (-log2);
    }

    /**
     * {@code log2(scale)} when the scale is an exact power of two (including
     * reciprocals like 0.125), else {@link Integer#MIN_VALUE}.
     */
    static int exactLog2(double scale) {
        for (int k = -30; k <= 30; k++) {
            if (scale == Math.scalb(1.0, k)) {
                return k;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Do the two cell sets share a key? Iterates the SMALLER set, the pattern
     * {@code TemporalControl.intersects} already uses.
     */
    public static boolean intersects(LongSet a, LongSet b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        LongSet small = a.size() <= b.size() ? a : b;
        LongSet large = small == a ? b : a;
        LongIterator it = small.iterator();
        while (it.hasNext()) {
            if (large.contains(it.nextLong())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience for tests and diagnostics: does the projection of
     * {@code srcCells} touch {@code destCells}?
     */
    public static boolean linked(LongSet srcCells, LongSet destCells, int gridShift,
                                 double scaleSrcToDest, int dilationDestBlocks) {
        LongOpenHashSet projected = new LongOpenHashSet(Math.max(8, srcCells.size()));
        project(srcCells, gridShift, scaleSrcToDest, dilationDestBlocks, projected);
        return intersects(projected, destCells);
    }
}
