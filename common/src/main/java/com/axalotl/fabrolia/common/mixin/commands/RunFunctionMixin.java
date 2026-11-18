package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.EnchantmentFunctionRouting;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.world.item.enchantment.effects.RunFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The enchantment {@code run_function} effect calls the function synchronously
 * inside the entity tick. On an island coordinator a body that is not proven
 * owner-local runs once, at this exact point, on fenced Main while the
 * coordinator parks ({@link EnchantmentFunctionRouting}).
 */
@Mixin(RunFunction.class)
public abstract class RunFunctionMixin {

    @WrapOperation(
            method = "apply",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/ServerFunctionManager;execute("
                            + "Lnet/minecraft/commands/functions/CommandFunction;"
                            + "Lnet/minecraft/commands/CommandSourceStack;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$runOnOwnerOrFencedMain(ServerFunctionManager manager,
                                                 CommandFunction<CommandSourceStack> function,
                                                 CommandSourceStack source,
                                                 Operation<Void> original) {
        EnchantmentFunctionRouting.execute(source.getLevel(), manager, function, source,
                () -> original.call(manager, function, source));
    }
}
