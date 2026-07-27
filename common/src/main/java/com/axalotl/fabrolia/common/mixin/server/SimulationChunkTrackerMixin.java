package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.api.fastutil.Long2ByteConcurrentHashMap;
import com.axalotl.fabrolia.common.regions.CompletedSimulationView;
import com.axalotl.fabrolia.common.regions.RegionTickingAccess;
import com.axalotl.fabrolia.common.regions.RegionTickingChunks;
import com.axalotl.fabrolia.common.regions.RegionTickingTrackerBind;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.SimulationChunkTracker;

/**
 * Two roles:
 *
 * <p>1) Makes {@code chunks} a concurrent map (entity-movement readers race the propagator).
 *
 * <p>2) AUTHORITATIVE entity-ticking seam for the incremental ticking list. {@code chunks}
 * is exactly the {@code Long2ByteMap} {@code DistanceManager.forEachEntityTickingChunk}
 * iterates (filtered by {@code ChunkLevel.isEntityTicking}), and c2me's rewritten ticket
 * propagator updates it through {@code setLevel} — unlike the vanilla
 * {@code ChunkMap.updateChunkScheduling} seam the old {@link RegionTickingChunks} hook used,
 * which c2me fires asymmetrically (the ENTITY_TICKING-loss transition was lost, so the list
 * accumulated stale chunks, diverged, and permanently latched off → optimization dead).
 * Hooking {@code setLevel} (the single place {@code chunks} is mutated) keeps the incremental
 * list bit-identical to vanilla's source, so it no longer drifts. Runs inside
 * {@code runDistanceManagerUpdates} (main thread / chunkMap-monitor) like the consumer, so
 * the non-thread-safe {@link RegionTickingChunks} stays single-writer as designed.
 */
@Mixin(SimulationChunkTracker.class)
public abstract class SimulationChunkTrackerMixin implements RegionTickingTrackerBind, CompletedSimulationView {

    @Shadow
    @Final
    @Mutable
    protected Long2ByteMap chunks;

    /** Old (current) level at a position — read at setLevel HEAD before the put/remove. */
    @Shadow
    protected abstract int getLevel(long pos);

    @Unique
    private ChunkMap fabrolia$chunkMap;

    /**
     * Simulation levels as of the last COMPLETED propagation wave (see
     * {@link CompletedSimulationView} for the incident). Plain immutable map,
     * republished via this volatile at {@code runAllUpdates} RETURN — mutation
     * and publish both run under the chunkMap monitor, so the dirty flag below
     * needs no synchronization of its own. Readers see completed states only.
     */
    @Unique
    private volatile Long2ByteOpenHashMap fabrolia$completedView;

    @Unique
    private boolean fabrolia$viewDirty;

    @Override
    public void fabrolia$setChunkMap(ChunkMap chunkMap) {
        this.fabrolia$chunkMap = chunkMap;
    }

    @Override
    public int fabrolia$completedLevel(long chunkPos) {
        return this.fabrolia$completedView.get(chunkPos);
    }

    @Override
    public Long2ByteMap fabrolia$completedView() {
        return this.fabrolia$completedView;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$replaceConcurrentChunks(CallbackInfo ci) {
        byte defaultVal = this.chunks.defaultReturnValue();
        this.chunks = new Long2ByteConcurrentHashMap();
        this.chunks.defaultReturnValue(defaultVal);
        Long2ByteOpenHashMap emptyView = new Long2ByteOpenHashMap();
        emptyView.defaultReturnValue(defaultVal);
        this.fabrolia$completedView = emptyView;
    }

    /** Republish the completed view once the wave has fully relaxed. Runs under
     *  the same chunkMap monitor as the mutations; the snapshot iteration sees a
     *  quiescent map. No-change waves skip the copy entirely. */
    @Inject(method = "runAllUpdates", at = @At("RETURN"))
    private void fabrolia$publishCompletedView(CallbackInfo ci) {
        if (!this.fabrolia$viewDirty) {
            return;
        }
        this.fabrolia$viewDirty = false;
        Long2ByteOpenHashMap copy = new Long2ByteOpenHashMap(Math.max(16, this.chunks.size()));
        copy.defaultReturnValue(this.chunks.defaultReturnValue());
        for (Long2ByteMap.Entry e : this.chunks.long2ByteEntrySet()) {
            copy.put(e.getLongKey(), e.getByteValue());
        }
        this.fabrolia$completedView = copy;
    }

    @Inject(method = "setLevel(JI)V", at = @At("HEAD"))
    private void fabrolia$trackEntityTicking(long pos, int newLevel, CallbackInfo ci) {
        this.fabrolia$viewDirty = true;
        ChunkMap cm = this.fabrolia$chunkMap;
        if (cm == null) {
            return;
        }
        int oldLevel = this.getLevel(pos);
        boolean was = ChunkLevel.isEntityTicking(oldLevel);
        boolean now = ChunkLevel.isEntityTicking(newLevel);
        if (was == now) {
            return;
        }
        RegionTickingChunks ticking = ((RegionTickingAccess) cm).fabrolia$regionTicking();
        if (now) {
            ChunkHolder h = ((RegionTickingAccess) cm).fabrolia$holderForTicking(pos);
            ticking.onBecameTicking(pos, h);
        } else {
            ticking.onStoppedTicking(pos);
        }
    }
}
