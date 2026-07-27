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
                mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumClassGroupFilterableListMixin")) {
            return !LITHIUM;
        }
        if (mixinClassName.endsWith("com.axalotl.fabrolia.common.mixin.c2me.TheChunkSystemMixin")) {
            return !C2ME;
        }
        return mixinClassName.endsWith("com.cupboard.mixin.ServerAddEntityMixin");
    }
}
