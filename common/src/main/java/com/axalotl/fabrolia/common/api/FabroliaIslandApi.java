package com.axalotl.fabrolia.common.api;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.IslandClock;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * Public island API for mods.
 *
 * <p>Work routed via {@link #executeOnOwner} retains the owning region's
 * ordering in both wall-tick and async-island modes. Async islands retain
 * their local clocks and deadlines, but mutable gameplay bodies execute
 * serially across islands on their actual worker owners or physical Main.
 *
 * <p>Ownership rules for mods, enforced by RegionAccessDetector in
 * development/compat modes: reading or mutating entities, block entities,
 * chunks, or containers you did not reach through your own tick context must
 * go through {@link #executeOnOwner} (or trigger a merge via interaction, when
 * async time is active). Global collections (player lists, global entity
 * iteration), scoreboards, advancements, bossbars and other W-lane state stay
 * safe to touch from the global/main context only.
 */
public final class FabroliaIslandApi {

    private FabroliaIslandApi() {}

    /** Stable id of the island owning this position, or -1 (regions inactive /
     *  unpopulated area). Main or worker thread. */
    public static long islandId(ServerLevel level, BlockPos pos) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null) return -1;
        IslandRegistry.PIsland p = rs.islandRegistry.byCell(
                rs.beCellKeyFor(pos));
        return p == null ? -1 : p.id;
    }

    /** Island-local logical time for relative timers; falls back to dimension
     *  game time when the region runtime or owner is unavailable. */
    public static long islandLogicalTick(ServerLevel level, BlockPos pos) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null) return level.getGameTime();
        IslandRegistry.PIsland p = rs.islandRegistry.byCell(rs.beCellKeyFor(pos));
        return p == null ? level.getGameTime() : p.logicalTick;
    }

    /** Dimension-global calendar time (the W clock: day/night, weather,
     *  schedules). Always safe to read from any context. */
    public static long worldTime(ServerLevel level) {
        return IslandClock.wallTime(level);
    }

    /**
     * Run {@code task} under the ownership/ordering domain of the given
     * position: inline only with both region ownership and current gameplay
     * admission, otherwise queued to the owning region's task lane.
     */
    public static void executeOnOwner(ServerLevel level, BlockPos pos, Runnable task) {
        RegionScheduler.executeAt(level, new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), task);
    }

    /** Entity-owner variant of {@link #executeOnOwner}. */
    public static void executeOnOwner(Entity entity, Runnable task) {
        RegionScheduler.executeForEntity(entity, task);
    }

    /**
     * Capture the current region-worker step for a handoff to another executor.
     * Submit the returned wrapper, rather than {@code task} itself, when the current
     * region task will synchronously wait for that executor and the callback may call
     * {@link #executeOnOwner}.  The permit expires with the exact parent step, so a
     * delayed callback cannot cross a player exact-turn admission cut as stale work.
     *
     * <p>The wrapper carries lifetime, not world-access authority. A foreign
     * callback must route mutations through {@code executeOnOwner}; it must not
     * wait for queued owner work while its parent step still owns that region.
     */
    public static Runnable causalHandoff(Runnable task) {
        return RegionScheduler.wrapCausalRegionHandoff(task);
    }

    /**
     * Observe this caller's spatial ownership/ordering context for {@code pos}:
     * its exact region claim, a quiescent owning unit, an already-held Main
     * claim, or the authenticated exclusive server turn.
     *
     * <p>This is not a transferable lease or admission to a mutable gameplay
     * body. A causal handoff alone cannot supply gameplay authority.
     * Spatial ownership ends with the enclosing claim/turn. A physical Main
     * thread without an existing quiescence receipt conservatively returns
     * false, including wall/synchronous and flat modes: their feature flags do
     * not prove exclusion of parallel work. Route such work through
     * {@link #executeOnOwner(ServerLevel, BlockPos, Runnable)}.
     */
    public static boolean ownsCurrentContext(ServerLevel level, BlockPos pos) {
        return RegionScheduler.ownsCurrentContext(level, pos);
    }
}
