package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.triggers.ItemUsedOnLocationTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemInstance;
import org.spongepowered.asm.mixin.Mixin;

/** Assert the selected producer scope before native block reads and eager context creation. */
@Mixin(ItemUsedOnLocationTrigger.class)
public abstract class ItemUsedOnLocationTriggerMixin {
    @WrapMethod(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemInstance;)V")
    private void fabrolia$ownedTrigger(ServerPlayer player, BlockPos pos, ItemInstance item,
                                       Operation<Void> original) {
        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(
                player, (ItemUsedOnLocationTrigger) (Object) this);
        original.call(player, pos, item);
    }
}
