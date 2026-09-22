package com.axalotl.fabrolia.common.mixin.utils;

import com.axalotl.fabrolia.common.utils.StatCriteriaPublication;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.StatType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces Stat's premature superclass cache escape with fully initialized publication. */
@Mixin(Stat.class)
public abstract class StatCriteriaMixin<T> {
    @Inject(
            method = "<init>(Lnet/minecraft/stats/StatType;Ljava/lang/Object;Lnet/minecraft/stats/StatFormatter;)V",
            at = @At("RETURN"), require = 1, allow = 1)
    private void fabrolia$publishInitializedStat(StatType<T> type, T value,
                                               StatFormatter formatter, CallbackInfo ci) {
        ((StatCriteriaPublication) this).fabrolia$publishInitializedStat();
    }
}
