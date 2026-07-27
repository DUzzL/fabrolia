package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.locks.LockSupport;

/**
 * A5 production hardening (2026-07-12) — player→WORLD mutation handlers run
 * on main (players are main-owned under async) but mutate ISLAND state that
 * may be mid-unit: block dig/place, item-on-block use, entity interaction,
 * container clicks (a click mutates a chest a hopper is pushing into on the
 * island thread). Each handler takes the TARGET's island unit claim for the
 * duration — human-rate packets, bounded by one unit, batch-aware spin (a
 * parked waiter island can only finish via the routed batch). Reentrant
 * under main's own quiesce windows.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerInteractionOwnerMixin {

    @Shadow public ServerPlayer player;

    @WrapMethod(method = "handleUseItemOn")
    private void fabrolia$ownUseItemOn(ServerboundUseItemOnPacket packet, Operation<Void> original) {
        withOwner(ChunkPos.pack(packet.getHitResult().getBlockPos()), () -> original.call(packet));
    }

    @WrapMethod(method = "handlePlayerAction")
    private void fabrolia$ownPlayerAction(ServerboundPlayerActionPacket packet, Operation<Void> original) {
        withOwner(ChunkPos.pack(packet.getPos()), () -> original.call(packet));
    }

    @WrapMethod(method = "handleUseItem")
    private void fabrolia$ownUseItem(ServerboundUseItemPacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    @WrapMethod(method = "handleInteract")
    private void fabrolia$ownInteract(ServerboundInteractPacket packet, Operation<Void> original) {
        // Target entity's chunk isn't decodable here without the level lookup
        // vanilla does inside; the player's own chunk bounds the interaction
        // reach (≤6 blocks) and shares the owner in all but cell-border cases,
        // where the claim of the ADJACENT same-island cell is equivalent
        // (one claim per island, not per region).
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    @WrapMethod(method = "handleContainerClick")
    private void fabrolia$ownContainerClick(ServerboundContainerClickPacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    // ------------------------------------------------------------------
    // Task #32 coverage extension — every remaining main-venue handler that
    // mutates island-owned state (vehicle entities, target mobs, BE-backed
    // menus, world item drops) takes the owner claim like the five above.
    // The player's own chunk bounds the interaction in all cases except the
    // vehicle pair (claim the ridden vehicle's island) and the sign edit
    // (claim the sign's island — the write happens in a deferred main task
    // after async text filtering, see fabrolia$ownSignText).
    // ------------------------------------------------------------------

    /** 26.2 split attack out of ServerboundInteractPacket. */
    @WrapMethod(method = "handleAttack")
    private void fabrolia$ownAttack(net.minecraft.network.protocol.game.ServerboundAttackPacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    /** Moves an island-ticked vehicle entity from main (passenger players are
     *  region-ineligible, so this handler never routes to a worker). */
    @WrapMethod(method = "handleMoveVehicle")
    private void fabrolia$ownMoveVehicle(net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket packet, Operation<Void> original) {
        net.minecraft.world.entity.Entity vehicle = this.player.getRootVehicle();
        long chunk = (vehicle != null && vehicle != this.player ? vehicle : this.player)
                .chunkPosition().pack();
        withOwner(chunk, () -> original.call(packet));
    }

    @WrapMethod(method = "handlePaddleBoat")
    private void fabrolia$ownPaddleBoat(net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket packet, Operation<Void> original) {
        net.minecraft.world.entity.Entity vehicle = this.player.getRootVehicle();
        long chunk = (vehicle != null && vehicle != this.player ? vehicle : this.player)
                .chunkPosition().pack();
        withOwner(chunk, () -> original.call(packet));
    }

    /** Riding jump / custom vehicle screen / leave-bed all touch island state
     *  at the player's position. */
    @WrapMethod(method = "handlePlayerCommand")
    private void fabrolia$ownPlayerCommand(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    /** Reads block state + block-entity NBT (includeData) from the target. */
    @WrapMethod(method = "handlePickItemFromBlock")
    private void fabrolia$ownPickBlock(net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket packet, Operation<Void> original) {
        withOwner(ChunkPos.pack(packet.pos()), () -> original.call(packet));
    }

    @WrapMethod(method = "handlePickItemFromEntity")
    private void fabrolia$ownPickEntity(net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    /** May drop an item entity into the island world. */
    @WrapMethod(method = "handleSetCreativeModeSlot")
    private void fabrolia$ownCreativeSlot(net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    /** Menu ops whose containers are backed by island block entities /
     *  merchant entities: mutual exclusion with the island ticking them
     *  (a hopper pushing into the open chest, the villager restocking). */
    @WrapMethod(method = "handleContainerButtonClick")
    private void fabrolia$ownButtonClick(net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    @WrapMethod(method = "handleSelectTrade")
    private void fabrolia$ownSelectTrade(net.minecraft.network.protocol.game.ServerboundSelectTradePacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    @WrapMethod(method = "handlePlaceRecipe")
    private void fabrolia$ownPlaceRecipe(net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    @WrapMethod(method = "handleRenameItem")
    private void fabrolia$ownRenameItem(net.minecraft.network.protocol.game.ServerboundRenameItemPacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    /** Writes BeaconBlockEntity effects. */
    @WrapMethod(method = "handleSetBeaconPacket")
    private void fabrolia$ownSetBeacon(net.minecraft.network.protocol.game.ServerboundSetBeaconPacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    /** Menu close can drop the carried stack into the island world. */
    @WrapMethod(method = "handleContainerClose")
    private void fabrolia$ownContainerClose(net.minecraft.network.protocol.game.ServerboundContainerClosePacket packet, Operation<Void> original) {
        withOwner(this.player.chunkPosition().pack(), () -> original.call(packet));
    }

    /** The sign write runs in a DEFERRED main task (after async chat
     *  filtering), not in handleSignUpdate itself — claim at the actual
     *  mutation point so the SignBlockEntity write is exclusive with the
     *  island that ticks it. */
    @WrapMethod(method = "updateSignText")
    private void fabrolia$ownSignText(net.minecraft.network.protocol.game.ServerboundSignUpdatePacket packet,
                                      java.util.List<net.minecraft.server.network.FilteredText> lines,
                                      Operation<Void> original) {
        withOwner(ChunkPos.pack(packet.getPos()), () -> original.call(packet, lines));
    }

    private void withOwner(long chunkPos, Runnable body) {
        if (!(this.player.level() instanceof ServerLevel sl)) {
            body.run();
            return;
        }
        // NETTY PASS-THROUGH (task #25 — the 2026-07-23 05:58/08:05 production
        // wedges): these @WrapMethods run BEFORE vanilla's
        // ensureRunningOnSameThread head, so on the network thread this wrap
        // used to resolve the island and take mainClaim — parking the epoll
        // loop behind live units and driving the main-confined claim ledger
        // (MAIN_HELD) and, via the claim spin, the routed-batch/fence
        // machinery from Netty, concurrently with main (both watchdog dumps
        // show a Netty thread inside mainClaim). Off-main this wrap must
        // contribute NOTHING: call straight through, let the untouched handler
        // head throw RunningOnDifferentThreadException and requeue to main
        // exactly as vanilla does, and engage on the main-thread replay where
        // the claim is legal. Vanilla behavior is unchanged in both orders.
        net.minecraft.server.MinecraftServer srv = sl.getServer();
        if (srv != null && !srv.isSameThread()) {
            body.run();
            return;
        }
        RegionizedServer rs = RegionScheduler.forLevel(sl);
        if (rs == null || !rs.asyncActive || rs.mainHoldsAllClaims()) {
            body.run();
            return;
        }
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkPos);
        if (owner == null) {
            body.run();
            return;
        }
        IslandCommands.mainClaim(owner);
        try {
            body.run();
        } finally {
            IslandCommands.mainRelease(owner);
        }
    }
}
