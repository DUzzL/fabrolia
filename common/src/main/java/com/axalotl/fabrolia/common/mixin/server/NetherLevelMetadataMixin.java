package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerLevel.class)
public abstract class NetherLevelMetadataMixin {
    @WrapMethod(method = "updateSleepingPlayerList")
    private void fabrolia$orderedSleepingPlayers(Operation<Void> original) {
        if (!NetherPortalPlayerAccess.capturesMetadata()
                || !NetherPortalPlayerAccess.deferMetadata(original::call)) original.call();
    }

    @WrapMethod(method = "resetEmptyTime")
    private void fabrolia$orderedEmptyTime(Operation<Void> original) {
        if (!NetherPortalPlayerAccess.capturesMetadata()
                || !NetherPortalPlayerAccess.deferMetadata(original::call)) original.call();
    }
}
