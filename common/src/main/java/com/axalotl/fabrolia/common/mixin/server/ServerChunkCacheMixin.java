package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.HashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaSpawnContext;
import com.axalotl.fabrolia.common.utils.SpawnFutureTaker;
import com.axalotl.fabrolia.common.regions.ChunkRegionKeyed;
import com.axalotl.fabrolia.common.regions.ChunkTicketDebugAccess;
import com.axalotl.fabrolia.common.regions.CompletedSimulationView;
import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.IslandBlockUpdateAccess;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionGrid;
import com.axalotl.fabrolia.common.regions.Region;
import com.axalotl.fabrolia.common.regions.RegionTaskFanIn;

@Mixin(value = ServerChunkCache.class, priority = 1500)
public abstract class ServerChunkCacheMixin extends ChunkSource implements SpawnFutureTaker,
        IslandBlockUpdateAccess, ChunkTicketDebugAccess {

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
    @Shadow public abstract void tickSpawningChunk(LevelChunk chunk, long timeDiff, List<MobCategory> spawningCategories, NaturalSpawner.SpawnState spawnCookie);


    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$replaceWithConcurrentSet(CallbackInfo ci) {
        this.chunkHoldersToBroadcast = ConcurrentHashMap.newKeySet();
        // Hand the ticket storage its serialization monitor (the ChunkMap instance)
        // — see TicketStorageSyncMixin for the race this closes.
        ((com.axalotl.fabrolia.common.regions.TicketLockBind) this.ticketStorage)
                .fabrolia$setTicketLock(this.chunkMap);
    }

    @Override
    public int fabrolia$liveSimulationLevel(long chunkKey) {
        synchronized (this.chunkMap) {
            SimulationChunkTracker tracker =
                    ((DistanceManagerAccessor) this.distanceManager)
                            .fabrolia$simulationChunkTracker();
            return tracker.getLevel(ChunkPos.unpack(chunkKey));
        }
    }

    @Override
    public String fabrolia$ticketDebug(long chunkKey) {
        synchronized (this.chunkMap) {
            SimulationChunkTracker tracker =
                    ((DistanceManagerAccessor) this.distanceManager)
                            .fabrolia$simulationChunkTracker();
            int liveLevel = tracker.getLevel(ChunkPos.unpack(chunkKey));
            int completedLevel =
                    ((CompletedSimulationView) tracker).fabrolia$completedLevel(chunkKey);
            ChunkHolder holder = this.getVisibleChunkIfPresent(chunkKey);
            int targetX = ChunkPos.getX(chunkKey);
            int targetZ = ChunkPos.getZ(chunkKey);
            int bestPropagatedLevel = Integer.MAX_VALUE;
            int retainingCount = 0;
            StringBuilder retainers = new StringBuilder();
            for (int dx = -32; dx <= 32; dx++) {
                for (int dz = -32; dz <= 32; dz++) {
                    int sourceX = targetX + dx;
                    int sourceZ = targetZ + dz;
                    int distance = Math.max(Math.abs(dx), Math.abs(dz));
                    long sourceKey = ChunkPos.pack(sourceX, sourceZ);
                    for (Ticket ticket : this.ticketStorage.getTickets(sourceKey)) {
                        int propagatedLevel = ticket.getTicketLevel() + distance;
                        bestPropagatedLevel = Math.min(
                                bestPropagatedLevel, propagatedLevel);
                        if (!ChunkLevel.isEntityTicking(propagatedLevel)) {
                            continue;
                        }
                        retainingCount++;
                        if (retainingCount <= 64) {
                            if (!retainers.isEmpty()) {
                                retainers.append("; ");
                            }
                            retainers.append('[').append(sourceX).append(',')
                                    .append(sourceZ).append("] d=").append(distance)
                                    .append(' ').append(ticket)
                                    .append(" => ").append(propagatedLevel);
                        }
                    }
                }
            }
            if (retainingCount > 64) {
                retainers.append("; ... +").append(retainingCount - 64);
            }
            return "liveLevel=" + liveLevel
                    + ", completedLevel=" + completedLevel
                    + ", holderTicketLevel="
                    + (holder == null ? "null" : holder.getTicketLevel())
                    + ", holderTicking="
                    + (holder != null && holder.getTickingChunk() != null)
                    + ", directLoad="
                    + this.ticketStorage.getTicketDebugString(chunkKey, false)
                    + ", directSimulation="
                    + this.ticketStorage.getTicketDebugString(chunkKey, true)
                    + ", directAll="
                    + List.copyOf(this.ticketStorage.getTickets(chunkKey))
                    + ", bestPropagatedLevel="
                    + (bestPropagatedLevel == Integer.MAX_VALUE
                    ? "none" : bestPropagatedLevel)
                    + ", retainers(" + retainingCount + ")=[" + retainers + ']';
        }
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
            var holders = new ArrayList<>(this.chunkHoldersToBroadcast);
            if (!holders.isEmpty()) {
                RegionScheduler.runGlobalMainBatch(this.level.getServer(), () -> {
                    for (ChunkHolder holder : holders) {
                        RegionScheduler.runGlobalMainBody(this.level.getServer(),
                                () -> fabrolia$publishChangedHolder(holder));
                    }
                });
            }
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
                && !com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .inExactPlayerActor()
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
    /** Previous tick's chunkGetter — a stateless method ref over this chunk source,
     *  identical every tick; cached so the tick-head precompute can use it. */
    @Unique private volatile NaturalSpawner.ChunkGetter fabrolia$lastChunkGetter;
    @Unique private volatile CompletableFuture<Void> fabrolia$blockTicksFuture;
    @Unique private long fabrolia$capturedTimeDiff;
    @Unique private boolean fabrolia$spawnLaunchedThisTick;


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
        RegionizedServer rs = RegionScheduler.forLevel(this.level);
        if (rs == null) return null;
        // Metadata only: submit every arm inline before entity dispatch. A global
        // gameplay task is neither needed nor authority for this publication.
        return fabrolia$groupAndSubmit(rs, chunks, action);
    }


    /**
     * Vanilla 26.2 {@code tickSpawningChunk} gates {@code spawnForChunk} on
     * {@code level.canSpawnEntitiesInChunk} (world border + entity-manager position-tickable).
     * That reads plain PESM maps which main mutates while pumping the spawn barrier, so
     * evaluate it ON MAIN here and hand the workers an immutable position set (published
     * through the task submission). {@code null} = spawning categories empty — vanilla skips
     * spawning entirely that tick (thunder still runs).
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
            ServerLevel lvl, List<LevelChunk> chunks, List<MobCategory> categories) {
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
        net.minecraft.world.level.border.WorldBorder wb = lvl.getWorldBorder();
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
                                                         Consumer<LevelChunk> action) {
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
            // A2 deadline drive (rs.armGate): global = arm + main awaits;
            // island token = arm, unit is the barrier; skipped = the prior arm
            // is still owed.  The island token also reserves this exact task
            // until its run/cancel terminal, so a late dispatch-snapshot arrival
            // cannot be followed by a second physical arm.
            IslandRegistry.ArmGate gate = rs.armGate(r);
            if (gate.isSkipped()) {
                continue;
            }
            ArrayList<LevelChunk> list = entry.getValue();
            if (gate.isIslandArm()) {
                Runnable body = () -> fabrolia$runChunksSerial(list, action);
                Runnable regionTask = RegionizedServer.armReservedTask(gate, body);
                fabrolia$enqueueIslandArmTask(r, regionTask,
                        "island chunk task rejected at scheduler drain boundary");
                continue;
            }
            CompletableFuture<Void> cf = new CompletableFuture<>();
            if (!RegionScheduler.enqueueRegionTask(r, () -> {
                try {
                    fabrolia$runChunksSerial(list, action);
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
            // A2 gate (2026-07-13 wedge fix): block ticks get the SAME island
            // treatment as the spawn path above. This dispatch used to submit
            // cf-wrapped tasks into ISLAND region queues and main AWAITED the
            // cf — which silently depended on out-of-unit claim-drains (the
            // exact ownership race the 2026-07-13 fixes close: island random
            // ticks ran at MAIN cadence, claim-free reroutes tripped the
            // cross-domain detector on vanilla snow/vine/amethyst ticks, and
            // once refused, main wedged on the cf until the watchdog fired).
            // Island token: futureless — the island's next unit drains it in queue
            // FIFO ([spawn][random ticks] before entities) at ITS cadence on
            // ITS clock. Skipped: the island still owes its previous arm.
            // Global: pre-A2 await.
            IslandRegistry.ArmGate gate = rs.armGate(r);
            if (gate.isSkipped()) {
                continue;
            }
            final CompletableFuture<Void> cf = gate.isIslandArm()
                    ? null : new CompletableFuture<>();
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
                    fabrolia$runChunksSerial(list, action);
                } catch (Throwable failure) {
                    Throwable winner = cf == null
                            ? fabrolia$publishFatalAndSelectWinner(failure)
                            : fabrolia$completeFutureAfterFatalPublication(cf, failure);
                    ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(winner);
                }
                if (cf != null) {
                    fabrolia$completeFutureAfterHealthyCommit(
                            cf, null,
                            "region random-tick work completed more than once");
                }
            };
            Runnable regionTask = gate.isIslandArm()
                    ? RegionizedServer.armReservedTask(gate, body) : body;
            if (gate.isIslandArm()) {
                fabrolia$enqueueIslandArmTask(r, regionTask,
                        "random-tick task rejected at scheduler drain boundary");
            } else if (!RegionScheduler.enqueueRegionTask(r, regionTask)) {
                throw new IllegalStateException(
                        "random-tick task rejected at scheduler drain boundary");
            }
            if (cf != null) futures.add(cf);
        }
        RegionScheduler.wakeWorkers();
        return futures.isEmpty() ? null
                : CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    /**
     * Publish a gate-1 task or close its exact reservation before propagating
     * the rejection.  This helper is private because static mixin helpers are
     * merged into the target class and Mixin forbids non-private static methods.
     */
    @Unique
    private static void fabrolia$enqueueIslandArmTask(
            Region region, Runnable task, String rejectionMessage) {
        Throwable failure = null;
        boolean accepted = false;
        try {
            accepted = RegionScheduler.enqueueRegionTask(region, task);
        } catch (Throwable publicationFailure) {
            failure = publicationFailure;
        }
        if (accepted) return;
        if (failure == null) failure = new IllegalStateException(rejectionMessage);
        if (task instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
            try {
                cancellable.cancelQueuedBeforeStartWithFailure(failure);
            } catch (Throwable terminalFailure) {
                failure = fabrolia$mergeFailure(failure, terminalFailure);
            }
        } else {
            failure = fabrolia$mergeFailure(failure, new IllegalStateException(
                    "island arm task lost its cancellation terminal"));
        }
        ServerChunkCacheMixin.<RuntimeException>fabrolia$rethrow(failure);
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
        if (Thread.currentThread() == this.mainThread
                || com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .inExactPlayerActor()) return;

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

    /** One main-thread request submission. No path may create a successor for
     *  the same entered obligation. */
    @Unique
    private CompletableFuture<ChunkResult<ChunkAccess>> fabrolia$submitMainThreadRequest(
            int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate) {
        CompletableFuture<ChunkResult<ChunkAccess>> chunkFuture = new CompletableFuture<>();
        FabroliaChunkMainPublication publication = new FabroliaChunkMainPublication();
        try {
            this.mainThreadProcessor.schedule(() -> fabrolia$runPublishedChunkMainRequest(
                    publication, chunkFuture, () -> {
                this.getChunkFutureMainThread(x, z, targetStatus, loadOrGenerate).whenComplete((result, ex) -> {
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
            // schedule may reject, or may enqueue and then throw. The request
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
        if (Thread.currentThread() == this.mainThread
                || com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .inExactPlayerActor()) return;

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

    /**
     * Vanilla getChunk/getChunkNow use a direct physical-thread comparison before
     * entering their main-only branch. During an exclusive actor turn that actor is
     * the logical main, so substitute the captured physical-main reference only for
     * those two exact comparisons; no field is mutated and other threads are unchanged.
     */
    @ModifyExpressionValue(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;",
                    ordinal = 0),
            require = 1,
            allow = 1)
    private Thread fabrolia$logicalPlayerActorGetChunkThread(Thread original) {
        return com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .inExactPlayerActor() ? this.mainThread : original;
    }

    @ModifyExpressionValue(
            method = "getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                    + "Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;",
                    ordinal = 0),
            require = 1,
            allow = 1)
    private Thread fabrolia$logicalPlayerActorGetChunkFutureThread(Thread original) {
        return com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .inExactPlayerActor() ? this.mainThread : original;
    }

    @ModifyExpressionValue(
            method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;",
                    ordinal = 0),
            require = 1,
            allow = 1)
    private Thread fabrolia$logicalPlayerActorGetChunkNowThread(Thread original) {
        return com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .inExactPlayerActor() ? this.mainThread : original;
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
                        + " thread=" + Thread.currentThread().getName());
        if (outcome instanceof com.axalotl.fabrolia.common.regions.ChunkBridgeWait.Result.Ready<ChunkResult<ChunkAccess>, ChunkAccess> ready) {
            return ready.chunk();
        }
        if (outcome instanceof com.axalotl.fabrolia.common.regions.ChunkBridgeWait.Result.GiveUpNull<ChunkResult<ChunkAccess>, ChunkAccess>) {
            // load=false only — the vanilla @Nullable contract; memoized so
            // the next lookups at this strand answer instantly for the TTL.
            fabrolia$softNullUntil.put(pos, System.nanoTime() + SOFT_NULL_TTL_NS);
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
        throw fabrolia$missingChunk(result);
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
    private static IllegalStateException fabrolia$missingChunk(@Nullable ChunkResult<?> result) {
        String error = result == null ? "unknown fabrolia chunk result" : result.getError();
        return (IllegalStateException) Util.pauseInIde(
                new IllegalStateException("Chunk not there when requested: " + error));
    }

    @Unique
    private static ChunkAccess fabrolia$unwrap(ChunkAccess chunk) {
        if (chunk instanceof ImposterProtoChunk imposter) {
            return imposter.getWrapped();
        }
        return chunk;
    }


    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;")
    )
    private NaturalSpawner.SpawnState fabrolia$captureTimeAndPassthrough(
            int spawnableChunkCount, Iterable<Entity> entities, NaturalSpawner.ChunkGetter chunkGetter,
            LocalMobCapCalculator localMobCapCalculator, Operation<NaturalSpawner.SpawnState> original,
            ProfilerFiller profiler, long timeDiff) {
        fabrolia$capturedTimeDiff = timeDiff;
        fabrolia$spawnLaunchedThisTick = false;
        fabrolia$lastChunkGetter = chunkGetter;
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
        // Surface the level to ParallelSpawnHelper so it can read the incremental SpawnIndex
        // instead of iterating the c2me-notickvd-filtered entity stream — the section walk
        // dominates fillBuffer (4.14% of server thread in R5FaKJnHJg).
        NaturalSpawner.SpawnState[] result = new NaturalSpawner.SpawnState[1];
        RegionScheduler.runGlobalMainBody(this.level.getServer(), () -> {
            FabroliaSpawnContext.currentLevel = this.level;
            try {
                result[0] = original.call(spawnableChunkCount, entities, chunkGetter,
                        localMobCapCalculator);
            } finally {
                FabroliaSpawnContext.currentLevel = null;
            }
        });
        return result[0];
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;tickSpawningChunk(Lnet/minecraft/world/level/chunk/LevelChunk;JLjava/util/List;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;)V")
    )
    private void fabrolia$interceptTickSpawning(ServerChunkCache instance, LevelChunk chunk,
                                             long timeDiff, List<MobCategory> spawningCategories,
                                             NaturalSpawner.SpawnState spawnCookie,
                                             Operation<Void> original) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaSpawn
                || !FabroliaConfig.enableRegionThreading || !RegionScheduler.isActive()) {
            RegionScheduler.runGlobalMainBody(this.level.getServer(),
                    () -> original.call(instance, chunk, timeDiff, spawningCategories, spawnCookie));
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
        // threading. Copy while the list is still populated.
        List<LevelChunk> list = new java.util.ArrayList<>(live);

        long capturedTimeDiff = fabrolia$capturedTimeDiff;

        // Region mode publishes metadata now; its coordinator enters the native
        // chunk bodies later, in spawn -> random ticks -> entities FIFO order.
        ServerLevel lvl = this.level;
        DistanceManager dm = this.distanceManager;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet spawnGate =
                fabrolia$buildSpawnGate(lvl, list, spawningCategories);
        fabrolia$deferredSpawnArm = () -> {
            com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onPass(lvl);
            fabrolia$spawnFuture = fabrolia$submitPerRegion(list, c -> {
                long packed = c.getPos().pack();
                c.incrementInhabitedTime(capturedTimeDiff);
                // Native 26.2 tickSpawningChunk performs thunder before spawning.
                if (dm.inEntityTickingRange(packed)) {
                    lvl.tickThunder(c);
                }
                if (spawnGate != null && spawnGate.contains(packed)) {
                    com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onChunkVisit(lvl, packed);
                    NaturalSpawner.spawnForChunk(lvl, c, spawnCookie, spawningCategories);
                }
            });
        };
    }


    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;collectSpawningChunks(Ljava/util/List;)V"),
            require = 1, allow = 1)
    private void fabrolia$admitSpawnCandidates(ChunkMap instance, List<LevelChunk> chunks,
                                             Operation<Void> original) {
        // The native distance predicates observe live players, unlike the later
        // position-only grouping and arm publication.
        RegionScheduler.runGlobalMainBody(this.level.getServer(),
                () -> original.call(instance, chunks));
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;tickCustomSpawners(Z)V"),
            require = 1, allow = 1)
    private void fabrolia$admitCustomSpawners(ServerLevel instance, boolean spawnEnemies,
                                            Operation<Void> original) {
        RegionScheduler.runGlobalMainBody(this.level.getServer(),
                () -> original.call(instance, spawnEnemies));
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V")
    )
    private void fabrolia$waitSpawnThenBlockTick(ChunkMap instance, Consumer<LevelChunk> tickingChunkConsumer,
                                              Operation<Void> original) {

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
            if (!tickChunks.isEmpty()) {
                // DEFERRED ARM — see fabrolia$deferredBlockTicksArm.
                fabrolia$deferredBlockTicksArm = () -> {
                    fabrolia$blockTicksFuture = fabrolia$submitPerRegion(tickChunks, tickingChunkConsumer);
                };
            }
            return;
        }

        // Regions off, or random-tick dispatch disabled: retain the real native
        // traversal and consumer, synchronously under a pre-body global envelope.
        RegionScheduler.runGlobalMainBody(this.level.getServer(),
                () -> original.call(instance, tickingChunkConsumer));
    }

    /** Start spawn-gate precomputation at the unconditional chunk-source head. */
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("HEAD"))
    private void fabrolia$kickSpawnPrecompute(CallbackInfo ci) {
        if (!FabroliaConfig.disabled && FabroliaConfig.enableFabroliaSpawn
                && RegionScheduler.isActive()) {
            com.axalotl.fabrolia.common.parallelised.utils.ParallelSpawnHelper
                    .precompute(this.level, fabrolia$lastChunkGetter);
        }
    }

    @WrapOperation(
            method = "tick(Ljava/util/function/BooleanSupplier;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;tick()V"))
    private void fabrolia$trackerOnMain(ChunkMap instance, Operation<Void> original) {
        // Tracking reads live entities across islands. Its native owner remains
        // physical Main; serial VMP helpers inherit this pre-body authority.
        RegionScheduler.runGlobalMainBody(this.level.getServer(), () -> original.call(instance));
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
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
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
        // Only region-owned obligations remain. Use their control/native-progress
        // barrier, not the removed detached flat-executor waiter.
        ParallelProcessor.pumpUntilDone(future);
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


    /** Select the shared generation winner before publishing a local terminal. */
    @Unique
    private static Throwable fabrolia$publishFatalAndSelectWinner(Throwable candidate) {
        if (candidate == null) {
            candidate = new NullPointerException("chunk work failure");
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
}
