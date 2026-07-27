package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.ChunkDelivery;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Delivery rewrite: observe batch acks (retire the oldest outstanding
 *  batch's records) and connection teardown (cancel every record; ack
 *  obligations die with the connection generation). */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class FabroliaChunkAckMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$bindSenderPlayer(net.minecraft.server.MinecraftServer server,
                                           net.minecraft.network.Connection connection,
                                           ServerPlayer player,
                                           net.minecraft.server.network.CommonListenerCookie cookie,
                                           CallbackInfo ci) {
        ((com.axalotl.fabrolia.common.regions.FabroliaSenderPlayer) (Object)
                ((ServerGamePacketListenerImpl) (Object) this).chunkSender).fabrolia$setPlayer(player);
    }

    @Inject(method = "handleChunkBatchReceived", at = @At("TAIL"))
    private void fabrolia$ack(ServerboundChunkBatchReceivedPacket packet, CallbackInfo ci) {
        ChunkDelivery.onAck(player);
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void fabrolia$quit(DisconnectionDetails details, CallbackInfo ci) {
        ChunkDelivery.quit(player);
        // Regional player ticking: invalidate every queued island task for this
        // player (epoch) and drop the ownership/sim records exactly once.
        com.axalotl.fabrolia.common.regions.PlayerOwnership.bumpEpoch(player);
        com.axalotl.fabrolia.common.regions.PlayerOwnership.forget(player);
        com.axalotl.fabrolia.common.regions.RegionalPlayerTicks.forget(player);
    }
}
