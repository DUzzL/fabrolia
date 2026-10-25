package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Cross-region effect containment (closes the one gap the color gate leaves open).
 *
 * <p>The color gate guarantees a region's 8 immediate neighbours are quiescent while
 * it ticks (they are different colours, serialized by the done-epoch DAG). So a worker's
 * neighbour-update cascade may freely READ its own region and those neighbours — all
 * distance ≤1, all quiescent. The danger is purely distance-2: a SAME-COLOUR region two
 * cells away ticks concurrently, and a cascade reaches it only by first propagating a
 * state change INTO an intervening neighbour region and letting a block there read across
 * the far boundary. So if a worker never WRITES (or dispatches a neighbour update) outside
 * its own claimed region, the inline cascade stays confined to that region — it can never
 * reach the concurrent distance-2 region, and every read it does is distance ≤1 and safe.
 *
 * <p>This class is that fence: {@link #crossRegionTargetOrNull} selects deferral when a
 * worker is about to touch a block outside its spatial authority. Ordinary region helpers
 * own just their claimed region. After an island's queue-only entity dispatch has joined,
 * {@link IslandEntityPhaseScope} authenticates its coordinator's serial entity pass and
 * permits complete vanilla callbacks throughout that island's core and write halo. Other
 * islands and dimensions remain protected. Keeping the block removal and its explosion
 * callback inline together prevents a second explosion from igniting the same TNT twice.
 *
 * <p>Replay: deferred effects of island timelines are replayed by the owning unit's
 * coordinator (or, at a topology rebuild, by Main while it holds every island claim);
 * only the global timeline's queue is replayed on Main after the phase barrier. The
 * "every region is quiescent" premise holds for that Main replay in sync mode only.
 *
 * <p>Cost: in-region effects (≈99.95% — the detector's live figure) are never deferred and
 * run exactly as before; only boundary crossings pay a queue add + a one-sub-phase delay
 * within the same tick. Island scheduled/BE ticks run with no claimed region (currentRegion
 * == null) and are already island-isolated, so they are never deferred.
 */
public final class CrossRegionGuard {

    /** Witness for the last setBlock on this thread: true = the write was
     *  DEFERRED (optimistic-true) rather than applied inline. Lets callers
     *  with vanilla failure fallbacks (falling-block landings) verify the
     *  replayed outcome and conserve matter (2026-07-15 duper source loss). */
    public static final ThreadLocal<Boolean> LAST_SETBLOCK_DEFERRED =
            ThreadLocal.withInitial(() -> false);


    private CrossRegionGuard() {}

    /**
     * Fast, allocation-free check: would the CURRENT region worker act on a block outside
     * its current spatial authority? Returns the level's {@link RegionizedServer} (so the caller can
     * snapshot {@code pos}, build the replay op and {@link RegionizedServer#deferCrossRegion}
     * it, then cancel the inline action) — or {@code null} for the in-region / not-a-worker
     * common path, which must run inline. Reads only {@code pos.getX()/getZ()}, so a reused
     * {@code MutableBlockPos} is fine; the caller snapshots only on the rare deferral path.
     */
    public static RegionizedServer crossRegionTargetOrNull(Level level, BlockPos pos) {
        if (FabroliaConfig.disabled
                || !FabroliaConfig.fabroliaRedstoneCrossRegionGuard
                || !FabroliaConfig.enableRegionThreading
                || !RegionScheduler.isActive()) {
            return null;
        }
        if (!(Thread.currentThread() instanceof RegionWorkerThread w)) {
            return null;    // main / pool / IO — the serialization point, run inline
        }
        IslandEntityPhaseScope exclusive = w.exclusiveEntityPhase;
        if (exclusive != null) {
            if (exclusive.ownsTarget(level, pos)) {
                return null; // one serial entity pass owns this entire island + halo
            }
            // An exclusive phase is spatially bounded. In particular, do not
            // let its null currentRegion stamp open writes to another island.
            return level instanceof ServerLevel sl ? RegionScheduler.forLevel(sl) : null;
        }
        Region claimed = w.currentRegion;
        if (claimed == null) {
            return null;    // island slice or unclaimed help batch — already isolated
        }
        RegionGrid grid = RegionScheduler.grid();
        if (grid == null) {
            return null;
        }
        // Hot path: compare grid keys via the shared grid only — no per-level map lookup.
        // 99.95% of worker writes are in-region (the detector's live figure), so the lookup
        // below is reached only on the rare boundary crossing.
        long targetKey = grid.keyFor(pos.getX() >> 4, pos.getZ() >> 4);
        if (targetKey == claimed.gridKey && level == claimed.level) {
            // (2026-07-15) grid keys do not encode the LEVEL: an Overworld
            // worker whose claimed key numerically equals an End target's key
            // wrote End blocks inline mid-End-phase. Same-key different-level
            // now falls through to the cross-region defer path.
            return null;    // in-region — untouched
        }
        if (!(level instanceof ServerLevel sl)) {
            return null;
        }
        return RegionScheduler.forLevel(sl);
    }
}
