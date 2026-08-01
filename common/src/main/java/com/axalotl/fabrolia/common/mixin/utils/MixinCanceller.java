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
        // ai.task.launch — a genuine target conflict, NOT a crutch.
        //
        // lithium's launch/BrainMixin @Overwrite's Brain.startEachNonRunningBehavior
        // (cached possibleTasks list) and Brain.getRunningBehaviors (a MaskedList),
        // and maintains that MaskedList from four more injectors. Fabrolia's own
        // entity/BrainMixin (priority 1500) already @WrapMethod's
        // startEachNonRunningBehavior with a volatile flat (activity, behavior)
        // snapshot and never calls original — so lithium's @Overwrite of that method
        // is dead code, while its runningTasks bookkeeping keeps running against a
        // list nothing rebuilds. Two implementations of the same method, ours wins,
        // theirs is left half-live. On top of that lithium's MaskedList has no
        // Fabrolia guard: ReferenceMaskedListMixin below targets a DIFFERENT class
        // (common.util.collections.ReferenceMaskedList, used by the cramming
        // EntitySection path), not the plain MaskedList that launch hands out of
        // getRunningBehaviors. If launch is ever wanted, clone ReferenceMaskedListMixin
        // against MaskedList first and drop Fabrolia's startEachNonRunningBehavior wrap.
        //
        // The rest of ai.task is ENABLED as of 2026-08-01. It used to be cancelled
        // wholesale to kill the villager freeze (2026-07-14 bisect: fabrolia+lithium =
        // stationary villagers). The recorded diagnosis on that cancel — "a memory-change
        // counter with no cross-thread visibility" — was WRONG, and the fix is now in
        // mixin/lithium/FabroliaLithiumBrainMemoryCounterMixin (atomic, fenced counter)
        // + FabroliaLithiumBrainMemoryBumpMixin (a bump AFTER every mutation). The real
        // defect was ORDERING, not visibility: ai.task.memory_changes bumps the counter
        // BEFORE the mutation it announces, so a reader in that window caches a result
        // computed from pre-mutation state against the post-bump count and the start gate
        // latches FOREVER. See those two classes for the full writeup. ai.task.replace_streams
        // and ai.task.run are stateless and were never implicated.
        if (mixinClassName.startsWith("net.caffeinemc.mods.lithium.mixin.ai.task.launch.")) {
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

            // SUPERSEDED, not disabled. lithium re-seats AttributeMap's
            // attributesToUpdate/attributesToSync from @Inject(<init>, RETURN) purely to
            // get ReferenceOpenHashSets with initial capacity 0; vanilla 26.2 already uses
            // ObjectOpenHashSet and AttributeInstance overrides neither equals nor hashCode,
            // so the two are semantically identical and NEITHER is thread-safe. Fabrolia's
            // entity/AttributeMapMixin (priority 1500) installs ConcurrentCollections.newHashSet()
            // at the same injection point. Keeping both would make the winner depend on
            // callback ordering at an identical injection point; the win forgone is two
            // allocations. If both are ever wanted, the robust lever is order = MAX_VALUE
            // on Fabrolia's @Inject, not mixin priority.
            case "net.caffeinemc.mods.lithium.mixin.collections.attributes.AttributeMapMixin":

            // MATCHED PAIR with mixin/lithium/FabroliaLithiumEntitySectionMixin — the cancel
            // exists so that Fabrolia's class is the SOLE implementor of lithium's
            // EntityMovementTrackerSection / PositionedEntityTrackingSection duck interfaces
            // on EntitySection. lithium's version adds three plain mutable fields (a
            // ReferenceOpenHashSet of visibility listeners, an ArrayList[] of per-class
            // once-listeners, a long[] of change times) and mutates them from every worker
            // that moves an entity. Fabrolia's is a full lock-free reimplementation of the
            // same interfaces (ConcurrentHashMap key set, CAS-installed
            // AtomicReferenceArray<ConcurrentLinkedQueue>, AtomicLongArray). DO NOT un-cancel:
            // two implementations of the same interface methods on one class is a mixin apply
            // error at best and silent duplicate state at worst.
            case "net.caffeinemc.mods.lithium.mixin.util.entity_movement_tracking.EntitySectionMixin":

            // MATCHED PAIR with mixin/lithium/FabroliaLithiumClassGroupFilterableListMixin,
            // and with the VMP cancel below — three mods rewriting ClassInstanceMultiMap.find.
            // lithium @Overwrite's it as get-then-put on the plain byClass HashMap (vanilla's
            // computeIfAbsent is one map op, so lithium is marginally worse, but BOTH are
            // unsafe). The actual repair is Fabrolia's parallel ConcurrentHashMap index keyed
            // by EntityClassGroup, maintained from @Inject(add/remove, RETURN). DO NOT
            // un-cancel: same duplicate-implementor problem as above.
            case "net.caffeinemc.mods.lithium.mixin.collections.entity_filtering.ClassInstanceMultiMapMixin":
            case "com.ishland.vmp.mixins.general.collections.MixinTypeFilterableList":

            case "me.drex.vanish.mixin.interaction.SimpleCriterionTriggerMixin":
                return true;
        }
        // Fabrolia mixins that reference lithium types or lithium-merged members
        // directly: they must not even load without lithium present.
        if (mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.LithiumServerLevel") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.LithiumGameEventDispatcherStorage") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.ReferenceMaskedListMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumEntitySectionMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumEntityMovementTrackerMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumInternerMixin") ||
                // Task #41: cast to Lithium's MemoryModificationCounter / target
                // Lithium's own mixin class, so neither may load without Lithium.
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumBrainMemoryCounterMixin") ||
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumBrainMemoryBumpMixin") ||
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
