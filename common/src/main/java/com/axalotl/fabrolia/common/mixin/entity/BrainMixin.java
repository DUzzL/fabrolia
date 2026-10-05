package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.AtomicMemoryStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = Brain.class, priority = 1500)
public class BrainMixin<E extends LivingEntity> {

    // ---- flat behavior snapshot (2026-07-12) --------------------------------
    // startEachNonRunningBehavior walked TreeMap -> HashMap -> LinkedHashSet
    // every tick per brain-mob (1.7 core-ms self just iterating on the
    // 2026-07-11 profiles). The structure only changes in addActivity /
    // removeAllBehaviors, so a flattened (activity, behaviors[]) array in the
    // exact original iteration order (TreeMap ascending priority, inner maps'
    // own order, LinkedHashSet insertion order) is observably identical. As in
    // vanilla, activeActivities is read live once per (priority, activity)
    // entry, before that entry's behaviors; the STOPPED filter stays live per
    // behavior.
    @org.spongepowered.asm.mixin.Unique
    private volatile Object[] fabrolia$flatBehaviors;

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private java.util.Map<Integer, java.util.Map<net.minecraft.world.entity.schedule.Activity, java.util.Set<net.minecraft.world.entity.ai.behavior.BehaviorControl<? super E>>>> availableBehaviorsByPriority;

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private java.util.Set<net.minecraft.world.entity.schedule.Activity> activeActivities;

    @org.spongepowered.asm.mixin.injection.Inject(method = "addActivity*", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void fabrolia$invalidateFlatOnAdd(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        fabrolia$flatBehaviors = null;
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "removeAllBehaviors", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void fabrolia$invalidateFlatOnClear(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        fabrolia$flatBehaviors = null;
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "startEachNonRunningBehavior")
    private void fabrolia$startEachFlat(net.minecraft.server.level.ServerLevel level, E body,
                                        com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        Object[] flat = fabrolia$flatBehaviors;
        if (flat == null) {
            java.util.ArrayList<Object> tmp = new java.util.ArrayList<>();
            for (var byActivity : this.availableBehaviorsByPriority.values()) {
                for (var entry : byActivity.entrySet()) {
                    tmp.add(entry.getKey());
                    tmp.add(entry.getValue().toArray(new net.minecraft.world.entity.ai.behavior.BehaviorControl<?>[0]));
                }
            }
            flat = tmp.toArray();
            fabrolia$flatBehaviors = flat;
        }
        long time = level.getGameTime();
        for (int i = 0, n = flat.length; i < n; i += 2) {
            if (this.activeActivities.contains((net.minecraft.world.entity.schedule.Activity) flat[i])) {
                for (var control : (net.minecraft.world.entity.ai.behavior.BehaviorControl<?>[]) flat[i + 1]) {
                    @SuppressWarnings("unchecked")
                    var behavior = (net.minecraft.world.entity.ai.behavior.BehaviorControl<? super E>) control;
                    if (behavior.getStatus() == net.minecraft.world.entity.ai.behavior.Behavior.Status.STOPPED) {
                        behavior.tryStart(level, body, time);
                    }
                }
            }
        }
    }

    @Shadow
    @Final
    @Mutable
    private Map<MemoryModuleType<?>, MemorySlot<?>> memories;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$useAtomicStorage(CallbackInfo ci) {
        if (!(this.memories instanceof AtomicMemoryStorage)) {
            this.memories = new AtomicMemoryStorage(BuiltInRegistries.MEMORY_MODULE_TYPE, this.memories);
        }
    }
}
