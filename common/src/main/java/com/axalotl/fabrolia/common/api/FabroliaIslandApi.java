package com.axalotl.fabrolia.common.api;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * Public island API for mods (async-islands compatibility surface, stage A1).
 *
 * <p>Contract today (global tick): every method is safe and meaningful — work
 * routed via {@link #executeOnOwner} runs under the owning region's ordering
 * exactly as Fabrolia's own cross-region deferrals do. When independent island
 * ticking flips (stages A2+, docs/ASYNC-ISLANDS.md), the SAME calls become the
 * thread-safe way to touch state owned by another island: mods that adopt this
 * surface now are forward-compatible with per-island TPS.
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

    /** The island's logical tick (== global game time until async stages flip;
     *  afterwards the owner's local timeline — use for island-local timers). */
    public static long islandLogicalTick(ServerLevel level, BlockPos pos) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null) return level.getGameTime();
        IslandRegistry.PIsland p = rs.islandRegistry.byCell(rs.beCellKeyFor(pos));
        return p == null ? level.getGameTime() : p.logicalTick;
    }

    /** Dimension-global calendar time (the W clock: day/night, weather,
     *  schedules). Always safe to read from any context. */
    public static long worldTime(ServerLevel level) {
        return level.getGameTime();
    }

    /**
     * Run {@code task} under the ownership/ordering domain of the given
     * position: inline when the caller already owns it, otherwise queued to
     * the owning region's task lane (vanilla-ordered with that region's
     * work). THE way for a mod to touch state it does not own.
     */
    public static void executeOnOwner(ServerLevel level, BlockPos pos, Runnable task) {
        RegionScheduler.executeAt(level, new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), task);
    }

    /** Entity-owner variant of {@link #executeOnOwner}. */
    public static void executeOnOwner(Entity entity, Runnable task) {
        RegionScheduler.executeForEntity(entity, task);
    }

    /** True when the current thread context may directly touch state at
     *  {@code pos} without routing (main thread outside parallel phases, or a
     *  region worker holding the owning region). */
    public static boolean ownsCurrentContext(ServerLevel level, BlockPos pos) {
        return com.axalotl.fabrolia.common.regions.CrossRegionGuard
                .crossRegionTargetOrNull(level, pos) == null;
    }
}
