package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.triggers.KilledByArrowTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Collection;

/** Native victim types are observed before its first context constructor. */
@Mixin(KilledByArrowTrigger.class)
public abstract class KilledByArrowTriggerMixin {
    @WrapMethod(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Collection;Lnet/minecraft/world/item/ItemStack;)V")
    private void fabrolia$ownedVictims(ServerPlayer player, Collection<Entity> victims, ItemStack weapon,
                                       Operation<Void> original) {
        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(player, (KilledByArrowTrigger) (Object) this);
        for (Entity victim : victims) AdvancementCaptureOwnership.requireEntityObservationOwnership(victim);
        original.call(player, victims, weapon);
    }
}
