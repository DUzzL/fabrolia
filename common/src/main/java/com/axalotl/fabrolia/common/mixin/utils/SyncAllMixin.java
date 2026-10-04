package com.axalotl.fabrolia.common.mixin.utils;

import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.warden.AngerManagement;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = {
        // BinaryHeap removed 2026-08-01 (task #40, audit finding #5), same grounds
        // as WorldBorder (2026-06-10): the monitor bought nothing and cost on a hot
        // path. javap-verified pure leaf — every method touches only the private
        // Node[]/size plus fields of nodes it owns; zero outward calls, so no cycle
        // can pass through it. Also 100% uncontended: PathFinder.openSet is private
        // per-PathFinder, each PathNavigation builds its own PathFinder, and every
        // heap access happens inside PathFinder.findPath which PathFinderMixin
        // already makes synchronized (itself under the nav monitor) — three nested
        // monitors guarding one thread's private array, paid per insert/pop/
        // changeCost in the A* inner loop (up to maxVisitedNodes times per path,
        // 12k pathing mobs). Re-adding it is only defensible if a third-party mod
        // shares PathFinder instances across mobs.
        // LevelChunkTicks: KEEP (re-affirmed 2026-08-01, task #41). This is not
        // inertia — it is the container's ONE monitor and Lithium's
        // world.tick_scheduler overwrite depends on it for correctness, not just
        // for collection safety. Lithium replaces the vanilla PriorityQueue with
        // a (time,priority) bucket tree PLUS a cached `nextTickQueue` head
        // pointer PLUS an `allTicks` dedup set, and the invariant tying the
        // three together ("nextTickQueue is the minimum-key bucket") is
        // established by whichever of queueTick/poll/removeIf ran last. Each of
        // those preserves it when run alone; nothing preserves it when a
        // schedule() from one worker interleaves a drain's poll() on another.
        // The three consequences, all source-verified against
        // lithium-fabric@mc26.2-0.25.1:
        //   §4.4 poll() on an empty-but-present head bucket returns null and
        //        leaves it wired as both nextTickQueue and the map's first key;
        //        queueTick re-selects it by firstLongKey() from then on, so the
        //        chunk's scheduled ticks stop FOREVER, silently, while
        //        hasScheduledTick keeps answering true.
        //   §4.5 updateNextTickQueue(true) removes the first map entry BEFORE
        //        checking it was the right one, so the guard destroys a bucket
        //        of live ticks and then throws out of LevelTicks.tick.
        //   §4.6 getAll()/pack() route through OrderedTickQueue.iterator() ->
        //        sort() -> handleCompaction(), which reallocates the backing
        //        array and resets firstIndex under a concurrent drainer.
        // Removing this entry is a real project, not a line deletion: schedule,
        // peek, hasScheduledTick and unpack would all need their own guard
        // first. LevelChunkTicksMixin (priority 2000, so its handlers escape
        // postApply and get NO blanket bit) already carries explicit monitors
        // for the three methods above and asserts this entry is still in force
        // at runtime — fabrolia$assertBlanketSyncOnce logs a loud ERROR if the
        // class comes out with zero synchronized methods. TickContainer-
        // WriteShapedApiTest#levelChunkTicksIsStillInTheBlanketSyncSet pins it
        // at build time. Do not remove either without the other.
        LevelChunkTicks.class,
        // DynamicGraphMinFixedPoint (2026-08-01, task #40 / audit finding #1): NOT
        // the light engine — 26.2's light engine does not use this class at all.
        // Subclasses are the chunk ticket/simulation propagator (Loading/Simulation
        // ChunkTracker, DistanceManager$FixedPlayerDistanceChunkTracker/
        // $PlayerTicketTracker) and POI's PoiManager$DistanceTracker. The monitor
        // is held across ENTIRE unbounded relaxation waves (runUpdates(MAX_VALUE)
        // is protected final — the blanket mask does NOT skip final). It is
        // load-bearing in two places: PoiDistanceTrackerMixin drains its deferral
        // queue under synchronized(this) on the POI tracker (7e769ee), and it
        // serialises any residual off-main entry into the ticket trackers. Do NOT
        // remove without replacing the POI tracker's guard. The convoy this monitor
        // caused on the spawn hot path (workers running naturalSpawnChunkCounter
        // waves from hasPlayersNearby while holding the ChunkMap monitor) is closed
        // structurally instead: DistanceManagerMixin reroutes the spawn-proximity
        // reads onto a completed-wave snapshot (FixedPlayerDistanceChunkTrackerMixin)
        // so workers never run — or wait on — the propagator at all; waves now run
        // only inside DistanceManager.runAllUpdates on main (off-main refusal in
        // ServerChunkCacheMixin.fabrolia$serializeDistanceUpdatesWithPlayerMove).
        DynamicGraphMinFixedPoint.class,
        // PathNavigation (task #40 rework of the task-#32 area): the blanket bit
        // stays for the owner-side mutators (tick/moveTo/createPath/recomputePath/
        // trimPath/followThePath...), but the monitor is no longer a door MAIN can
        // block on: SynchronisePlugin exempts the stale-tolerant reads (getPath,
        // isDone, isInProgress, isStuck, getTargetPos, getMaxDistanceToWaypoint,
        // canFloat, getNodeEvaluator) and exempts stop(), whose cross-context
        // callers are owner-routed by FabroliaPathNavigationMixin.fabrolia$routedStop
        // → NavRecompute.stopCrossContextDeferred (covers TeleportCommand /
        // PiglinAi.stopWalking / randomTeleport — every MAIN-side mutator door the
        // 2026-08-01 audit found; the debug ENTITY_PATHS poll is covered by the
        // read exemptions). RESIDUAL, accepted and documented: (a) the owner still
        // holds the monitor across the getGroundY/trimPath/isStableDestination →
        // getChunk(load=true) → ChunkBridgeWait park — after this change the only
        // threads that can block on it are OTHER WORKERS (YieldJobSite cross-villager
        // createPath, onAttributeUpdated), which is a bounded stall, not a cycle:
        // main keeps pumping mainThreadProcessor, so the holder's chunk future
        // completes. (b) subclass overrides (FlyingPathNavigation.tick has no
        // super.tick()) are NOT covered by the blanket — flying mobs already run
        // vanilla's unmonitored shape; do not "fix" that by listing subclasses here
        // without re-auditing every door.
        PathNavigation.class,
        // SectionStorage REMOVED 2026-08-01 (task #38, second half): the
        // staged blanket monitor from 7d58bbb is gone now that every one of
        // the four things it actually covered has a real replacement:
        //   1. `storage` — WRAPPED (never replaced) in LockedLong2ObjectMap at
        //      <init> RETURN with injector order = Integer.MAX_VALUE, so it
        //      wraps Lithium's ListeningLong2ObjectOpenHashMap instead of
        //      fighting it (the @Shadow initialiser Lithium silently defeated
        //      was the true root cause of the 2026-07-29 01:51 rehash AIOOBE);
        //      one-shot startup assert makes any future silent defeat loud.
        //   2. Lithium's `columns` BitSet sidecar — writes serialise inside
        //      the wrapper's write lock; reads snapshot-and-CLONE under the
        //      read lock in FabroliaLithiumPoiStorageMixin (lock released
        //      across unpackChunk — a hold there self-deadlocks and AB-BAs
        //      against loadLock).
        //   3. C2ME's merged c2me$unloadPoi — whole body under loadLock and
        //      skipped while a load is in flight (PoiUnloadGuardMixin).
        //   4. getOrLoad's loser-vs-winner ISE window — per-chunk completion
        //      handshake (PoiChunkLoadFence) + unpackPendingLoads skip +
        //      bounded retry, in SectionStorageMixin.
        // The full corrected lock order and its two invariants live in
        // SectionStorageMixin's javadoc. The monitor also never made reads
        // safe here either: every Lithium column read escaped it lazily.
        // PoiSection REMOVED 2026-08-01 (task #38): it was the direct cause of
        // three production watchdog deadlocks (2026-07-29 07:21/10:08/10:26).
        // The blanket bit made PoiSection.getRecords synchronized, so a worker
        // holding the DistanceTracker monitor in runUpdates -> getLevelFromSource
        // -> isVillageCenter blocked on a section that another worker held in
        // release -> setDirty -> DistanceTracker.update. The claim in 7d58bbb
        // that these classes are "leaves" was wrong in both directions.
        // Replacement is structural, not another monitor: PoiDistanceTrackerMixin
        // defers every notification INTO the village graph, which deletes all
        // four cycles of the DistanceTracker/SectionStorage/PoiSection triangle.
        // Writes stay serialised by PoiSectionMixin's targeted add/remove/release/
        // refresh monitors. Structural writes publish one immutable read view
        // before their exact vanilla dirty notification; vanilla getRecords/pack/
        // scalar reads and every Lithium reader consume that volatile view without
        // a monitor. The blanket bit never made reads safe anyway — getRecords
        // returns a LAZY Stream and Lithium exposes an escaping iterator, both
        // consumed after the method monitor exits.
        EuclideanGameEventListenerRegistry.class,
        // SimpleCriterionTrigger removed 2026-07-17: the blanket per-instance
        // monitor was held by MAIN across trigger -> award -> rewards ->
        // function fence (vanilla runs awards inside trigger()), while a
        // region worker ticking a player blocked on the same monitor inside
        // the entity phase main was fencing against = production three-way
        // deadlock (main -> unit claim -> entity barrier -> worker -> monitor
        // -> main). The monitor is unnecessary in 26.2: the trigger class is
        // stateless (listener maps live on PlayerAdvancements, guarded by the
        // per-player monitor pairing in PlayerAdvancementsMixin +
        // AdvancementBuffer.buildPending), and ALL trigger paths now route
        // through AdvancementBuffer.trigger — evaluated and awarded on the
        // player's owner thread, global reward work routed to Main drains.
        AngerManagement.class,
        // WorldBorder removed 2026-06-10: synchronise-all turned every border READ
        // (isWithinBounds per spawn position, clamp per entity move) into contention on
        // one global monitor — 4%+ of 30 region workers parked in C2 monitor blobs under
        // dense spawn load. Border state is mutated only by /worldborder on main; the
        // extent object swap is a reference write (atomic), and a one-tick-stale read
        // during a border lerp is behaviorally invisible. No sync needed for readers.
        ActiveProfiler.class,
})
public class SyncAllMixin {
}
