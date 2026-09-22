package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only global builders move to Main; ServerPlayer.teleport itself remains on its claimant. */
@Mixin(ServerPlayer.class)
public abstract class NetherPlayerMetadataMixin {
    @WrapOperation(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;sendPlayerPermissionLevel(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void fabrolia$orderedPermissions(PlayerList list, ServerPlayer player, Operation<Void> original) {
        if (!NetherPortalPlayerAccess.deferMetadata(() -> original.call(list, player))) original.call(list, player);
    }

    @WrapOperation(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;sendLevelInfo(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void fabrolia$orderedLevelInfo(PlayerList list, ServerPlayer player, ServerLevel level, Operation<Void> original) {
        if (!NetherPortalPlayerAccess.deferMetadata(() -> original.call(list, player, level))) original.call(list, player, level);
    }

    @WrapMethod(method = "triggerDimensionChangeTriggers")
    private void fabrolia$orderedDimensionCriteria(ServerLevel oldLevel, Operation<Void> original) {
        if (!NetherPortalPlayerAccess.deferMetadata(() -> original.call(oldLevel))) original.call(oldLevel);
    }
}
