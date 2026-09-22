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
 * <h2>Intersect populated cells, never expand empty space</h2>
 * At vanilla scales, projecting Overworld cells into Nether space collapses
 * the footprint. Datapacks may reverse or greatly enlarge that scale ratio.
 * {@link #projectOnto} intersects each projected rectangle with populated
 * destination cells, bounding work by the actual footprints rather than by
 * the area of otherwise empty space between them.
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
 * positive. Bounds are calculated in destination block space before flooring
 * to cell coordinates, including at negative coordinates.
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
     * For populated-footprint queries use {@link #projectOnto}; this explicit
     * materialization API necessarily costs the full projected area.
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
        project(srcCells, null, gridShift, scaleSrcToDest, dilationDestBlocks, out);
    }

    /**
     * Append only projected cells present in {@code destCells}. Work per source
     * cell is bounded by the smaller of its projected area and the populated
     * destination footprint, regardless of the coordinate scale ratio.
     */
    public static void projectOnto(LongSet srcCells, LongSet destCells, int gridShift,
                                   double scaleSrcToDest, int dilationDestBlocks, LongSet out) {
        if (destCells.isEmpty()) {
            return;
        }
        project(srcCells, destCells, gridShift, scaleSrcToDest, dilationDestBlocks, out);
    }

    private static void project(LongSet srcCells, LongSet destCells, int gridShift,
                                double scaleSrcToDest, int dilationDestBlocks, LongSet out) {
        if (srcCells.isEmpty()) {
            return;
        }
        if (!(scaleSrcToDest > 0.0) || !Double.isFinite(scaleSrcToDest)) {
            throw new IllegalArgumentException("non-positive teleportation scale: " + scaleSrcToDest);
        }
        final int cellBlocks = cellSizeBlocks(gridShift);

        LongIterator it = srcCells.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int cx = cellX(key);
            int cz = cellZ(key);

            // Source cell footprint in source blocks: [c * cellBlocks, +cellBlocks).
            long sxMin = (long) cx * cellBlocks;
            long sxEnd = sxMin + cellBlocks;
            long szMin = (long) cz * cellBlocks;
            long szEnd = szMin + cellBlocks;

            // Keep scaled bounds wide until clipping to representable cell keys.
            // An int narrowing cast or an overflowing left shift can wrap a
            // valid extreme projection back onto unrelated populated cells.
            // Portal transforms scale continuous entity coordinates, not just
            // integer block origins. The source upper endpoint is exclusive;
            // ceil(scaled endpoint) - 1 is the final reachable destination block.
            long cellXMin = Math.max(Integer.MIN_VALUE, (long) Math.floor(
                    (Math.floor(sxMin * scaleSrcToDest) - dilationDestBlocks) / cellBlocks));
            long cellXMax = Math.min(Integer.MAX_VALUE, (long) Math.floor(
                    (Math.ceil(sxEnd * scaleSrcToDest) - 1 + dilationDestBlocks) / cellBlocks));
            long cellZMin = Math.max(Integer.MIN_VALUE, (long) Math.floor(
                    (Math.floor(szMin * scaleSrcToDest) - dilationDestBlocks) / cellBlocks));
            long cellZMax = Math.min(Integer.MAX_VALUE, (long) Math.floor(
                    (Math.ceil(szEnd * scaleSrcToDest) - 1 + dilationDestBlocks) / cellBlocks));
            if (cellXMin > cellXMax || cellZMin > cellZMax) {
                continue;
            }

            long width = cellXMax - cellXMin + 1;
            long height = cellZMax - cellZMin + 1;
            if (destCells != null && width > destCells.size() / height) {
                LongIterator candidates = destCells.iterator();
                while (candidates.hasNext()) {
                    long candidate = candidates.nextLong();
                    int x = cellX(candidate);
                    int z = cellZ(candidate);
                    if (x >= cellXMin && x <= cellXMax && z >= cellZMin && z <= cellZMax) {
                        out.add(candidate);
                    }
                }
            } else {
                for (long dx = cellXMin; dx <= cellXMax; dx++) {
                    for (long dz = cellZMin; dz <= cellZMax; dz++) {
                        long candidate = cellKey((int) dx, (int) dz);
                        if (destCells == null || destCells.contains(candidate)) {
                            out.add(candidate);
                        }
                    }
                }
            }
        }
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
        LongOpenHashSet projected = new LongOpenHashSet(
                Math.max(8, Math.min(srcCells.size(), destCells.size())));
        projectOnto(srcCells, destCells, gridShift, scaleSrcToDest, dilationDestBlocks, projected);
        return !projected.isEmpty();
    }
}
