package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaSpawnContext;
import com.axalotl.fabrolia.common.utils.SpawnFutureTaker;
import com.axalotl.fabrolia.common.regions.ChunkRegionKeyed;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionGrid;
import com.axalotl.fabrolia.common.regions.Region;

@Mixin(value = ServerChunkCache.class, priority = 1500)
public abstract class ServerChunkCacheMixin extends ChunkSource implements SpawnFutureTaker {

    @Shadow @Final public ChunkMap chunkMap;
    @Shadow @Final
    private Thread mainThread;
    @Shadow @Final public ServerChunkCache.MainThreadExecutor mainThreadProcessor;
    @Shadow @Final private DistanceManager distanceManager;
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
    }

    /**
     * Ticket-consolidation read-side lock. {@code runDistanceManagerUpdates} drains the
     * Simulation/Loading {@code ChunkTracker} fixpoint and reads {@link
     * net.minecraft.world.level.TicketStorage} ({@code getTicketLevelAt → getLowestTicket})
     * — a plain {@code Long2ObjectOpenHashMap<List<Ticket>>} that vanilla only ever touches
     * on the server thread.
     *
     * <p>With {@code enableRegionPlayerTicks}, players (incl. the 50+ Carpet fake players)
     * tick on region workers, and {@code ServerPlayer.tick → ServerChunkCache.move →
     * ChunkMap.move} WRITES that same machinery off-main: {@code DistanceManager.addPlayer/
     * removePlayer → TicketStorage.addTicket/removeTicket} (grows/shrinks the per-chunk
     * {@code List<Ticket>} and feeds {@code ChunkTracker.update}). {@link
     * com.axalotl.fabrolia.common.mixin.server.ChunkMapMixin#fabrolia$syncPlayerMove} already
     * serialises those writes on the {@code ChunkMap} instance monitor, but the consolidation
     * READ ran lock-free — a worker's {@code List.add} grow racing main's {@code
     * getLowestTicket} iteration surfaced a transient null element ("Cannot invoke
     * Ticket.getType() because ticket is null", crash 2026-06-25), and the tracker's pending
     * {@code DynamicGraphMinFixedPoint} queue raced {@code update} vs {@code runUpdates}.
     *
     * <p>Take the SAME {@code ChunkMap} monitor here so move-writes and consolidation-reads
     * are mutually exclusive — restoring vanilla's single-thread invariant for the whole
     * ticket/tracker cycle without changing its semantics. Only engaged while players
     * actually tick off-main; the main-only path keeps vanilla's lock-free consolidation.
     * No deadlock: {@code move} holds the monitor for bounded, non-blocking section
     * bookkeeping (never parks on a chunk load), and consolidation under the monitor
     * schedules but never blocks on a worker.
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
                || !FabroliaConfig.enableRegionPlayerTicks
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
    @Unique private volatile CompletableFuture<Void> fabrolia$trackerFuture;
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

    /**
     * Region-mode helper: group {@code chunks} by owning region and submit one task per
     * region running {@code action} over its chunks. Tasks land in the region queue AHEAD
     * of the entity task the entity phase will submit later — per-region FIFO restores
     * vanilla's per-area phase order (spawn → random ticks → entities) that the flat-pool
     * pipeline deliberately relaxed. Regions are armed immediately, so this work overlaps
     * main's remaining chunk-phase serial body (broadcast etc.).
     */
    /** SERIAL-per-region chunk runner for the SPAWN arm (task #32 root cause 3):
     *  vanilla's spawning-chunk loop is SEQUENTIAL — each chunk's local-cap
     *  check sees every earlier chunk's spawns. Fanning the spawn action across
     *  workers (runChunksMaybeParallel) let a whole region's chunks read the
     *  count in one open window and overshoot the per-player local cap by a
     *  pack per lane. spawnForChunk per chunk is cheap dice rolls (the heavy
     *  random-tick lane keeps its fan-out, and REGIONS still run in parallel
     *  to each other — no main-thread bottleneck), so the spawn arm runs its
     *  region's chunks serially: local-cap visibility inside a region is
     *  vanilla-sequential, and the per-candidate re-check (SpawnStateMixin)
     *  bounds the residual cross-region window to ~one candidate per region. */
    @Unique
    private static void fabrolia$runChunksSerial(ArrayList<LevelChunk> list,
                                                 Consumer<LevelChunk> rawAction) {
        for (int i = 0, n = list.size(); i < n; i++) {
            LevelChunk c = list.get(i);
            try {
                if (((ServerLevel) c.getLevel()).shouldTickBlocksAt(c.getPos().pack())) {
                    rawAction.accept(c);
                }
            } catch (Exception e) {
                LOGGER.error("Region spawn task failed for {}", c.getPos(), e);
            }
        }
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
        RegionizedServer rs =
                RegionScheduler.forLevel(this.level);
        if (rs == null) return null;
        // Offload the whole grouping+submission to a pool worker: the per-chunk grid-key
        // bucketing over hundreds of ticking chunks ×2 phases cost main 4.8% of the
        // server thread (02:20 profile). Main only allocates the outer future; the
        // global task groups, submits per-region tasks, and forwards completion.
        final List<LevelChunk> chunksCopy = new ArrayList<>(chunks);
        // R4 color gate: every region's work for the epoch must be submitted
        // BEFORE entity dispatch (a region armed after dispatch never gets an
        // entity phase and would wedge the color DAG). Grouping is cheap now
        // (cached region keys), so run it inline instead of offloading.
        if (FabroliaConfig.useRegionColorGate()) {
            return fabrolia$groupAndSubmit(rs, chunksCopy, action, serialChunks);
        }
        CompletableFuture<Void> outer = new CompletableFuture<>();
        RegionScheduler.enqueueGlobalMainSafe(() -> {
            try {
                CompletableFuture<Void> inner = fabrolia$groupAndSubmit(rs, chunksCopy, action, serialChunks);
                if (inner == null) {
                    outer.complete(null);
                } else {
                    inner.whenComplete((v, t) -> outer.complete(null));
                }
            } catch (Throwable t) {
                LOGGER.error("Region chunk grouping failed", t);
                outer.complete(null);
            }
        });
        RegionScheduler.wakeWorkers();
        return outer;
    }

    /**
     * Run one region's chunk work (spawn or random ticks), fanning out across region
     * workers via the region's help-queue when the chunk list is large. A dense region's
     * 64-chunk spawnForChunk chain ran serially on the claiming worker and WAS the
     * entity-barrier tail (~18% of main parked, 02:59 profile, workers 82% idle).
     * Same protocol as RegionizedServer.parallelTickOwned: helpers execute under this
     * region's claim-holder with currentRegion set (region-lock discipline), the
     * coordinator self-drains leftovers (progress guaranteed solo), region-local latch,
     * no nesting of claims — no cycle.
     */
    @Unique
    private static void fabrolia$runChunksMaybeParallel(Region region,
                                                     ArrayList<LevelChunk> list,
                                                     Consumer<LevelChunk> rawAction) {
        // Unload guard: the ticking-chunk list is a tick-start snapshot, but these tasks
        // run inside the parallel window where main's barrier pump executes chunk-system
        // tasks — a chunk can drop out of block-ticking range (unload pipeline starting)
        // mid-window. Mutating it then races c2me's serialization (live "chunks corrupted
        // / crashes when coming back" reports). shouldTickBlocksAt is the same ticket
        // gate vanilla builds the list from; a chunk that lost it is skipped — identical
        // observable to the list having been built a moment later.
        final Consumer<LevelChunk> action = c -> {
            if (!((ServerLevel) c.getLevel()).shouldTickBlocksAt(c.getPos().pack())) {
                return;
            }
            rawAction.accept(c);
        };
        int n = list.size();
        // Main-steal compatibility: when the barrier pump (main thread) drains this
        // region's queue via RegionScheduler.stealOneRegionTask, run INLINE — the
        // fan-out path below parks on a latch for help batches, and main parked there
        // stops draining mainThreadProcessor (a helper hitting a Tier-4 getChunk would
        // then wait on main forever — cycle). Workers keep the fan-out.
        if (n <= 16 || !(Thread.currentThread() instanceof com.axalotl.fabrolia.common.regions.RegionWorkerThread)) {
            for (int i = 0; i < n; i++) {
                try {
                    action.accept(list.get(i));
                } catch (Exception e) {
                    LOGGER.error("Region chunk task failed for {}", list.get(i).getPos(), e);
                }
            }
            return;
        }
        int workers = Math.max(1, RegionScheduler.workerCount());
        int batchSize = Math.max(8, (n + workers - 1) / workers);
        int batchCount = (n + batchSize - 1) / batchSize;
        AtomicInteger latch =
                new AtomicInteger(batchCount);
        for (int b = 1; b < batchCount; b++) {
            final int from = b * batchSize;
            final int to = Math.min(from + batchSize, n);
            region.helpQueue.add(() -> {
                try {
                    for (int i = from; i < to; i++) {
                        try {
                            action.accept(list.get(i));
                        } catch (Exception e) {
                            LOGGER.error("Region chunk task failed for {}", list.get(i).getPos(), e);
                        }
                    }
                } finally {
                    latch.decrementAndGet();
                }
            });
            RegionScheduler.enqueueReady(region);
        }
        if (batchCount > 1) RegionScheduler.wakeWorkers();
        try {
            int to = Math.min(batchSize, n);
            for (int i = 0; i < to; i++) {
                try {
                    action.accept(list.get(i));
                } catch (Exception e) {
                    LOGGER.error("Region chunk task failed for {}", list.get(i).getPos(), e);
                }
            }
        } finally {
            latch.decrementAndGet();
        }
        Runnable help;
        while ((help = region.helpQueue.poll()) != null) {
            help.run();
        }
        long spinUntil = System.nanoTime() + 30_000L;
        while (latch.get() > 0) {
            if (System.nanoTime() < spinUntil) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(25_000L);
            }
        }
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
                        Class<?> configClass = Class.forName("me.wesley1808.servercore.common.config.Config");
                        fabrolia$servercoreConfigGet = configClass.getMethod("get");
                        Class<?> mainConfigClass = Class.forName("me.wesley1808.servercore.common.config.MainConfig");
                        fabrolia$servercoreFeatures = mainConfigClass.getMethod("features");
                        Class<?> featureConfigClass = Class.forName("me.wesley1808.servercore.common.config.data.FeatureConfig");
                        fabrolia$servercoreRandomTickDistance = featureConfigClass.getMethod("chunkTickDistanceAffectsRandomTicks");
                    } catch (ClassNotFoundException absent) {
                        // ServerCore not present.
                    } catch (Throwable t) {
                        fabrolia$servercoreWarned = true;
                        LOGGER.warn("[region-ticking] could not inspect ServerCore random-tick-distance setting; disabling incremental chunk list", t);
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
        } catch (Throwable t) {
            if (!fabrolia$servercoreWarned) {
                fabrolia$servercoreWarned = true;
                LOGGER.warn("[region-ticking] failed to query ServerCore random-tick-distance setting; disabling incremental chunk list", t);
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
        // The map and per-region lists MUST be fresh per call: spawn-phase and
        // block-ticks-phase groupings are independently offloaded to the global
        // pool (fabrolia$submitPerRegion) and can run CONCURRENTLY on this instance,
        // and under barrier overlap a region task may outlive the tick that
        // submitted it. A reused-scratch variant (2026-06-12) had region tasks
        // iterating lists the other phase's grouping was clearing/refilling —
        // 800k+ IndexOutOfBounds in workers, chunk batches silently skipped.
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
            // A2 deadline drive (rs.armGate): 0 = arm + main awaits (global
            // lane / async off — pre-A2 behavior); 1 = arm, unit is the
            // barrier (main must never wait on a lagging island's queue);
            // 2 = the island still owes its previous arm — skip this cycle
            // (vanilla-lag semantics: missed spawn/random cycles, never
            // accumulated double-drains).
            int gate = rs.armGate(r);
            if (gate == 2) {
                continue;
            }
            ArrayList<LevelChunk> list = entry.getValue();
            if (gate == 1) {
                r.queue().submit(serialChunks
                        ? () -> fabrolia$runChunksSerial(list, action)
                        : () -> fabrolia$runChunksMaybeParallel(r, list, action));
                RegionScheduler.enqueueReady(r);
                continue;
            }
            CompletableFuture<Void> cf = new CompletableFuture<>();
            r.queue().submit(() -> {
                try {
                    if (serialChunks) {
                        fabrolia$runChunksSerial(list, action);
                    } else {
                        fabrolia$runChunksMaybeParallel(r, list, action);
                    }
                } finally {
                    cf.complete(null);
                }
            });
            RegionScheduler.enqueueReady(r);
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
            // gate 1: futureless — the island's next unit drains it in queue
            // FIFO ([spawn][random ticks] before entities) at ITS cadence on
            // ITS clock. gate 2: the island still owes its previous arm —
            // vanilla-lag semantics, skip. gate 0: global lane, pre-A2 await.
            int gate = rs.armGate(r);
            if (gate == 2) {
                continue;
            }
            final CompletableFuture<Void> cf = gate == 0 ? new CompletableFuture<>() : null;
            r.queue().submit(() -> {
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
                    fabrolia$runChunksMaybeParallel(r, list, action);
                } finally {
                    if (cf != null) cf.complete(null);
                }
            });
            RegionScheduler.enqueueReady(r);
            if (cf != null) futures.add(cf);
        }
        RegionScheduler.wakeWorkers();
        return futures.isEmpty() ? null
                : CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    @Unique private static final long INITIAL_PARK_NS = 50_000L;
    @Unique private static final long MAX_PARK_NS = 1_000_000L;


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

        // Tier 3: miss path — let vanilla's main-thread helper decide whether load=false
        // may use an existing eligible holder or load=true must add a ticket.
        // SAFE despite Server-thread parking in ParallelProcessor.pumpUntilDone because
        // pumpUntilDone now drains mainThreadProcessor.pollTask() per level while waiting
        // (chunk-system tasks only — never MinecraftServer.pollTask which can run Carpet
        // bot-kill / mod tasks that re-enter ChunkMap monitor). See pumpUntilDone comment.
        CompletableFuture<ChunkResult<ChunkAccess>> chunkFuture = new CompletableFuture<>();
        this.mainThreadProcessor.execute(() -> {
            try {
                this.getChunkFutureMainThread(x, z, targetStatus, loadOrGenerate).whenComplete((result, ex) -> {
                    if (ex != null) {
                        chunkFuture.completeExceptionally(ex);
                        return;
                    }
                    chunkFuture.complete(result);
                });
            } catch (Throwable t) {
                chunkFuture.completeExceptionally(t);
            }
        });
        cir.setReturnValue(fabrolia$awaitMainThreadResult(chunkFuture, pos, targetStatus, loadOrGenerate));
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

    @Unique
    private @Nullable ChunkAccess fabrolia$awaitMainThreadResult(CompletableFuture<ChunkResult<ChunkAccess>> chunkFuture,
                                                              long pos,
                                                              ChunkStatus status,
                                                              boolean loadOrGenerate) {
        long sleepNs = INITIAL_PARK_NS;
        long stuckSince = System.nanoTime();
        int stuck = 0;
        while (!chunkFuture.isDone()) {
            ChunkHolder holder = this.getVisibleChunkIfPresent(pos);
            if (holder != null) {
                ChunkAccess ready = fabrolia$extractReady(holder, status);
                if (ready != null) return ready;
            }
            // Task #32 forensics: this park is only deadlock-free while NOTHING
            // main can block on is held by this thread (the nav-monitor cycle
            // fixed by NavRecompute was exactly such a hold). A wait this long
            // means main is not reaching a pump point — dump who we are and
            // what we wait for BEFORE the watchdog kills the server silently.
            if (System.nanoTime() - stuckSince > 10_000_000_000L) {
                stuckSince = System.nanoTime();
                org.slf4j.LoggerFactory.getLogger("Fabrolia-ChunkBridge").error(
                        "[fabrolia] awaitMainThreadResult stuck {}x10s: thread={} chunk=[{}, {}] status={} load={} "
                        + "— main is not servicing mainThreadProcessor; if this thread holds a game monitor "
                        + "(nav/entity), that is a lock-order bug",
                        ++stuck, Thread.currentThread().getName(),
                        ChunkPos.getX(pos), ChunkPos.getZ(pos), status, loadOrGenerate,
                        new Throwable("awaitMainThreadResult wait site"));
            }
            LockSupport.parkNanos(sleepNs);
            sleepNs = Math.min(sleepNs << 1, MAX_PARK_NS);
        }

        ChunkResult<ChunkAccess> result = chunkFuture.join();
        ChunkAccess chunk = fabrolia$extractFromResult(result);
        if (chunk != null) return chunk;

        ChunkHolder holder = this.getVisibleChunkIfPresent(pos);
        if (holder != null) {
            ChunkAccess ready = fabrolia$extractReady(holder, status);
            if (ready != null) return ready;
        }
        if (loadOrGenerate) {
            throw fabrolia$missingChunk(result);
        }
        return null;
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
        FabroliaSpawnContext.currentLevel = this.level;
        try {
            return original.call(spawnableChunkCount, entities, chunkGetter, localMobCapCalculator);
        } finally {
            FabroliaSpawnContext.currentLevel = null;
        }
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
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaSpawn) {
            original.call(instance, chunk, timeDiff, spawningCategories, spawnCookie);
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

        int n = list.size();
        long capturedTimeDiff = fabrolia$capturedTimeDiff;

        // Region mode: spawn per-chunk on the OWNING region's worker. Mob-cap machinery is
        // untouched — global per-category counts are atomic and the SpawnState cookie's
        // checks behave identically from any pool; only the executor changes (region worker
        // = Tier-1 local chunk access) and the await point (rides to the entity barrier,
        // where per-region FIFO puts spawn ahead of that region's random ticks + entities —
        // vanilla's per-area phase order, restored rather than relaxed).
        if (FabroliaConfig.enableRegionThreading
                && RegionScheduler.isActive()) {
            ServerLevel lvl0 = this.level;
            DistanceManager dm0 = this.distanceManager;
            it.unimi.dsi.fastutil.longs.LongOpenHashSet gate0 =
                    fabrolia$buildSpawnGate(lvl0, list, spawningCategories);
            // DEFERRED ARM (see fabrolia$deferredSpawnArm): collected on main now, submitted
            // to the region queues at entity-phase start — after main's runBlockEvents —
            // so spawn/thunder/inhabited-time writers never overlap piston moveBlocks.
            fabrolia$deferredSpawnArm = () -> {
                com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onPass(lvl0);
                // serialChunks=true (task #32): vanilla-sequential local-cap
                // visibility within each region; regions stay parallel.
                fabrolia$spawnFuture = fabrolia$submitPerRegion(list, c -> {
                    long packed = c.getPos().pack();
                    c.incrementInhabitedTime(capturedTimeDiff);
                    // Vanilla 26.2 moved thunder OUT of tickChunk INTO tickSpawningChunk —
                    // replicate it here or thunderstorms never strike (no lightning, no
                    // skeleton-trap horses). Same worker safety envelope as spawnForChunk:
                    // region-local reads + addFreshEntity; level.random is worker-striped.
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

        // ONE serial off-main task (task #32 root cause 3): the old poolSize*4 batch
        // fan-out let every batch read the per-player local-cap counts in the same
        // open window — whole-neighborhood pack bursts past the cap (vanilla's
        // sequential spawning-chunk loop is what bounds overshoot to ~one pack).
        // spawnForChunk is cheap dice rolls per chunk (the heavy random-tick lane
        // keeps its own fan-out), so a single worker walking the list serially
        // restores vanilla local-cap visibility at negligible wall cost, still off
        // main, still riding to the entity barrier.
        ServerLevel lvl = this.level;
        DistanceManager dmF = this.distanceManager;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet gateF =
                fabrolia$buildSpawnGate(lvl, list, spawningCategories);
        List<LevelChunk> chunks = list;

        fabrolia$spawnFuture = CompletableFuture.runAsync(() -> {
            com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onPass(lvl);
            for (int i = 0; i < n; i++) {
                LevelChunk c = chunks.get(i);
                try {
                    long packed = c.getPos().pack();
                    c.incrementInhabitedTime(capturedTimeDiff);
                    // Vanilla 26.2 tickSpawningChunk step 2 — see the region path note.
                    if (dmF.inEntityTickingRange(packed)) {
                        lvl.tickThunder(c);
                    }
                    if (gateF != null && gateF.contains(packed)) {
                        com.axalotl.fabrolia.common.parallelised.utils.SpawnRateDiag.onChunkVisit(lvl, packed);
                        NaturalSpawner.spawnForChunk(lvl, c, spawnCookie, spawningCategories);
                    }
                } catch (Exception e) {
                    LOGGER.error("Exception spawning in chunk {}", c.getPos(), e);
                }
            }
        }, ParallelProcessor.executor);
    }


    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V")
    )
    private void fabrolia$waitSpawnThenBlockTick(ChunkMap instance, Consumer<LevelChunk> tickingChunkConsumer,
                                              Operation<Void> original) {
        // Pipelined spawn + block-tick: do NOT pre-wait for spawn before launching block
        // ticks — instead launch block ticks immediately and await BOTH futures together at
        // the end. Spawn workers and random-tick workers run on the same pool, so they
        // interleave naturally via work-stealing instead of serialising into back-to-back
        // phases (which previously parked main for 2.98% of server thread in R5FaKJnHJg
        // while the spawn pool drained, then again for block ticks).
        //
        // Invariants preserved:
        //   - Mob cap: enforced inside spawn workers via AtomicIntegerArray CAS in
        //     NaturalSpawnerMixin.fabrolia$capAndAdd — no thread can succeed past the cap.
        //   - Spawn frequency: every spawning chunk still dispatched once per tick,
        //     SpawnState cookie still snapshot once at createState time; nothing changed
        //     in the spawn loop itself.
        //   - Per-chunk ordering: vanilla's "spawn chunk X then block-tick X" sequence is
        //     replaced by overlap. Block ticks (random ticks + fluid ticks) and spawn
        //     don't share mutable state per-chunk in vanilla — random tick reads/writes
        //     block states, spawn reads block states + writes entity manager. The two
        //     paths already raced safely under the prior parallel-spawn design (workers
        //     called level.addFreshEntity concurrently with each other and with this
        //     thread). Adding parallel block ticks into the same window doesn't
        //     introduce a new class of race — the worst-case is the same rare visual
        //     edge case that parallel spawn already accepts (mob places in a position
        //     a concurrent random tick just rendered unspawnable, or vice versa).
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

        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaRandomTicks) {
            // Sequential random ticks. Still let spawn run in background — they run on the
            // worker pool, this thread runs block ticks here; combine waits at the end.
            original.call(instance, tickingChunkConsumer);
        } else {
            List<LevelChunk> tickChunks = new ArrayList<>();
            original.call(instance, (Consumer<LevelChunk>) tickChunks::add);

            int n = tickChunks.size();
            if (n > 0) {
                int poolSize = Math.max(1, ParallelProcessor.getPoolSize());
                // poolSize*4 batches with min 8 chunks/batch: random tick cost per chunk varies
                // wildly (a mineshaft section with hoppers/spawners vs. an empty grass chunk),
                // so ~4 batches per worker gives ForkJoinPool's work-stealing enough to balance
                // without paying scheduling overhead of 200+ tiny CompletableFutures.
                int batchSize = Math.max(8, (n + (poolSize << 2) - 1) / (poolSize << 2));
                int batchCount = (n + batchSize - 1) / batchSize;
                CompletableFuture<?>[] futures = new CompletableFuture<?>[batchCount];

                for (int b = 0, idx = 0; b < n; b += batchSize, idx++) {
                    int start = b;
                    int end = Math.min(b + batchSize, n);
                    futures[idx] = CompletableFuture.runAsync(() -> {
                        for (int j = start; j < end; j++) {
                            try {
                                tickingChunkConsumer.accept(tickChunks.get(j));
                            } catch (Exception e) {
                                LOGGER.error("Exception ticking chunk {}", tickChunks.get(j).getPos(), e);
                            }
                        }
                    }, ParallelProcessor.executor);
                }

                blockTicksFut = CompletableFuture.allOf(futures);
            }
        }

        // Spawn-barrier overlap: when enabled, the spawn future is NOT awaited here — it
        // rides to ServerLevelMixin's entity barrier (taken via SpawnFutureTaker), so main
        // parks once for max(entityBatch, spawn) instead of paying a separate spawn park
        // in the chunk phase (profiled 8-10% of server thread across all modes in the
        // 2026-06-09 50-bot session). Block ticks (random/fluid) are ALWAYS awaited here:
        // they mutate block state and vanilla order requires them complete before
        // scheduled ticks / entities. Spawn only adds entities — a mob spawned
        // mid-entity-phase is visible/collidable immediately and takes its first tick
        // next tick, the same guarantee vanilla gives entities added during
        // entityTickList iteration.
        boolean rideAlong = FabroliaConfig.enableSpawnBarrierOverlap && !FabroliaConfig.disabled;

        CompletableFuture<Void> combined;
        if (rideAlong) {
            combined = blockTicksFut; // spawnFut stays in fabrolia$spawnFuture for the taker
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
            // Surface spawn-worker exceptions the same way the prior code did. We only need
            // to check spawnFut explicitly because pumpUntilDone already drains the combined
            // future and surfaces exceptions through its own join path.
            if (spawnFut != null && spawnFut.isCompletedExceptionally()) {
                spawnFut.join();
            }
            fabrolia$spawnFuture = null;
        }
    }

    /**
     * SpawnFutureTaker: ServerLevelMixin adopts this level's in-flight spawn future into
     * the entity-phase barrier. Single-consumer per tick (main thread only — both the
     * chunk phase that sets it and the entity phase that takes it run on main).
     */
    /**
     * VMP tracker tick (vanilla: serial inside ServerChunkCache.tick, BEFORE the
     * entity phase) → pool, overlapping the rest of the chunk phase (~6 ms of
     * tickChunks/broadcast). 5.4 ms of main-thread time on the 50-bot load.
     * Entity positions are stable until the entity phase, which awaits this
     * future first — vanilla tracker-sees-pre-tick-positions semantics hold.
     * Spawn-added entities enter via VMP's staging (already next-tick) — no
     * visibility change. Packet interleaving vs block events may shift within
     * the tick (client-render nuance, no game-state effect).
     */
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
    private void fabrolia$trackerOffMain(ChunkMap instance, Operation<Void> original) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaVmpTracking
                || !RegionScheduler.isActive()) {
            original.call(instance);
            return;
        }
        var exec = ParallelProcessor.executor;
        if (exec == null || exec.isShutdown()) {
            original.call(instance);
            return;
        }
        CompletableFuture<Void> stale = fabrolia$trackerFuture;
        if (stale != null && !stale.isDone()) {
            fabrolia$pumpUntilDone(stale);   // belt-and-suspenders: never two in flight
        }
        fabrolia$trackerFuture = CompletableFuture.runAsync(() -> {
            try {
                original.call(instance);
            } catch (Throwable t) {
                LOGGER.error("Off-main tracker tick failed", t);
            }
        }, exec);
    }

    @Override
    public CompletableFuture<Void> fabrolia$takeTrackerFuture() {
        CompletableFuture<Void> f = fabrolia$trackerFuture;
        fabrolia$trackerFuture = null;
        return f;
    }

    /** A2 deadline drive — save-all integrity: units may be mid-phase at any
     *  moment; serializing chunks/entities they own would tear NBT. Quiesce
     *  every island (units complete, none restart) around the save, exactly
     *  the docs/ASYNC-ISLANDS.md "quiesce-all → save" rule. No-op when async
     *  is off (claims always free). */
    @WrapMethod(method = "save")
    private void fabrolia$quiesceIslandsForSave(boolean flush, Operation<Void> original) {
        RegionizedServer rsq = RegionScheduler.forLevel(this.level);
        if (rsq != null && rsq.asyncActive) {
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
            com.axalotl.fabrolia.common.regions.CrossDimArrivals.flushLevel(rsq);
            try {
                original.call(flush);
            } finally {
                if (took) rsq.releaseAllIslands();
            }
        } else {
            original.call(flush);
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
        Runnable s = fabrolia$deferredSpawnArm;
        fabrolia$deferredSpawnArm = null;
        if (s != null) s.run();
        Runnable b = fabrolia$deferredBlockTicksArm;
        fabrolia$deferredBlockTicksArm = null;
        if (b != null) b.run();
    }

    @Unique
    private void fabrolia$pumpUntilDone(CompletableFuture<?> future) {
        net.minecraft.server.MinecraftServer s = this.level.getServer();
        if (s != null && s.isSameThread()) {
            // Drain per-level mainThreadProcessor (chunk-system tasks ONLY) but never
            // s.pollTask() — full rationale in ParallelProcessor.pumpUntilDone.
            // Spawn workers mid-spawnForChunk → addFreshEntity briefly hold the ChunkMap
            // monitor; chunk-system callbacks pumped here contend that monitor with
            // bounded hold time — not a cycle. The previous pure-park caused worker
            // deadlock when an fabrolia getChunk (Tier 4 ticket) needed Server thread to
            // drain mainThreadProcessor while Server was parked here.
            s.blockingCount++;
            try {
                while (!future.isDone()) {
                    boolean drained = false;
                    for (net.minecraft.server.level.ServerLevel lvl : s.getAllLevels()) {
                        if (lvl.getChunkSource().mainThreadProcessor.pollTask()) {
                            drained = true;
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
            // TICKET-SAFETY FIX (2026-07-13): PARK ONLY off-main. The old branch
            // called ServerChunkCache.pollTask(), whose MainThreadExecutor
            // override runs runDistanceManagerUpdates FIRST — ticket ticking
            // from any thread but the server thread mutates the unsynchronized
            // ticket/tracker maps concurrently with main (vanilla CME class)
            // and trips VMP's guard verbatim: ConcurrentModificationException
            // ("Attempted to tick tickets asynchronously") → the exception
            // surfaces in packet handling → client kick. Completion is main's
            // job; every main-side barrier/claim spin drains these executors.
            while (!future.isDone()) {
                LockSupport.parkNanos("Fabrolia ServerChunkCacheMixin pumpUntilDone off-main", 100_000L);
            }
        }
        if (future.isCompletedExceptionally()) {
            future.join();
        }
    }
}
