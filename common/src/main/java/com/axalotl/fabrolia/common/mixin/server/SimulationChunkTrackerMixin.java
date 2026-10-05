package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.api.fastutil.Long2ByteConcurrentHashMap;
import com.axalotl.fabrolia.common.regions.CompletedChunkLevels;
import com.axalotl.fabrolia.common.regions.CompletedSimulationView;
import com.axalotl.fabrolia.common.regions.RegionTickingTrackerBind;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.SimulationChunkTracker;

/**
 * Two roles:
 *
 * <p>1) Makes {@code chunks} a concurrent map (entity-movement readers race the propagator)
 * and publishes its completed-wave view ({@link CompletedSimulationView}).
 *
 * <p>2) Pearl-freeze trace at {@code setLevel}, the single mutation point of {@code chunks}
 * (the map {@code DistanceManager.forEachEntityTickingChunk} iterates): a chunk leaving
 * entity-ticking range is reported to {@code PearlTrace}.
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
     * {@link CompletedSimulationView} for the incident). Immutable paged
     * snapshot, republished via this volatile at {@code runAllUpdates} RETURN.
     * Readers see completed states only.
     */
    @Unique
    private volatile CompletedChunkLevels fabrolia$completedView;

    /**
     * Live paged mirror of {@code chunks}. Mutation ({@code setLevel}) and
     * publish both run under the chunkMap monitor, so it needs no
     * synchronization of its own.
     */
    @Unique
    private CompletedChunkLevels.Builder fabrolia$liveLevels;

    /** {@code chunks.size() - mirror size} accepted at the last resync (entries equal to the default). */
    @Unique
    private int fabrolia$mirrorSkew;

    @Unique
    private boolean fabrolia$mirrorWarned;

    @Unique
    private static final org.slf4j.Logger FABROLIA$LOG = org.slf4j.LoggerFactory.getLogger("Fabrolia");

    @Override
    public void fabrolia$setChunkMap(ChunkMap chunkMap) {
        this.fabrolia$chunkMap = chunkMap;
    }

    @Override
    public int fabrolia$completedLevel(long chunkPos) {
        return this.fabrolia$completedView.get(chunkPos);
    }

    @Override
    public CompletedChunkLevels fabrolia$completedLevels() {
        return this.fabrolia$completedView;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$replaceConcurrentChunks(CallbackInfo ci) {
        byte defaultVal = this.chunks.defaultReturnValue();
        this.chunks = new Long2ByteConcurrentHashMap();
        this.chunks.defaultReturnValue(defaultVal);
        this.fabrolia$liveLevels = new CompletedChunkLevels.Builder(defaultVal);
        this.fabrolia$completedView = this.fabrolia$liveLevels.published();
    }

    /** Republish the completed view once the wave has fully relaxed. Runs under
     *  the same chunkMap monitor as the mutations. No-change waves keep the
     *  previous snapshot; a changed wave copies only the pages it touched. */
    @Inject(method = "runAllUpdates", at = @At("RETURN"))
    private void fabrolia$publishCompletedView(CallbackInfo ci) {
        CompletedChunkLevels.Builder live = this.fabrolia$liveLevels;
        int authoritative = this.chunks.size();
        if (live.size() + this.fabrolia$mirrorSkew != authoritative) {
            // Something mutated chunks without passing setLevel. The flat copy
            // this replaced absorbed that silently; resync, and say so once.
            live.resetFrom(this.chunks);
            if (!this.fabrolia$mirrorWarned) {
                this.fabrolia$mirrorWarned = true;
                FABROLIA$LOG.warn("Simulation level map changed outside setLevel ({} live entries);"
                        + " completed view resynchronized from the live map", authoritative);
            }
            this.fabrolia$mirrorSkew = authoritative - live.size();
        }
        this.fabrolia$completedView = live.publish();
    }

    /** Mirrors the authoritative post-write value: vanilla removes at >= 33. */
    @Inject(method = "setLevel(JI)V", at = @At("RETURN"))
    private void fabrolia$mirrorLevel(long pos, int newLevel, CallbackInfo ci) {
        this.fabrolia$liveLevels.set(pos, this.chunks.get(pos));
    }

    @Inject(method = "setLevel(JI)V", at = @At("HEAD"))
    private void fabrolia$traceEntityTickingLoss(long pos, int newLevel, CallbackInfo ci) {
        if (!com.axalotl.fabrolia.common.utils.FabroliaDebug.PEARL_TRACE
                || !(this.fabrolia$chunkMap instanceof com.axalotl.fabrolia.common.regions.LevelAccess la)) {
            return;
        }
        if (ChunkLevel.isEntityTicking(this.getLevel(pos)) && !ChunkLevel.isEntityTicking(newLevel)) {
            // Pearl-freeze trace (2026-08-18 ender-pearl incident): a chunk
            // leaving entity-ticking with a live pearl inside freezes the
            // pearl (no ticks, no renewals). Anomaly-filtered and
            // rate-limited inside PearlTrace — silent for plain unloads.
            com.axalotl.fabrolia.common.utils.PearlTrace.frozen(la.fabrolia$level(), pos);
        }
    }
}
