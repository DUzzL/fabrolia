package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.api.fastutil.Long2ByteConcurrentHashMap;
import com.axalotl.fabrolia.common.regions.CompletedPlayerProximityView;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two roles (mirrors {@link SimulationChunkTrackerMixin}):
 *
 * <p>1) Makes {@code chunks} a concurrent map (spawn-worker readers race the
 * propagator's put/remove).
 *
 * <p>2) Publishes the completed-wave snapshot behind
 * {@link CompletedPlayerProximityView} (task #40, audit finding #1) that
 * {@code DistanceManagerMixin} routes the spawn-proximity reads onto. The live
 * {@code chunks} map is the propagator's WORKING state — mid-wave, entries are
 * transient and {@code setLevel} outright {@code remove()}s a chunk whenever a
 * relaxation step passes {@code maxDistance} — so only completed states may be
 * observed by gates.
 *
 * <p>Publication runs at {@code runAllUpdates} RETURN. Post-reroute, the only
 * callers of {@code runAllUpdates} are {@code DistanceManager.runAllUpdates}
 * (main-only: ServerChunkCacheMixin refuses off-main distance updates, and
 * takes the ChunkMap monitor on main while region player ticks are on) and
 * {@code PlayerTicketTracker.runAllUpdates}'s super call inside the same
 * consolidation — single-writer, like the SimulationChunkTracker precedent, so
 * the dirty flag needs no synchronization of its own; the volatile publish is
 * the release fence for readers. The PlayerTicketTracker instance publishes a
 * (never-read) view of its own — harmless, one small copy per changed wave.
 */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$FixedPlayerDistanceChunkTracker")
public class FixedPlayerDistanceChunkTrackerMixin implements CompletedPlayerProximityView {

    @Shadow
    @Final
    @Mutable
    protected Long2ByteMap chunks;

    /** Levels as of the last COMPLETED wave. Plain immutable map, republished
     *  via this volatile; readers (spawn workers, main) see completed states only. */
    @Unique
    private volatile Long2ByteOpenHashMap fabrolia$completedChunks;

    @Unique
    private boolean fabrolia$viewDirty;

    @Override
    public int fabrolia$completedLevel(long chunkPos) {
        return this.fabrolia$completedChunks.get(chunkPos);
    }

    @Override
    public Long2ByteMap fabrolia$completedChunks() {
        return this.fabrolia$completedChunks;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$replaceConcurrentChunks(DistanceManager maxDistance, int par2, CallbackInfo ci) {
        byte defaultVal = this.chunks.defaultReturnValue();
        this.chunks = new Long2ByteConcurrentHashMap();
        this.chunks.defaultReturnValue(defaultVal);
        Long2ByteOpenHashMap emptyView = new Long2ByteOpenHashMap();
        emptyView.defaultReturnValue(defaultVal);
        this.fabrolia$completedChunks = emptyView;
    }

    @Inject(method = "setLevel(JI)V", at = @At("HEAD"))
    private void fabrolia$markViewDirty(long pos, int newLevel, CallbackInfo ci) {
        this.fabrolia$viewDirty = true;
    }

    /** Republish once the wave has fully relaxed; the snapshot iteration sees a
     *  quiescent map (single wave driver — see class javadoc). No-change waves
     *  skip the copy entirely. */
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
        this.fabrolia$completedChunks = copy;
    }
}
