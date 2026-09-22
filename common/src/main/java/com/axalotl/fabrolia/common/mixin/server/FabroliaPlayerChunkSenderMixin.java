package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.ChunkBatchAckReplay;
import com.axalotl.fabrolia.common.regions.ChunkDelivery;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-07-14 delivery rewrite: this mixin is now a THIN adapter between the
 * vanilla sender surface (which VMP also drives — it has no sender mixins of
 * its own, audited) and {@link ChunkDelivery}, the single authoritative
 * pipeline. Removed from the old version: async collect filtering with
 * pendingChunks reinsertion, take-once snapshot consumption, the batch
 * exception belt, and the 30s ack-liveness reset — the transactional batch
 * in ChunkDelivery makes leaked acknowledgement obligations impossible, so
 * recovery-as-control-flow is gone (a liveness WARN remains, report-only).
 *
 * <p>Vanilla's plain {@code pendingChunks} mirror is bypassed completely.
 * Fabrolia cancels its two mutators, replaces sendNextChunks, and routes
 * isPending to ChunkDelivery, so one concurrent lifecycle owns selection and
 * publication. dropChunk's old {@code pendingChunks.remove == false} heuristic
 * is replaced by the exact record's "full EventLoop task was accepted" result.
 */
@Mixin(PlayerChunkSender.class)
public abstract class FabroliaPlayerChunkSenderMixin
        implements com.axalotl.fabrolia.common.regions.FabroliaSenderPlayer {

    /** Exact 26.2 post-first-ack batch window. Also bounds hostile surplus
     *  ACK work and is sufficient to retire every possible live obligation. */
    @Unique private static final int FABROLIA_MAX_PENDING_ACKS = 10;

    @Shadow private int unacknowledgedBatches;
    @Shadow private int maxUnacknowledgedBatches;
    @Shadow private float batchQuota;
    @Shadow private float desiredChunksPerTick;

    @Unique private volatile ServerPlayer fabrolia$player;
    @Unique private final NetherPortalPlayerAccess.ChunkSendScope fabrolia$sendScope =
            new NetherPortalPlayerAccess.ChunkSendScope();
    /** Guarded only by this PlayerChunkSender's intrinsic monitor. */
    @Unique private int fabrolia$pendingAckCount;
    /** Raw float bits; guarded by the same monitor. */
    @Unique private int fabrolia$latestAckDesiredBits;
    /** Terminal connection fence; guarded by the same monitor. */
    @Unique private boolean fabrolia$ackMailboxClosed;

    @Override
    public void fabrolia$setPlayer(ServerPlayer player) {
        fabrolia$player = player;
    }

    @Override
    public ServerPlayer fabrolia$player() {
        return fabrolia$player;
    }

    /**
     * External ACK publication (island or Main). The monitor section contains
     * bounded primitive operations only: no world, connection, delivery-ledger or other monitor
     * call can form a sender-lock edge. Once the count reaches vanilla's exact
     * maximum outstanding window, further surplus ACKs only replace the latest
     * desired-rate bits; ten replays already reduce every legal Main-owned
     * counter to zero and retire every legal outstanding batch.
     */
    @Override
    public synchronized void fabrolia$enqueueChunkBatchAckBits(int desiredChunksPerTickBits) {
        if (fabrolia$ackMailboxClosed) {
            return;
        }
        fabrolia$latestAckDesiredBits = desiredChunksPerTickBits;
        if (fabrolia$pendingAckCount < FABROLIA_MAX_PENDING_ACKS) {
            fabrolia$pendingAckCount++;
        }
    }

    /** Atomically transfers monitor-owned intent to Main as
     *  {@code [count:32 | desiredRawBits:32]}. Primitive operations only. */
    @Unique
    private synchronized long fabrolia$takeChunkBatchAcks() {
        int count = fabrolia$pendingAckCount;
        if (count == 0) {
            return 0L;
        }
        long snapshot = ((long) count << 32)
                | (fabrolia$latestAckDesiredBits & 0xffff_ffffL);
        fabrolia$pendingAckCount = 0;
        return snapshot;
    }

    /** Teardown is another primitive-only monitor transaction. */
    @Override
    public synchronized void fabrolia$discardChunkBatchAcks() {
        fabrolia$pendingAckCount = 0;
        fabrolia$ackMailboxClosed = true;
    }

    /**
     * Exact venue split around the vanilla 26.2 ACK method. Every external call
     * (island or Main) enters the same mailbox, preserving FIFO across a sticky
     * lane hand-off. Only ChunkBatchAckReplay's exact-sender, physical-Main
     * one-shot permit invokes the complete transformed original (including
     * C2ME's TAIL) and then retires exactly one delivery obligation.
     */
    @WrapMethod(method = "onChunkBatchReceivedByClient")
    private void fabrolia$routeChunkBatchAck(float desiredChunksPerTick,
                                              Operation<Void> original) {
        PlayerChunkSender sender = (PlayerChunkSender) (Object) this;
        if (ChunkBatchAckReplay.consume(sender)) {
            original.call(desiredChunksPerTick);
            // Vanilla/C2ME ACK handling is one entered body.  Its Fabrolia
            // ledger tail is a separate success-only stage and therefore gets
            // a fresh atomic entry cut; a peer W selected by the time the
            // transformed original returns forbids that successor entirely.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> ChunkDelivery.onAck(fabrolia$player));
            return;
        }
        fabrolia$enqueueChunkBatchAckBits(Float.floatToRawIntBits(desiredChunksPerTick));
    }

    /**
     * Main-only drain. Replaying the latest desired value N times is exactly
     * equivalent to replaying all N values for 26.2: every call decrements the
     * unacknowledged counter with a zero floor and sets max-unacked to ten;
     * only the final desired value survives; and quota becomes one iff the
     * counter reaches zero. C2ME's audited TAIL merely scales that final value.
     */
    @Unique
    private void fabrolia$replayChunkBatchAcksOnMain(long snapshot) {
        int count = (int) (snapshot >>> 32);
        if (count == 0) {
            return;
        }
        float latestDesired = Float.intBitsToFloat((int) snapshot);
        PlayerChunkSender sender = (PlayerChunkSender) (Object) this;
        // The exact installed replay body is bounded scalar math + C2ME's
        // scalar TAIL, followed by ChunkDelivery's non-throwing FIFO retire.
        // ChunkBatchAckOwnershipTest pins that surface: clearing the compact
        // snapshot before replay is therefore a commit, not a lossy try-loop.
        for (int i = 0; i < count; i++) {
            // Arms a one-shot exact-sender permit, then re-enters the target
            // wrapper: transformed vanilla body, then exactly one ledger ACK.
            // Every replay is its own causal body admission. A peer W between
            // two ACKs prevents the next transformed vanilla body from entering.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> ChunkBatchAckReplay.replay(sender, latestDesired));
        }
    }

    /**
     * Main success sequence. Fixed pacing writes use the lifecycle cut, while
     * every transformed vanilla ACK receives its own outside-monitor body
     * admission. This prevents a peer W between two ACKs from becoming a
     * catch-and-continue permission for the later replay.
     */
    @Unique
    private void fabrolia$applyCompletedAndReplay(int completed, long ackSnapshot) {
        if (completed > 0) {
            RegionScheduler.commitSuccessIfHealthy(() -> {
                batchQuota -= completed;
                unacknowledgedBatches++;
            });
        }
        fabrolia$replayChunkBatchAcksOnMain(ackSnapshot);
        RegionScheduler.commitSuccessIfHealthy(() -> {
            if (unacknowledgedBatches < maxUnacknowledgedBatches) {
                float cap = Math.max(1.0f, desiredChunksPerTick);
                batchQuota = Math.min(batchQuota + desiredChunksPerTick, cap);
            }
        });
    }

    @Inject(method = "markChunkPendingToSend", at = @At("HEAD"), cancellable = true)
    private void fabrolia$track(LevelChunk chunk, CallbackInfo ci) {
        ServerPlayer p = fabrolia$player;
        if (p == null) {
            throw new IllegalStateException(
                    "chunk sender used before listener bound its owning player");
        }
        ChunkDelivery.track(p, chunk);
        ci.cancel();
    }

    /**
     * Island-side incremental block publication may query this gate while
     * Main is mutating vanilla's plain LongOpenHashSet. ChunkDelivery already
     * owns the same lifecycle in a concurrent per-player map, so use that
     * authoritative view instead of touching pendingChunks off-main.
     */
    @WrapMethod(method = "isPending")
    private boolean fabrolia$isPendingConcurrent(long chunkKey,
                                                  Operation<Boolean> original) {
        ServerPlayer player = this.fabrolia$player;
        return player != null
                ? ChunkDelivery.isPending(player, chunkKey)
                : original.call(chunkKey);
    }

    @Inject(method = "dropChunk", at = @At("HEAD"), cancellable = true)
    private void fabrolia$untrack(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        fabrolia$player = player;
        ChunkDelivery.untrack(player, pos);
        ci.cancel();
    }

    /**
     * Full replacement of the batch construction. Vanilla's pacing fields
     * remain authoritative. The prior EventLoop transaction's completion
     * intent is applied first, so quota and unacknowledged state cannot advance
     * before Start, every real full send, and Finished all succeeded.
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"), cancellable = true)
    private void fabrolia$send(ServerPlayer player, CallbackInfo ci) {
        RegionScheduler.throwWorkerFailureIfAny();
        ci.cancel();
        // A Nether worker may already have changed player.level while Respawn
        // remains in its Main metadata outbox. Exclude the whole sender pass
        // against capture, not just a racy level comparison before selection.
        if (!fabrolia$sendScope.tryEnter(player)) return;
        try (var ignored = fabrolia$sendScope) {
            fabrolia$player = player;
            // Completion and ACK skeletons survive the transition unchanged.
            // Previously offered EventLoop tasks precede its Respawn in FIFO.
            long ackSnapshot = fabrolia$takeChunkBatchAcks();
            int completed = ChunkDelivery.applyCompletedChunkBatch(player);
            fabrolia$applyCompletedAndReplay(completed, ackSnapshot);
            if (unacknowledgedBatches >= maxUnacknowledgedBatches) {
                return;
            }
            if (batchQuota < 1.0f) {
                return;
            }
            ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
            if (level == null) {
                return;
            }
            // A receipt offer is not success; a later pass commits completion.
            ChunkDelivery.sendNextChunks(player, level, (int) batchQuota);
        }
    }
}
