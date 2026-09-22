package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess;
import com.axalotl.fabrolia.common.regions.WorldGenEntityConstruction;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Thread-safety for the 26.1 locator-bar waypoint manager. One instance per
 * ServerLevel; internally plain HashSets + a guava Table of player↔transmitter
 * connections. Vanilla only ever touches it from the server thread. The removed
 * worker-player prototype demonstrated the failure mode (confirmed live 2026-06-09:
 * concurrent movement updates produced a HashIterator CME). The monitor remains a
 * defensive guard for mod/compat off-main callers; it is not a player-tick router.
 *
 * <p>Instance monitor, NOT {@code FabroliaLocks.tickLockOr}: the manager is a
 * level-wide shared structure — players in different regions share it, so a
 * per-region lock is unsound here (the exact cross-region-shared-state case the
 * tickLockOr javadoc excludes). Uncontended monitor cost ~20ns per movement tick
 * per player is noise.
 */
@Mixin(ServerWaypointManager.class)
public abstract class ServerWaypointManagerMixin {

    @WrapMethod(method = "trackWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V")
    private void fabrolia$trackWaypoint(WaypointTransmitter transmitter, Operation<Void> original) {
        if (transmitter instanceof Entity entity) WorldGenEntityConstruction.revoke(entity);
        var metadata = NetherPortalPlayerAccess.metadataOwner(transmitter);
        if (metadata != null && metadata.deferMetadata(() -> {
            synchronized (this) { original.call(transmitter); }
        })) return;
        synchronized (this) { original.call(transmitter); }
    }

    @WrapMethod(method = "updateWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V")
    private void fabrolia$updateWaypoint(WaypointTransmitter transmitter, Operation<Void> original) {
        var metadata = NetherPortalPlayerAccess.metadataOwner(transmitter);
        if (metadata != null && transmitter instanceof Entity entity) WorldGenEntityConstruction.revoke(entity);
        if (metadata != null && metadata.deferMetadata(() -> {
            synchronized (this) { original.call(transmitter); }
        })) return;
        synchronized (this) { original.call(transmitter); }
    }

    @WrapMethod(method = "untrackWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V")
    private void fabrolia$untrackWaypoint(WaypointTransmitter transmitter, Operation<Void> original) {
        var metadata = NetherPortalPlayerAccess.metadataOwner(transmitter);
        if (metadata != null && transmitter instanceof Entity entity) WorldGenEntityConstruction.revoke(entity);
        if (metadata != null && metadata.deferMetadata(() -> {
            synchronized (this) { original.call(transmitter); }
        })) return;
        synchronized (this) { original.call(transmitter); }
    }

    @WrapMethod(method = "addPlayer")
    private void fabrolia$addPlayer(ServerPlayer player, Operation<Void> original) {
        var metadata = NetherPortalPlayerAccess.metadataOwner(player);
        if (metadata != null && metadata.deferMetadata(() -> {
            synchronized (this) { original.call(player); }
        })) return;
        synchronized (this) { original.call(player); }
    }

    @WrapMethod(method = "updatePlayer")
    private void fabrolia$updatePlayer(ServerPlayer player, Operation<Void> original) {
        var metadata = NetherPortalPlayerAccess.metadataOwner(player);
        if (metadata != null && metadata.deferMetadata(() -> {
            synchronized (this) { original.call(player); }
        })) return;
        synchronized (this) { original.call(player); }
    }

    @WrapMethod(method = "removePlayer")
    private void fabrolia$removePlayer(ServerPlayer player, Operation<Void> original) {
        var metadata = NetherPortalPlayerAccess.metadataOwner(player);
        if (metadata != null && metadata.deferMetadata(() -> {
            synchronized (this) { original.call(player); }
        })) return;
        synchronized (this) { original.call(player); }
    }

    @WrapMethod(method = "breakAllConnections")
    private void fabrolia$breakAllConnections(Operation<Void> original) {
        var metadata = NetherPortalPlayerAccess.metadataOwner(null);
        if (metadata != null && metadata.deferMetadata(() -> {
            synchronized (this) { original.call(); }
        })) return;
        synchronized (this) { original.call(); }
    }

    @WrapMethod(method = "remakeConnections")
    private void fabrolia$remakeConnections(WaypointTransmitter transmitter, Operation<Void> original) {
        if (transmitter instanceof Entity entity) WorldGenEntityConstruction.revoke(entity);
        var metadata = NetherPortalPlayerAccess.metadataOwner(transmitter);
        if (metadata != null && metadata.deferMetadata(() -> {
            synchronized (this) { original.call(transmitter); }
        })) return;
        synchronized (this) { original.call(transmitter); }
    }
}
