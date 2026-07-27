package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.api.fastutil.AtomicMobCategoryCounts;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Atomizes {@code MobCounts} per-MobCategory counter so per-player mob cap stays
 * race-safe under parallel spawn dispatch. Vanilla's {@code Object2IntMap} +
 * {@code computeInt}-style read-modify-write loses updates when multiple workers
 * spawn near the same player; the {@link AtomicIntegerArray} backing for
 * {@code add} + atomic read in {@code canSpawn} closes that window without any
 * per-spawn claim/CAS-loop.
 *
 * <p>MC 26.2 refactored the counter storage: the old {@code int[] spawnGroupDensities}
 * (indexed by {@code MobCategory.ordinal()}) was replaced with {@code Object2IntMap<MobCategory> counts}.
 * The mixin now replaces the vanilla {@code counts} map with an
 * {@link AtomicMobCategoryCounts} view backed by an {@link AtomicIntegerArray}
 * indexed by ordinal. That makes the atomic array the single source of truth:
 * vanilla/original {@code computeInt}, fabrolia {@code add}, {@code canSpawn}, and
 * reflection readers all observe the same live values. The atomic dimension is
 * fixed at startup ({@code MobCategory.values().length}) — no resize hazard.
 */
@Mixin(value = LocalMobCapCalculator.MobCounts.class, priority = 1100)
public abstract class LocalMobCapCalculatorMobCountsMixin {

    @Mutable
    @Final
    @Shadow
    private Object2IntMap<MobCategory> counts;

    @Unique
    private AtomicIntegerArray fabrolia$atomic;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fabrolia$initAtomic(CallbackInfo ci) {
        // Fresh MobCounts starts with all categories at 0 — matches vanilla
        // (Object2IntMap.getOrDefault returns 0 for absent keys before any add).
        this.fabrolia$atomic = new AtomicIntegerArray(MobCategory.values().length);
        this.counts = new AtomicMobCategoryCounts(this.fabrolia$atomic);
    }

    @WrapMethod(method = "add(Lnet/minecraft/world/entity/MobCategory;)V")
    private void fabrolia$atomicAdd(MobCategory category, Operation<Void> original) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaSpawn) {
            original.call(category);
            return;
        }
        int ord = category.ordinal();
        this.fabrolia$atomic.incrementAndGet(ord);
    }

    @WrapMethod(method = "canSpawn(Lnet/minecraft/world/entity/MobCategory;)Z")
    private boolean fabrolia$atomicCanSpawn(MobCategory category, Operation<Boolean> original) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaSpawn) {
            return original.call(category);
        }
        return this.fabrolia$atomic.get(category.ordinal()) < category.getMaxInstancesPerChunk();
    }
}
