package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.SpearUseGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guard the actual target captured by vanilla, before its first dereference. */
@Mixin(SpearUseGoal.class)
public abstract class SpearUseGoalMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/world/entity/monster/Monster;getTarget()Lnet/minecraft/world/entity/LivingEntity;"),
            cancellable = true)
    private void fabrolia$nullSafeTarget(CallbackInfo ci, @Local LivingEntity target) {
        if (target == null) ci.cancel();
    }
}
