package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaListenerLane;
import com.axalotl.fabrolia.common.regions.IslandCommands;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Regional player ticking (EXPERIMENTAL, default off — see RegionalPlayerTicks):
 *
 * <ul>
 *   <li>{@code tickPlayer}'s {@code doTick} invoke is skipped for region-owned
 *       players — their island worker runs the simulation tick at the island's
 *       cadence (the {@code ServerPlayer.tick} wrap tail in ServerPlayerMixin).</li>
 *   <li>Simulation packets route into the owner island's FIFO lane with an
 *       epoch token, so packet application shares ONE timeline with physics
 *       and sculk sensors — the A5 torn-player-state class (movement apply on
 *       main vs travel physics on a worker) cannot exist. Handlers re-execute
 *       on the worker; their same-thread assertions accept region workers
 *       (PacketUtilsWorkerMixin). Task #32 extends coverage to every
 *       simulation-ordered handler (attack, hotbar switch, swing, input,
 *       player command, pick item) — an unrouted order-sensitive handler
 *       executing immediately on main could overtake earlier routed packets
 *       (hotbar switch applying before the use-item that preceded it).</li>
 * </ul>
 *
 * All wraps are inert while the feature is off: routePacket/mainShouldSkipDoTick
 * return false and vanilla behavior is byte-identical.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketRoutingMixin implements FabroliaListenerLane {

    @Shadow public ServerPlayer player;

    @WrapOperation(method = "tickPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;doTick()V"))
    private void fabrolia$regionOwnedDoTick(ServerPlayer p, Operation<Void> original) {
        if (RegionalPlayerTicks.mainShouldSkipDoTick(p)) {
            return;
        }
        original.call(p);
    }

    @Unique
    private final ReentrantLock fabrolia$laneLock = new ReentrantLock();

    @Override
    public ReentrantLock fabrolia$laneLock() {
        return fabrolia$laneLock;
    }

    /**
     * Task #32 — main-side acquire of the apply mutex MUST keep pumping: a
     * worker-venue routed apply can park in the chunk main-bridge
     * ({@code awaitMainThreadResult}) while holding this lock, and the old
     * {@code synchronized (connection)} entry here was a raw, non-pumping
     * monitor block on main — the exact cycle shape of the production nav
     * deadlock, just through the connection monitor. tryLock + chunk-service
     * drain guarantees the worker's bridge always completes.
     */
    @Unique
    private void fabrolia$lockPumping() {
        if (fabrolia$laneLock.tryLock()) {
            return;
        }
        while (!fabrolia$laneLock.tryLock()) {
            IslandCommands.drainMainChunkServices(); // no-op off-main
            LockSupport.parkNanos(20_000L);
        }
    }

    /**
     * Connection-state serialization: the listener's per-tick movement
     * bookkeeping (awaiting-teleport timeout, position resets, keepalive-side
     * fields) and the teleport-acknowledgement handshake share fields with
     * {@code handleMovePlayer}, which the lane may execute on a region worker.
     * Every routed apply runs under the lane lock (RegionalPlayerTicks
     * .drainLane); these wraps take the same lock on the main-owned side, so
     * anti-cheat / teleport-ack state is mutually exclusive instead of torn.
     * Uncontended (feature off / no routed packets) this is one CAS.
     */
    @WrapMethod(method = "tick")
    private void fabrolia$serializeListenerTick(Operation<Void> original) {
        fabrolia$lockPumping();
        try {
            original.call();
        } finally {
            fabrolia$laneLock.unlock();
        }
    }

    @WrapMethod(method = "handleAcceptTeleportPacket")
    private void fabrolia$serializeTeleportAck(net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket packet,
                                               Operation<Void> original) {
        fabrolia$lockPumping();
        try {
            original.call(packet);
        } finally {
            fabrolia$laneLock.unlock();
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

    /** 26.2 split attack out of the interact packet — same routing rules:
     *  reach-bounded, must not overtake the movement/use packets before it. */
    @Inject(method = "handleAttack", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routeAttack(net.minecraft.network.protocol.game.ServerboundAttackPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "attack", () -> self.handleAttack(packet))) {
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

    /** Hotbar switch MUST share the lane: an immediate main apply while an
     *  earlier use-item sits queued would use the WRONG slot (reorder). */
    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routeCarriedItem(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "setCarriedItem", () -> self.handleSetCarriedItem(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleAnimate", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routeSwing(net.minecraft.network.protocol.game.ServerboundSwingPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "swing", () -> self.handleAnimate(packet))) {
            ci.cancel();
        }
    }

    /** Input feeds the region-owned doTick physics — applying it on main
     *  while a worker simulates the player is a torn read. */
    @Inject(method = "handlePlayerInput", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routeInput(net.minecraft.network.protocol.game.ServerboundPlayerInputPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "input", () -> self.handlePlayerInput(packet))) {
            ci.cancel();
        }
    }

    /** Sneak/sprint/leave-bed. Vehicle branches (riding jump, custom screen)
     *  can only be reached by passengers, who are region-INeligible — those
     *  applies stay on main (with the interaction claim). */
    @Inject(method = "handlePlayerCommand", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routePlayerCommand(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "playerCommand", () -> self.handlePlayerCommand(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePickItemFromBlock", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routePickBlock(net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "pickBlock", () -> self.handlePickItemFromBlock(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePickItemFromEntity", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routePickEntity(net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "pickEntity", () -> self.handlePickItemFromEntity(packet))) {
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

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void fabrolia$sequenceButtonClick(net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "containerButton", true, () -> self.handleContainerButtonClick(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSelectTrade", at = @At("HEAD"), cancellable = true)
    private void fabrolia$sequenceSelectTrade(net.minecraft.network.protocol.game.ServerboundSelectTradePacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "selectTrade", true, () -> self.handleSelectTrade(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void fabrolia$sequencePlaceRecipe(net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "placeRecipe", true, () -> self.handlePlaceRecipe(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleRenameItem", at = @At("HEAD"), cancellable = true)
    private void fabrolia$sequenceRenameItem(net.minecraft.network.protocol.game.ServerboundRenameItemPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "renameItem", true, () -> self.handleRenameItem(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetBeaconPacket", at = @At("HEAD"), cancellable = true)
    private void fabrolia$sequenceSetBeacon(net.minecraft.network.protocol.game.ServerboundSetBeaconPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "setBeacon", true, () -> self.handleSetBeaconPacket(packet))) {
            ci.cancel();
        }
    }

    /** Creative slot writes go through the inventory menu AND may drop item
     *  entities into the island world — main-owned, lane-sequenced. */
    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void fabrolia$sequenceCreativeSlot(net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RegionalPlayerTicks.routePacket(player, "creativeSlot", true, () -> self.handleSetCreativeModeSlot(packet))) {
            ci.cancel();
        }
    }
}
