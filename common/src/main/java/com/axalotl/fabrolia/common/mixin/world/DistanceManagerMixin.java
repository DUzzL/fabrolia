package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.axalotl.fabrolia.api.fastutil.ConcurrentLongLinkedOpenHashSet;
import com.axalotl.fabrolia.common.regions.CompletedPlayerProximityView;
import com.axalotl.fabrolia.common.regions.CompletedSimulationView;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.SimulationChunkTracker;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {

    @Shadow
    @Final
    @Mutable
    protected Set<ChunkHolder> chunksToUpdateFutures = ConcurrentCollections.newHashSet();

    @Shadow
    @Final
    @Mutable
    private LongSet ticketsToRelease = new ConcurrentLongLinkedOpenHashSet();

    @Shadow
    @Final
    private SimulationChunkTracker simulationChunkTracker;

    // -----------------------------------------------------------------------
    // Completed-wave simulation reads (see CompletedSimulationView for the
    // incident). The tracker's live map is the propagator's WORKING state —
    // mid-wave it holds transient levels and even removes entries entirely
    // (setLevel at ≥33), so a worker gate reading it concurrently could see a
    // fully-simulated chunk as "not simulated" for one evaluation and freeze
    // that chunk's ticks for a tick (cross-chunk contraption skew, 2026-07-06).
    // Every consumer below now reads the snapshot published at wave completion:
    // coherent completed states only, on any thread, at the same lookup cost.
    // -----------------------------------------------------------------------

    @Inject(method = "inEntityTickingRange", at = @At("HEAD"), cancellable = true)
    private void fabrolia$completedEntityTicking(long key, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(ChunkLevel.isEntityTicking(
                ((CompletedSimulationView) this.simulationChunkTracker).fabrolia$completedLevel(key)));
    }

    @Inject(method = "inBlockTickingRange", at = @At("HEAD"), cancellable = true)
    private void fabrolia$completedBlockTicking(long key, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(ChunkLevel.isBlockTicking(
                ((CompletedSimulationView) this.simulationChunkTracker).fabrolia$completedLevel(key)));
    }

    @Inject(method = "getChunkLevel", at = @At("HEAD"), cancellable = true)
    private void fabrolia$completedChunkLevel(long key, boolean simulation, CallbackInfoReturnable<Integer> cir) {
        if (simulation) {
            cir.setReturnValue(
                    ((CompletedSimulationView) this.simulationChunkTracker).fabrolia$completedLevel(key));
        }
    }

    /** Same iteration vanilla does, over the completed view — keeps the deferred
     *  block-tick/spawn collection consistent with the gates the workers use.
     *  NOTE: the parameter is it.unimi.dsi.fastutil.longs.LongConsumer — the
     *  vanilla signature. The java.util.function twin compiles fine (fastutil's
     *  extends it, and the mixin AP is off) but fails at APPLY with an invalid
     *  descriptor — live boot crash 2026-07-06. */
    @Inject(method = "forEachEntityTickingChunk", at = @At("HEAD"), cancellable = true)
    private void fabrolia$forEachCompletedEntityTicking(LongConsumer consumer, CallbackInfo ci) {
        Long2ByteMap view = ((CompletedSimulationView) this.simulationChunkTracker).fabrolia$completedView();
        for (Long2ByteMap.Entry entry : Long2ByteMaps.fastIterable(view)) {
            if (ChunkLevel.isEntityTicking(entry.getByteValue())) {
                consumer.accept(entry.getLongKey());
            }
        }
        ci.cancel();
    }

    // -----------------------------------------------------------------------
    // Completed-wave PLAYER-PROXIMITY reads (task #40, audit finding #1 — see
    // CompletedPlayerProximityView for the full incident). Vanilla's three
    // spawn-proximity readers each start by RUNNING the naturalSpawnChunkCounter
    // propagation wave inline — under the DynamicGraphMinFixedPoint blanket
    // monitor, unbounded, and (via getPlayersCloseForSpawning) while the calling
    // spawn worker holds the ChunkMap monitor: main's consolidation wave then
    // convoys every worker in ChunkMap.move behind one parked spawn worker.
    // The unsynchronized door (anyPlayerCloseEnoughForSpawning) additionally let
    // workers run the wave themselves, racing playersPerChunk rehashes with no
    // ChunkMap monitor (the 2026-06-10 AIOOBE class). The wraps below make all
    // three readers PURE SNAPSHOT READS: the wave INVOKE is dropped (waves run
    // only in DistanceManager.runAllUpdates — main-only via ServerChunkCacheMixin's
    // off-main refusal + ChunkMap-monitor wrap), and level/size/keySet reads come
    // from the immutable view published at wave completion. Vanilla's TriState
    // ring logic and iteration shape stay untouched bytecode. Handlers take
    // @Coerce Object because FixedPlayerDistanceChunkTracker is a private inner
    // class (invisible to this mixin's source).
    // -----------------------------------------------------------------------

    @WrapOperation(
            method = {"hasPlayersNearby", "getNaturalSpawnChunkCount", "getSpawnCandidateChunks"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;runAllUpdates()V"))
    private void fabrolia$noEagerWaveOnReads(@Coerce Object tracker, Operation<Void> original) {
        // Deliberately NOT calling original: readers must never drive (or block
        // on) the propagator. Consolidation already ran this tick on main before
        // any consumer — staleness bound is one wave, the CompletedSimulationView
        // class of tolerance.
    }

    @WrapOperation(
            method = "hasPlayersNearby",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;getLevel(J)I"))
    private int fabrolia$completedProximityLevel(@Coerce Object tracker, long pos, Operation<Integer> original) {
        return ((CompletedPlayerProximityView) tracker).fabrolia$completedLevel(pos);
    }

    /** getNaturalSpawnChunkCount reads {@code chunks.size()} (mob-cap scaling),
     *  getSpawnCandidateChunks hands out {@code chunks.keySet().iterator()} —
     *  both now read the immutable snapshot, so the iterator can never observe
     *  a mid-wave remove (and never needs the live map's guarantees). */
    @WrapOperation(
            method = {"getNaturalSpawnChunkCount", "getSpawnCandidateChunks"},
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;chunks:Lit/unimi/dsi/fastutil/longs/Long2ByteMap;",
                    opcode = Opcodes.GETFIELD))
    private Long2ByteMap fabrolia$completedProximityChunks(@Coerce Object tracker, Operation<Long2ByteMap> original) {
        return ((CompletedPlayerProximityView) tracker).fabrolia$completedChunks();
    }
}
