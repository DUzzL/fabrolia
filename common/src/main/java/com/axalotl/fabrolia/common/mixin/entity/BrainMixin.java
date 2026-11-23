package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.AtomicMemoryStorage;
import com.axalotl.fabrolia.common.regions.CopperContainerFootprint;
import net.minecraft.core.BlockPos;
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
public class BrainMixin<E extends LivingEntity> implements CopperContainerFootprint.RetainedTarget {

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
        Object[] flat = fabrolia$flat();
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

    /** The flat (activity, behaviors[]) snapshot, rebuilt after addActivity / removeAllBehaviors. */
    @org.spongepowered.asm.mixin.Unique
    private Object[] fabrolia$flat() {
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
        return flat;
    }

    @Override
    public BlockPos fabrolia$retainedCopperContainerPos() {
        // CopperGolemAi installs one native transport behavior directly in its activity.
        // Even STOPPED can retain its target. Read the actual behavior, not a memory proxy.
        Object[] flat = fabrolia$flat();
        for (int i = 1; i < flat.length; i += 2) {
            for (Object behavior : (Object[]) flat[i]) {
                if (behavior instanceof CopperContainerFootprint.RetainedTarget transport) {
                    return transport.fabrolia$retainedCopperContainerPos();
                }
            }
        }
        return null;
    }

    // ---- running behaviors from the flat snapshot ----------------------------
    // tickEachRunningBehavior asks getRunningBehaviors() every tick, which walks
    // the same TreeMap -> HashMap -> LinkedHashSet structure over the behaviors
    // of every activity (active or not) and collects the RUNNING ones into a new
    // list. Lithium replaces that walk (ai.task.launch), but that mixin is
    // cancelled here (MixinCanceller), so every brain mob paid the vanilla walk:
    // a third of a villager's brain tick on the 2026-11-18 profiles. The flat
    // snapshot holds the same behaviors in the same order, so the list handed to
    // vanilla's loop is identical: complete before the first tickOrStop, same
    // members, same order. getRunningBehaviors() itself stays vanilla for every
    // other caller.
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "tickEachRunningBehavior",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/Brain;getRunningBehaviors()Ljava/util/List;"))
    private java.util.List<net.minecraft.world.entity.ai.behavior.BehaviorControl<? super E>> fabrolia$runningFromFlat(
            Brain<E> self,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<java.util.List<net.minecraft.world.entity.ai.behavior.BehaviorControl<? super E>>> original) {
        Object[] flat = fabrolia$flat();
        it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.world.entity.ai.behavior.BehaviorControl<? super E>> running =
                new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
        for (int i = 1, n = flat.length; i < n; i += 2) {
            for (var control : (net.minecraft.world.entity.ai.behavior.BehaviorControl<?>[]) flat[i]) {
                if (control.getStatus() == net.minecraft.world.entity.ai.behavior.Behavior.Status.RUNNING) {
                    @SuppressWarnings("unchecked")
                    var behavior = (net.minecraft.world.entity.ai.behavior.BehaviorControl<? super E>) control;
                    running.add(behavior);
                }
            }
        }
        return running;
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
