package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Keeps vanilla's goal set wherever one thread reaches a mob's goals.
 *
 * <p>Vanilla holds {@code availableGoals} in an insertion-ordered set, and
 * {@code GoalSelector.tick} depends on that order: of two goals with the same
 * priority the one iterated first takes the flag, and the order of the
 * {@code canUse} calls is the order in which the mob's random is drawn. A
 * concurrent hash set iterates by the goals' identity hash, so that order
 * changed from mob to mob and from run to run (audit 2026-10-25, A-05).
 *
 * <p>Under region threading a mob and everything that reaches its goals run on
 * one thread at a time: the serial island pass, sync mode, and neighbouring
 * regions under the ColorGate. Only the flat path without regions ticks
 * neighbouring mobs on several threads, where {@code PathfinderMob.isPanicking}
 * reads a partner's goals; it keeps the concurrent set. A mob keeps the set it
 * was built with, so a mob built under region threading has vanilla's set also
 * after a later {@code /fabrolia regions off}.
 */
@Mixin(GoalSelector.class)
public class GoalSelectorMixin {

    @Shadow
    @Final
    @Mutable
    private Set<WrappedGoal> availableGoals;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$concurrentGoalsOnlyWithoutRegions(CallbackInfo ci) {
        if (!FabroliaConfig.disabled && !FabroliaConfig.enableRegionThreading) {
            this.availableGoals = ConcurrentCollections.newHashSet();
        }
    }
}
