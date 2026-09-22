package com.axalotl.fabrolia.common.mixin.network;

import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.FabroliaConnectionEventLoop;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Linearizes network ingress with an async island player's simulation lane.
 *
 * <p>Connection still owns decode, disconnect handling and packet counters.  Only the
 * already-decoded {@link Packet#handle} call is moved: an authenticated island session
 * appends it to that player's FIFO, while login/configuration and non-owned play sessions
 * retain the exact vanilla call.
 */
@Mixin(Connection.class)
public abstract class ConnectionIslandPlayerMixin
        implements FabroliaConnectionEventLoop {

    @Shadow private Channel channel;
    /**
     * The exact publication primitive used by the full-chunk enqueue fence.
     * EventLoop.execute is a task-queue offer/wakeup; it never awaits channel
     * writability and never invokes the task inline. Reserve the current pool
     * generation before that queue becomes visible: a delayed Netty task may run
     * after normal stop or during an exact-turn exclusive cut, where acquiring a
     * fresh root success permit would be an invalid cross-generation admission.
     */
    @Override
    public void fabrolia$enqueueEventLoopTask(Runnable task) {
        Channel current = channel;
        if (current == null) {
            throw new IllegalStateException(
                    "full-chunk publication attempted before channel activation");
        }
        RegionScheduler.executeTrackedExternal(current.eventLoop(), () -> {
            try {
                // ExternalTask installs the retained generation's causal token.
                // Receipt-owned nested success cuts therefore remain valid even
                // when Netty drains after the root generation stopped accepting.
                task.run();
            } catch (Throwable failure) {
                // Netty executors may log a task exception and keep draining.
                // Arm physical Main first and rethrow the same first-wins object;
                // no later channel publication may start in this generation.
                IslandCommands.publishAndPropagate(failure);
            }
        });
    }

    @WrapOperation(
            method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/TickablePacketListener;tick()V",
                    ordinal = 0),
            require = 1,
            allow = 1)
    private void fabrolia$islandOwnPlayListenerTick(TickablePacketListener listener,
                                                    Operation<Void> original) {
        if (listener instanceof ServerGamePacketListenerImpl game) {
            AsyncIslandPlayerTicks.runMainListenerTick(game, () -> original.call(listener));
        } else {
            original.call(listener);
        }
    }

    @WrapOperation(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;"
                    + "Lnet/minecraft/network/protocol/Packet;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/Connection;genericsFtw("
                            + "Lnet/minecraft/network/protocol/Packet;"
                            + "Lnet/minecraft/network/PacketListener;)V",
                    ordinal = 0),
            require = 1,
            allow = 1)
    private void fabrolia$enqueueIslandPlayerPacket(Packet<?> packet,
                                                     PacketListener listener,
                                                     Operation<Void> original) {
        if (!AsyncIslandPlayerTicks.enqueuePacket(packet, listener)) {
            original.call(packet, listener);
        }
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V")
    private void fabrolia$netherOrderedSend(Packet<?> packet,
                                           io.netty.channel.ChannelFutureListener listener,
                                           boolean flush, Operation<Void> original) {
        // Only this transfer's own calls belong to its FIFO. Capturing unrelated
        // sends by recipient can move a source UPDATE behind its deferred UNTRACK.
        if (!NetherPortalPlayerAccess.capturesMetadata()
                || !NetherPortalPlayerAccess.deferMetadata(
                        () -> original.call(packet, listener, flush))) {
            original.call(packet, listener, flush);
        }
    }
}
