package com.axalotl.fabrolia.common.mixin.network;

import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.ConnectionOutbox;
import com.axalotl.fabrolia.common.regions.FabroliaConnectionEventLoop;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess;
import com.axalotl.fabrolia.common.regions.PassiveConnectionLoops;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Linearizes network ingress with an async island player's simulation lane.
 *
 * <p>Connection still owns decode, disconnect handling and packet counters.  Only the
 * already-decoded {@link Packet#handle} call is moved: an authenticated island session
 * appends it to that player's FIFO, while login/configuration and non-owned play sessions
 * retain the exact vanilla call.
 *
 * <p>Egress: every off-loop send, flush and ordered transport task goes through the
 * connection's {@link ConnectionOutbox}, so a region worker step's packets reach the
 * event loop as one task per connection instead of one wakeup per packet.
 */
@Mixin(Connection.class)
public abstract class ConnectionIslandPlayerMixin
        implements FabroliaConnectionEventLoop {

    @Shadow private Channel channel;

    @Shadow
    private void doSendPacket(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        throw new AssertionError();
    }

    /** Every off-loop transport operation of this connection, in issue order. */
    @Unique private final ConnectionOutbox fabrolia$outbox = new ConnectionOutbox();

    /**
     * The exact publication primitive used by the full-chunk enqueue fence.
     * The task enters this connection's ordered outbox behind every operation
     * issued before it (held packets included) and wakes the loop now; it never
     * awaits channel writability and never invokes the task inline. Reserve the current pool
     * generation before that queue becomes visible: a delayed Netty task may run
     * after normal stop or during an exact-turn exclusive cut, where acquiring a
     * fresh root success permit would be an invalid cross-generation admission.
     * A passive loop (a fake player's EmbeddedChannel) is noted afterwards, also when
     * publication failed part-way: its queue runs only when Main operates the channel,
     * so every pool fixed point runs it ({@link PassiveConnectionLoops}).
     */
    @Override
    public void fabrolia$enqueueEventLoopTask(Runnable task) {
        Channel current = channel;
        if (current == null) {
            throw new IllegalStateException(
                    "full-chunk publication attempted before channel activation");
        }
        EventLoop loop = current.eventLoop();
        ConnectionOutbox outbox = fabrolia$outbox;
        try {
            RegionScheduler.executeTrackedExternal(queued -> outbox.execute(loop, queued), () -> {
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
        } finally {
            PassiveConnectionLoops.noteQueued(current);
        }
    }

    /**
     * Off-loop branch of sendPacket (after the sent counter and Fabric's sent callback,
     * before vanilla/VMP post the per-packet task): the same doSendPacket runs on the
     * loop from this connection's outbox. A listener-free send of a region worker step
     * is held until the step ends; a listener-bearing one is never held.
     */
    @Inject(method = "sendPacket",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/channel/Channel;eventLoop()Lio/netty/channel/EventLoop;",
                    ordinal = 1),
            cancellable = true,
            require = 1,
            allow = 1)
    private void fabrolia$orderedOffLoopSend(Packet<?> packet, ChannelFutureListener listener,
                                             boolean flush, CallbackInfo ci) {
        fabrolia$outbox.offer(channel.eventLoop(),
                () -> doSendPacket(packet, listener, flush), flush, listener == null);
        ci.cancel();
    }

    /** Off-loop branch of flush(): the channel flush runs from the outbox, behind every
     *  operation appended before it. */
    @Inject(method = "flush()V",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/channel/Channel;eventLoop()Lio/netty/channel/EventLoop;",
                    ordinal = 1),
            cancellable = true,
            require = 1,
            allow = 1)
    private void fabrolia$orderedOffLoopFlush(CallbackInfo ci) {
        Channel current = channel;
        fabrolia$outbox.offer(current.eventLoop(), current::flush, true, true);
        ci.cancel();
    }

    /**
     * Channel operations that post themselves to the loop outside the outbox: the tick
     * flush (VMP may redirect it away), close, and the protocol-switch tasks. Whatever
     * was appended before them is queued ahead of them first.
     */
    @Inject(method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/channel/Channel;flush()Lio/netty/channel/Channel;"),
            require = 1,
            allow = 1)
    private void fabrolia$publishBeforeTickFlush(CallbackInfo ci) {
        fabrolia$publishBeforeDirect();
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/channel/Channel;close()Lio/netty/channel/ChannelFuture;"),
            require = 1,
            allow = 1)
    private void fabrolia$publishBeforeClose(CallbackInfo ci) {
        fabrolia$publishBeforeDirect();
    }

    @Inject(method = {"setupInboundProtocol", "setupOutboundProtocol"},
            at = @At(value = "INVOKE",
                    target = "Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;)"
                            + "Lio/netty/channel/ChannelFuture;"),
            require = 2,
            allow = 2)
    private void fabrolia$publishBeforeProtocolSwitch(CallbackInfo ci) {
        fabrolia$publishBeforeDirect();
    }

    @Unique
    private void fabrolia$publishBeforeDirect() {
        Channel current = channel;
        if (current != null) fabrolia$outbox.publishBeforeDirect(current.eventLoop());
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
