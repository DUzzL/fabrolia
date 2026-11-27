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
 * <p>Routing to one region does not serialize work in another: independent
 * islands can run mutable callbacks concurrently on different local clocks.
 *
 * <p>Ownership rules for mods, enforced by RegionAccessDetector in
 * development/compat modes: reading or mutating entities, block entities,
 * chunks, or containers you did not reach through your own tick context must
 * go through {@link #executeOnOwner} (or trigger a merge via interaction, when
 * async time is active). Global collections (player lists, global entity
 * iteration), scoreboards, bossbars and other W-lane state stay safe to touch
 * from the global/main context only. A player's advancements may be awarded or
 * revoked only on the thread that currently owns that player (its island turn,
 * or Main for a Main-owned player or under the global fence); elsewhere
 * {@code PlayerAdvancements.award/revoke} throw.
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
        if (rs == null || !rs.asyncActive) return IslandClock.wallTime(level);
        IslandRegistry.PIsland p = rs.islandRegistry.byCell(rs.beCellKeyFor(pos));
        return p == null ? IslandClock.wallTime(level) : p.logicalTick;
    }

    /** Dimension-global calendar time (the W clock: day/night, weather,
     *  schedules). Always safe to read from any context. */
    public static long worldTime(ServerLevel level) {
        return IslandClock.wallTime(level);
    }

    /**
     * Run {@code task} inline under existing spatial ownership. With a shared clock and
     * region workers, unowned submissions run FIFO on exclusive Main after the tick.
     * With independent clocks they route to the target owner. With regions off, physical
     * Main runs inline; other callers post to its native queue. Queued submission is not
     * completion. Do not wait for the callback from a tick that must finish first.
     * A position never retargets a replacement level; an entity follows its current level.
     * Turning regions off finishes already accepted shared tasks before retiring their owner;
     * new submissions can be rejected during the transition. Normal stop settles accepted work before saving.
     *
     * <p>An addressed callback may run on one region helper, not the island's exclusive
     * coordinator. It cannot synchronously invoke a command block there: even the local
     * command proof covers an entire island. With the region scheduler active, submit
     * the <em>whole</em> command-capable operation through {@link RegionScheduler#enqueueGlobal}
     * instead, before it starts. Resolve its target and consume the actual command result
     * inside that exclusive Main callback. Never queue just the command and wait for it
     * while retaining a region claim.
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
