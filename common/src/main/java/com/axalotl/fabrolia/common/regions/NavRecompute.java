package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaSafeNavigation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/**
 * Task #32 (2026-07-28 production watchdog deadlock) — owner-routed navigation
 * recompute.
 *
 * <p>Every {@code PathNavigation} method is instance-synchronized
 * (SyncAllMixin), and the owner worker holds that monitor across its whole
 * {@code tick()} — including the {@code getGroundY → getBlockState → getChunk}
 * main-thread bridge when the next waypoint sits in a not-yet-loaded chunk
 * (vanilla loads it synchronously; a worker parks on the main-serviced
 * future). Any OTHER thread that enters the same monitor while its own
 * progress is required to service that future closes a cycle: the production
 * crash was main (block-place packet → {@code sendBlockUpdated} →
 * {@code recomputePath}) parked on a monitor held by a region worker that was
 * itself parked in {@code awaitMainThreadResult} — main could no longer pump
 * {@code mainThreadProcessor}, watchdog fired.
 *
 * <p>Invariant restored here: <b>a nav monitor is only ever entered from its
 * mob's own execution context</b> — the owning island's worker, or main while
 * the mob is main-owned (wall/global domain) or while main provably holds the
 * owner island quiescent (interaction claim / global fence). Every other
 * caller defers the recompute to the owner's ordered island lane (serviced at
 * unit start, before the entity phase, under the unit claim — the nav monitor
 * is free there) instead of blocking. Vanilla tolerance: {@code recomputePath}
 * is already rate-limited (20 gt) and self-defers via
 * {@code hasDelayedRecomputation}; an owner-cadence deferral is the same
 * observable class as vanilla-under-lag, and the pre-fix behavior (full
 * pathfinding executed by whatever thread changed the block, racing the owner)
 * was both the deadlock edge and an ownership violation.
 */
public final class NavRecompute {

    private NavRecompute() {}

    /** Coalesce key for the island lane: identity of the nav — one pending
     *  recompute per navigation per island (recompute is idempotent-latest). */
    private record Key(PathNavigation nav) {}

    /**
     * Replacement for the {@code navigation.recomputePath()} loop body of
     * {@code ServerLevel.sendBlockUpdated} (and the explosion suppress-drain):
     * inline exactly when the calling thread owns the mob's simulation,
     * otherwise a non-blocking deferral to the owner.
     */
    public static void recomputeOwnerSafe(ServerLevel level, PathNavigation nav) {
        Mob mob = nav instanceof FabroliaSafeNavigation safe ? safe.fabrolia$navMob() : null;
        if (mob == null) {
            nav.recomputePath(); // mixin absent (dev edge) — vanilla body
            return;
        }
        if (mob.isRemoved()) {
            return; // leaked entry (task #24 class): dead mobs get no notify
        }
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) {
            // Sync / classic mode: vanilla call, vanilla thread. (Classic
            // pool workers already tolerate brief monitor contention here —
            // the holder never waits on the blocker; main only waits at
            // pumping barriers.)
            nav.recomputePath();
            return;
        }
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(mob.chunkPosition().pack());
        // Re-resolve ownership at execution time — the mob may have migrated
        // (merge/split/portal) between enqueue and unit start; the recursion
        // re-routes and converges on whoever owns it then (coalesced per nav,
        // so a migrating mob chases at most one pending task per island).
        dispatch(rs, owner, new Key(nav), nav::recomputePath,
                () -> recomputeOwnerSafe(level, nav));
    }

    /**
     * Routing core (package-visible for the regression tests — the deadlock
     * graph is proven against THIS method): run {@code inlineRecompute} only
     * in the mob's own execution context, defer everywhere else, block
     * nowhere. {@code owner == null} means the mob is main-owned (wall/global
     * domain).
     */
    static void dispatch(RegionizedServer rs, IslandRegistry.PIsland owner,
                         Object coalesceKey, Runnable inlineRecompute, Runnable redispatch) {
        if (owner == null) {
            var srv = RegionScheduler.getServer();
            if (srv == null || srv.isSameThread()) {
                inlineRecompute.run();
            } else {
                srv.execute(redispatch);
            }
            return;
        }
        if (currentThreadOwns(rs, owner)) {
            inlineRecompute.run();
            return;
        }
        CommandShards.enqueueIslandTask(owner, coalesceKey, redispatch);
    }

    /** True when the calling thread is the owner island's execution context:
     *  its unit coordinator / a worker inside one of its regions, or main
     *  while it holds this island quiescent (interaction claim or global
     *  fence — both imply no unit is mid-flight, so the nav monitor cannot be
     *  held by a parked worker). */
    private static boolean currentThreadOwns(RegionizedServer rs, IslandRegistry.PIsland owner) {
        if (Thread.currentThread() instanceof RegionWorkerThread w) {
            Region r = w.currentRegion;
            EpochDomain d = r != null ? r.domain : w.currentDomain;
            return d != null && d.owner == owner;
        }
        var srv = RegionScheduler.getServer();
        if (srv != null && srv.isSameThread()) {
            return rs.mainHoldsAllClaims() || IslandCommands.mainHolds(owner);
        }
        return false;
    }
}
