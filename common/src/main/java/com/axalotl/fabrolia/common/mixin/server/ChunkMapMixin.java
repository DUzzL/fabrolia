package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.api.utils.ConcurrentList;
import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
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
     *  every attempt succeeds; a busy or already-queued island's chunk simply
     *  remains eligible for a later C2ME idle scan (returning false before the
     *  vanilla body does not arm its nextChunkSaveTime cooldown). Inside a main
     *  quiesce window (save-all/rebuild) main
     *  already holds every claim — proceed directly, re-claiming would
     *  deadlock the pass.
     *
     *  <p>C2ME ENHANCED auto-save calls this from main's idle task loop. The
     *  queued-unit checks on BOTH sides of the claim CAS close its race with
     *  {@code IslandTickUnit}: without them save could repeatedly win the
     *  default main deadline queue→idle-save gap, make the unit abandon after
     *  200us, and let W advance
     *  while the island L/redstone machine stayed frozen (2026-08-22 craft
     *  incident candidate audit). This is only an opportunistic save; yielding
     *  is its contract. */
    @WrapMethod(method = "saveChunkIfNeeded")
    private boolean fabrolia$ownerSafeIncrementalSave(ChunkHolder holder, long now,
                                                      Operation<Boolean> original) {
        com.axalotl.fabrolia.common.regions.RegionizedServer rs =
                com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(this.level);
        if (rs == null || !rs.asyncActive || rs.currentThreadHoldsAllClaims()) {
            return fabrolia$runIncrementalSaveBody(holder, now, original);
        }
        com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland owner =
                rs.islandOwnerOfChunk(holder.getPos().pack());
        if (owner == null) {
            return fabrolia$runIncrementalSaveBody(holder, now, original);
        }
        if (!com.axalotl.fabrolia.common.regions.IslandUnitAdmission
                .opportunisticSaveMayClaim(owner.submitQueued.get())) {
            return false;
        }
        if (!owner.tryClaimAutonomousWork()) {
            return false;   // unit in flight — cooldown machinery retries
        }
        // Submission may publish between the pre-check and our CAS. Back out
        // before noteClaim/original and release the claim before returning.
        if (!com.axalotl.fabrolia.common.regions.IslandUnitAdmission
                .opportunisticSaveMayClaim(owner.submitQueued.get())) {
            owner.unitClaim.set(false);
            return false;
        }
        boolean saved = false;
        Throwable failure = null;
        try {
            owner.noteClaim("incrSave");
            saved = fabrolia$runIncrementalSaveBody(holder, now, original);
        } catch (Throwable thrown) {
            failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                    .publishFatalAndSelectWinner(thrown);
        }
        try {
            owner.clearClaim();
        } catch (Throwable cleanup) {
            failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                    .publishFatalAndSelectWinner(
                            fabrolia$mergeSaveFailure(failure, cleanup));
        }
        try {
            owner.unitClaim.set(false);
        } catch (Throwable cleanup) {
            failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                    .publishFatalAndSelectWinner(
                            fabrolia$mergeSaveFailure(failure, cleanup));
        }
        if (failure == null) {
            try {
                com.axalotl.fabrolia.common.regions.RegionScheduler
                        .commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                        .publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            com.axalotl.fabrolia.common.regions.IslandCommands
                    .publishAndPropagate(failure);
        }
        return saved;
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$runIncrementalSaveBody(
            ChunkHolder holder, long now, Operation<Boolean> original) {
        try {
            return com.axalotl.fabrolia.common.regions.RegionScheduler
                    .callSuccessBooleanIfHealthy(
                            () -> original.call(holder, now));
        } catch (Throwable failure) {
            com.axalotl.fabrolia.common.regions.IslandCommands
                    .publishAndPropagate(failure);
            return false; // unreachable: publishAndPropagate always exact-rethrows
        }
    }

    /**
     * Fence the complete unload transaction, not only opportunistic saves.
     * Vanilla's continuation snapshots chunk sections and block-entity tags,
     * marks the LevelChunk unloaded, and detaches it from ServerLevel. Letting
     * an island tick overlap any part of that sequence can persist block state
     * without its block-entity payload (for example, an apparently empty
     * shulker box).
     *
     * <p>The continuation first arrives inside {@code processUnloads}' queue
     * iteration, where a blocking claim is forbidden: its claim pump can
     * re-enter chunk services and mutate the collection being iterated. An
     * owned continuation is therefore deferred exactly once. The next
     * {@code processUnloads} invocation executes it at method HEAD, before any
     * vanilla iterator exists, and may safely take the current execution-time
     * owner claim. Owner resolution cannot be captured when the future is
     * registered because a topology rebuild may retire and replace that owner
     * before the future becomes ready.
     */
    @WrapOperation(
            method = "scheduleUnload",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;"
                            + "thenRunAsync(Ljava/lang/Runnable;"
                            + "Ljava/util/concurrent/Executor;)"
                            + "Ljava/util/concurrent/CompletableFuture;"),
            require = 1,
            allow = 1)
    private CompletableFuture<Void> fabrolia$fenceChunkUnloadContinuation(
            CompletableFuture<?> saveSyncFuture, Runnable body, Executor executor,
            Operation<CompletableFuture<Void>> original,
            long chunkPos, ChunkHolder holder) {
        FabroliaOwnerSafeUnloadTask task = new FabroliaOwnerSafeUnloadTask(
                this.level, chunkPos, holder, this.pendingUnloads,
                this.fabrolia$deferredOwnerUnloads, body);
        try {
            CompletableFuture<Void> dispatched =
                    original.call(saveSyncFuture, task, executor);
            return dispatched.thenCompose(ignored -> task.completion());
        } catch (Throwable thrown) {
            Throwable winner = com.axalotl.fabrolia.common.regions.RegionScheduler
                    .publishFatalAndSelectWinner(thrown);
            task.failBeforeDispatch(winner);
            FabroliaOwnerSafeUnloadTask.<RuntimeException>rethrow(winner);
            return CompletableFuture.failedFuture(winner);
        }
    }

    /**
     * Proven top-level venue for the one blocking escalation: no toDrop or
     * unloadQueue iterator has been created yet. Snapshot the initial count so
     * claim-pump re-entry may append work without executing it twice this pass.
     * Accepted unloads deliberately outrank {@code haveTime}: postponing this
     * exact bounded snapshot could strand a vanilla future or shutdown save.
     */
    @Inject(method = "processUnloads", at = @At("HEAD"), require = 1)
    private void fabrolia$drainOwnerSafeUnloadsAtProcessHead(
            BooleanSupplier haveTime, CallbackInfo ci) {
        fabrolia$drainDeferredOwnerUnloads(false);
    }

    /** Keep shutdown/idle drains alive until every accepted unload body ran. */
    @Inject(method = "hasWork", at = @At("RETURN"), cancellable = true,
            require = 1)
    private void fabrolia$includeDeferredOwnerUnloads(
            CallbackInfoReturnable<Boolean> cir) {
        ConcurrentLinkedQueue<FabroliaOwnerSafeUnloadTask> deferred =
                this.fabrolia$deferredOwnerUnloads;
        if (!cir.getReturnValue() && deferred != null && !deferred.isEmpty()) {
            cir.setReturnValue(true);
        }
    }

    @Override
    public void fabrolia$flushDeferredOwnerUnloads() {
        fabrolia$drainDeferredOwnerUnloads(true);
    }

    @Unique
    private void fabrolia$drainDeferredOwnerUnloads(boolean requireEmpty) {
        ConcurrentLinkedQueue<FabroliaOwnerSafeUnloadTask> deferred =
                this.fabrolia$deferredOwnerUnloads;
        if (deferred == null) return;
        if (fabrolia$drainingOwnerUnloads) {
            if (requireEmpty && !deferred.isEmpty()) {
                throw new IllegalStateException(
                        "level save re-entered a non-empty deferred chunk-unload drain");
            }
            return;
        }
        fabrolia$drainingOwnerUnloads = true;
        try {
            int due = deferred.size();
            while (due-- > 0) {
                FabroliaOwnerSafeUnloadTask task = deferred.poll();
                if (task == null) break;
                task.runAtProcessHead();
            }
        } finally {
            fabrolia$drainingOwnerUnloads = false;
        }
        if (requireEmpty && !deferred.isEmpty()) {
            throw new IllegalStateException(
                    "level save retained accepted deferred chunk-unload work");
        }
    }

    @Unique
    static final class FabroliaOwnerSafeUnloadTask implements Runnable {
        private final net.minecraft.server.level.ServerLevel level;
        private final long chunkPos;
        private final ChunkHolder holder;
        private final Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
        private final ConcurrentLinkedQueue<FabroliaOwnerSafeUnloadTask> deferred;
        private final Runnable body;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        FabroliaOwnerSafeUnloadTask(
                net.minecraft.server.level.ServerLevel level,
                long chunkPos,
                ChunkHolder holder,
                Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads,
                ConcurrentLinkedQueue<FabroliaOwnerSafeUnloadTask> deferred,
                Runnable body) {
            this.level = java.util.Objects.requireNonNull(level, "level");
            this.chunkPos = chunkPos;
            this.holder = java.util.Objects.requireNonNull(holder, "holder");
            this.pendingUnloads = java.util.Objects.requireNonNull(
                    pendingUnloads, "pendingUnloads");
            this.deferred = java.util.Objects.requireNonNull(deferred, "deferred");
            this.body = java.util.Objects.requireNonNull(body, "body");
        }

        CompletableFuture<Void> completion() {
            return completion;
        }

        void failBeforeDispatch(Throwable failure) {
            completion.completeExceptionally(
                    java.util.Objects.requireNonNull(failure, "failure"));
        }

        @Override
        public void run() {
            execute(false);
        }

        void runAtProcessHead() {
            execute(true);
        }

        private void execute(boolean topLevel) {
            try {
                net.minecraft.server.MinecraftServer server = level.getServer();
                if (server == null || !server.isSameThread()) {
                    throw new IllegalStateException(
                            "chunk unload continuation entered off physical Main");
                }
                // Vanilla's remove(pos, holder) is the final revival guard.
                // Recheck before claiming as well, so a deferred stale task
                // cannot fence or consume a replacement holder at this key.
                if (pendingUnloads.get(chunkPos) != holder) {
                    completeHealthy();
                    return;
                }

                com.axalotl.fabrolia.common.regions.RegionizedServer runtime =
                        com.axalotl.fabrolia.common.regions.RegionScheduler
                                .forLevel(level);
                if (runtime == null || !runtime.asyncActive
                        || runtime.currentThreadHoldsAllClaims()) {
                    runBody();
                    completeHealthy();
                    return;
                }
                com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland owner =
                        runtime.islandOwnerOfChunk(chunkPos);
                if (owner == null || owner.retired
                        || com.axalotl.fabrolia.common.regions.IslandCommands
                                .mainHolds(owner)) {
                    // A null current owner is safe: topology can publish that
                    // state only from Main's all-claims rebuild window, before
                    // any successor unit is admitted.
                    runBody();
                    completeHealthy();
                    return;
                }
                if (!topLevel) {
                    // Success-only publication: a peer fatal winner may cancel
                    // this successor instead of leaving a hidden accepted task.
                    com.axalotl.fabrolia.common.regions.RegionScheduler
                            .commitSuccessIfHealthy(() -> deferred.add(this));
                    return;
                }
                runWithOwnerClaim(owner);
                completeHealthy();
            } catch (Throwable thrown) {
                Throwable winner =
                        com.axalotl.fabrolia.common.regions.RegionScheduler
                                .publishFatalAndSelectWinner(thrown);
                completion.completeExceptionally(winner);
                FabroliaOwnerSafeUnloadTask.<RuntimeException>rethrow(winner);
            }
        }

        private void runBody() {
            com.axalotl.fabrolia.common.regions.RegionScheduler
                    .runSuccessBodyIfHealthy(body);
        }

        private void runWithOwnerClaim(
                com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland owner) {
            boolean release = false;
            Throwable failure = null;
            try {
                release = com.axalotl.fabrolia.common.regions.IslandCommands
                        .mainClaimScoped(owner);
                runBody();
            } catch (Throwable bodyFailure) {
                failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                        .publishFatalAndSelectWinner(bodyFailure);
            } finally {
                if (release) {
                    try {
                        com.axalotl.fabrolia.common.regions.IslandCommands
                                .mainRelease(owner);
                    } catch (Throwable cleanupFailure) {
                        failure = com.axalotl.fabrolia.common.regions.RegionScheduler
                                .publishFatalAndSelectWinner(
                                        mergeFailure(failure, cleanupFailure));
                    }
                }
            }
            if (failure != null) {
                FabroliaOwnerSafeUnloadTask.<RuntimeException>rethrow(failure);
            }
        }

        private void completeHealthy() {
            com.axalotl.fabrolia.common.regions.RegionScheduler
                    .commitSuccessIfHealthy(() -> {
                if (!completion.complete(null)) {
                    throw new IllegalStateException(
                            "chunk unload continuation completed more than once");
                }
            });
        }

        private static Throwable mergeFailure(
                Throwable primary, Throwable cleanup) {
            if (primary == null) return cleanup;
            if (cleanup != null && cleanup != primary) {
                try {
                    primary.addSuppressed(cleanup);
                } catch (Throwable ignored) {
                    // Mandatory claim cleanup cannot replace the body primary.
                }
            }
            return primary;
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void rethrow(Throwable failure)
                throws T {
            throw (T) failure;
        }
    }

    @Unique
    private ConcurrentLinkedQueue<FabroliaOwnerSafeUnloadTask>
            fabrolia$deferredOwnerUnloads;
    @Unique
    private boolean fabrolia$drainingOwnerUnloads;

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

    /**
     * The players {@code DistanceManager.playersPerChunk} counts for natural
     * spawning — the source of {@code naturalSpawnChunkCounter}: every PlayerMap
     * member whose ignored bit is clear ({@code skipPlayer}: spectators without
     * the spectatorsGenerateChunks rule). Each is visited with the chunk of its
     * last section position — exactly the key {@code addPlayer} registered.
     * Reads only the PlayerMap's detached snapshot and monitor: safe off-main.
     */
    @Override
    public void fabrolia$forEachSpawnCountedPlayer(
            java.util.function.ObjLongConsumer<ServerPlayer> action) {
        for (ServerPlayer player : this.playerMap.getAllPlayers()) {
            if (this.playerMap.ignored(player)) continue;
            SectionPos section = player.getLastSectionPos();
            action.accept(player, ChunkPos.pack(section.x(), section.z()));
        }
    }

    @Shadow
    protected abstract boolean playerIsCloseEnoughForSpawning(ServerPlayer player, ChunkPos pos);

    @Override
    public boolean fabrolia$playerCloseEnoughForSpawning(ServerPlayer player, ChunkPos pos) {
        return this.playerIsCloseEnoughForSpawning(player, pos);
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
    private PlayerMap playerMap;

    @Shadow
    protected abstract boolean skipPlayer(ServerPlayer player);

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
        fabrolia$deferredOwnerUnloads = new ConcurrentLinkedQueue<>();
        // Island workers add (LevelChunk#markUnsaved) while Main iterates in
        // saveChunksEagerly: the iterator itself must be race-free.
        chunksToEagerlySave = new com.axalotl.fabrolia.common.utils.EagerSaveChunkSet();
        // Hand the simulation tracker its ChunkMap back-ref for the pearl-freeze
        // trace on its setLevel seam (see SimulationChunkTrackerMixin). The tracker
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

    // releaseGeneration is deliberately not wrapped. Exact 26.2 delegates only to
    // GenerationChunkHolder.decreaseGenerationRefCount(): an AtomicInteger decrement
    // plus zero-transition future completion. acquireGeneration is monitor-free too.
    // Adding ChunkMap's broad monitor to the release half creates a gratuitous
    // generation-completion/holder-adjacent -> ChunkMap lock edge.

    /**
     * Player-position transition gate. The removed worker-player prototype exposed two
     * concurrent section/ignored-state transitions (historical 2026-06-10 bot burst:
     * AIOOBE during fastutil rehash), so the complete vanilla body remains one ChunkMap
     * transaction whenever it can write player position, ticket, PlayerMap, or VMP area
     * membership.
     *
     * <p>The overwhelmingly common movement heartbeat does not cross a section and does
     * not change the ignored bit. Running that body under ChunkMap's monitor made an A5
     * player wait behind Main's complete C2ME distance-manager consolidation on every
     * packet, even though vanilla reaches no shared membership write. Keep that unchanged
     * path monitor-free. Its tracking prefix is independently safe: the entity map is
     * concurrent, each TrackedEntity mutation has its own monitor, and installed VMP
     * redirects the vanilla all-tracker prefix away while protecting its component indexes
     * with their own locks.
     */
    @WrapMethod(method = "move")
    private void fabrolia$syncPlayerMove(ServerPlayer player, Operation<Void> original) {
        SectionPos lastSection = player.getLastSectionPos();
        SectionPos currentSection = SectionPos.of(player);
        boolean ignored = this.playerMap.ignored(player);
        boolean skip = this.skipPlayer(player);
        boolean transition =
                com.axalotl.fabrolia.common.regions.PlayerMoveLockPolicy
                        .requiresGlobalTransaction(
                                lastSection.asLong(), currentSection.asLong(),
                                ignored, skip);
        if (!transition) {
            // Vanilla repeats these four reads after its tracking prefix. Replay the
            // admitted tuple so a concurrent status/spectator transition cannot turn
            // this monitor-free reader into a PlayerMap/ticket/VMP writer (TOCTOU).
            com.axalotl.fabrolia.common.regions.PlayerMoveReadSnapshot.pushStable(
                    lastSection, currentSection, ignored, skip);
            try {
                original.call(player);
            } finally {
                com.axalotl.fabrolia.common.regions.PlayerMoveReadSnapshot.pop();
            }
            return;
        }
        synchronized (this) {
            // Mask any outer fast-path snapshot during a re-entrant transition move.
            com.axalotl.fabrolia.common.regions.PlayerMoveReadSnapshot.pushLive();
            try {
                original.call(player);
            } finally {
                com.axalotl.fabrolia.common.regions.PlayerMoveReadSnapshot.pop();
            }
        }
    }

    /**
     * Main's periodic view reconciliation must not read a player's position
     * half-way through an entered A5 movement. Try the exact owner before
     * taking ChunkMap; a busy periodic update remains due on the next tick,
     * which computes a fresh target rather than replaying a captured view.
     */
    @WrapMethod(method = "updateChunkTracking")
    private void fabrolia$ownerSafePeriodicView(ServerPlayer player, Operation<Void> original) {
        if (!com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .isPhysicalServerThread(this.level.getServer())) {
            // Entered worker movement keeps its existing exact unit/session.
            fabrolia$syncTrackingViewUpdate(player, original);
            return;
        }
        com.axalotl.fabrolia.common.regions.RegionScheduler.runSuccessBodyIfHealthy(() ->
                com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks.tryRunMainTrackingView(
                        player, () -> fabrolia$syncTrackingViewUpdate(player, original)));
    }

    /** Share move/status's transaction through both the old-view read and the
     * final view publication. Locking just move permits Main's vanilla tick
     * prefix to delete a chunk after a returning move has already re-added it.
     * Vanilla only inspects ready holders here. Worker moves reach this body
     * only in their already-monitored transition branch; stable heartbeats
     * never acquire this monitor. NeoForge watch hooks keep that existing
     * venue, while Main reconciliation gains no worker-to-Main wait edge.
     * VMP's replacement bodies remain unchanged. */
    @Unique
    private synchronized void fabrolia$syncTrackingViewUpdate(
            ServerPlayer player, Operation<Void> original) {
        com.axalotl.fabrolia.common.regions.RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(player));
    }

    @WrapMethod(method = "applyChunkTrackingView")
    private synchronized void fabrolia$syncTrackingViewApply(
            ServerPlayer player, net.minecraft.server.level.ChunkTrackingView view,
            Operation<Void> original) {
        com.axalotl.fabrolia.common.regions.RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(player, view));
    }

    @ModifyExpressionValue(method = "move",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getLastSectionPos()"
                            + "Lnet/minecraft/core/SectionPos;"),
            require = 1, allow = 1)
    private SectionPos fabrolia$stableMoveLastSection(SectionPos live) {
        return com.axalotl.fabrolia.common.regions.PlayerMoveReadSnapshot
                .lastSectionOr(live);
    }

    @ModifyExpressionValue(method = "move",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/SectionPos;of("
                            + "Lnet/minecraft/world/level/entity/EntityAccess;)"
                            + "Lnet/minecraft/core/SectionPos;"),
            require = 1, allow = 1)
    private SectionPos fabrolia$stableMoveCurrentSection(SectionPos live) {
        return com.axalotl.fabrolia.common.regions.PlayerMoveReadSnapshot
                .currentSectionOr(live);
    }

    @ModifyExpressionValue(method = "move",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/PlayerMap;ignored("
                            + "Lnet/minecraft/server/level/ServerPlayer;)Z"),
            require = 1, allow = 1)
    private boolean fabrolia$stableMoveIgnored(boolean live) {
        return com.axalotl.fabrolia.common.regions.PlayerMoveReadSnapshot.ignoredOr(live);
    }

    @ModifyExpressionValue(method = "move",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;skipPlayer("
                            + "Lnet/minecraft/server/level/ServerPlayer;)Z"),
            require = 1, allow = 1)
    private boolean fabrolia$stableMoveSkip(boolean live) {
        return com.axalotl.fabrolia.common.regions.PlayerMoveReadSnapshot.skipOr(live);
    }

    /**
     * Keep the multi-structure membership write under ChunkMap's outer monitor. The
     * component indexes provide their own coherent read snapshots: vanilla PlayerMap is
     * protected by {@link PlayerMapMixin}, while VMP's replacement watching-area manager
     * is protected by FabroliaVmpAreaPlayerChunkWatchingManagerMixin.
     */
    @WrapMethod(method = "updatePlayerStatus")
    private synchronized void fabrolia$syncPlayerMapMembership(
            ServerPlayer player, boolean track, Operation<Void> original) {
        original.call(player, track);
    }

    @WrapMethod(method = "getPlayers")
    private List<ServerPlayer> fabrolia$snapshotBroadcastPlayers(
            ChunkPos pos, boolean boundaryOnly,
            Operation<List<ServerPlayer>> original) {
        // Never take ChunkMap here. ChunkHolder.broadcastChanges invokes this while its
        // holder monitor is owned, whereas Main's C2ME distance update owns ChunkMap and
        // then enters that same holder. The former ChunkMap monitor completed a real
        // holder -> ChunkMap / ChunkMap -> holder deadlock under terrain generation.
        // PlayerMap/VMP detach their live storage before this body iterates it; retain a
        // final immutable copy so no compatibility implementation can leak a live view.
        return List.copyOf(original.call(pos, boundaryOnly));
    }

    @WrapMethod(method = "getPlayersCloseForSpawning")
    private List<ServerPlayer> fabrolia$snapshotPlayersCloseForSpawning(
            ChunkPos pos, Operation<List<ServerPlayer>> original) {
        // Same lock-order rule as getPlayers. Vanilla consumes PlayerMap's detached
        // getAllPlayers snapshot and builds an ImmutableList; VMP's component index has
        // its own shared snapshot lease. ChunkMap must never be a viewer-read lock.
        return List.copyOf(original.call(pos));
    }

    /**
     * Async islands: Main's spawn pass covers only the GLOBAL LANE (chunks with
     * no island owner — each island runs its own pass, IslandTickUnit). Drop
     * island-owned candidates at the source so Main never resolves, shuffles,
     * gates or ticks them. Candidates cluster by region, so the owner lookup is
     * memoised per grid cell.
     */
    @WrapOperation(method = "collectSpawningChunks",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap$DistanceManager;getSpawnCandidateChunks()Lit/unimi/dsi/fastutil/longs/LongIterator;"))
    private it.unimi.dsi.fastutil.longs.LongIterator fabrolia$globalLaneSpawnCandidates(
            net.minecraft.server.level.ChunkMap.DistanceManager instance,
            Operation<it.unimi.dsi.fastutil.longs.LongIterator> original) {
        it.unimi.dsi.fastutil.longs.LongIterator all = original.call(instance);
        com.axalotl.fabrolia.common.regions.RegionizedServer rs =
                com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(this.level);
        if (rs == null || !rs.asyncActive) return all;
        com.axalotl.fabrolia.common.regions.RegionGrid grid = rs.regionizer().grid;
        return new it.unimi.dsi.fastutil.longs.LongIterator() {
            private long memoCell = Long.MIN_VALUE;
            private boolean memoGlobal;
            private boolean ready;
            private long next;

            private boolean advance() {
                while (!ready && all.hasNext()) {
                    long key = all.nextLong();
                    long cell = grid.keyFor(key);
                    if (cell != memoCell) {
                        memoCell = cell;
                        memoGlobal = rs.islandOwnerOfCell(cell) == null;
                    }
                    if (memoGlobal) {
                        next = key;
                        ready = true;
                    }
                }
                return ready;
            }

            @Override
            public boolean hasNext() {
                return advance();
            }

            @Override
            public long nextLong() {
                if (!advance()) throw new java.util.NoSuchElementException();
                ready = false;
                return next;
            }
        };
    }

    @Unique
    private static Throwable fabrolia$mergeSaveFailure(
            Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Claim cleanup diagnostics cannot replace the save primary.
            }
        }
        return primary;
    }
}
