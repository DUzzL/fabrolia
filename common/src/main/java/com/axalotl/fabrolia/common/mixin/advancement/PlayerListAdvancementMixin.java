package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routed advancement work of a leaving player is applied before vanilla
 * {@code PlayerList.remove} saves its advancements and drops the
 * PlayerAdvancements object. The HEAD runs inside Fabrolia's removal wrapper
 * ({@code PlayerListMixin}), which holds the global fence while islands are live.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListAdvancementMixin {

    @Inject(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
    private void fabrolia$applyRoutedAdvancementsBeforeSave(ServerPlayer player, CallbackInfo ci) {
        AdvancementBuffer.drainBeforeRemoval(player);
    }
}
