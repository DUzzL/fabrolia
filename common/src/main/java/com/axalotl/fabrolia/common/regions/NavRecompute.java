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

    /** Coalesce key for deferred stops — a distinct record type so a pending
     *  recompute never swallows a stop's coalescing slot (and vice versa). */
    private record StopKey(PathNavigation nav) {}

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
     * Task #40 (audit finding #2): owner-routed {@code PathNavigation.stop()}.
     *
     * <p>{@code stop} is exempted from the blanket monitor (SynchronisePlugin)
     * because its body is one reference write — and because it is the ONLY
     * mutator the 2026-08-01 door sweep found MAIN calling on a foreign mob's
     * navigation ({@code TeleportCommand.performTeleport},
     * {@code PiglinAi.stopWalking} from the interact packet,
     * {@code LivingEntity.randomTeleport} / {@code /spreadplayers}). Keeping
     * the bit there meant main could block on a nav monitor a region worker
     * holds across an unbounded {@code ChunkBridgeWait} — main then cannot
     * pump {@code mainThreadProcessor}, the worker's chunk future never
     * completes, watchdog: the task-#32 shape verbatim through a door
     * {@code recomputeOwnerSafe} does not cover.
     *
     * <p>Called from {@code FabroliaPathNavigationMixin.fabrolia$routedStop}
     * ({@code @Inject} at {@code stop} HEAD, monitor-free by plugin
     * exemption). Returns {@code true} when the stop was DEFERRED to the
     * owner's context — the injector then cancels; {@code false} means the
     * caller IS the owner context (or async is off) and the vanilla body
     * runs right now. Deferral tolerance is vanilla's own: the mob keeps
     * following its old path for at most one owner-lane drain (unit start),
     * the same observable class as a stop under server lag; the pre-fix
     * behavior was an unbounded block or a cross-thread unguarded write
     * racing the owner's synchronized tick.
     *
     * <p>The redispatch re-enters {@code stop} in the owner's context and
     * re-resolves ownership there — a mob that migrated (teleport is the
     * common caller!) is chased to whoever owns it then, coalesced per nav.
     */
    public static boolean stopCrossContextDeferred(ServerLevel level, Mob mob, PathNavigation nav) {
        if (mob == null || mob.isRemoved()) {
            return false; // dead mob: the plain write is unobservable — run it
        }
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) {
            return false; // sync / classic mode: vanilla call, vanilla thread
        }
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(mob.chunkPosition().pack());
        return stopDeferred(rs, owner, nav, () -> {
            if (!mob.isRemoved()) {
                nav.stop();
            }
        });
    }

    /** Package-visible core for the regression tests (mirrors how the task-#32
     *  graph is proven against {@link #dispatch}): routes through the SAME
     *  proven dispatch and reports whether the vanilla body must be skipped. */
    static boolean stopDeferred(RegionizedServer rs, IslandRegistry.PIsland owner,
                                PathNavigation nav, Runnable deferredStop) {
        boolean[] ranInline = new boolean[1];
        dispatch(rs, owner, new StopKey(nav), () -> ranInline[0] = true, deferredStop);
        return !ranInline[0];
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
