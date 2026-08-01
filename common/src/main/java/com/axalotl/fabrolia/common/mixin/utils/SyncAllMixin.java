package com.axalotl.fabrolia.common.mixin.utils;

import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.warden.AngerManagement;
import net.minecraft.world.level.chunk.storage.SectionStorage;
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
        // SectionStorage (2026-07-29 01:51 crash): AIOOBE 'Index -1' in
        // Long2ObjectOpenHashMap.rehash from PoiManager.tick/unpackChunk with
        // region workers mutating POIs concurrently. NOTE the real root cause,
        // found 2026-08-01: Fabrolia's SectionStorageMixin installs a concurrent
        // `storage` map via a @Shadow field initialiser, and Lithium's
        // ai.poi.SectionStorageMixin @Injects at <init> RETURN and OVERWRITES it
        // with a plain ListeningLong2ObjectOpenHashMap — the map Fabrolia thought
        // it had was never installed. Lithium winning is also load-bearing: if
        // Fabrolia won, Lithium's onEntryAdded would never fire, its `columns`
        // map would stay empty and every POI query would hit
        // "Failed to load POI section data!". The correct repair is to WRAP
        // Lithium's listening map (@Inject <init> RETURN, order = MAX_VALUE),
        // not replace it — plus a defensive BitSet clone at the getNonEmpty
        // boundary, the shape already proven in Sepals. Until that lands this
        // blanket monitor is the only thing covering `storage` AND Lithium's
        // unguarded `columns` AND C2ME's merged c2me$unloadPoi, so it STAYS.
        // Staged deliberately: it is a contention and latency liability, not a
        // deadlock one, now that PoiSection is out (see below).
        SectionStorage.class,
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