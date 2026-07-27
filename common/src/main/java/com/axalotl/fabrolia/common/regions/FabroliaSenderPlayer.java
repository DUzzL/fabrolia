package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;

/** Duck on PlayerChunkSender: the owning player, set at listener construction
 *  (markChunkPendingToSend has no player parameter — without this, the login
 *  burst tracked nothing and those chunks could never send). */
public interface FabroliaSenderPlayer {
    void fabrolia$setPlayer(ServerPlayer player);

    ServerPlayer fabrolia$player();
}
