package com.axalotl.fabrolia.common.mixin.network;

import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Removes the only worker-to-Main join in the complete play-listener tick.
 *
 * <p>{@code disconnect(DisconnectionDetails)} sends the disconnect packet, makes
 * the connection read-only, then calls {@link MinecraftServer#executeBlocking} for
 * connection teardown. An A5 listener owns an island claim at that point; joining
 * Main can therefore deadlock against a save/topology fence which is waiting for the
 * same claim. The authenticated helper publishes teardown to Main without waiting.
 * Every ordinary caller retains the exact vanilla blocking call; the eventual
 * play-listener {@code onDisconnect} owns its physical-Main terminal fence.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerIslandDisconnectMixin {

    @WrapOperation(
            method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;"
                            + "executeBlocking(Ljava/lang/Runnable;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$deferPlayerOwnerDisconnect(
            MinecraftServer server, Runnable teardown, Operation<Void> original) {
        Object self = this;
        if (self instanceof ServerGamePacketListenerImpl game
                && AsyncIslandPlayerTicks.deferBlockingDisconnect(
                        game, server, teardown)) {
            return;
        }
        original.call(server, teardown);
    }
}
