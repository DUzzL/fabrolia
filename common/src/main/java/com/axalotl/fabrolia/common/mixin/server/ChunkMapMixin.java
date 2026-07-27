package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.api.utils.ConcurrentList;
import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerPlayer;

@Mixin(value = ChunkMap.class, priority = 1500)
public abstract class ChunkMapMixin extends SimpleRegionStorage implements ChunkHolder.PlayerProvider,
        com.axalotl.fabrolia.common.regions.RegionTickingAccess,
        com.axalotl.fabrolia.common.regions.LevelAccess {

    @Override
    public net.minecraft.server.level.ServerLevel fabrolia$level() {
        return this.level;
    }


    /** A2-blocker fix: the AUTHORITATIVE full-status seam. Vanilla calls this on
     *  main for every promote AND demote (incl. ticket-only unloads, which the
     *  fabric CHUNK_UNLOAD event missed on 26.2 — the bug that froze island
     *  topology after bridge tickets were removed). Feeds the same idempotent
     *  set as the platform events, so double delivery is harmless by design. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "onFullChunkStatusChange", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fabrolia$fullStatusSeam(net.minecraft.world.level.ChunkPos pos,
                                         net.minecraft.server.level.FullChunkStatus status,
                                         org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.axalotl.fabrolia.common.regions.RegionScheduler.onChunkFullChange(
                this.level, pos.pack(),
                status.isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL), null);
    }

    /** A2 deadline drive — owner-safe INCREMENTAL chunk saves. With async
     *  islands live, units may be mid-phase over this chunk at any slot;
     *  serializing it on main would read torn block/BE state. Opportunistic
     *  claim: between units there is a wide window every 50 ms, so nearly
     *  every attempt succeeds; a busy island's chunk simply retries via
     *  vanilla's own nextChunkSaveTime cooldown (returning false = not saved
     *  this attempt). Inside a main quiesce window (save-all/rebuild) main
     *  already holds every claim — proceed directly, re-claiming would
     *  deadlock the pass. */
    @WrapMethod(method = "saveChunkIfNeeded")
    private boolean fabrolia$ownerSafeIncrementalSave(ChunkHolder holder, long now,
                                                      Operation<Boolean> original) {
        com.axalotl.fabrolia.common.regions.RegionizedServer rs =
                com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(this.level);
        if (rs == null || !rs.asyncActive || rs.mainHoldsAllClaims()) {
            return original.call(holder, now);
        }
        com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland owner =
                rs.islandOwnerOfChunk(holder.getPos().pack());
        if (owner == null) {
            return original.call(holder, now);
        }
        if (!owner.unitClaim.compareAndSet(false, true)) {
            return false;   // unit in flight — cooldown machinery retries
        }
        owner.noteClaim("incrSave");
        try {
            return original.call(holder, now);
        } finally {
            owner.clearClaim();
            owner.unitClaim.set(false);
        }
    }

    /** Incremental per-region holder lists for ChunkMap.forEachBlockTickingChunk
     *  (item 2 of the
     *  post-recon list): maintained from updateChunkScheduling on the main
     *  thread, consumed by the block-tick phase instead of the per-tick
     *  O(all-chunks) rebuild + regroup. */
    @Unique
    private final com.axalotl.fabrolia.common.regions.RegionTickingChunks fabrolia$regionTicking =
            new com.axalotl.fabrolia.common.regions.RegionTickingChunks(
                    com.axalotl.fabrolia.common.regions.RegionGrid.DEFAULT_SHIFT);

    @Override
    public com.axalotl.fabrolia.common.regions.RegionTickingChunks fabrolia$regionTicking() {
        return fabrolia$regionTicking;
    }

    @Override
    public Iterable<ChunkHolder> fabrolia$visibleHolders() {
        return this.visibleChunkMap.values();
    }

    @Override
    public Iterable<ChunkHolder> fabrolia$updatingHolders() {
        return this.updatingChunkMap.values();
    }

    @Override
    public Iterable<ChunkHolder> fabrolia$pendingUnloadHolders() {
        return this.pendingUnloads.values();
    }

    // ENTITY_TICKING transition tracking MOVED to SimulationChunkTrackerMixin.setLevel.
    // The old updateChunkScheduling hook here fired asymmetrically under c2me's rewritten
    // ticket system — the ENTITY_TICKING-loss transition was lost, so the incremental list
    // accumulated stale chunks, diverged at the self-check, and permanently latched off
    // (optimization dead). setLevel is the single mutation point of the exact Long2ByteMap
    // DistanceManager.forEachEntityTickingChunk reads, which c2me DOES update reliably, so
    // the list now tracks vanilla's source bit-for-bit. fabrolia$holderForTicking (below)
    // resolves the holder for a just-promoted chunk from that seam.

    @Override
    public ChunkHolder fabrolia$holderForTicking(long packedChunkPos) {
        ChunkHolder h = this.updatingChunkMap.get(packedChunkPos);
        return h != null ? h : this.visibleChunkMap.get(packedChunkPos);
    }

    @Shadow
    @Final
    net.minecraft.server.level.ServerLevel level;


    @Shadow
    @Final
    @Mutable
    private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;

    @Shadow
    @Final
    @Mutable
    private List<ChunkGenerationTask> pendingGenerationTasks;

    @Shadow
    private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Mutable
    @Shadow
    @Final
    private LongSet chunksToEagerlySave;

    public ChunkMapMixin(RegionStorageInfo info, Path folder, DataFixer fixerUpper, boolean sync, DataFixTypes dataFixType) {
        super(info, folder, fixerUpper, sync, dataFixType);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void replaceConVars(CallbackInfo ci) {
        entityMap = new Int2ObjectConcurrentHashMap<>();
        pendingGenerationTasks = new ConcurrentList<>();
        chunksToEagerlySave = LongSets.synchronize(new LongLinkedOpenHashSet());
        // Hand the simulation tracker its ChunkMap back-ref so its setLevel seam can feed
        // the entity-ticking incremental list (see SimulationChunkTrackerMixin). The tracker
        // exists by <init> TAIL (built inside the DistanceManager during the ChunkMap ctor).
        net.minecraft.server.level.SimulationChunkTracker tracker =
                ((com.axalotl.fabrolia.common.mixin.server.DistanceManagerAccessor)
                        ((ChunkMap) (Object) this).getDistanceManager()).fabrolia$simulationChunkTracker();
        if (tracker != null) {
            ((com.axalotl.fabrolia.common.regions.RegionTickingTrackerBind) tracker)
                    .fabrolia$setChunkMap((ChunkMap) (Object) this);
        }
    }

    // addEntity / removeEntity wraps removed 2026-06-09. The deadlock previously
    // attributed to this removal was actually AdvancementBuffer.drain using plain
    // CompletableFuture.join() without draining mainThreadProcessor — fixed via
    // ParallelProcessor.pumpUntilDone in AdvancementBuffer, see
    // [[feedback_all_main_barriers_must_pump]]. With that fix in place, the
    // ChunkMap monitor is no longer load-bearing for chunk-system safety:
    //   - entityMap (Int2ObjectConcurrentHashMap) put for different entity IDs is
    //     atomic; same-ID add would be a vanilla bug (and vanilla throws ISE on it)
    //   - newTracker.updatePlayers iterates SnapshotList players (concurrent reads)
    //   - per-tracker updatePlayer is synchronized via ChunkMapTrackedEntityMixin
    //   - seenBy set is ConcurrentCollections.newHashSet (concurrent add/remove)
    //   - VMP NET addEntityTracker has its own fabrolia$lock for internal serialisation
    // Spawn-worker throughput was throttled by N (workers) sharing the ChunkMap
    // monitor on every addFreshEntity, especially under multi-player load.

    @WrapMethod(method = "releaseGeneration")
    private synchronized void releaseGeneration(GenerationChunkHolder chunkHolder, Operation<Void> original) {
        original.call(chunkHolder);
    }

    /**
     * Player-position structures gate. With players ticking on region workers, two moving
     * players hit {@code ChunkMap.move} concurrently (EntityPlayerMPFake.tick → VMP
     * updatePosition redirect → ServerChunkCache.move) — DistanceManager.playersPerChunk
     * is a plain Long2ObjectOpenHashMap and corrupts under concurrent remove (live
     * 2026-06-10: AIOOBE Index -1 in rehash, 12 hits in one bot-spawn burst). Same maps
     * are READ by spawn workers via getPlayersCloseForSpawning (LocalMobCapCalculator,
     * per spawn position) — reads during a concurrent rehash can loop or misreport.
     * One instance monitor over both paths; hold times are sub-µs map ops + section-pos
     * bookkeeping, ~50 moves/tick worst case — uncontended cost is noise.
     */
    @WrapMethod(method = "move")
    private synchronized void fabrolia$syncPlayerMove(ServerPlayer player, Operation<Void> original) {
        original.call(player);
    }

    @WrapMethod(method = "getPlayersCloseForSpawning")
    private synchronized List<ServerPlayer> fabrolia$syncPlayersCloseForSpawning(
            ChunkPos pos, Operation<List<ServerPlayer>> original) {
        return original.call(pos);
    }

    @Inject(method = "addEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;pauseInIde(Ljava/lang/Throwable;)Ljava/lang/Throwable;"), cancellable = true)
    private void skipThrowLoadEntity(Entity entity, CallbackInfo ci) {
        ci.cancel();
    }
}
