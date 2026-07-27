package com.axalotl.fabrolia.common.mixin.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Thread-safety for the 26.1 locator-bar waypoint manager. One instance per
 * ServerLevel; internally plain HashSets + a guava Table of player↔transmitter
 * connections. Vanilla only ever touches it from the server thread; with region
 * threading every player movement tick calls {@code updatePlayer} via
 * {@code Entity.setPosRaw} from that player's region worker — two players moving
 * concurrently CME each other (HashIterator over connections while another thread
 * mutates: confirmed live 2026-06-09, Ticking player → ServerWaypointManager
 * .updatePlayer → Sets$3$1.computeNext → CME).
 *
 * <p>Instance monitor, NOT {@code FabroliaLocks.tickLockOr}: the manager is a
 * level-wide shared structure — players in different regions share it, so a
 * per-region lock is unsound here (the exact cross-region-shared-state case the
 * tickLockOr javadoc excludes). Uncontended monitor cost ~20ns per movement tick
 * per player is noise. Packet sends inside connection updates are netty-fabrolia
 * and safe under the monitor.
 */
@Mixin(ServerWaypointManager.class)
public abstract class ServerWaypointManagerMixin {

    @WrapMethod(method = "trackWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V")
    private synchronized void fabrolia$trackWaypoint(WaypointTransmitter transmitter, Operation<Void> original) {
        original.call(transmitter);
    }

    @WrapMethod(method = "updateWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V")
    private synchronized void fabrolia$updateWaypoint(WaypointTransmitter transmitter, Operation<Void> original) {
        original.call(transmitter);
    }

    @WrapMethod(method = "untrackWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V")
    private synchronized void fabrolia$untrackWaypoint(WaypointTransmitter transmitter, Operation<Void> original) {
        original.call(transmitter);
    }

    @WrapMethod(method = "addPlayer")
    private synchronized void fabrolia$addPlayer(ServerPlayer player, Operation<Void> original) {
        original.call(player);
    }

    @WrapMethod(method = "updatePlayer")
    private synchronized void fabrolia$updatePlayer(ServerPlayer player, Operation<Void> original) {
        original.call(player);
    }

    @WrapMethod(method = "removePlayer")
    private synchronized void fabrolia$removePlayer(ServerPlayer player, Operation<Void> original) {
        original.call(player);
    }

    @WrapMethod(method = "breakAllConnections")
    private synchronized void fabrolia$breakAllConnections(Operation<Void> original) {
        original.call();
    }

    @WrapMethod(method = "remakeConnections")
    private synchronized void fabrolia$remakeConnections(WaypointTransmitter transmitter, Operation<Void> original) {
        original.call(transmitter);
    }
}
