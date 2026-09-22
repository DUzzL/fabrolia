package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Exact viewer/session identity staged for an owner-venue membership commit. */
public record IslandPendingPairing(
        ServerPlayer player,
        ServerGamePacketListenerImpl listener,
        Intent intent) {

    public enum Intent { ADD, REVALIDATE, REMOVE }

    public IslandPendingPairing(ServerPlayer player,
                                ServerGamePacketListenerImpl listener) {
        this(player, listener, Intent.ADD);
    }

    public boolean matchesExactly(ServerPlayer candidate) {
        return candidate == player && candidate != null
                && candidate.connection == listener
                && listener != null && listener.player == player;
    }

    /**
     * Removal is keyed by the player object captured by vanilla/VMP, not by
     * the listener's current player field. During respawn/dimension change the
     * listener may already point at the successor while its old pairing still
     * has to be removed from this exact tracker.
     */
    public boolean matchesRemovalPlayer(ServerPlayer candidate) {
        return candidate == player && listener != null;
    }
}
