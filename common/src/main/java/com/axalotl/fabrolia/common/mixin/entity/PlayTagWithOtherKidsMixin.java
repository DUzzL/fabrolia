package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.PlayTagWithOtherKids;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(PlayTagWithOtherKids.class)
public class PlayTagWithOtherKidsMixin {
    @Inject(method = "checkHowManyChasersEachFriendHas", at = @At("HEAD"), cancellable = true)
    private static void fabrolia$countCapturedTargets(List<LivingEntity> friends,
            CallbackInfoReturnable<Map<LivingEntity, Integer>> cir) {
        Map<LivingEntity, Integer> counts = new HashMap<>();
        for (LivingEntity friend : friends) {
            // Panic may erase the memory immediately after this read. Only this
            // captured value participates in both presence and counting.
            LivingEntity target = friend.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).orElse(null);
            if (target != null) counts.merge(target, 1, Integer::sum);
        }
        cir.setReturnValue(counts);
    }
}
