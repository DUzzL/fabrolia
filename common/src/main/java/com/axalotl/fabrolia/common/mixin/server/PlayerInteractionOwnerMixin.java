package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.mixin.network.ServerCommonPacketListenerAccessor;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * WORLD-mutation handlers retain actual source and target island claims. Island
 * replay validates its exact already-claimed venue and never blocks on Main.
 * Raw Netty entry performs no lookup or claim; vanilla owns its normal requeue.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerInteractionOwnerMixin {

    @Shadow public ServerPlayer player;

    private boolean fabrolia$rawEntry() {
        return !AsyncIslandPlayerTicks.inPacketReplay()
                && !com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(((ServerCommonPacketListenerAccessor) this).fabrolia$getServer());
    }

    @WrapMethod(method = "handleUseItemOn")
    private void fabrolia$ownUseItemOn(ServerboundUseItemOnPacket packet, Operation<Void> original) {
        withOwner(ChunkPos.pack(packet.getHitResult().getBlockPos()), true,
                () -> original.call(packet));
    }

    @WrapMethod(method = "handlePlayerAction")
    private void fabrolia$ownPlayerAction(ServerboundPlayerActionPacket packet, Operation<Void> original) {
        if (fabrolia$rawEntry()) {
            original.call(packet);
            return;
        }
        ServerboundPlayerActionPacket.Action action = packet.getAction();
        withOwner(fabrolia$playerActionOwnerChunk(packet, action),
                fabrolia$isDestroyBlockAction(action), () -> original.call(packet));
    }

    /**
     * Exact 26.2 split: only the three destroy-block actions consume the packet
     * position. STAB, swap, drop and release-use mutate the source player (and a
     * dropped item at that player's location); their wire position is a dummy
     * value and must never be interpreted as an external island target.
     */
    private long fabrolia$playerActionOwnerChunk(
            ServerboundPlayerActionPacket packet,
            ServerboundPlayerActionPacket.Action action) {
        if (fabrolia$isDestroyBlockAction(action)) {
            return ChunkPos.pack(packet.getPos());
        }
        if (action == ServerboundPlayerActionPacket.Action.STAB
                || action == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                || action == ServerboundPlayerActionPacket.Action.DROP_ITEM
                || action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                || action == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
            return this.player.chunkPosition().pack();
        }
        throw com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks
                .packetInteractionFailure("unknown 26.2 player action owner: " + action);
    }

    private static boolean fabrolia$isDestroyBlockAction(
            ServerboundPlayerActionPacket.Action action) {
        return action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK;
    }

    @WrapMethod(method = "handleUseItem")
    private void fabrolia$ownUseItem(ServerboundUseItemPacket packet, Operation<Void> original) {
        if (!fabrolia$routeFishingRetrieve(packet, original)) {
            withPlayerOwner(() -> original.call(packet));
        }
    }

    @WrapMethod(method = "handleInteract")
    private void fabrolia$ownInteract(ServerboundInteractPacket packet, Operation<Void> original) {
        withEntityOwner(packet.entityId(), () -> original.call(packet));
    }

    @WrapMethod(method = "handleContainerClick")
    private void fabrolia$ownContainerClick(ServerboundContainerClickPacket packet, Operation<Void> original) {
        withPlayerOwner(() -> original.call(packet));
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
        withEntityOwner(packet.entityId(), () -> original.call(packet));
    }

    @WrapMethod(method = "handleMoveVehicle")
    private void fabrolia$ownMoveVehicle(net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket packet, Operation<Void> original) {
        withVehicleOwner(() -> original.call(packet));
    }

    @WrapMethod(method = "handlePaddleBoat")
    private void fabrolia$ownPaddleBoat(net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket packet, Operation<Void> original) {
        withVehicleOwner(() -> original.call(packet));
    }

    private void withVehicleOwner(Runnable body) {
        if (fabrolia$rawEntry()) {
            body.run();
            return;
        }
        Entity vehicle = this.player.getRootVehicle();
        Entity target = vehicle != null && vehicle != this.player ? vehicle : this.player;
        withOwner(target.chunkPosition().pack(), body);
    }

    /** Riding jump / custom vehicle screen / leave-bed all touch island state
     *  at the player's position. */
    @WrapMethod(method = "handlePlayerCommand")
    private void fabrolia$ownPlayerCommand(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket packet, Operation<Void> original) {
        withPlayerOwner(() -> original.call(packet));
    }

    /** Reads block state + block-entity NBT (includeData) from the target. */
    @WrapMethod(method = "handlePickItemFromBlock")
    private void fabrolia$ownPickBlock(net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket packet, Operation<Void> original) {
        withOwner(ChunkPos.pack(packet.pos()), () -> original.call(packet));
    }

    @WrapMethod(method = "handlePickItemFromEntity")
    private void fabrolia$ownPickEntity(net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket packet, Operation<Void> original) {
        withEntityOwner(packet.id(), () -> original.call(packet));
    }

    /** Main-owned NBT query still needs a coherent target snapshot. */
    @WrapMethod(method = "handleEntityTagQuery")
    private void fabrolia$ownEntityTagQuery(
            net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket packet,
            Operation<Void> original) {
        withEntityOwner(packet.getEntityId(), () -> original.call(packet));
    }

    /** May drop an item entity into the island world. */
    @WrapMethod(method = "handleSetCreativeModeSlot")
    private void fabrolia$ownCreativeSlot(net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket packet, Operation<Void> original) {
        withPlayerOwner(() -> original.call(packet));
    }

    /** Menu ops whose containers are backed by island block entities /
     *  merchant entities: mutual exclusion with the island ticking them
     *  (a hopper pushing into the open chest, the villager restocking). */
    @WrapMethod(method = "handleContainerButtonClick")
    private void fabrolia$ownButtonClick(net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket packet, Operation<Void> original) {
        withPlayerOwner(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSelectTrade")
    private void fabrolia$ownSelectTrade(net.minecraft.network.protocol.game.ServerboundSelectTradePacket packet, Operation<Void> original) {
        withPlayerOwner(() -> original.call(packet));
    }

    @WrapMethod(method = "handlePlaceRecipe")
    private void fabrolia$ownPlaceRecipe(net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket packet, Operation<Void> original) {
        withPlayerOwner(() -> original.call(packet));
    }

    @WrapMethod(method = "handleRenameItem")
    private void fabrolia$ownRenameItem(net.minecraft.network.protocol.game.ServerboundRenameItemPacket packet, Operation<Void> original) {
        withPlayerOwner(() -> original.call(packet));
    }

    /** Writes BeaconBlockEntity effects. */
    @WrapMethod(method = "handleSetBeaconPacket")
    private void fabrolia$ownSetBeacon(net.minecraft.network.protocol.game.ServerboundSetBeaconPacket packet, Operation<Void> original) {
        withPlayerOwner(() -> original.call(packet));
    }

    /** Menu close can drop the carried stack into the island world. */
    @WrapMethod(method = "handleContainerClose")
    private void fabrolia$ownContainerClose(net.minecraft.network.protocol.game.ServerboundContainerClosePacket packet, Operation<Void> original) {
        withPlayerOwner(() -> original.call(packet));
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

    /**
     * A normal rod use is player-local. Reeling an existing hook is different:
     * FishingRodItem.use directly invokes player.fishing.retrieve(). The hook
     * venue cannot be classified safely before owning it, so every active reel
     * in async mode uses the already-established Main FIFO + all-island fence.
     */
    private boolean fabrolia$routeFishingRetrieve(
            ServerboundUseItemPacket packet, Operation<Void> original) {
        if (fabrolia$rawEntry()) return false;

        if (this.player.fishing == null
                || !(this.player.getItemInHand(packet.getHand()).getItem()
                instanceof FishingRodItem)) {
            return false;
        }

        Runnable retrieve = () -> original.call(packet);
        if (AsyncIslandPlayerTicks.inPacketReplay(this.player)) {
            throw AsyncIslandPlayerTicks.packetInteractionFailure(
                    "fishing retrieval entered an island packet body");
        }

        if (!IslandCommands.requiresMainFence()) {
            return false;
        }
        AsyncIslandPlayerTicks.runFencedMainMutation(
                "fishing-rod retrieve", retrieve);
        return true;
    }

    private void withPlayerOwner(Runnable body) {
        if (fabrolia$rawEntry()) {
            body.run();
            return;
        }
        withOwner(this.player.chunkPosition().pack(), body);
    }

    private void withOwner(long chunkPos, Runnable body) {
        withOwner(chunkPos, false, body);
    }

    private void withOwner(long chunkPos, boolean clientSelectedTarget, Runnable body) {
        if (fabrolia$rawEntry()) {
            body.run();
            return;
        }
        if (!(this.player.level() instanceof ServerLevel sl)) {
            body.run();
            return;
        }
        if (AsyncIslandPlayerTicks.inPacketReplay(this.player)) {
            if (!AsyncIslandPlayerTicks.ownsPacketInteractionTarget(this.player, chunkPos)) {
                if (clientSelectedTarget) {
                    // Vanilla rejects an out-of-reach BlockPos. The wire value is
                    // not an internal ownership invariant and must never select W.
                    return;
                }
                throw com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks
                        .packetInteractionFailure(
                                "A5 source-local packet left its claimed island");
            }
            body.run();
            return;
        }
        RegionizedServer runtime = RegionScheduler.forLevel(sl);
        if (runtime == null || !runtime.asyncActive || runtime.currentThreadHoldsAllClaims()) {
            RegionScheduler.runSuccessBodyIfHealthy(body);
            return;
        }
        IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(chunkPos);
        if (owner == null) {
            RegionScheduler.runSuccessBodyIfHealthy(body);
            return;
        }
        // The claim wait can outlive this session (disconnect, respawn, level
        // change, island retire). Revalidate the exact player/listener/venue
        // binding after the claim before running the body. Mount/dismount of
        // the same live player is not a session change: the body still runs,
        // as vanilla would, so sequence acks and container state stay in sync.
        ServerPlayer expected = this.player;
        boolean release = IslandCommands.mainClaimScoped(owner);
        Throwable failure = null;
        try {
            if (this.player == expected
                    && expected.connection == (Object) this
                    && expected.level() == sl
                    && RegionScheduler.forLevel(sl) == runtime
                    && runtime.islandOwnerOfChunk(chunkPos) == owner
                    && !owner.retired) {
                RegionScheduler.runSuccessBodyIfHealthy(body);
            }
        } catch (Throwable bodyFailure) {
            failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure);
        }
        try {
            if (release) IslandCommands.mainRelease(owner);
        } catch (Throwable cleanupFailure) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    fabrolia$mergeFailure(failure, cleanupFailure));
        }
        if (failure != null) IslandCommands.publishAndPropagate(failure);
    }

    /**
     * Resolve client-selected entity ids in the venue that already owns the source
     * player. Raw Netty entry performs no lookup and reaches vanilla's requeue.
     * Island replay accepts only a stable target in the exact claimed island; stale
     * or foreign ids are benign input rejection. Physical Main retains source and
     * target claims and revalidates the resolved identity after a target wait.
     */
    private void withEntityOwner(int entityId, Runnable body) {
        if (fabrolia$rawEntry()) {
            body.run();
            return;
        }
        if (!(this.player.level() instanceof ServerLevel level)) {
            body.run();
            return;
        }
        if (AsyncIslandPlayerTicks.inPacketReplay(this.player)) {
            Entity target = level.getEntityOrPart(entityId);
            if (target == null) {
                // Vanilla still updates source-player interaction state for an
                // absent id; no world target exists outside the claimed venue.
                body.run();
                return;
            }
            if (target.level() != level
                    || !com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks
                    .ownsPacketInteractionTarget(
                            this.player, target.chunkPosition().pack())) {
                return;
            }
            Entity current = level.getEntityOrPart(entityId);
            if (current != target || current.level() != level
                    || this.player.level() != level
                    || !com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks
                    .ownsPacketInteractionTarget(
                            this.player, current.chunkPosition().pack())) {
                return;
            }
            if (current instanceof ServerPlayer) {
                throw AsyncIslandPlayerTicks.packetInteractionFailure(
                        "player-target interaction entered an island packet body");
            }
            body.run();
            return;
        }

        RegionizedServer runtime = RegionScheduler.forLevel(level);
        if (runtime == null || !runtime.asyncActive) {
            RegionScheduler.runSuccessBodyIfHealthy(body);
            return;
        }
        if (runtime.currentThreadHoldsAllClaims()) {
            Entity target = level.getEntityOrPart(entityId);
            if (target == null
                    || (target.level() == level
                    && runtime.islandOwnerOfChunk(target.chunkPosition().pack())
                    == runtime.islandOwnerOfChunk(this.player.chunkPosition().pack()))) {
                RegionScheduler.runSuccessBodyIfHealthy(body);
            }
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            Entity target = level.getEntityOrPart(entityId);
            if (target == null) {
                withPlayerOwner(body);
                return;
            }
            if (target.level() != level || this.player.level() != level) return;
            IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(target.chunkPosition().pack());
            IslandRegistry.PIsland playerOwner = runtime.islandOwnerOfChunk(this.player.chunkPosition().pack());
            if (owner != playerOwner) return;
            if (target instanceof ServerPlayer) {
                AsyncIslandPlayerTicks.runFencedMainMutation(
                        "player-target entity interaction", () -> withEntityOwner(entityId, body));
                return;
            }
            if (owner == null) {
                RegionScheduler.runSuccessBodyIfHealthy(body);
                return;
            }
            IslandCommands.mainClaim(owner);
            boolean bodyRan = false;
            Throwable failure = null;
            try {
                Entity current = level.getEntityOrPart(entityId);
                if (current == target && current.level() == level && !owner.retired
                        && runtime.islandOwnerOfChunk(current.chunkPosition().pack()) == owner
                        && this.player.level() == level
                        && runtime.islandOwnerOfChunk(this.player.chunkPosition().pack()) == owner) {
                    RegionScheduler.runSuccessBodyIfHealthy(body);
                    bodyRan = true;
                }
            } catch (Throwable bodyFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure);
            }
            try {
                IslandCommands.mainRelease(owner);
            } catch (Throwable cleanupFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        fabrolia$mergeFailure(failure, cleanupFailure));
            }
            if (failure != null) IslandCommands.publishAndPropagate(failure);
            if (bodyRan) return;
        }
    }

    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && cleanup != primary) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
            }
        }
        return primary;
    }
}
