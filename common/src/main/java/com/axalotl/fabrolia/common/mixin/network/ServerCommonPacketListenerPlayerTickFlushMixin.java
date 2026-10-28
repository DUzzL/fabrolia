package com.axalotl.fabrolia.common.mixin.network;

import com.axalotl.fabrolia.common.regions.FabroliaConnectionEventLoop;
import com.axalotl.fabrolia.common.regions.FabroliaChunkForgetPublisher;
import com.axalotl.fabrolia.common.regions.FabroliaFullChunkPublisher;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Ordered chunk publication surfaces of the server packet listener. Autonomous island
 * owners deliberately keep vanilla's ordinary off-main {@code flush=true} in
 * {@code send}: otherwise their outbound stream would still be released only by the
 * slower physical-Main connection pass and a healthy 20 TPS player island would remain
 * visually capped by Main.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerPlayerTickFlushMixin
        implements FabroliaFullChunkPublisher, FabroliaChunkForgetPublisher {

    @Shadow @Final protected MinecraftServer server;
    @Shadow @Final protected Connection connection;

    /**
     * Offers one complete receipt-gated batch transaction. The delivery-owned
     * publication runs Start, all full snapshots, and Finished on the ordered
     * EventLoop, and owns exact success/failure terminalization. This surface
     * never joins the task or waits for channel writability.
     */
    @Override
    public void fabrolia$enqueueFullChunkBatch(
            FabroliaFullChunkPublisher.FullChunkBatchPublication publication) {
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException(
                    "full-chunk publication requires physical Main authority");
        }
        ServerCommonPacketListenerImpl self =
                (ServerCommonPacketListenerImpl) (Object) this;
        FabroliaFullChunkPublisher.PacketSender sender = self::send;
        ((FabroliaConnectionEventLoop) connection)
                .fabrolia$enqueueEventLoopTask(
                        () -> publication.runPublished(sender));
    }

    /**
     * A tracker retirement may run on an island/VMP worker. It reaches this
     * surface only after the exact Rec publication gate is released. Both the
     * older full task and this immutable Forget task therefore enter the same
     * EventLoop FIFO in lifecycle order; listener send remains outside every
     * Rec/chunk gate.
     */
    @Override
    public void fabrolia$enqueueChunkForget(ClientboundForgetLevelChunkPacket packet) {
        ServerCommonPacketListenerImpl self =
                (ServerCommonPacketListenerImpl) (Object) this;
        FabroliaFullChunkPublisher.PublicationReceipt receipt =
                new FabroliaFullChunkPublisher.PublicationReceipt();
        receipt.publish(
                ((FabroliaConnectionEventLoop) connection)::fabrolia$enqueueEventLoopTask,
                () -> self.send(packet));
    }
}
