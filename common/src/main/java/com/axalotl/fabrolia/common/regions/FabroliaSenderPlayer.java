package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;

/**
 * Duck on PlayerChunkSender: the owning player plus the bounded hand-off for
 * client chunk-batch acknowledgements.
 *
 * <p>The player is set at listener construction (markChunkPendingToSend has no
 * player parameter — without this, the login burst tracked nothing and those
 * chunks could never send). The acknowledgement method accepts raw float bits
 * so the sender venue wrapper can perform the conversion before entering its
 * tiny monitor; NaN payload identity is immaterial to vanilla but preserving
 * the bits makes the transfer lossless.</p>
 */
public interface FabroliaSenderPlayer {
    void fabrolia$setPlayer(ServerPlayer player);

    ServerPlayer fabrolia$player();

    /** External ingress: enqueue one bounded acknowledgement intent. */
    void fabrolia$enqueueChunkBatchAckBits(int desiredChunksPerTickBits);

    /** Connection teardown: discard intents and permanently close the mailbox. */
    void fabrolia$discardChunkBatchAcks();
}
