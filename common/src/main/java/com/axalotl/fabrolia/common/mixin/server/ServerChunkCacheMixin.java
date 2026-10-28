package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.HashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import com.axalotl.fabrolia.common.utils.SpawnFutureTaker;
import com.axalotl.fabrolia.common.regions.ChunkRegionKeyed;
import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.GlobalBlockUpdateDrain;
import com.axalotl.fabrolia.common.regions.IslandBlockUpdateAccess;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionGrid;
import com.axalotl.fabrolia.common.regions.Region;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.axalotl.fabrolia.common.regions.RegionTaskFanIn;
import com.axalotl.fabrolia.common.regions.IslandChunkPassAccess;
import com.axalotl.fabrolia.common.regions.NaturalSpawnChunks;
import com.axalotl.fabrolia.common.regions.SpawnCapLanes;
import com.axalotl.fabrolia.common.parallelised.utils.ParallelSpawnHelper;
import com.axalotl.fabrolia.api.spawn.FabroliaSpawnStateView;
import java.util.concurrent.atomic.AtomicIntegerArray;

@Mixin(value = ServerChunkCache.class, priority = 1500)
public abstract class ServerChunkCacheMixin extends ChunkSource implements SpawnFutureTaker,
        IslandBlockUpdateAccess, IslandChunkPassAccess {

    @Shadow @Final public ChunkMap chunkMap;
    @Shadow @Final
    private Thread mainThread;
    @Shadow @Final public ServerChunkCache.MainThreadExecutor mainThreadProcessor;
    @Shadow @Final private DistanceManager distanceManager;
    @Shadow @Final private net.minecraft.world.level.TicketStorage ticketStorage;
    @Shadow @Final private ServerLevel level;
    @Shadow private volatile NaturalSpawner.@Nullable SpawnState lastSpawnState;
    @Shadow private boolean spawnEnemies;
    @Shadow @Final @Mutable private Set<ChunkHolder> chunkHoldersToBroadcast;
    @Shadow @Final private List<LevelChunk> spawningChunks; // ServerCore кеширует этот список

    @Shadow public abstract @Nullable ChunkHolder getVisibleChunkIfPresent(long key);
    @Shadow protected abstract CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate);
    @Shadow protected abstract void getFullChunk(long chunkKey, Consumer<LevelChunk> output);


    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$replaceWithConcurrentSet(CallbackInfo ci) {
        this.chunkHoldersToBroadcast = ConcurrentHashMap.newKeySet();
        // Hand the ticket storage its serialization monitor (the ChunkMap instance)
        // — see TicketStorageSyncMixin for the race this closes.
        ((com.axalotl.fabrolia.common.regions.TicketLockBind) this.ticketStorage)
                .fabrolia$setTicketLock(this.chunkMap);
    }

    /**
     * Remember the owning island as well as vanilla's global broadcast set.
     * The island publishes this holder before its listener tail acknowledges
     * block prediction; the global slot remains a fallback for gap chunks,
     * topology handoff, and changes produced outside an island turn.
     */
    @Inject(method = "blockChanged", at = @At("TAIL"))
    private void fabrolia$queueIslandBlockUpdate(net.minecraft.core.BlockPos pos,
                                                  CallbackInfo ci,
                                                  @Local ChunkHolder holder) {
        // A successful prediction-bearing packet mutation needs an exact
        // actor correction before the listener sends its sequence ACK.  The
        // holder publication below updates every watcher; this per-listener
        // journal also covers cross-island targets and VMP viewer filtering.
        AsyncIslandPlayerTicks.recordPredictedBlockChange(pos);

        RegionizedServer runtime = RegionScheduler.forLevel(this.level);
        if (runtime == null || !runtime.asyncActive) {
            return;
        }
        long chunkKey = ChunkPos.pack(pos);
        IslandRegistry.PIsland island = runtime.islandOwnerOfChunk(chunkKey);
        if (island == null || island.retired) {
            return;
        }
        if (holder != null) {
            // A stale entry is harmless: the drain arbitrates through the
            // global set under the holder monitor. Missing an entry is not.
            island.pendingBlockUpdates.add(holder);
        }
    }

    /**
     * Vanilla iterates {@code chunkHoldersToBroadcast} and then calls
     * {@code clear()}. Once block changes can arrive concurrently, that clear
     * can erase a holder inserted after the iterator passed it. Claim each
     * holder with remove-under-holder-lock instead; a concurrent producer is
     * either included in this broadcast or re-adds the holder for a later one.
     * A holder a live island may write is published only under that island's
     * claim, or handed to the island ({@link GlobalBlockUpdateDrain}).
     */
    @WrapMethod(method = "broadcastChangedChunks")
    private void fabrolia$losslessGlobalBlockUpdateDrain(ProfilerFiller profiler,
                                                          Operation<Void> original) {
        boolean pushed = false;
        Throwable failure = null;
        try {
            profiler.push("broadcast");
            pushed = true;
            // Bound this Main slice. A producer which adds/re-adds after the
            // snapshot remains published for the next pass; it cannot keep a
            // weakly-consistent CHM iterator alive indefinitely.
            GlobalBlockUpdateDrain.drainLevel(RegionScheduler.forLevel(this.level),
                    new ArrayList<>(this.chunkHoldersToBroadcast),
                    holder -> RegionScheduler.runSuccessBodyIfHealthy(
                            () -> fabrolia$publishChangedHolder(holder)));
        } catch (Throwable primary) {
            failure = fabrolia$publishFatalAndSelectWinner(primary);
        }
        if (pushed) {
            try {
                profiler.pop();
            } catch (Throwable cleanup) {
                failure = failure == null
                        ? fabrolia$publishFatalAndSelectWinner(cleanup)
                        : fabrolia$mergeFailure(failure, cleanup);
            }
        }
        if (failure == null) {
            try {
                // Even an empty snapshot has a success terminal. A peer W
                // selected after the final holder may not be hidden by a
                // normal return from this transformed vanilla phase.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable terminalFailure) {
                failure = fabrolia$publishFatalAndSelectWinner(terminalFailure);
            }
        }
        if (failure != null) {
            ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    /** Publish pending holders owned by {@code island} before player ACKs. */
    @Override
    public void fabrolia$flushIslandBlockUpdates(RegionizedServer runtime,
                                                  IslandRegistry.PIsland island) {
        // Bound one owner-tail slice. A residual generation re-added below is
        // always work for the next island unit, never another pass through the
        // same weakly-consistent CHM iterator.
        for (ChunkHolder holder : new ArrayList<>(island.pendingBlockUpdates)) {
            // Ownership revalidation, hint detachment and the actual vanilla
            // broadcast are one body admission.  No peer W can land between
            // removing the hint and entering holder.broadcastChanges().
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> fabrolia$flushOneIslandBlockUpdate(
                            runtime, island, holder));
        }
    }

    @Unique
    private void fabrolia$flushOneIslandBlockUpdate(
            RegionizedServer runtime, IslandRegistry.PIsland island,
            ChunkHolder holder) {
        long chunkKey = holder.getPos().pack();
        IslandRegistry.PIsland current = runtime.islandOwnerOfChunk(chunkKey);
        if (current != island || island.retired) {
            island.pendingBlockUpdates.remove(holder);
            if (current != null && !current.retired) {
                current.pendingBlockUpdates.add(holder);
            }
            return;
        }
        // Removing the island hint first is safe: any new blockChanged call
        // re-adds it at TAIL, after its holder mutation is visible.
        if (island.pendingBlockUpdates.remove(holder)
                && !fabrolia$publishChangedHolder(holder)) {
            // A new dirty generation appeared while the admitted snapshot was
            // being published. Keep one bounded island hint, but never hold the
            // actor prediction ACK hostage: runLate owns its direct correction.
            island.pendingBlockUpdates.add(holder);
        }
    }

    @Unique
    private boolean fabrolia$publishChangedHolder(ChunkHolder holder) {
        synchronized (holder) {
            // The monitor covers BOTH claiming and publication. If Main won
            // just before an island drain, the island waits here until the
            // update packet has actually been queued; it cannot race ahead
            // and send the prediction ACK first.
            boolean claimedGlobalToken = this.chunkHoldersToBroadcast.remove(holder);
            if (!holder.hasChangesToBroadcast()) {
                // Either another publisher completed this exact generation or
                // this was a harmless stale hint.  Consuming a stale global
                // token here prevents it from living forever.
                return true;
            }
            LevelChunk chunk = holder.getTickingChunk();
            if (chunk == null) {
                // Exact 26.2 lifecycle: ChunkHolder.blockChanged itself first
                // requires getTickingChunk(), so this dirty generation was
                // admitted while BLOCK_TICKING and the holder was demoted before
                // publication. Vanilla skips it and clears the global token;
                // promotion/full-chunk delivery owns later observers. For a
                // prediction-bearing island packet, runLate's direct actor
                // correction remains the hard update-before-ACK authority.
                // Retrying this retired generation would otherwise fail every
                // island unit forever during ordinary load/unload churn.
                return true;
            }
            // Once vanilla publication is entered, its state/packet effects may
            // be partial. The exact Throwable must escape without re-enqueuing
            // this generation for a second broadcast attempt.
            holder.broadcastChanges(chunk);
            // Keep this local for bytecode/test visibility: both a claimed
            // token and a recovered orphan-dirty generation use the same
            // publication path above.
            if (!claimedGlobalToken && holder.hasChangesToBroadcast()) {
                this.chunkHoldersToBroadcast.add(holder);
                return false;
            }
            return true;
        }
    }

    /**
     * Ticket-consolidation read-side lock. {@code runDistanceManagerUpdates} drains the
     * Simulation/Loading {@code ChunkTracker} fixpoint and reads {@link
     * net.minecraft.world.level.TicketStorage} ({@code getTicketLevelAt → getLowestTicket})
     * — a plain {@code Long2ObjectOpenHashMap<List<Ticket>>} that vanilla only ever touches
     * on the server thread.
     *
     * <p>The rejected player-tick experiment was removed after the exact-26.2 audit. A
     * mounted player can nevertheless inherit a worker-owned root vehicle's
     * recursive passenger venue, and mod callbacks may also reach
     * {@code ServerPlayer.tick → ServerChunkCache.move → ChunkMap.move} off-main.
     * That path WRITES the same machinery: {@code DistanceManager.addPlayer/
     * removePlayer → TicketStorage.addTicket/removeTicket} (grows/shrinks the per-chunk
     * {@code List<Ticket>} and feeds {@code ChunkTracker.update}). {@link
     * com.axalotl.fabrolia.common.mixin.server.ChunkMapMixin#fabrolia$syncPlayerMove} already
     * serialises the section/ignored transition which performs those writes on the
     * {@code ChunkMap} instance monitor, but the consolidation
     * READ ran lock-free — a worker's {@code List.add} grow racing main's {@code
     * getLowestTicket} iteration surfaced a transient null element ("Cannot invoke
     * Ticket.getType() because ticket is null", crash 2026-06-25), and the tracker's pending
     * {@code DynamicGraphMinFixedPoint} queue raced {@code update} vs {@code runUpdates}.
     *
     * <p>Take the SAME {@code ChunkMap} monitor here so move-writes and consolidation-reads
     * are mutually exclusive — restoring vanilla's single-thread invariant for the whole
     * ticket/tracker cycle without changing its semantics. Conservatively engage while
     * regions are active; there is no longer a player-tick feature gate.
     * <p>Lock-order boundary (2026-08-25 production deadlock): C2ME consolidation may
     * enter a {@code NewChunkHolderVanillaInterface} monitor while this ChunkMap monitor
     * is held. Therefore no holder-owned path may enter ChunkMap in the opposite order.
     * In particular, ChunkHolder block/light publication now reads detached viewer
     * snapshots through PlayerMap/VMP component-local monitors; ChunkMap's getPlayers
     * and getPlayersCloseForSpawning wrappers are deliberately monitor-free. Keep this
     * outer ticket/write guard, but never reuse it as a viewer-read lock.
     */
    @WrapMethod(method = "runDistanceManagerUpdates")
    private boolean fabrolia$serializeDistanceUpdatesWithPlayerMove(Operation<Boolean> original) {
        // TICKET-SAFETY GUARD (2026-07-13, production CME kick): distance-
        // manager updates TICK TICKETS — server-thread-only state in vanilla
        // (unsynchronized fastutil maps); VMP enforces it with a guard whose
        // ConcurrentModificationException surfaced in packet handling and
        // kicked the player ("Attempted to tick tickets asynchronously").
        // While regions are active, an off-main call is REFUSED outright:
        // "no updates ran" is legal at every vanilla call site (opportunistic
        // loops), main's own pumps run the updates within the tick, and the
        // one-shot stack below names the illegal caller in production logs.
        if (!FabroliaConfig.disabled && FabroliaConfig.enableRegionThreading
                && RegionScheduler.isActive()
                && Thread.currentThread() != this.mainThread) {
            if (!FABROLIA$OFF_MAIN_DISTANCE_LOGGED.getAndSet(true)) {
                LOGGER.error("[ticket-safety] runDistanceManagerUpdates attempted OFF-MAIN on {} — refused. "
                        + "This call would corrupt ticket state (or trip VMP's async guard and kick a player). "
                        + "Report this stack:", Thread.currentThread().getName(),
                        new Throwable("off-main distance-manager caller"));
            }
            return false;
        }
        if (FabroliaConfig.disabled
                || !FabroliaConfig.enableRegionThreading
                || !RegionScheduler.isActive()) {
            return original.call();
        }
        synchronized (this.chunkMap) {
            return original.call();
        }
    }

    @Unique private static final java.util.concurrent.atomic.AtomicBoolean
            FABROLIA$OFF_MAIN_DISTANCE_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-ChunkTick");

    @Unique private volatile CompletableFuture<Void> fabrolia$spawnFuture;
    @Unique private volatile CompletableFuture<Void> fabrolia$blockTicksFuture;
    @Unique private boolean fabrolia$spawnLaunchedThisTick;
    /** The level's border, resolved on Main every chunk phase for off-main gates. */
    @Unique private volatile net.minecraft.world.level.border.WorldBorder fabrolia$worldBorder;


    /**
     * Region mode: deferred per-region submission of the spawn / random-tick chunk work.
     * The chunk phase only COLLECTS (chunk lists, spawn gate, positions — all main-thread
     * reads); the actual region-queue submit runs at the START of the entity phase via
     * {@link #fabrolia$armDeferredChunkWork()} — i.e. AFTER main's runBlockEvents.
     * Why: piston block events (runBlockEvents → moveBlocks) mutate chunk sections,
     * heightmaps and the block-entity map on main. With immediate arming, region workers
     * ran random ticks / precipitation / thunder — WRITERS — into those same chunks
     * concurrently (entity-ticking chunks exist only around players, which is exactly
     * where the "flying machine breaks only in the alt's chunk" reports pointed). Two
     * unsynchronized writers on one LevelChunk tear palette/heightmap/BE-map state.
     * Deferring the arm serializes ALL region chunk work after blockEvents while keeping
     * the ColorGate invariant (every region's work submitted BEFORE entity dispatch) and
     * per-region FIFO (spawn → random ticks → entities). Costs only the runBlockEvents
     * span of worker overlap (sub-ms on typical ticks).
     */
    @Unique private Runnable fabrolia$deferredSpawnArm;
    @Unique private Runnable fabrolia$deferredBlockTicksArm;

    @Unique
    private static FabroliaFlatSubmission fabrolia$beginFlatFanout(
            ExecutorService executor, int batchCount, FabroliaFlatBatch body) {
        if (batchCount <= 0) {
            return new FabroliaFlatSubmission(CompletableFuture.completedFuture(null), null);
        }
        FabroliaFlatFailureGate gate;
        try {
            gate = new FabroliaFlatFailureGate(batchCount, body);
        } catch (Throwable constructionFailure) {
            Throwable winner = fabrolia$publishFatalAndSelectWinner(constructionFailure);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(winner);
            return new FabroliaFlatSubmission(failed, winner);
        }
        try {
            for (FabroliaFlatTask task : gate.tasks) {
                gate.runNext(() -> executor.execute(task));
            }
        } catch (Throwable submissionFailure) {
            gate.publishFailure(submissionFailure);
        } finally {
            gate.finishSubmission();
        }
        return new FabroliaFlatSubmission(gate.completion, gate.failureOrNull());
    }

    /** Dense live chunk work stays on its claim-holding coordinator. Independent
     * islands may overlap; a helper cannot borrow another island's claim. */
    @Unique
    private static void fabrolia$runChunksSerial(ArrayList<LevelChunk> list,
                                                 Consumer<LevelChunk> rawAction) {
        for (int i = 0, n = list.size(); i < n; i++) {
            fabrolia$runChunkIfHealthy(list.get(i), rawAction);
        }
    }

    /** One chunk predicate + mutation body, atomically admitted against generation W. */
    @Unique
    private static void fabrolia$runChunkIfHealthy(
            LevelChunk chunk, Consumer<LevelChunk> rawAction) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (((ServerLevel) chunk.getLevel())
                    .shouldTickBlocksAt(chunk.getPos().pack())) {
                com.axalotl.fabrolia.common.regions.IslandClock.runHandlingTick(
                        (ServerLevel) chunk.getLevel(), chunk, rawAction);
            }
        });
    }

    @Unique
    private CompletableFuture<Void> fabrolia$submitPerRegion(List<LevelChunk> chunks,
                                                          Consumer<LevelChunk> action) {
        return fabrolia$submitPerRegion(chunks, action, false);
    }

    @Unique
    private CompletableFuture<Void> fabrolia$submitPerRegion(List<LevelChunk> chunks,
                                                          Consumer<LevelChunk> action,
                                                          boolean serialChunks) {
        RegionizedServer rs = RegionScheduler.forLevel(this.level);
        if (rs == null) return null;
        List<LevelChunk> chunksCopy = new ArrayList<>(chunks);
        if (FabroliaConfig.useRegionColorGate()) {
            return fabrolia$groupAndSubmit(rs, chunksCopy, action, serialChunks);
        }
        CompletableFuture<Void> outer = new CompletableFuture<>();
        RegionScheduler.enqueueGlobalMainSafe(() -> {
            try {
                CompletableFuture<Void> inner =
                        fabrolia$groupAndSubmit(rs, chunksCopy, action, serialChunks);
                if (inner == null) {
                    fabrolia$completeFutureAfterHealthyCommit(
                            outer, null, "outer chunk work completed more than once");
                } else {
                    inner.whenComplete((v, tx) -> {
                        try {
                            if (tx == null) {
                                fabrolia$completeFutureAfterHealthyCommit(
                                        outer, null,
                                        "outer chunk work completed more than once");
                            } else {
                                fabrolia$completeTransportedFutureAfterFatalPublication(outer, tx);
                            }
                        } catch (Throwable callbackFailure) {
                            fabrolia$completeFutureAfterFatalPublication(outer, callbackFailure);
                        }
                    });
                }
            } catch (Throwable t) {
                Throwable winner = fabrolia$completeFutureAfterFatalPublication(outer, t);
                ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(winner);
            }
        });
        RegionScheduler.wakeWorkers();
        return outer;
    }

    @Unique
    private static void fabrolia$runChunksMaybeParallel(Region region,
                                                        ArrayList<LevelChunk> list,
                                                        Consumer<LevelChunk> rawAction) {
        int n = list.size();
        if (n > 16 && Thread.currentThread() instanceof RegionWorkerThread) {
            int workers = Math.max(1, RegionScheduler.workerCount());
            int batchSize = Math.max(8, (n + workers - 1) / workers);
            int batchCount = (n + batchSize - 1) / batchSize;
            RegionTaskFanIn fanIn = new RegionTaskFanIn();
            RegionTaskFanIn.RegisteredTask coordinator = fanIn.register(() -> {
                int to = Math.min(batchSize, n);
                for (int i = 0; i < to; i++) {
                    fabrolia$runChunkIfHealthy(list.get(i), rawAction);
                }
            });
            boolean publicationFailed = false;
            int published = 0;
            try {
                for (int b = 1; b < batchCount; b++) {
                    int from = b * batchSize;
                    int to = Math.min(from + batchSize, n);
                    boolean accepted = fanIn.submit(() -> {
                        for (int i = from; i < to; i++) {
                            fabrolia$runChunkIfHealthy(list.get(i), rawAction);
                        }
                    }, helper -> RegionScheduler.enqueueHelpTask(region, helper),
                            "region chunk helper rejected by scheduler lifecycle");
                    if (!accepted) {
                        publicationFailed = true;
                        break;
                    }
                    published++;
                }
                if (published > 0) {
                    RegionScheduler.wakeWorkers();
                }
            } catch (Throwable failure) {
                fanIn.fail(failure);
                publicationFailed = true;
            }
            if (publicationFailed) {
                coordinator.cancelBeforeStart(null);
            } else {
                coordinator.run();
            }
            fanIn.drainAndAwait(region.helpQueue);
        } else {
            for (int i = 0; i < n; i++) {
                fabrolia$runChunkIfHealthy(list.get(i), rawAction);
            }
        }
    }


    /**
     * Vanilla 26.2 {@code tickSpawningChunk} gates {@code spawnForChunk} on
     * {@code level.canSpawnEntitiesInChunk} (world border + entity-manager position-tickable).
     * That reads plain PESM maps which main mutates while pumping the spawn barrier, so
     * evaluate it once per pass here — on Main for the global lane, on the island
     * coordinator for an island pass — over the lock-free ticking-chunk mirror, and hand
     * the chunk bodies an immutable position set. {@code null} = spawning categories
     * empty — vanilla skips spawning entirely that tick (thunder still runs).
     * The border is resolved by Main once per tick ({@link #fabrolia$worldBorder}):
     * {@code getWorldBorder()} goes through SavedDataStorage, a Main-only map.
     *
     * <p>The vanilla body is replicated inline ({@code entityManager.canPositionTick(pos)
     * && worldBorder.isWithinBounds(pos)}) instead of calling {@code canSpawnEntitiesInChunk}
     * per chunk: in 26.2 {@code getWorldBorder()} resolves through
     * {@code SavedDataStorage.computeIfAbsent} on EVERY call — 1.1s of the server thread at
     * 51 players (xMDQKeDhXO, 2026-07-02). One border lookup per tick, identical semantics
     * (the border instance is stable; only the lookup is hoisted).
     */
    @Unique
    private static it.unimi.dsi.fastutil.longs.LongOpenHashSet fabrolia$buildSpawnGate(
            ServerLevel lvl, net.minecraft.world.level.border.WorldBorder wb,
            List<LevelChunk> chunks, List<MobCategory> categories) {
        if (categories.isEmpty()) {
            return null;
        }
        var em = ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) lvl)
                .fabrolia$entityManager();
        // Incremental ticking-chunk mirror (PersistentEntitySectionManagerMixin):
        // one lock-free probe per chunk instead of accessor -> concurrent-map get ->
        // enum check (~2.5x cheaper at ~2.5k spawning chunks). The gate stays a
        // frozen per-tick snapshot — whole-phase coherence for the spawn pass.
        var ticking = ((com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess) em)
                .fabrolia$tickingChunks();
        var gate = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(Math.max(16, chunks.size()));
        for (int i = 0, n = chunks.size(); i < n; i++) {
            ChunkPos pos = chunks.get(i).getPos();
            long packed = pos.pack();
            if (ticking.get(packed) != 0 && wb.isWithinBounds(pos)) {
                gate.add(packed);
            }
        }
        return gate;
    }

    @Unique private static volatile boolean fabrolia$servercoreLookupDone;
    @Unique private static volatile boolean fabrolia$servercoreWarned;
    @Unique private static java.lang.reflect.Method fabrolia$servercoreConfigGet;
    @Unique private static java.lang.reflect.Method fabrolia$servercoreFeatures;
    @Unique private static java.lang.reflect.Method fabrolia$servercoreRandomTickDistance;

    @Unique
    private static boolean fabrolia$servercoreRandomTickDistanceAffectsRandomTicks() {
        if (!fabrolia$servercoreLookupDone) {
            synchronized (ServerChunkCacheMixin.class) {
                if (!fabrolia$servercoreLookupDone) {
                    try {
                        ClassLoader loader = ServerChunkCacheMixin.class.getClassLoader();
                        Class<?> configClass = Class.forName(
                                "me.wesley1808.servercore.common.config.Config", false, loader);
                        fabrolia$servercoreConfigGet = configClass.getMethod("get");
                        Class<?> mainConfigClass = Class.forName(
                                "me.wesley1808.servercore.common.config.MainConfig", false, loader);
                        fabrolia$servercoreFeatures = mainConfigClass.getMethod("features");
                        Class<?> featureConfigClass = Class.forName(
                                "me.wesley1808.servercore.common.config.data.FeatureConfig",
                                false, loader);
                        fabrolia$servercoreRandomTickDistance = featureConfigClass.getMethod("chunkTickDistanceAffectsRandomTicks");
                    } catch (ClassNotFoundException absent) {
                        // ServerCore not present.
                    } catch (NoSuchMethodException | SecurityException | LinkageError unavailable) {
                        fabrolia$servercoreWarned = true;
                        LOGGER.warn("[region-ticking] could not inspect ServerCore random-tick-distance setting; disabling incremental chunk list", unavailable);
                    } finally {
                        fabrolia$servercoreLookupDone = true;
                    }
                }
            }
        }
        if (fabrolia$servercoreConfigGet == null || fabrolia$servercoreFeatures == null || fabrolia$servercoreRandomTickDistance == null) {
            return fabrolia$servercoreWarned;
        }
        try {
            Object config = fabrolia$servercoreConfigGet.invoke(null);
            Object features = fabrolia$servercoreFeatures.invoke(config);
            return Boolean.TRUE.equals(fabrolia$servercoreRandomTickDistance.invoke(features));
        } catch (java.lang.reflect.InvocationTargetException targetFailure) {
            Throwable cause = targetFailure.getCause();
            ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(
                    cause != null ? cause : targetFailure);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException unavailable) {
            if (!fabrolia$servercoreWarned) {
                fabrolia$servercoreWarned = true;
                LOGGER.warn("[region-ticking] failed to query ServerCore random-tick-distance setting; disabling incremental chunk list", unavailable);
            }
            return true;
        }
    }

    @Unique
    private CompletableFuture<Void> fabrolia$groupAndSubmit(RegionizedServer rs,
                                                         List<LevelChunk> chunks,
                                                         Consumer<LevelChunk> action,
                                                         boolean serialChunks) {
        // Group by raw grid key first (pure bit math, no monitors) — the first version
        // called regionFor(ChunkPos) per chunk, paying the regionizer monitor + the
        // synchronized noteChunk debug-set for EVERY ticking chunk every tick: 5.38% of
        // the server thread in the 00:05 profile. Region lookup now happens once per
        // REGION, via regionForGridKey (no noteChunk on this hot path).
        //
        // Fresh lists belong to their published arms: a region task may outlive
        // this Main grouping pass, so scratch reuse would corrupt its chunk order.
        // The ticking-chunk list clusters spatially (holder iteration follows
        // generation order), so a last-key run-length check skips the map probe
        // for most chunks.
        // Region key is cached ON the chunk for its lifetime (position is
        // immutable) — the loop reads a field instead of chasing ChunkPos and
        // re-deriving shifts for ~60k ticking chunks every tick.
        int shift = rs.regionizer().grid.shift;
        Long2ObjectOpenHashMap<ArrayList<LevelChunk>> byKey =
                new Long2ObjectOpenHashMap<>(64);
        long lastKey = Long.MIN_VALUE;
        ArrayList<LevelChunk> lastList = null;
        for (int i = 0, n = chunks.size(); i < n; i++) {
            LevelChunk c = chunks.get(i);
            long key = ((ChunkRegionKeyed) c).fabrolia$regionKey(shift);
            ArrayList<LevelChunk> list;
            if (key == lastKey) {
                list = lastList;
            } else {
                list = byKey.get(key);
                if (list == null) {
                    list = new ArrayList<>(8);
                    byKey.put(key, list);
                }
                lastKey = key;
                lastList = list;
            }
            list.add(c);
        }
        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(byKey.size());
        for (var entry : Long2ObjectMaps.fastIterable(byKey)) {
            Region r = rs.regionizer().regionForGridKey(entry.getLongKey());
            // Island-owned regions are ticked by their island's own unit at
            // the island's cadence (IslandTickUnit chunk pass); Main arms and
            // awaits only the global remainder lane.
            if (rs.islandOwned(r)) {
                continue;
            }
            ArrayList<LevelChunk> list = entry.getValue();
            CompletableFuture<Void> cf = new CompletableFuture<>();
            if (!RegionScheduler.enqueueRegionTask(r, () -> {
                try {
                    if (serialChunks) {
                        fabrolia$runChunksSerial(list, action);
                    } else {
                        fabrolia$runChunksMaybeParallel(r, list, action);
                    }
                } catch (Throwable failure) {
                    Throwable winner = fabrolia$completeFutureAfterFatalPublication(cf, failure);
                    ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(winner);
                }
                fabrolia$completeFutureAfterHealthyCommit(
                        cf, null,
                        "region chunk work completed more than once");
            })) {
                throw new IllegalStateException(
                        "chunk task rejected at scheduler drain boundary");
            }
            futures.add(cf);
        }
        RegionScheduler.wakeWorkers();
        return futures.isEmpty() ? null
                : CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    /**
     * Block-tick dispatch from chunk POSITIONS (not pre-resolved LevelChunks). The server
     * thread is the tick bottleneck (~579 vs ~380 busiest worker, 50-bot profile); the old
     * path resolved {@code loadedChunk} per ticking chunk AND read the cached region key per
     * chunk ON MAIN (~17% of the server thread: loadedChunk 74 + regionKey 28 + the grouping).
     * Here main only does pure bit-math grouping ({@code grid.keyFor(pos)} — no chunk needed),
     * and each region WORKER resolves its own chunks via {@code getVisibleChunkIfPresent +
     * ChunkHolder.getTickingChunk} — the exact worker-safe path {@code fabrolia$getChunk} Tier 1
     * already uses off-main. NOTE on mechanism: under c2me-rewrites-chunk-system,
     * getVisibleChunkIfPresent is OVERWRITTEN to the flowsched getHolder path (StampedLock
     * optimistic read + bounded per-holder monitor) rather than vanilla's volatile snapshot
     * map — still thread-safe off-main and allocation-free; getTickingChunk stays vanilla
     * (volatile future getNow). The expensive resolver this design avoids is
     * {@code ServerChunkCache.getTickingChunk} (flowsched future alloc per call); the holder
     * getter is cheap on workers and authoritative (no stale-cache window —
     * {@code shouldTickBlocksAt} re-checks per chunk anyway).
     * Must run INLINE (every region submitted before entity dispatch — ColorGate invariant).
     */
    @Unique
    private CompletableFuture<Void> fabrolia$submitBlockTickPositions(
            RegionizedServer rs, it.unimi.dsi.fastutil.longs.LongArrayList positions,
            Consumer<LevelChunk> action) {
        final var grid = rs.regionizer().grid;
        Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.longs.LongArrayList> byKey =
                new Long2ObjectOpenHashMap<>(64);
        long lastKey = Long.MIN_VALUE;
        it.unimi.dsi.fastutil.longs.LongArrayList lastList = null;
        for (int i = 0, n = positions.size(); i < n; i++) {
            long pos = positions.getLong(i);
            long key = grid.keyFor(pos);   // pure bit math, no LevelChunk
            it.unimi.dsi.fastutil.longs.LongArrayList list;
            if (key == lastKey) {
                list = lastList;
            } else {
                list = byKey.get(key);
                if (list == null) {
                    list = new it.unimi.dsi.fastutil.longs.LongArrayList(8);
                    byKey.put(key, list);
                }
                lastKey = key;
                lastList = list;
            }
            list.add(pos);
        }
        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(byKey.size());
        for (var entry : Long2ObjectMaps.fastIterable(byKey)) {
            Region r = rs.regionizer().regionForGridKey(entry.getLongKey());
            it.unimi.dsi.fastutil.longs.LongArrayList posList = entry.getValue();
            // Island-owned regions: random/fluid/thunder ticks run inside the
            // island unit's chunk pass on ITS clock (IslandTickUnit). Main only
            // arms and awaits the global remainder lane.
            if (rs.islandOwned(r)) {
                continue;
            }
            final CompletableFuture<Void> cf = new CompletableFuture<>();
            Runnable body = () -> {
                try {
                    // Resolve ticking chunks ON THE WORKER (loadedChunk is server-thread-only;
                    // this is the worker-safe holder path fabrolia$getChunk Tier 1 uses).
                    ArrayList<LevelChunk> list = new ArrayList<>(posList.size());
                    for (int i = 0, n = posList.size(); i < n; i++) {
                        ChunkHolder h = this.getVisibleChunkIfPresent(posList.getLong(i));
                        if (h != null) {
                            LevelChunk c = h.getTickingChunk();
                            if (c != null) list.add(c);
                        }
                    }
                    // Serial within the region (audit C-01): help batches of one region ran
                    // on several workers at once, and random ticks of neighbouring chunks
                    // write the same LevelChunkSection counters and heightmaps unguarded.
                    fabrolia$runChunksSerial(list, action);
                } catch (Throwable failure) {
                    Throwable winner = fabrolia$completeFutureAfterFatalPublication(cf, failure);
                    ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(winner);
                }
                fabrolia$completeFutureAfterHealthyCommit(
                        cf, null,
                        "region random-tick work completed more than once");
            };
            if (!RegionScheduler.enqueueRegionTask(r, body)) {
                throw new IllegalStateException(
                        "random-tick task rejected at scheduler drain boundary");
            }
            futures.add(cf);
        }
        RegionScheduler.wakeWorkers();
        return futures.isEmpty() ? null
                : CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }


    /**
     * Fabrolia-thread getChunk fast path. Tiered lookup, cheapest first:
     *
     * <ol>
     *   <li><b>Cached ticking chunk</b> — {@code holder.getTickingChunk()} for {@code FULL} status.
     *       Single field read, no future polling. The 95%+ hot case on a settled server.</li>
     *   <li><b>Completed-future probe</b> — chunk finished generation but is not visible via
     *       the direct holder getter yet. Join only already-done futures; never park on a raw
     *       generation future from a worker.</li>
     *   <li><b>Main-thread future</b> — all misses go through {@code getChunkFutureMainThread}
     *       on {@code mainThreadProcessor}, preserving vanilla's {@code load=false} vs
     *       {@code load=true} ticket/scheduling split while keeping ChunkMap mutation on the
     *       same executor the fabrolia barriers pump.</li>
     * </ol>
     *
     * <p>The non-null vanilla contract is preserved for {@code load=true}: the cold path
     * either returns a real chunk or throws the same shape of {@link IllegalStateException}
     * vanilla throws after {@code ChunkResult.orElse(null)} fails.
     */
    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void fabrolia$getChunk(int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate, CallbackInfoReturnable<ChunkAccess> cir) {
        // Owner-local datapack function bodies touch only their partition's
        // chunks (plan-proven; fail-stop on a miss instead of a race).
        com.axalotl.fabrolia.common.regions.IslandFunctionScope.checkChunk(this.level, x, z);
        if (Thread.currentThread() == this.mainThread) return;

        long pos = ChunkPos.pack(x, z);
        ChunkHolder holder = this.getVisibleChunkIfPresent(pos);

        if (holder != null) {
            // Tier 1: cached chunk (95%+ hot case). Pure read; safe for load=false.
            ChunkAccess ready = fabrolia$extractReady(holder, targetStatus);
            if (ready != null) {
                cir.setReturnValue(ready);
                return;
            }

            // Tier 2: completed future probe only. Parking on a raw generation future from
            // here can wedge when its completion needs the mainThreadProcessor that the
            // server thread is only pumping at the outer barrier.
            ChunkAccess completed = fabrolia$extractCompleted(holder, targetStatus);
            if (completed != null) {
                cir.setReturnValue(completed);
                return;
            }
        }

        // A held map's island pixel pass proved these chunks owned and loaded
        // and holds the map monitor, which Main also takes: a concurrent FULL
        // demotion must not make it wait on Main here. Cold path only.
        if (targetStatus == ChunkStatus.FULL) {
            LevelChunk owned = com.axalotl.fabrolia.common.parallelised.utils.MapItemUpdates
                    .ownedPassChunk(this.level, x, z);
            if (owned != null) {
                cir.setReturnValue(owned);
                return;
            }
        }

        // Task #34 negative cache: a chunk that already burned a full soft-null
        // budget answers null immediately for the TTL — ten mobs clustered at
        // one stranded chunk must not each park 200ms per collision pass per
        // tick (the review's N×budget entity-barrier stall). Checked AFTER the
        // Tier-1/2 probes, so a chunk that became ready is never masked; a
        // stale entry expires by TTL and is removed on first read past it.
        if (!loadOrGenerate) {
            Long until = fabrolia$softNullUntil.get(pos);
            if (until != null) {
                if (System.nanoTime() - until < 0L) {
                    cir.setReturnValue(null);
                    return;
                }
                fabrolia$softNullUntil.remove(pos, until);
            }
        }

        // Tier 3: miss path — let vanilla's main-thread helper decide whether load=false
        // may use an existing eligible holder or load=true must add a ticket.
        // SAFE despite Server-thread parking in ParallelProcessor.pumpUntilDone because
        // pumpUntilDone now drains mainThreadProcessor.pollTask() per level while waiting
        // (chunk-system tasks only — never MinecraftServer.pollTask which can run Carpet
        // bot-kill / mod tasks that re-enter ChunkMap monitor). See pumpUntilDone comment.
        cir.setReturnValue(fabrolia$awaitMainThreadResult(
                fabrolia$submitMainThreadRequest(x, z, targetStatus, loadOrGenerate),
                pos, targetStatus, loadOrGenerate));
    }

    /** Task #34: load=false soft-null memo — pos → nanotime deadline. Written
     *  only when a full bridge budget expired (strand state); read before
     *  Tier-3 so repeat lookups at a strand answer instantly. Entries are
     *  few (one per stranded chunk) and self-expire. */
    @Unique
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> fabrolia$softNullUntil =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Unique
    private static final long SOFT_NULL_TTL_NS = 2_000_000_000L;

    /**
     * Non-expiring load ticket for one off-Main {@code load=true} request.
     * Vanilla's blocking {@code getChunk} relies on its 1-tick UNKNOWN ticket
     * surviving because the caller parks Main in {@code managedBlock}, so
     * {@code purgeStaleTickets} cannot run before the chunk arrives. An island
     * caller does not park Main: after a tick or two UNKNOWN is purged and a
     * cold load/generation resolves to UNLOADED ("Chunk not there when
     * requested"). The pin keeps vanilla's contract until the request resolves.
     * One unregistered type per request (NetherPortalPreparation's pattern):
     * TicketStorage merges tickets by type identity and level, so a request's
     * pin never merges with another's. No PERSIST flag: never saved. Never put
     * these types in equals/hash collections (TicketType is a record).
     */
    @Unique
    private Ticket fabrolia$pinBridgeLoad(ChunkPos pos, int ticketLevel) {
        Ticket pin = new Ticket(new TicketType(0L, TicketType.FLAG_LOADING), ticketLevel);
        this.ticketStorage.addTicket(pin, pos);
        return pin;
    }

    /** Main only. Vanilla's blocking getChunk returns with its UNKNOWN ticket
     *  still armed; re-arm it only while this request's pin is live (shutdown's
     *  deactivateTicketsOnClosing may already have moved the pin out). The scan
     *  and both ticket writes are one step under the ChunkMap monitor that
     *  guards TicketStorage (TicketStorageSyncMixin): island player moves edit
     *  the same per-chunk ticket list concurrently. */
    @Unique
    private void fabrolia$releaseBridgePin(ChunkPos pos, Ticket pin, boolean rearm) {
        synchronized (this.chunkMap) {
            boolean live = false;
            for (Ticket ticket : this.ticketStorage.getTickets(pos.pack())) {
                if (ticket == pin) {
                    live = true;
                    break;
                }
            }
            if (!live) return;
            if (rearm) {
                this.ticketStorage.addTicket(new Ticket(TicketType.UNKNOWN, pin.getTicketLevel()), pos);
            }
            this.ticketStorage.removeTicket(pin, pos);
        }
    }

    /** One main-thread request submission. No path may create a successor for
     *  the same entered obligation. */
    @Unique
    private CompletableFuture<ChunkResult<ChunkAccess>> fabrolia$submitMainThreadRequest(
            int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate) {
        CompletableFuture<ChunkResult<ChunkAccess>> chunkFuture = new CompletableFuture<>();
        FabroliaChunkMainPublication publication = new FabroliaChunkMainPublication();
        try {
            this.mainThreadProcessor.execute(() -> fabrolia$runPublishedChunkMainRequest(
                    publication, chunkFuture, () -> {
                CompletableFuture<ChunkResult<ChunkAccess>> vanilla;
                if (loadOrGenerate) {
                    ChunkPos pinned = new ChunkPos(x, z);
                    Ticket pin = fabrolia$pinBridgeLoad(pinned, ChunkLevel.byStatus(targetStatus));
                    try {
                        vanilla = this.getChunkFutureMainThread(x, z, targetStatus, true);
                    } catch (Throwable requestFailure) {
                        fabrolia$releaseBridgePin(pinned, pin, false);
                        throw requestFailure;
                    }
                    // Completion may run on a generation thread; tickets are Main
                    // state. Main's task loop only logs a task's exception, so the
                    // release is a fail-stop body like every other Main mutation.
                    vanilla.whenComplete((result, ex) -> this.mainThreadProcessor.execute(
                            () -> RegionScheduler.runSuccessBodyIfHealthy(
                                    () -> fabrolia$releaseBridgePin(pinned, pin, true))));
                } else {
                    vanilla = this.getChunkFutureMainThread(x, z, targetStatus, false);
                }
                vanilla.whenComplete((result, ex) -> {
                    try {
                        if (ex != null) {
                            fabrolia$completeChunkRequestFailure(chunkFuture, ex);
                        } else {
                            fabrolia$completeFutureAfterHealthyCommit(
                                    chunkFuture, result,
                                    "chunk bridge request completed more than once");
                        }
                    } catch (Throwable callbackFailure) {
                        // Do not let CompletionStage hide W in its discarded
                        // dependent future and strand the causal chunk waiter.
                        fabrolia$completeChunkRequestFailure(
                                chunkFuture, callbackFailure);
                    }
                });
            }));
            if (!publication.accept()) {
                throw new IllegalStateException(
                        "chunk-Main request publication lost its offering receipt");
            }
        } catch (Throwable publicationFailure) {
            // execute may reject, or may enqueue and then throw. The request
            // future is the causal receipt in both cases: select W and make it
            // terminal, but never submit the vanilla request a second time.
            Throwable failure;
            try {
                failure = fabrolia$completeChunkRequestFailure(
                        chunkFuture, publicationFailure);
            } finally {
                // Mandatory queue-publication cleanup: even a diagnostic
                // failure while terminalizing may not strand an OFFERING task.
                publication.cancel();
            }
            ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
        return chunkFuture;
    }

    /**
     * Two-phase queue-publication receipt.  Main may detach the lambda while
     * {@code execute} is still returning on its producer, but it cannot enter
     * the vanilla request until that return commits ACCEPTED.  An
     * enqueue-then-throw producer first terminalizes the causal future with W,
     * then publishes CANCELLED to release the detached lambda without a body.
     */
    @Unique
    static final class FabroliaChunkMainPublication {
        private static final int OFFERING = 0;
        private static final int ACCEPTED = 1;
        private static final int CANCELLED = 2;

        private final AtomicInteger state = new AtomicInteger(OFFERING);

        boolean accept() {
            return state.compareAndSet(OFFERING, ACCEPTED);
        }

        void cancel() {
            state.compareAndSet(OFFERING, CANCELLED);
        }

        boolean awaitAccepted() {
            int spins = 64;
            for (;;) {
                int observed = state.get();
                if (observed == ACCEPTED) return true;
                if (observed == CANCELLED) return false;
                if (spins-- > 0) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(this, 10_000L);
                }
            }
        }
    }

    /** Detached queue entry: publication must commit before body admission. */
    @Unique
    private static void fabrolia$runPublishedChunkMainRequest(
            FabroliaChunkMainPublication publication,
            CompletableFuture<ChunkResult<ChunkAccess>> chunkFuture,
            Runnable requestBody) {
        if (!publication.awaitAccepted()) {
            // The producer terminalized chunkFuture before publishing CANCELLED.
            // Read that typed receipt rather than a possibly retired scheduler
            // generation, and preserve its exact W without touching the body.
            try {
                chunkFuture.join();
            } catch (Throwable transported) {
                ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(
                        fabrolia$unwrapCompletion(transported));
            }
            throw new IllegalStateException(
                    "cancelled chunk-Main publication completed normally");
        }
        fabrolia$runChunkMainRequest(chunkFuture, requestBody);
    }

    /**
     * Execution-time admission for the exact queued chunk-Main request.  Queue
     * publication is not permission to enter the vanilla body: a peer W may win
     * while this lambda waits in MainThreadExecutor.  Refusal and an entered-body
     * failure both terminalize the request receipt with that exact generation W
     * before the task rethrows into the physical-Main fail-stop boundary.
     */
    @Unique
    private static void fabrolia$runChunkMainRequest(
            CompletableFuture<ChunkResult<ChunkAccess>> chunkFuture,
            Runnable requestBody) {
        try {
            RegionScheduler.runSuccessBodyIfHealthy(requestBody);
        } catch (Throwable requestFailure) {
            Throwable failure = fabrolia$completeChunkRequestFailure(
                    chunkFuture, requestFailure);
            ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    @Unique
    private static Throwable fabrolia$completeChunkRequestFailure(
            CompletableFuture<ChunkResult<ChunkAccess>> target, Throwable thrown) {
        Throwable failure = fabrolia$unwrapCompletion(thrown);
        // Select the exact first-wins object before waking any waiter. A
        // load=false caller may already have taken its vanilla-null timeout,
        // so the future alone is not a crash boundary; and a later local
        // failure must never replace an earlier peer W as the future's cause.
        failure = fabrolia$publishFatalAndSelectWinner(failure);
        try {
            if (!target.completeExceptionally(failure)) {
                failure = fabrolia$mergeFailure(failure, new IllegalStateException(
                        "chunk bridge failure completed an already-terminal request"));
            }
        } catch (Throwable completionFailure) {
            failure = fabrolia$mergeFailure(failure, completionFailure);
        }
        return failure;
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void fabrolia$getChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        if (Thread.currentThread() == this.mainThread) return;

        ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.pack(x, z));
        if (holder == null) {
            cir.setReturnValue(null);
            return;
        }

        LevelChunk ticking = holder.getTickingChunk();
        if (ticking != null) {
            cir.setReturnValue(ticking);
            return;
        }

        ChunkAccess chunk = holder.getChunkIfPresent(ChunkStatus.FULL);
        if (chunk instanceof LevelChunk lc) {
            cir.setReturnValue(lc);
            return;
        }

        cir.setReturnValue(null);
    }

    @Unique
    private static @Nullable ChunkAccess fabrolia$extractReady(ChunkHolder holder, ChunkStatus status) {
        if (status == ChunkStatus.FULL) {
            LevelChunk ticking = holder.getTickingChunk();
            if (ticking != null) return ticking;
        }

        ChunkAccess chunk = holder.getChunkIfPresent(status);
        if (chunk != null) return fabrolia$unwrap(chunk);

        chunk = holder.getChunkIfPresentUnchecked(status);
        if (chunk != null) return fabrolia$unwrap(chunk);

        return null;
    }

    /**
     * Task #34 rework: the wait loop lives in {@link com.axalotl.fabrolia.common.regions.ChunkBridgeWait}
     * (bounded vanilla-null for load=false; one exact, unbounded obligation for
     * load=true). This method keeps the extraction/contract logic:
     * <ul>
     *   <li>load=true completed-empty is the vanilla missing-chunk invariant;
     *       it fails immediately and never submits a successor request.</li>
     *   <li>An exceptional completion propagates its exact unwrapped cause.</li>
     *   <li>load=false budget expiry feeds the soft-null memo so a persistent
     *       strand costs ONE budget per TTL, not one per lookup.</li>
     * </ul>
     */
    @Unique
    private @Nullable ChunkAccess fabrolia$awaitMainThreadResult(
            CompletableFuture<ChunkResult<ChunkAccess>> chunkFuture,
            long pos,
            ChunkStatus status,
            boolean loadOrGenerate) {
        var outcome = com.axalotl.fabrolia.common.regions.ChunkBridgeWait.await(
                chunkFuture,
                () -> {
                    ChunkHolder h = this.getVisibleChunkIfPresent(pos);
                    return h == null ? null : fabrolia$extractReady(h, status);
                },
                loadOrGenerate,
                () -> "[" + ChunkPos.getX(pos) + ", " + ChunkPos.getZ(pos) + "] status=" + status
                        + " thread=" + Thread.currentThread().getName(),
                // The request's pin keeps a stranded holder from ever failing
                // its futures: a recognized C2ME FLAG_BROKEN holder fail-stops.
                () -> com.axalotl.fabrolia.common.regions.C2meStrandHeal.inspectAndMaybeHeal(
                        this.getVisibleChunkIfPresent(pos), this.chunkMap, pos));
        if (outcome instanceof com.axalotl.fabrolia.common.regions.ChunkBridgeWait.Result.Ready<ChunkResult<ChunkAccess>, ChunkAccess> ready) {
            return ready.chunk();
        }
        if (outcome instanceof com.axalotl.fabrolia.common.regions.ChunkBridgeWait.Result.GiveUpNull<ChunkResult<ChunkAccess>, ChunkAccess>) {
            // load=false only — the vanilla @Nullable contract; memoized so
            // the next lookups at this strand answer instantly for the TTL.
            long now = System.nanoTime();
            // Entries otherwise expire only when their chunk is looked up
            // again; a strand nobody revisits would stay forever.
            if (fabrolia$softNullUntil.size() >= 64) {
                fabrolia$softNullUntil.values().removeIf(until -> now - until >= 0L);
            }
            fabrolia$softNullUntil.put(pos, now + SOFT_NULL_TTL_NS);
            // A recognized BROKEN holder is a real entered-pipeline failure,
            // never a reason to clear state or start another upgrade.
            com.axalotl.fabrolia.common.regions.C2meStrandHeal.inspectAndMaybeHeal(
                    this.getVisibleChunkIfPresent(pos), this.chunkMap, pos);
            return null;
        }
        var done = (com.axalotl.fabrolia.common.regions.ChunkBridgeWait.Result.Done<ChunkResult<ChunkAccess>, ChunkAccess>) outcome;
        ChunkResult<ChunkAccess> result;
        try {
            result = done.future().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(
                    fabrolia$unwrapCompletion(wrapped));
            return null;
        }
        ChunkAccess chunk = fabrolia$extractFromResult(result);
        if (chunk != null) return chunk;

        ChunkHolder holder = this.getVisibleChunkIfPresent(pos);
        if (holder != null) {
            ChunkAccess ready = fabrolia$extractReady(holder, status);
            if (ready != null) return ready;
        }
        if (!loadOrGenerate) return null;
        throw fabrolia$missingChunk(result, this.level, pos, status);
    }

    @Unique
    private static @Nullable ChunkAccess fabrolia$extractCompleted(ChunkHolder holder, ChunkStatus status) {
        AtomicReferenceArray<?> futures = holder.futures;
        CompletableFuture<?> future = (CompletableFuture<?>) futures.get(status.getIndex());
        if (future != null && future.isDone()) {
            return fabrolia$extractFromFuture(future);
        }
        if (status == ChunkStatus.FULL) {
            CompletableFuture<?> fullFuture = holder.getFullChunkFuture();
            if (fullFuture.isDone()) {
                return fabrolia$extractFromFuture(fullFuture);
            }
        }
        return null;
    }

    @Unique
    private static @Nullable ChunkAccess fabrolia$extractFromFuture(CompletableFuture<?> future) {
        Object raw = future.join();
        if (raw instanceof ChunkResult<?> result) {
            return fabrolia$extractFromResult(result);
        }
        return null;
    }

    @Unique
    private static @Nullable ChunkAccess fabrolia$extractFromResult(@Nullable ChunkResult<?> result) {
        Object chunk = result == null ? null : result.orElse(null);
        return chunk instanceof ChunkAccess ca ? fabrolia$unwrap(ca) : null;
    }

    @Unique
    private static IllegalStateException fabrolia$missingChunk(@Nullable ChunkResult<?> result,
                                                               ServerLevel level, long pos,
                                                               ChunkStatus status) {
        String error = result == null ? "unknown fabrolia chunk result" : result.getError();
        return (IllegalStateException) Util.pauseInIde(
                new IllegalStateException("Chunk not there when requested: " + error
                        + " (" + level.dimension().identifier() + " [" + ChunkPos.getX(pos)
                        + ", " + ChunkPos.getZ(pos) + "] " + status + ", off-Main bridge, thread "
                        + Thread.currentThread().getName() + ")"));
    }

    @Unique
    private static ChunkAccess fabrolia$unwrap(ChunkAccess chunk) {
        if (chunk instanceof ImposterProtoChunk imposter) {
            return imposter.getWrapped();
        }
        return chunk;
    }


    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;")
    )
    private NaturalSpawner.SpawnState fabrolia$laneCensusPassthrough(
            int spawnableChunkCount, ServerLevel censusLevel, NaturalSpawner.ChunkGetter chunkGetter,
            LocalMobCapCalculator localMobCapCalculator, Operation<NaturalSpawner.SpawnState> original,
            ProfilerFiller profiler) {
        fabrolia$spawnLaunchedThisTick = false;
        fabrolia$worldBorder = this.level.getWorldBorder();
        // Belt-and-suspenders: with enableSpawnBarrierOverlap the previous tick's spawn
        // future is normally taken (and awaited) by ServerLevelMixin's entity barrier.
        // If some path skipped the entity phase, don't let last tick's spawn run
        // unsupervised into this tick's phases — await the stale future before reset.
        CompletableFuture<Void> stale = fabrolia$spawnFuture;
        if (stale != null && !stale.isDone()) {
            fabrolia$pumpUntilDone(stale);
        }
        fabrolia$spawnFuture = null;
        CompletableFuture<Void> staleBlocks = fabrolia$blockTicksFuture;
        if (staleBlocks != null && !staleBlocks.isDone()) {
            fabrolia$pumpUntilDone(staleBlocks);
        }
        fabrolia$blockTicksFuture = null;
        // Stale deferred arms (entity phase skipped last tick — tick freeze etc.):
        // drop them; last tick's chunk work must never fire into this tick's phases.
        fabrolia$deferredSpawnArm = null;
        fabrolia$deferredBlockTicksArm = null;

        // ASYNC ISLANDS — Main's census covers only the GLOBAL LANE (chunks
        // with no island owner): every island takes its own census in its own
        // chunk pass. The lane getter is the single membership rule for the
        // ledger reduce, the vanilla entity scan, the potential charges and the
        // local-cap seed. The global-cap check then reads the SUM of all lane
        // slots (SpawnCapLanes): this state is built with the summed
        // spawnable-chunk count, publishes Main's own lane, then folds the
        // island slots into its counts — vanilla's level total, each island's
        // share at most one island period old.
        RegionizedServer rs = RegionScheduler.forLevel(this.level);
        boolean lanes = rs != null && rs.asyncActive;
        NaturalSpawner.ChunkGetter getter = chunkGetter;
        int stateChunkCount = spawnableChunkCount;
        int laneChunkCount = spawnableChunkCount;
        int[] islandSlots = null;
        if (lanes) {
            // The islands always census from the incremental ledger. Its
            // 256-gt ground-truth audit normally rides Main's incremental
            // createState; when Main takes the vanilla scan instead,
            // keep the audit on Main's single-entry cadence here.
            if (!ParallelSpawnHelper.incrementalCensusActive()
                    && (this.level.getGameTime() & 255) == 0) {
                ParallelSpawnHelper.auditLedgerForIslandLanes(this.level);
            }
            getter = (key, output) -> {
                if (rs.islandOwnerOfChunk(key) == null) chunkGetter.query(key, output);
            };
            it.unimi.dsi.fastutil.longs.LongArrayList lanePlayerChunks =
                    new it.unimi.dsi.fastutil.longs.LongArrayList();
            ((com.axalotl.fabrolia.common.regions.RegionTickingAccess) this.chunkMap)
                    .fabrolia$forEachSpawnCountedPlayer((player, chunkKey) -> {
                        if (rs.islandOwnerOfChunk(chunkKey) == null) lanePlayerChunks.add(chunkKey);
                    });
            laneChunkCount = com.axalotl.fabrolia.common.regions.NaturalSpawnChunks
                    .candidates(lanePlayerChunks.iterator()).size();
            islandSlots = rs.spawnCapLanes.sum(rs.islandRegistry().identitySnapshot(), null, false);
            stateChunkCount = laneChunkCount + islandSlots[SpawnCapLanes.CHUNKS];
        }
        NaturalSpawner.SpawnState state =
                original.call(stateChunkCount, censusLevel, getter, localMobCapCalculator);
        if (lanes) {
            AtomicIntegerArray counts = ((FabroliaSpawnStateView) state).fabrolia$countsBacking();
            rs.spawnCapLanes.publishGlobalLane(SpawnCapLanes.slot(counts, laneChunkCount));
            for (int i = 0; i < SpawnCapLanes.CATEGORIES; i++) {
                if (islandSlots[i] != 0) counts.addAndGet(i, islandSlots[i]);
            }
        }
        return state;
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;tickSpawningChunk(Lnet/minecraft/world/level/chunk/LevelChunk;Ljava/util/List;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;)V")
    )
    private void fabrolia$interceptTickSpawning(ServerChunkCache instance, LevelChunk chunk,
                                             List<MobCategory> spawningCategories,
                                             NaturalSpawner.SpawnState spawnCookie,
                                             Operation<Void> original) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaSpawn) {
            original.call(instance, chunk, spawningCategories, spawnCookie);
            return;
        }

        if (fabrolia$spawnLaunchedThisTick) return;
        fabrolia$spawnLaunchedThisTick = true;

        List<LevelChunk> live = this.spawningChunks;
        if (live == null || live.isEmpty()) return;
        // SNAPSHOT (2026-07-17 zero-spawn root cause): vanilla REUSES and
        // CLEARS this.spawningChunks at the end of tickChunks (SCC:397). The
        // region-mode deferred arm runs at entity-phase start and the batch
        // futures outlive tickChunks — both iterated an already-empty (or
        // refilling) list: spawnForChunk was never invoked once, killing ALL
        // natural spawning, thunder, and inhabitedTime accrual under region
        // threading. Copy while the list is still populated. Under async
        // islands this list holds only global-lane chunks (ChunkMapMixin
        // filters island-owned candidates at getSpawnCandidateChunks).
        List<LevelChunk> list = new java.util.ArrayList<>(live);

        if (FabroliaConfig.enableRegionThreading && RegionScheduler.isActive()) {
            // Region mode publishes metadata now; its coordinator enters the native
            // chunk bodies later, in spawn -> random ticks -> entities FIFO order.
            ServerLevel lvl0 = this.level;
            DistanceManager dm0 = this.distanceManager;
            it.unimi.dsi.fastutil.longs.LongOpenHashSet gate0 =
                    fabrolia$buildSpawnGate(lvl0, fabrolia$worldBorder, list, spawningCategories);
            fabrolia$deferredSpawnArm = () -> {
                com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onPass(lvl0);
                fabrolia$spawnFuture = fabrolia$submitPerRegion(list, c -> {
                    long packed = c.getPos().pack();
                    c.incrementInhabitedTime();
                    // Native 26.3 tickSpawningChunk performs thunder before spawning.
                    if (dm0.inEntityTickingRange(packed)) {
                        lvl0.tickThunder(c);
                    }
                    if (gate0 != null && gate0.contains(packed)) {
                        com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onChunkVisit(lvl0, packed);
                        NaturalSpawner.spawnForChunk(lvl0, c, spawnCookie, spawningCategories);
                    }
                }, true);
            };
            return;
        }

        ServerLevel lvl = this.level;
        DistanceManager dmF = this.distanceManager;
        List<LevelChunk> chunks = list;
        int n = chunks.size();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet gateF =
                fabrolia$buildSpawnGate(lvl, fabrolia$worldBorder, chunks, spawningCategories);
        FabroliaFlatSubmission spawnSubmission = fabrolia$beginFlatFanout(
                ParallelProcessor.executor, 1, (failureGate, ignoredBatch) -> {
                    com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onPass(lvl);
                    for (int i = 0; i < n; i++) {
                        int chunkIndex = i;
                        failureGate.runNext(() -> {
                            LevelChunk c = chunks.get(chunkIndex);
                            long packed = c.getPos().pack();
                            c.incrementInhabitedTime();
                            if (dmF.inEntityTickingRange(packed)) {
                                lvl.tickThunder(c);
                            }
                            if (gateF != null && gateF.contains(packed)) {
                                com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onChunkVisit(lvl, packed);
                                NaturalSpawner.spawnForChunk(lvl, c, spawnCookie, spawningCategories);
                            }
                        });
                    }
                });
        fabrolia$spawnFuture = spawnSubmission.completion();
        if (spawnSubmission.failureAtReturn() != null) {
            fabrolia$awaitAcceptedFlatWork(spawnSubmission.completion(), null);
        }
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V")
    )
    private void fabrolia$waitSpawnThenBlockTick(ChunkMap instance, Consumer<LevelChunk> tickingChunkConsumer,
                                              Operation<Void> original) {
        CompletableFuture<Void> spawnFut = fabrolia$spawnFuture;
        CompletableFuture<Void> blockTicksFut = null;
        // Region mode: random/fluid/thunder ticks per-chunk on the OWNING region's worker,
        // queued AFTER that region's spawn task and BEFORE its entity task (FIFO) — each
        // region runs spawn → random ticks → entities for its own area in vanilla order.
        // No await here at all: both futures ride to the entity barrier via the taker.
        // Cross-area relaxation (accepted, documented): vanilla runs ALL random ticks
        // before runBlockEvents and before ANY entity; here a region's random ticks can
        // overlap main's blockEvents and other regions' phases — same boundary class as
        // the rest of region threading.
        if (!FabroliaConfig.disabled && FabroliaConfig.enableFabroliaRandomTicks
                && FabroliaConfig.enableRegionThreading
                && RegionScheduler.isActive()) {
            // Collect entity-ticking chunks by POSITION (cheap concurrent Long2ByteMap walk
            // via DistanceManager.forEachEntityTickingChunk — the actual 26.2 source of
            // forEachBlockTickingChunk); LevelChunk resolution + region-key derivation move
            // onto the region workers (fabrolia$submitBlockTickPositions). Always accurate (the
            // sim-tracker set is authoritative) — no incremental holder list to drift, no
            // main-thread-only chunk cache.
            //
            // ServerCore can replace forEachBlockTickingChunk with its own ChunkTickManager
            // when chunk-tick-distance-affects-random-ticks is enabled. In that mode the
            // source is no longer the sim-tracker set, so preserve ServerCore semantics and
            // fall back to the real method.
            if (!fabrolia$servercoreRandomTickDistanceAffectsRandomTicks()) {
                RegionizedServer rs = RegionScheduler.forLevel(this.level);
                if (rs != null) {
                    // ServerCore's mode is read live each tick: once it is off, clear the
                    // islands' ServerCore snapshots, or they keep ticking the stale list
                    // (audit C-09). With the mode off this only writes null.
                    fabrolia$publishIslandRandomTickSnapshots(List.of());
                    // Main only COLLECTS positions from the authoritative sim-tracker set
                    // (server-thread-only walk); per-chunk LevelChunk resolution + region-key
                    // derivation move onto the region workers in fabrolia$submitBlockTickPositions.
                    it.unimi.dsi.fastutil.longs.LongArrayList positions =
                            new it.unimi.dsi.fastutil.longs.LongArrayList();
                    this.chunkMap.getDistanceManager().forEachEntityTickingChunk(positions::add);
                    if (!positions.isEmpty()) {
                        // DEFERRED ARM — see fabrolia$deferredBlockTicksArm.
                        fabrolia$deferredBlockTicksArm = () -> {
                            fabrolia$blockTicksFuture = fabrolia$submitBlockTickPositions(rs, positions, tickingChunkConsumer);
                        };
                    }
                    return;
                }
            }
            List<LevelChunk> tickChunks = new ArrayList<>();
            original.call(instance, (Consumer<LevelChunk>) tickChunks::add);
            fabrolia$publishIslandRandomTickSnapshots(tickChunks);
            if (!tickChunks.isEmpty()) {
                // DEFERRED ARM — see fabrolia$deferredBlockTicksArm.
                fabrolia$deferredBlockTicksArm = () -> {
                    fabrolia$blockTicksFuture = fabrolia$submitPerRegion(tickChunks, tickingChunkConsumer, true);
                };
            }
            return;
        }

        // Random ticks of the flat (region threading off) path run serially on
        // Main (audit C-01): the former pool fan-out ticked neighbouring chunks at
        // once, and their writes share LevelChunkSection counters and heightmaps,
        // which nothing guards. Island-owned chunks are ticked by their island unit.
        List<LevelChunk> collected = new ArrayList<>();
        original.call(instance, (Consumer<LevelChunk>) collected::add);
        fabrolia$publishIslandRandomTickSnapshots(collected);
        for (LevelChunk c : fabrolia$mainOwnedOnly(collected)) {
            tickingChunkConsumer.accept(c);
        }

        boolean rideAlong = FabroliaConfig.enableSpawnBarrierOverlap && !FabroliaConfig.disabled;
        CompletableFuture<Void> combined;
        if (rideAlong) {
            combined = blockTicksFut;
        } else if (spawnFut != null && blockTicksFut != null) {
            combined = CompletableFuture.allOf(spawnFut, blockTicksFut);
        } else if (spawnFut != null) {
            combined = spawnFut;
        } else {
            combined = blockTicksFut;
        }
        if (combined != null) {
            fabrolia$pumpUntilDone(combined);
        }
        if (!rideAlong) {
            if (spawnFut != null && spawnFut.isCompletedExceptionally()) {
                spawnFut.join();
            }
            fabrolia$spawnFuture = null;
        }
    }

    @WrapOperation(
            method = "tick(Ljava/util/function/BooleanSupplier;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;tick()V"))
    private void fabrolia$trackerOnMain(ChunkMap instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }


    /** A2 deadline drive — save-all integrity: units may be mid-phase at any
     *  moment; serializing chunks/entities they own would tear NBT. Quiesce
     *  every island (units complete, none restart) around the save, exactly
     *  the docs/ASYNC-ISLANDS.md "quiesce-all → save" rule. No-op when async
     *  is off (claims always free). */
    @WrapMethod(method = "save")
    private void fabrolia$quiesceIslandsForSave(boolean flush, Operation<Void> original) {
        RegionizedServer rsq = RegionScheduler.forLevel(this.level);
        if (rsq == null || !rsq.asyncActive) {
            // A different level can still own the active worker generation.
            // This bypass skips only claim acquisition, never atomic admission
            // of the real vanilla chunk-save body.
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(flush));
            return;
        }
        boolean took = rsq.quiesceAllIslands();
        // THIS level's claims held (not other levels'): materialize only
        // THIS level's staged cross-dimension arrivals — entity saving
        // would otherwise miss them. flushAll() here (task #26) also
        // published OTHER levels' arrivals: addDuringTeleport into an
        // un-quiesced destination from main, racing that level's worker and
        // half-registering a portal minecart into a torn chunk section
        // (visible + island-ticked, but unable to engage its rail → drifts
        // to rest inside the portal, cooldown-re-arm trapped until the
        // chunk reloads from NBT — the unattended-autosave field failure).
        // Each level materializes its own arrivals under its own quiesce
        // during its own save pass of the same autosave cycle.
        Throwable failure = null;
        try {
            // Flush and vanilla serialization are distinct fallible stages:
            // W during the flush must refuse entry to the later save body.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> com.axalotl.fabrolia.common.regions.CrossDimArrivals
                            .flushLevel(rsq));
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(flush));
        } catch (Throwable primary) {
            // Select exact W before exhaustive claim release; releaseAllIslands
            // merges cleanup diagnostics without replacing that winner.
            failure = RegionScheduler.publishFatalAndSelectWinner(primary);
        }
        if (took) {
            failure = rsq.releaseAllIslands(failure);
        }
        if (failure == null) {
            try {
                // Cleanup is not a success edge: catch a peer W which wins
                // while the quiesce claims are being released.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
        }
    }

    public CompletableFuture<Void> fabrolia$takeSpawnFuture() {
        CompletableFuture<Void> f = fabrolia$spawnFuture;
        fabrolia$spawnFuture = null;
        return f;
    }

    @Override
    public CompletableFuture<Void> fabrolia$takeBlockTicksFuture() {
        CompletableFuture<Void> f = fabrolia$blockTicksFuture;
        fabrolia$blockTicksFuture = null;
        return f;
    }

    @Override
    public void fabrolia$armDeferredChunkWork() {
        // Entity-phase start (main thread): runBlockEvents is done — submit the deferred
        // spawn work first, then random/fluid ticks (per-region FIFO keeps vanilla's
        // spawn → random ticks → entities order per area). Sets fabrolia$spawnFuture /
        // fabrolia$blockTicksFuture for the takers; MUST precede dispatchOwnedEntityTicks
        // (ColorGate: every region's work submitted before entity dispatch).
        // Adopt regions populated during this tick's chunk phase BEFORE the arm
        // decides lanes: a region armed as global here and adopted by an island
        // at the pre-dispatch rebuild would get its chunk cycle twice this wall
        // tick (Main's task + the island's own pass). Idempotent on the dirty
        // flag; the pre-dispatch call stays as the airtight dispatch cut.
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            RegionizedServer rs = RegionScheduler.forLevel(this.level);
            if (rs != null && rs.asyncActive) rs.rebuildIslandsIfDirty();
            Runnable spawnArm = fabrolia$deferredSpawnArm;
            fabrolia$deferredSpawnArm = null;
            if (spawnArm != null) spawnArm.run();
        });
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            Runnable blockTicksArm = fabrolia$deferredBlockTicksArm;
            fabrolia$deferredBlockTicksArm = null;
            if (blockTicksArm != null) blockTicksArm.run();
        });
    }

    @Unique
    private void fabrolia$pumpUntilDone(CompletableFuture<?> future) {
        fabrolia$awaitAcceptedFlatWork(future, null);
    }

    @Unique
    private void fabrolia$awaitAcceptedFlatWork(CompletableFuture<?> first,
                                                @Nullable CompletableFuture<?> second) {
        Throwable failure = null;
        failure = fabrolia$awaitFlatFutureTerminal(first, failure);
        if (second != null && second != first) {
            failure = fabrolia$awaitFlatFutureTerminal(second, failure);
        }
        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable globalWinner) {
            if (failure == null) {
                failure = globalWinner;
            } else if (failure != globalWinner) {
                failure = fabrolia$mergeFailure(globalWinner, failure);
            }
        }
        failure = fabrolia$observeFlatFutureFailure(first, failure);
        if (second != null && second != first) {
            failure = fabrolia$observeFlatFutureFailure(second, failure);
        }
        if (failure != null) {
            ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    @Unique
    private Throwable fabrolia$awaitFlatFutureTerminal(CompletableFuture<?> future,
                                                       Throwable failure) {
        MinecraftServer s = this.level.getServer();
        if (s != null && s.isSameThread()) {
            s.blockingCount++;
            try {
                while (!future.isDone()) {
                    boolean drained = false;
                    if (failure == null) {
                        try {
                            for (ServerLevel lvl : s.getAllLevels()) {
                                if (RegionScheduler.callSuccessBooleanIfHealthy(
                                        () -> lvl.getChunkSource().mainThreadProcessor.pollTask())) {
                                    drained = true;
                                }
                            }
                        } catch (Throwable progressFailure) {
                            failure = fabrolia$recordFlatAwaitFailure(failure, progressFailure);
                        }
                    }
                    if (!drained) {
                        LockSupport.parkNanos("Fabrolia ServerChunkCacheMixin pumpUntilDone", 100_000L);
                    }
                }
            } finally {
                s.blockingCount--;
            }
        } else {
            while (!future.isDone()) {
                LockSupport.parkNanos("Fabrolia ServerChunkCacheMixin pumpUntilDone off-main", 100_000L);
            }
        }
        return failure;
    }

    @Unique
    private static Throwable fabrolia$observeFlatFutureFailure(CompletableFuture<?> future,
                                                               Throwable failure) {
        try {
            future.join();
            return failure;
        } catch (Throwable wrapped) {
            return failure != null ? failure
                    : fabrolia$publishFatalAndSelectWinner(fabrolia$unwrapCompletion(wrapped));
        }
    }

    @Unique
    private static Throwable fabrolia$recordFlatAwaitFailure(Throwable failure,
                                                             Throwable progressFailure) {
        return failure == null
                ? fabrolia$publishFatalAndSelectWinner(progressFailure)
                : fabrolia$mergeFailure(failure, progressFailure);
    }


    /** Global publication linearizes before any local future wakes its waiter. */
    @Unique
    private static <T> void fabrolia$completeFutureAfterHealthyCommit(
            CompletableFuture<T> target, T result, String duplicateMessage) {
        try {
            RegionScheduler.commitSuccessIfHealthy(() -> {
                if (!target.complete(result)) {
                    throw new IllegalStateException(duplicateMessage);
                }
            });
        } catch (Throwable completionFailure) {
            Throwable winner = fabrolia$completeFutureAfterFatalPublication(
                    target, completionFailure);
            ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(winner);
        }
    }

    /** Global publication linearizes before any local future wakes its waiter. */
    @Unique
    private static Throwable fabrolia$completeFutureAfterFatalPublication(
            CompletableFuture<?> target, Throwable candidate) {
        Throwable winner = fabrolia$publishFatalAndSelectWinner(
                candidate);
        try {
            if (!target.completeExceptionally(winner)) {
                winner = fabrolia$mergeFailure(winner, new IllegalStateException(
                        "chunk work failure reached an already-terminal future"));
            }
        } catch (Throwable terminalFailure) {
            // Future terminalization is cleanup. It cannot replace or trigger
            // a second attempt of the exact entered vanilla operation.
            winner = fabrolia$mergeFailure(winner, terminalFailure);
        }
        return winner;
    }

    @Unique
    private static Throwable fabrolia$completeTransportedFutureAfterFatalPublication(
            CompletableFuture<?> target, Throwable transported) {
        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable globalWinner) {
            return fabrolia$completeFutureAfterFatalPublication(target, globalWinner);
        }
        return fabrolia$completeFutureAfterFatalPublication(
                target, fabrolia$unwrapCompletion(transported));
    }


    /** Select the shared generation winner before publishing a local terminal. */
    @Unique
    private static Throwable fabrolia$publishFatalAndSelectWinner(Throwable candidate) {
        if (candidate == null) {
            candidate = new NullPointerException("flat chunk fan-out failure");
        }
        return RegionScheduler.publishFatalAndSelectWinner(candidate);
    }

    @Unique
    private static Throwable fabrolia$unwrapCompletion(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException wrapped
                && wrapped.getCause() != null) {
            return wrapped.getCause();
        }
        return failure;
    }

    @Unique
    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && cleanup != primary) {
            try {
                if (primary.getSuppressed().length >= 8) return primary;
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
                // Cleanup diagnostics cannot replace the exact tick failure.
            }
        }
        return primary;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Unique
    @FunctionalInterface
    interface FabroliaFlatBatch {
        void run(FabroliaFlatFailureGate failureGate, int batchIndex);
    }

    @Unique
    static final class FabroliaFlatFailureGate {
        private static final int MAX_SUPPRESSED = 8;

        private final FabroliaFlatTask[] tasks;
        private final AtomicInteger pending;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Throwable failure;
        private int suppressedCleanup;

        private FabroliaFlatFailureGate(int batchCount, FabroliaFlatBatch body) {
            this.tasks = new FabroliaFlatTask[batchCount];
            this.pending = new AtomicInteger(batchCount + 1);
            for (int i = 0; i < batchCount; i++) {
                this.tasks[i] = new FabroliaFlatTask(this, body, i);
            }
        }

        void runNext(Runnable body) {
            if (body == null) throw new NullPointerException("body");
            try {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    synchronized (this) {
                        if (this.failure != null) {
                            ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(this.failure);
                        }
                    }
                    body.run();
                });
            } catch (Throwable thrown) {
                ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(publishFailure(thrown));
            }
        }

        Throwable publishFailure(Throwable candidate) {
            synchronized (this) {
                return publishFailureLocked(candidate);
            }
        }

        private Throwable publishFailureLocked(Throwable candidate) {
            if (candidate == null) {
                candidate = new NullPointerException("flat chunk fan-out failure");
            }
            if (this.failure == null) {
                this.failure = fabrolia$publishFatalAndSelectWinner(candidate);
                for (FabroliaFlatTask task : this.tasks) {
                    try {
                        task.cancelQueuedBeforeStart();
                    } catch (Throwable cancellationFailure) {
                        if (cancellationFailure != this.failure
                                && this.suppressedCleanup < MAX_SUPPRESSED) {
                            fabrolia$mergeFailure(this.failure, cancellationFailure);
                            this.suppressedCleanup++;
                        }
                    }
                }
            }
            return this.failure;
        }

        synchronized Throwable failureOrNull() {
            return this.failure;
        }

        private void finishSubmission() {
            finishObligation();
        }

        private void finishTask() {
            finishObligation();
        }

        private void finishObligation() {
            int remaining = this.pending.decrementAndGet();
            if (remaining < 0) {
                Throwable duplicate = publishFailure(new IllegalStateException(
                        "flat chunk fan-out obligation completed more than once"));
                ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(duplicate);
            }
            if (remaining == 0) {
                Throwable terminalFailure = failureOrNull();
                if (terminalFailure == null) {
                    fabrolia$completeFutureAfterHealthyCommit(
                            this.completion, null,
                            "flat chunk fan-out completed more than once");
                } else {
                    this.completion.completeExceptionally(terminalFailure);
                }
            }
        }
    }

    @Unique
    static final class FabroliaFlatSubmission {
        private final CompletableFuture<Void> completion;
        private final Throwable failureAtReturn;

        private FabroliaFlatSubmission(CompletableFuture<Void> completion, Throwable failureAtReturn) {
            this.completion = completion;
            this.failureAtReturn = failureAtReturn;
        }

        CompletableFuture<Void> completion() {
            return this.completion;
        }

        Throwable failureAtReturn() {
            return this.failureAtReturn;
        }
    }

    @Unique
    static final class FabroliaFlatTask implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final FabroliaFlatFailureGate gate;
        private final FabroliaFlatBatch body;
        private final int batchIndex;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private FabroliaFlatTask(FabroliaFlatFailureGate gate, FabroliaFlatBatch body, int batchIndex) {
            this.gate = gate;
            this.body = body;
            this.batchIndex = batchIndex;
        }

        @Override
        public void run() {
            if (!this.state.compareAndSet(QUEUED, RUNNING)) {
                if (this.state.get() != CANCELLED) {
                    Throwable duplicate = this.gate.publishFailure(new IllegalStateException(
                            "flat chunk fan-out task executed more than once"));
                    ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(duplicate);
                }
                return;
            }
            Throwable failure = null;
            try {
                this.gate.runNext(() -> this.body.run(this.gate, this.batchIndex));
            } catch (Throwable thrown) {
                failure = this.gate.publishFailure(thrown);
            } finally {
                this.state.set(TERMINAL);
                this.gate.finishTask();
            }
            if (failure != null) {
                ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(failure);
            }
        }

        private void cancelQueuedBeforeStart() {
            if (this.state.compareAndSet(QUEUED, CANCELLED)) {
                this.gate.finishTask();
            }
        }
    }

    /**
     * ServerCore's {@code chunkTickDistanceAffectsRandomTicks} replaces the
     * random-tick chunk source with its ChunkTickManager — Main-mutated state
     * that must not be walked from an island coordinator. Under that mode Main
     * partitions the real {@code forEachBlockTickingChunk} result by owner once
     * per Main tick and publishes each island's positions; the island pass
     * reads its last snapshot (one Main tick of staleness, the completed-view
     * class) and re-resolves live ticking chunks itself. Off that mode the
     * field stays null and islands walk the completed simulation view.
     */
    @Unique
    private void fabrolia$publishIslandRandomTickSnapshots(List<LevelChunk> chunks) {
        RegionizedServer rs = RegionScheduler.forLevel(this.level);
        if (rs == null || !rs.asyncActive) return;
        boolean servercore = fabrolia$servercoreRandomTickDistanceAffectsRandomTicks();
        HashMap<IslandRegistry.PIsland, it.unimi.dsi.fastutil.longs.LongArrayList> byIsland =
                servercore ? new HashMap<>() : null;
        if (servercore) {
            for (int i = 0, n = chunks.size(); i < n; i++) {
                long key = chunks.get(i).getPos().pack();
                IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(key);
                if (owner == null) continue;
                byIsland.computeIfAbsent(owner, k -> new it.unimi.dsi.fastutil.longs.LongArrayList(64))
                        .add(key);
            }
        }
        for (IslandRegistry.PIsland island : rs.islandRegistry().identitySnapshot()) {
            island.servercoreRandomTickChunks = byIsland == null ? null
                    : byIsland.getOrDefault(island, IslandRegistry.NO_CHUNKS);
        }
    }

    /** {@code chunks} minus island-owned ones; the same list when no lane applies. */
    @Unique
    private List<LevelChunk> fabrolia$mainOwnedOnly(List<LevelChunk> chunks) {
        RegionizedServer rs = RegionScheduler.forLevel(this.level);
        if (rs == null || !rs.asyncActive) return chunks;
        ArrayList<LevelChunk> owned = new ArrayList<>(chunks.size());
        for (int i = 0, n = chunks.size(); i < n; i++) {
            LevelChunk c = chunks.get(i);
            if (rs.islandOwnerOfChunk(c.getPos().pack()) == null) owned.add(c);
        }
        return owned;
    }

    /**
     * Vanilla {@code ServerChunkCache.tickChunks} for ONE island, executed by
     * the island's unit coordinator on the island's clock (IslandTickUnit:
     * after its scheduled ticks and raids, before its block events — the exact
     * {@code chunkSource.tick} slot). Everything below is the vanilla body with
     * "the level" replaced by "this island":
     *
     * <ul>
     *   <li>inhabited time — +1 per pass, exactly like 26.3's per-tick increment.</li>
     *   <li>{@code createState} — census, potential charges and local-cap seed
     *       over the island's own FULL chunks ({@link ParallelSpawnHelper#censusIslandLane});
     *       the local cap is exact (every mob within a player's cap radius is
     *       on the player's island); the potential calculator is island-local
     *       (another island's charges sit ≥256 blocks away — the one accepted
     *       approximation).</li>
     *   <li>spawnable chunks and their count — {@link NaturalSpawnChunks}
     *       around the island's own players ({@code naturalSpawnChunkCounter}
     *       restricted to this island).</li>
     *   <li>global cap — {@link SpawnCapLanes}: this island publishes its slot
     *       and the state is built from the SUM of every lane's slot.</li>
     *   <li>category gating ({@code % 400} persistent categories) — island L.</li>
     *   <li>spawning chunk list — candidate ∧ ticking chunk ∧ owned by this
     *       island ∧ vanilla's non-spectator 128-block player test, shuffled
     *       with the level RNG exactly like vanilla; then per chunk inhabited
     *       time → thunder → spawnForChunk.</li>
     *   <li>random/fluid ticks — the island's entity-ticking chunks through
     *       vanilla {@code ServerLevel.tickChunk}.</li>
     * </ul>
     *
     * <p>Serial on the coordinator: an island region is claimable only inside
     * the unit's entity-phase window (ColorGate), and vanilla's chunk pass is
     * sequential over the level RNG. {@code customSpawners} stay level-wide on
     * Main. Frozen tick rate: vanilla runs no chunk pass, neither does this.
     */
    @Override
    public void fabrolia$tickIslandChunks(RegionizedServer rs, IslandRegistry.PIsland island,
                                          long islandTime) {
        ServerLevel lvl = this.level;
        if (FabroliaConfig.disabled || lvl.isDebug() || !lvl.tickRateManager().runsNormally()) {
            return;
        }
        long time = islandTime;

        // --- this island's lane: its own cells, resolved ONCE per pass ----------
        // A cell's owner only changes in a topology rebuild, which quiesces every
        // unit first, so the pass-entry answer is the answer for the whole pass.
        // Ownership ⊆ cells ∪ halo (rebuild assigns the island's domain to
        // exactly that closure). One owner lookup per own cell replaces one
        // regionizer-map probe per ledger key / charge / candidate / player.
        RegionGrid grid = rs.regionizer().grid;
        it.unimi.dsi.fastutil.longs.LongArrayList ownCellList =
                new it.unimi.dsi.fastutil.longs.LongArrayList(island.cells.size() + island.haloCells.size());
        it.unimi.dsi.fastutil.longs.LongIterator islandCells = new IslandRegistry.DualLongIterator(
                island.cells.iterator(), island.haloCells.iterator());
        while (islandCells.hasNext()) {
            long cell = islandCells.nextLong();
            if (rs.islandOwnerOfCell(cell) == island) ownCellList.add(cell);
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet ownCells =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(ownCellList);
        java.util.function.LongPredicate laneKeys = key -> ownCells.contains(grid.keyFor(key));

        // --- createState, this island's lane ---------------------------------
        NaturalSpawner.ChunkGetter laneGetter = (key, output) -> {
            if (laneKeys.test(key)) this.getFullChunk(key, output);
        };
        LocalMobCapCalculator localMobCap = new LocalMobCapCalculator(this.chunkMap);
        ((com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView) localMobCap).fabrolia$useSerialLane();
        ParallelSpawnHelper.LaneCensus census = ParallelSpawnHelper.censusIslandLane(
                lvl, laneGetter, laneKeys, ownCellList.toLongArray(), grid.shift, localMobCap);

        var chunkMapAccess = (com.axalotl.fabrolia.common.regions.RegionTickingAccess) this.chunkMap;
        ArrayList<ServerPlayer> islandPlayers = new ArrayList<>(4);
        it.unimi.dsi.fastutil.longs.LongArrayList playerChunks =
                new it.unimi.dsi.fastutil.longs.LongArrayList(4);
        chunkMapAccess.fabrolia$forEachSpawnCountedPlayer((player, chunkKey) -> {
            if (laneKeys.test(chunkKey)) {
                islandPlayers.add(player);
                playerChunks.add(chunkKey);
            }
        });
        it.unimi.dsi.fastutil.longs.LongOpenHashSet candidates =
                NaturalSpawnChunks.candidates(playerChunks.iterator());

        int[] own = SpawnCapLanes.slot(census.counts(), candidates.size());
        island.spawnCapSlot = own;
        int[] total = rs.spawnCapLanes.sum(rs.islandRegistry().identitySnapshot(), island, true);
        SpawnCapLanes.add(total, own);
        NaturalSpawner.SpawnState state = new NaturalSpawner.SpawnState(
                total[SpawnCapLanes.CHUNKS], SpawnCapLanes.counts(total),
                census.potential(), localMobCap);

        // --- vanilla tickChunks gating, on island L ----------------------------
        boolean doMobSpawning = lvl.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS);
        int tickSpeed = lvl.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
        List<MobCategory> spawningCategories = doMobSpawning
                ? NaturalSpawner.getFilteredSpawningCategories(state, this.spawnEnemies, time % 400L == 0L)
                : List.of();

        // --- collectSpawningChunks + shuffle, this island -----------------------
        ArrayList<LevelChunk> spawningChunks = new ArrayList<>(candidates.size());
        it.unimi.dsi.fastutil.longs.LongIterator candidateIt = candidates.iterator();
        while (candidateIt.hasNext()) {
            long key = candidateIt.nextLong();
            // Candidates around island players reach past its own cells (halo
            // edge, another lane's chunks): spawn only into chunks this lane owns.
            if (!laneKeys.test(key)) continue;
            // Vanilla's three filters (holder present, ticking chunk, a player
            // within 128 blocks) are side-effect-free reads, so the pure
            // distance test runs first: the ~30% of the 17x17 candidate square
            // outside every player's 128-block circle never reaches C2ME's
            // holder map. Same chunks, same order.
            ChunkPos pos = ChunkPos.unpack(key);
            boolean close = false;
            for (int i = 0, n = islandPlayers.size(); i < n && !close; i++) {
                close = chunkMapAccess.fabrolia$playerCloseEnoughForSpawning(islandPlayers.get(i), pos);
            }
            if (!close) continue;
            ChunkHolder holder = this.getVisibleChunkIfPresent(key);
            if (holder == null) continue;
            LevelChunk chunk = holder.getTickingChunk();
            if (chunk != null) spawningChunks.add(chunk);
        }
        Util.shuffle(spawningChunks, lvl.getRandom());

        // --- tickSpawningChunk per chunk ----------------------------------------
        it.unimi.dsi.fastutil.longs.LongOpenHashSet gate =
                fabrolia$buildSpawnGate(lvl, fabrolia$worldBorder, spawningChunks, spawningCategories);
        DistanceManager dm = this.distanceManager;
        com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onPass(lvl);
        for (int i = 0, n = spawningChunks.size(); i < n; i++) {
            LevelChunk chunk = spawningChunks.get(i);
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                long packed = chunk.getPos().pack();
                chunk.incrementInhabitedTime();
                if (dm.inEntityTickingRange(packed)) {
                    lvl.tickThunder(chunk);
                }
                if (gate != null && gate.contains(packed)) {
                    com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onChunkVisit(lvl, packed);
                    NaturalSpawner.spawnForChunk(lvl, chunk, state, spawningCategories);
                }
            });
        }

        // --- forEachBlockTickingChunk → tickChunk, this island's chunks -------
        it.unimi.dsi.fastutil.longs.LongArrayList servercoreChunks = island.servercoreRandomTickChunks;
        if (servercoreChunks != null) {
            // ServerCore mode: Main's per-island snapshot of ITS chunk source
            // (fabrolia$publishIslandRandomTickSnapshots); resolve live here.
            for (int i = 0, n = servercoreChunks.size(); i < n; i++) {
                long key = servercoreChunks.getLong(i);
                if (!laneKeys.test(key)) continue;
                ChunkHolder holder = this.getVisibleChunkIfPresent(key);
                if (holder == null) continue;
                LevelChunk chunk = holder.getTickingChunk();
                if (chunk == null) continue;
                RegionScheduler.runSuccessBodyIfHealthy(() -> lvl.tickChunk(chunk, tickSpeed));
            }
            return;
        }
        // Vanilla's forEachEntityTickingChunk walks one completed-wave snapshot
        // (DistanceManagerMixin); read the same snapshot through a page cursor
        // instead of a cancellable inEntityTickingRange call per chunk.
        com.axalotl.fabrolia.common.regions.CompletedChunkLevels.Reader entityTicking =
                ((com.axalotl.fabrolia.common.regions.CompletedSimulationView)
                        ((DistanceManagerAccessor) dm).fabrolia$simulationChunkTracker())
                        .fabrolia$completedLevels().reader();
        int shift = grid.shift;
        int side = 1 << shift;
        for (int c = 0, cellCount = ownCellList.size(); c < cellCount; c++) {
            long cell = ownCellList.getLong(c);
            int baseX = grid.regionX(cell) << shift;
            int baseZ = grid.regionZ(cell) << shift;
            for (int dz = 0; dz < side; dz++) {
                for (int dx = 0; dx < side; dx++) {
                    long key = ChunkPos.pack(baseX + dx, baseZ + dz);
                    if (!net.minecraft.server.level.ChunkLevel.isEntityTicking(entityTicking.get(key))) continue;
                    ChunkHolder holder = this.getVisibleChunkIfPresent(key);
                    if (holder == null) continue;
                    LevelChunk chunk = holder.getTickingChunk();
                    if (chunk == null) continue;
                    RegionScheduler.runSuccessBodyIfHealthy(() -> lvl.tickChunk(chunk, tickSpeed));
                }
            }
        }
    }
}
