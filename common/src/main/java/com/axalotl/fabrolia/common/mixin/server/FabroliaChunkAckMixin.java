package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.ChunkDelivery;
import com.axalotl.fabrolia.common.regions.FabroliaSenderPlayer;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Delivery acknowledgement ownership transfer.
 *
 * <p>The ACK venue split itself lives around PlayerChunkSender's vanilla ACK
 * method so physical-Main execution composes with every other transformation
 * of that method (notably C2ME's TAIL). This listener mixin only binds the
 * sender and tears its bounded mailbox down with the connection.</p>
 */
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

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void fabrolia$quit(DisconnectionDetails details, CallbackInfo ci) {
        PlayerChunkSender sender = ((ServerGamePacketListenerImpl) (Object) this).chunkSender;
        ((FabroliaSenderPlayer) (Object) sender).fabrolia$discardChunkBatchAcks();
        ChunkDelivery.quit(player);
    }
}
