package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;

/**
 * Narrow outbound capability for a lifecycle-ordered chunk Forget.
 * Implementations may only offer an immutable task to the connection's
 * ordered EventLoop; listener send and compatibility callbacks run later.
 */
public interface FabroliaChunkForgetPublisher {

    void fabrolia$enqueueChunkForget(ClientboundForgetLevelChunkPacket packet);
}
