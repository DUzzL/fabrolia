package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.axalotl.fabrolia.api.fastutil.ConcurrentLongLinkedOpenHashSet;
import com.axalotl.fabrolia.common.regions.CompletedSimulationView;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.SimulationChunkTracker;
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
        ((CompletedSimulationView) this.simulationChunkTracker).fabrolia$completedLevels()
                .forEach(ChunkLevel::isEntityTicking, consumer);
        ci.cancel();
    }

    // -----------------------------------------------------------------------
    // Spawn-proximity wave drop (task #40, audit finding #1). Vanilla's three
    // spawn-proximity readers each start by RUNNING the naturalSpawnChunkCounter
    // propagation wave inline — under the DynamicGraphMinFixedPoint blanket
    // monitor, unbounded, and (via getPlayersCloseForSpawning) while the calling
    // spawn worker holds the ChunkMap monitor: main's consolidation wave then
    // convoys every worker in ChunkMap.move behind one parked spawn worker.
    // The unsynchronized door (anyPlayerCloseEnoughForSpawning) additionally let
    // workers run the wave themselves, racing playersPerChunk rehashes with no
    // ChunkMap monitor (the 2026-06-10 AIOOBE class). The wrap below drops the
    // wave INVOKE from the readers — waves run only in
    // DistanceManager.runAllUpdates (main-only via ServerChunkCacheMixin's
    // off-main refusal + ChunkMap-monitor wrap).
    //
    // The completed-wave SNAPSHOT that used to sit on top of this
    // (CompletedPlayerProximityView) was REMOVED 2026-08-02: it paid a full
    // map copy per changed wave. Readers read the LIVE concurrent map
    // (FixedPlayerDistanceChunkTrackerMixin) — but a live read is only a
    // vanilla answer when the tracker is settled; see the proximity epoch
    // below. The handler takes @Coerce Object because
    // FixedPlayerDistanceChunkTracker is a private inner class.
    // -----------------------------------------------------------------------

    @WrapOperation(
            method = {"hasPlayersNearby", "getNaturalSpawnChunkCount", "getSpawnCandidateChunks"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;runAllUpdates()V"))
    private void fabrolia$noEagerWaveOnReads(@Coerce Object tracker, Operation<Void> original) {
        // Deliberately NOT calling original: readers must never drive (or block
        // on) the propagator. Consolidation already ran this tick on main before
        // any consumer (DistanceManager.runAllUpdates drives
        // naturalSpawnChunkCounter first of the four trackers) — staleness bound
        // is one wave, the CompletedSimulationView class of tolerance. This drop
        // is the load-bearing half of task #40 and is unconditional: re-enabling
        // the inline wave reopens the ChunkMap-monitor convoy and the
        // playersPerChunk rehash race, so it must not be one command away.
    }

    // -----------------------------------------------------------------------
    // Settled proximity answers (2026-10-24 tunnel-bore mob-cap review).
    // Vanilla's hasPlayersNearby drains the wave first, so its definite
    // TRUE/FALSE always describe the CURRENT player positions. Without the
    // drain, a worker reader can meet the tracker in two non-vanilla states:
    //   * mid-wave: every level increase passes through levelCount-1, which
    //     REMOVES the chunk from the map (level 9 > maxDistance 8) — a chunk
    //     one chunk away from the player reads as definite FALSE;
    //   * queued: a player crossed a section, the update is enqueued, the
    //     wave has not run yet — the ring at the new edge reads FALSE.
    // A definite FALSE makes getPlayersCloseForSpawning return no players,
    // and LocalMobCapCalculator caches that for the whole spawn pass: the
    // island seed then drops every mob of that chunk from the player's local
    // count (probe: 80 seeded mobs, cap still open). One wrong answer is not
    // "one spawn check" — it is one whole pass of accepted spawns.
    //
    // The fix keeps the live map and the dropped reader wave, and only
    // refuses a DEFINITE answer unless the tracker is settled: no writer in
    // flight, no queued update, and no writer started between the two epoch
    // reads around the level read (seqlock). Otherwise the reader gets
    // DEFAULT, for which vanilla itself evaluates the exact per-player test
    // (getPlayersCloseForSpawning / anyPlayerCloseEnoughForSpawningInternal,
    // including mod hooks such as Vanish's playerIsCloseEnoughForSpawning).
    // DEFAULT is never wrong, only slower; settled answers are vanilla's.
    // The only naturalSpawnChunkCounter writers are the update() calls in
    // addPlayer/removePlayer and the wave in runAllUpdates(ChunkMap) (26.3
    // bytecode; C2ME notickvd only injects before the same update INVOKEs).
    // -----------------------------------------------------------------------

    @org.spongepowered.asm.mixin.Unique
    private final java.util.concurrent.atomic.AtomicLong fabrolia$proximityEpoch =
            new java.util.concurrent.atomic.AtomicLong();

    @org.spongepowered.asm.mixin.Unique
    private final java.util.concurrent.atomic.AtomicInteger fabrolia$proximityWriters =
            new java.util.concurrent.atomic.AtomicInteger();

    /** An update was enqueued that no completed wave has propagated yet. */
    @org.spongepowered.asm.mixin.Unique
    private volatile boolean fabrolia$proximityQueued;

    /** Writer entry: the count first, then the epoch, so a reader that saw no
     *  writer either sees this epoch move or read the pre-write state. */
    @org.spongepowered.asm.mixin.Unique
    private long fabrolia$beginProximityWrite() {
        this.fabrolia$proximityWriters.incrementAndGet();
        return this.fabrolia$proximityEpoch.incrementAndGet();
    }

    @org.spongepowered.asm.mixin.Unique
    private void fabrolia$endProximityWrite() {
        this.fabrolia$proximityEpoch.incrementAndGet();
        this.fabrolia$proximityWriters.decrementAndGet();
    }

    @WrapOperation(
            method = {"addPlayer", "removePlayer"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;update(JIZ)V"))
    private void fabrolia$queueProximityUpdate(@Coerce Object tracker, long chunkPos, int level,
                                               boolean onlyDecrease, Operation<Void> original) {
        this.fabrolia$beginProximityWrite();
        try {
            this.fabrolia$proximityQueued = true;
            original.call(tracker, chunkPos, level, onlyDecrease);
        } finally {
            this.fabrolia$endProximityWrite();
        }
    }

    @WrapOperation(
            method = "runAllUpdates(Lnet/minecraft/server/level/ChunkMap;)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;runAllUpdates()V"))
    private void fabrolia$propagateProximity(@Coerce Object tracker, Operation<Void> original) {
        long started = this.fabrolia$beginProximityWrite();
        try {
            original.call(tracker);
            // runUpdates(MAX_VALUE) drained the queue. Clear the flag only
            // when no other writer overlapped this wave: an update enqueued
            // meanwhile keeps it set until the next wave settles it.
            if (this.fabrolia$proximityEpoch.get() == started
                    && this.fabrolia$proximityWriters.get() == 1) {
                this.fabrolia$proximityQueued = false;
            }
        } finally {
            this.fabrolia$endProximityWrite();
        }
    }

    @WrapOperation(
            method = "hasPlayersNearby",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;getLevel(J)I"))
    private int fabrolia$settledProximityLevel(@Coerce Object tracker, long chunkPos,
                                               Operation<Integer> original) {
        // Level 8 is the outer ring: vanilla maps it to DEFAULT (it is above
        // INSCRIBED_SQUARE_SPAWN_DISTANCE_CHUNK and not above 8), i.e. "ask
        // every player" — the exact answer, whatever state the tracker is in.
        long before = this.fabrolia$proximityEpoch.get();
        if (this.fabrolia$proximityWriters.get() != 0 || this.fabrolia$proximityQueued) {
            return 8;
        }
        int level = original.call(tracker, chunkPos);
        java.lang.invoke.VarHandle.acquireFence();
        return this.fabrolia$proximityEpoch.get() == before ? level : 8;
    }
}
