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