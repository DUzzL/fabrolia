package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.api.fastutil.Long2ByteConcurrentHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes {@code chunks} a concurrent map: spawn-worker readers (the
 * {@code hasPlayersNearby} path via {@code ChunkMap.getPlayersCloseForSpawning})
 * race the propagator's put/remove, which runs main-only inside
 * {@code DistanceManager.runAllUpdates} after {@code DistanceManagerMixin}
 * dropped the readers' inline wave (task #40).
 *
 * <p>The completed-wave snapshot that used to be published here
 * ({@code CompletedPlayerProximityView}) was REMOVED 2026-08-02: with the map
 * concurrent, a mid-wave read cannot corrupt anything — the worst case is one
 * transient proximity answer on one spawn check for one tick, self-healing —
 * while the snapshot paid a full map copy per changed wave and interposed a
 * wave of staleness directly under the mob cap (the
 * {@code getNaturalSpawnChunkCount} cap scale). The three spawn-proximity
 * readers now read this live concurrent map.
 */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$FixedPlayerDistanceChunkTracker")
public class FixedPlayerDistanceChunkTrackerMixin {

    @Shadow
    @Final
    @Mutable
    protected Long2ByteMap chunks;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$replaceConcurrentChunks(DistanceManager maxDistance, int par2, CallbackInfo ci) {
        byte defaultVal = this.chunks.defaultReturnValue();
        this.chunks = new Long2ByteConcurrentHashMap();
        this.chunks.defaultReturnValue(defaultVal);
    }
}
