package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;

/**
 * One-shot authority for replaying a compacted chunk-batch ACK on physical
 * Main. Every external invocation of PlayerChunkSender's ACK method enters the
 * bounded mailbox, including invocations that happen to arrive on Main during
 * a lane hand-off. Only {@link #replay} may arm the transformed vanilla body.
 *
 * <p>The permit is exact-sender and thread-local: an island call can never
 * steal Main's permit for the same sender. {@link #consume} removes the permit
 * before the transformed vanilla body runs, so a re-entrant external ACK is
 * enqueued behind it instead of bypassing FIFO. A separate active marker makes
 * nested internal replay fail closed even after the one-shot permit has been
 * consumed.</p>
 */
public final class ChunkBatchAckReplay {

    private static final ThreadLocal<PlayerChunkSender> PERMIT = new ThreadLocal<>();
    private static final ThreadLocal<PlayerChunkSender> ACTIVE = new ThreadLocal<>();

    private ChunkBatchAckReplay() { }

    /** Main-only internal entry used by PlayerChunkSender's mailbox drain. */
    public static void replay(PlayerChunkSender sender, float desiredChunksPerTick) {
        if (sender == null) {
            throw new IllegalArgumentException("null chunk sender replay");
        }
        ServerPlayer player = ((FabroliaSenderPlayer) (Object) sender).fabrolia$player();
        MinecraftServer server = player == null ? null : player.level().getServer();
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("chunk-batch ACK replay requires physical Main");
        }
        if (ACTIVE.get() != null || PERMIT.get() != null) {
            throw new IllegalStateException("nested chunk-batch ACK replay");
        }
        ACTIVE.set(sender);
        PERMIT.set(sender);
        try {
            sender.onChunkBatchReceivedByClient(desiredChunksPerTick);
            if (PERMIT.get() == sender) {
                throw new IllegalStateException("chunk-batch ACK replay permit was not consumed");
            }
        } finally {
            PERMIT.remove();
            ACTIVE.remove();
        }
    }

    /**
     * Called by the PlayerChunkSender wrapper. Successful consumption is the
     * sole authority to invoke its transformed original body and ledger ACK.
     */
    public static boolean consume(PlayerChunkSender sender) {
        if (sender == null || PERMIT.get() != sender) {
            return false;
        }
        PERMIT.remove();
        return true;
    }
}
