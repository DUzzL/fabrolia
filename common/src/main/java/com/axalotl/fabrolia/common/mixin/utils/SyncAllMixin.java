package com.axalotl.fabrolia.common.mixin.utils;

import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.warden.AngerManagement;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = {
        BinaryHeap.class,
        LevelChunkTicks.class,
        DynamicGraphMinFixedPoint.class,
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
        // refresh monitors; reads are unlocked again, as they were designed to be
        // (and were for months before 7d58bbb). The blanket bit never made reads
        // safe anyway — getRecords returns a LAZY Stream and Lithium's
        // getInChunkColumn a lazy Iterable, both consumed after the monitor exits.
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
        // through AdvancementBuffer when async is active — matching at fire
        // time under the pa monitor, awards deferred to main drain points.
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