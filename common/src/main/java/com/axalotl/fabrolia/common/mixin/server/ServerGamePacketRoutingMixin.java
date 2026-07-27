package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.RegionalPlayerTicks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Regional player ticking (EXPERIMENTAL, default off — see RegionalPlayerTicks):
 *
 * <ul>
 *   <li>{@code tickPlayer}'s {@code doTick} invoke is skipped for region-owned
 *       players — their island worker runs the simulation tick at the island's
 *       cadence (the {@code ServerPlayer.tick} wrap tail in ServerPlayerMixin).</li>
 *   <li>Simulation packets (move / interact / use item / use item on / player
 *       action) route into the owner island's FIFO lane with an epoch token, so
 *       packet application shares ONE timeline with physics and sculk sensors —
 *       the A5 torn-player-state class (movement apply on main vs travel physics
 *       on a worker) cannot exist. Handlers re-execute on the worker; their
 *       same-thread assertions accept region workers (PacketUtilsWorkerMixin).</li>
 * </ul>
 *
 * All wraps are inert while the feature is off: routePacket/mainShouldSkipDoTick
 * return false and vanilla behavior is byte-identical.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketRoutingMixin {

    @Shadow public ServerPlayer player;

    @WrapOperation(method = "tickPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;doTick()V"))
    private void fabrolia$regionOwnedDoTick(ServerPlayer p, Operation<Void> original) {
        if (RegionalPlayerTicks.mainShouldSkipDoTick(p)) {
            return;
        }
        original.call(p);
    }

    /**
     * Connection-state serialization: the listener's per-tick movement
     * bookkeeping (awaiting-teleport timeout, position resets, keepalive-side
     * fields) and the teleport-acknowledgement handshake share fields with
     * {@code handleMovePlayer}, which the lane may execute on a region worker.
     * Every routed apply runs under {@code synchronized(connection)}
     * (RegionalPlayerTicks.drainLane); these wraps take the same monitor on
     * the main-owned side, so anti-cheat / teleport-ack state is mutually
     * exclusive instead of torn. Uncontended (feature off / no routed
     * packets) this is a biased-lock no-op.
     */
    @WrapMethod(method = "tick")
    private void fabrolia$serializeListenerTick(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @WrapMethod(method = "handleAcceptTeleportPacket")
    private void fabrolia$serializeTeleportAck(net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket packet,
                                               Operation<Void> original) {
        synchronized (this) {
            original.call(packet);
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routeMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "move", () -> self.handleMovePlayer(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routeInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "interact", () -> self.handleInteract(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routeUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "useItem", () -> self.handleUseItem(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routeUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "useItemOn", () -> self.handleUseItemOn(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routeAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "action", () -> self.handlePlayerAction(packet))) {
            ci.cancel();
        }
    }

    /** Containers stay MAIN-owned (v1 scope) but are SEQUENCED through the
     *  same per-player lane: a click can never overtake the routed
     *  use-item-on that opened the container. */
    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void fabrolia$sequenceContainerClick(net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "containerClick", true, () -> self.handleContainerClick(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void fabrolia$sequenceContainerClose(net.minecraft.network.protocol.game.ServerboundContainerClosePacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "containerClose", true, () -> self.handleContainerClose(packet))) {
            ci.cancel();
        }
    }
}
