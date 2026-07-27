package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.ChunkDelivery;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
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
 * <p>Vanilla's {@code pendingChunks} is kept as an exact mirror of
 * "tracked but full packet not yet enqueued": added by the vanilla body of
 * markChunkPendingToSend, removed either by dropChunk (vanilla body — its
 * forget-packet logic is untouched) or by our enqueue callback. That keeps
 * vanilla {@code isPending} — and every vanilla/VMP broadcast gate built on
 * it — correct without redirecting anything.
 */
@Mixin(PlayerChunkSender.class)
public abstract class FabroliaPlayerChunkSenderMixin
        implements com.axalotl.fabrolia.common.regions.FabroliaSenderPlayer {

    @Shadow @Final private it.unimi.dsi.fastutil.longs.LongSet pendingChunks;
    @Shadow private int unacknowledgedBatches;
    @Shadow private int maxUnacknowledgedBatches;
    @Shadow private float batchQuota;
    @Shadow private float desiredChunksPerTick;

    @Unique private ServerPlayer fabrolia$player;

    @Override
    public void fabrolia$setPlayer(ServerPlayer player) {
        fabrolia$player = player;
    }

    @Override
    public ServerPlayer fabrolia$player() {
        return fabrolia$player;
    }

    @Inject(method = "markChunkPendingToSend", at = @At("TAIL"))
    private void fabrolia$track(LevelChunk chunk, CallbackInfo ci) {
        ServerPlayer p = fabrolia$player;
        if (p != null) {
            ChunkDelivery.track(p, chunk);
        }
    }

    @Inject(method = "dropChunk", at = @At("HEAD"))
    private void fabrolia$untrack(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        fabrolia$player = player;
        ChunkDelivery.untrack(player, pos.pack());
    }

    /**
     * Full replacement of the batch construction. Vanilla's pacing fields
     * remain authoritative: same unacked gate, same quota accumulation, and
     * the unacknowledged counter is committed only after ChunkDelivery got a
     * Finished packet onto the wire (transactional — no leak on exception).
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"), cancellable = true)
    private void fabrolia$send(ServerPlayer player, CallbackInfo ci) {
        ci.cancel();
        fabrolia$player = player;
        if (unacknowledgedBatches >= maxUnacknowledgedBatches) {
            return;
        }
        float cap = Math.max(1.0f, desiredChunksPerTick);
        batchQuota = Math.min(batchQuota + desiredChunksPerTick, cap);
        if (batchQuota < 1.0f) {
            return;
        }
        ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
        if (level == null) {
            return;
        }
        int sent = ChunkDelivery.sendNextChunks(player, level, (int) batchQuota,
                pendingChunks::remove);
        if (sent > 0) {
            batchQuota -= sent;
            unacknowledgedBatches++;
        }
    }
}
