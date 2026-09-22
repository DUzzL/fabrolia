package com.axalotl.fabrolia.common.mixin.carpet;

import com.axalotl.fabrolia.common.regions.CarpetPlayerMutationRouter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * Exact optional adapter for fabric-carpet {@code 26.2+v260616}'s
 * {@code /player} mutation hub.
 *
 * <p>Every action-pack command in this Carpet artifact converges on the one
 * {@code Consumer.accept(actionPack)} call in {@code PlayerCommand.manipulate}; wrapping
 * that call covers start/stop use and attack, movement, look/turn, sneak/sprint,
 * mount/dismount, drop and slot selection.  Kill is a separate callsite and is routed
 * too because the fake override calls {@code shakeOff()} synchronously before scheduling
 * its Main-thread disconnect.
 *
 * <p>The full method descriptors and {@code require=1} intentionally make an un-audited
 * Carpet bytecode change fail during mixin application instead of silently restoring a
 * Main/island race.
 */
@Pseudo
@Mixin(targets = "carpet.commands.PlayerCommand", remap = false)
public abstract class CarpetPlayerCommandIslandMixin {

    @WrapOperation(
            method = "manipulate(Lcom/mojang/brigadier/context/CommandContext;"
                    + "Ljava/util/function/Consumer;)I",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V",
                    ordinal = 0,
                    remap = false),
            require = 1)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void fabrolia$routeActionPackMutation(
            Consumer consumer, Object actionPack, Operation<Void> original,
            @Local(ordinal = 0) ServerPlayer player) {
        if (CarpetPlayerMutationRouter.route(player,
                () -> consumer.accept(actionPack), "action-pack")) {
            return;
        }
        original.call(consumer, actionPack);
    }

    @WrapOperation(
            method = "kill(Lcom/mojang/brigadier/context/CommandContext;)I",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;"
                            + "kill(Lnet/minecraft/server/level/ServerLevel;)V",
                    ordinal = 0,
                    remap = true),
            require = 1)
    private static void fabrolia$routeFakeKill(
            ServerPlayer player, ServerLevel level, Operation<Void> original) {
        if (CarpetPlayerMutationRouter.route(player,
                () -> player.kill(level), "kill")) {
            return;
        }
        original.call(player, level);
    }
}
