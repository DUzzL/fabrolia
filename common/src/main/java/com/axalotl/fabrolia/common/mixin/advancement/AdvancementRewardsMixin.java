package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRewardRouting;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Contract C2's single routing point ({@link AdvancementRewardRouting}): every reward
 * grant — award completions and {@code checkForAutomaticTriggers}' direct grant —
 * passes here before any reward runs.
 */
@Mixin(AdvancementRewards.class)
public abstract class AdvancementRewardsMixin {

    @WrapMethod(method = "grant(Lnet/minecraft/server/level/ServerPlayer;)V")
    private void fabrolia$routeGrant(ServerPlayer player, Operation<Void> original) {
        if (!AdvancementRewardRouting.routeGrant((AdvancementRewards) (Object) this, player)) {
            original.call(player);
        }
    }

    @WrapOperation(
            method = "lambda$grant$1(Lnet/minecraft/server/MinecraftServer;"
                    + "Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lnet/minecraft/commands/functions/CommandFunction;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/ServerFunctionManager;execute("
                            + "Lnet/minecraft/commands/functions/CommandFunction;"
                            + "Lnet/minecraft/commands/CommandSourceStack;)V"),
            require = 1, allow = 1)
    private static void fabrolia$routeRewardFunction(ServerFunctionManager manager,
                                                     CommandFunction<CommandSourceStack> function,
                                                     CommandSourceStack source,
                                                     Operation<Void> original) {
        AdvancementRewardRouting.dispatchRewardFunction(function, source,
                () -> original.call(manager, function, source));
    }
}
