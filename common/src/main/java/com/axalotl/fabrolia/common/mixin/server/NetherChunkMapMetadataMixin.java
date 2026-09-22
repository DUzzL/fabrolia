package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChunkMap.class)
public abstract class NetherChunkMapMetadataMixin {
    @WrapMethod(method = "updatePlayerStatus")
    private void fabrolia$orderedWatchedPlayerStatus(ServerPlayer player, boolean added, Operation<Void> original) {
        if (NetherPortalPlayerAccess.capturesMetadata()) {
            NetherPortalPlayerAccess.deferMetadata(() -> {
                synchronized (this) { original.call(player, added); }
            });
        } else original.call(player, added);
    }
}
