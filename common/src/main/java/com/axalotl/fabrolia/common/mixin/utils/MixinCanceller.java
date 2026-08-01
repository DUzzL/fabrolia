package com.axalotl.fabrolia.common.mixin.utils;

import java.util.List;

public class MixinCanceller implements com.bawnorton.mixinsquared.api.MixinCanceller {
    private boolean LITHIUM = false;
    private boolean VMP = false;
    private boolean C2ME = false;

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        if (mixinClassName.contains("lithium") && !mixinClassName.contains("fabrolia")) {
            LITHIUM = true;
        }
        if (mixinClassName.contains("c2me") && !mixinClassName.contains("fabrolia")) {
            C2ME = true;
        }
        if (mixinClassName.contains("ishland.vmp") && !mixinClassName.contains("fabrolia")) {
            VMP = true;
        }
        // Villager-freeze fix (2026-07-14, bisected: fabrolia+lithium =
        // stationary villagers; lithium.mixin.ai.task=false = normal): lithium's
        // brain-task optimization caches behavior start checks against a plain
        // per-brain memory-change counter with no cross-thread visibility.
        // Fabrolia ticks entity brains on pool/region threads, so counter bumps
        // made on one thread stay invisible to the start-check on another —
        // behaviors never re-evaluate, walk targets are never set, villagers
        // freeze (sensors and AcquirePoi still run: memories were written, only
        // the cached start gate was stale). Cancel the task package; pathing/
        // poi/sensor lithium modules stay active.
        if (mixinClassName.startsWith("net.caffeinemc.mods.lithium.mixin.ai.task.")) {
            return true;
        }
        // Task #38 (review D5): fast_portals @Overwrite's ensureLoadedAndValid
        // with two unguarded @Unique fields on PoiManager (preloadedCenterChunks:
        // plain LongOpenHashSet, clear()+add on every call; preloadRadius) that
        // region workers hit concurrently via AcquirePoi-class behaviors — the
        // same fastutil-rehash AIOOBE family as the 2026-07-29 01:51 crash, live.
        // Do NOT monitor-wrap it instead: the body calls getChunk(EMPTY), and a
        // main caller waiting on that monitor while a worker-holder waits on the
        // chunk future is precisely the task-#32 nav-monitor deadlock shape.
        // Cancelling restores the vanilla body, whose loadedChunks dedup set is
        // replaced with a concurrent one in PoiManagerMixin. The package's
        // PortalForcerMixin stays active — it only needs ensureLoadedAndValid
        // (vanilla now) and lithium$findNearestForPortalLogic (ai.poi, alive).
        if (mixinClassName.equals("net.caffeinemc.mods.lithium.mixin.ai.poi.fast_portals.PoiManagerMixin")) {
            return true;
        }
        switch (mixinClassName) {
            case "com.ishland.c2me.base.mixin.instrumentation.MixinServerChunkManager":
            case "com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers.MixinThreadedAnvilChunkStorage":
            case "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading_detections.random_instances.MixinWorld":
            case "net.caffeinemc.mods.lithium.mixin.collections.attributes.AttributeMapMixin":
            case "net.caffeinemc.mods.lithium.mixin.util.entity_movement_tracking.EntitySectionMixin":
            case "net.caffeinemc.mods.lithium.mixin.collections.entity_filtering.ClassInstanceMultiMapMixin":
            case "com.ishland.vmp.mixins.general.collections.MixinTypeFilterableList":
            case "me.drex.vanish.mixin.interaction.SimpleCriterionTriggerMixin":
                return true;
        }
        if (mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.LithiumServerLevel") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.LithiumGameEventDispatcherStorage") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.ReferenceMaskedListMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumEntitySectionMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumEntityMovementTrackerMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumInternerMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumClassGroupFilterableListMixin") ||
                // Task #38: these three target Lithium-merged methods / a
                // Lithium anonymous class, so without Lithium they must not
                // load — and WITH Lithium they must fail LOUDLY if a target
                // string rots (default require), which is why they are gated
                // here rather than written with require = 0.
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumPoiStorageMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumPoiColumnIteratorMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumPoiTakeAtMixin")) {
            return !LITHIUM;
        }
        if (mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.c2me.TheChunkSystemMixin") ||
                // Task #38 (review D3): wraps the C2ME-merged c2me$unloadPoi.
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.c2me.PoiUnloadGuardMixin")) {
            return !C2ME;
        }
        return mixinClassName.endsWith("com.cupboard.mixin.ServerAddEntityMixin");
    }
}
