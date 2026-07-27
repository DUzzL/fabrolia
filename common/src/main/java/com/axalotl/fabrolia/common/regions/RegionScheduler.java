package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.axalotl.fabrolia.common.ParallelProcessor;

/**
 * Top-level region scheduler. Lifecycle tied to {@link MinecraftServer}: started
 * in SERVER_STARTING after {@link ParallelProcessor#setServer},
 * stopped in SERVER_STOPPING.
 *
 * <p>Gated by {@link FabroliaConfig#enableRegionThreading} — when off, the scheduler stays
 * dormant, the entity index is not maintained, and ServerLevelMixin takes the chunked
 * fabrolia-pool path. Runtime toggle re-bootstraps the index from EntityTickList.
 *
 * <p>Region-owned surface: {@link #dispatchOwnedEntityTicks(ServerLevel)} submits one
 * task per non-empty region over the region's own entity set; the caller barriers on the
 * returned future. Index maintenance flows through {@link #onEntityAdd}/{@link #onEntityRemove}
 * (EntityTickListMixin) and lazy cross-cell moves in RegionizedServer.tickOwned.
 */
public final class RegionScheduler {

    /** The level main is currently ticking (source-domain resolution for
     *  main-thread cross-dimension teleports). Main thread writes only. */
    public static volatile net.minecraft.server.level.ServerLevel mainCurrentLevel;

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionScheduler.class);

    private RegionScheduler() {}

    private static volatile MinecraftServer server;
    private static volatile RegionWorkerPool pool;
    private static volatile RegionGrid grid;
    private static final Map<ServerLevel, RegionizedServer> PER_LEVEL = new ConcurrentHashMap<>();
    private static volatile boolean active;

    public static boolean isActive() { return active; }
    public static MinecraftServer getServer() { return server; }
    public static int workerCount() { return pool == null ? 0 : pool.size(); }
    public static RegionGrid grid() { return grid; }

    public static synchronized void start(MinecraftServer mcServer) {
        if (active) return;
        FabroliaConfig.enforceSafetyInvariants();
        if (!FabroliaConfig.enableRegionThreading) {
            LOGGER.debug("RegionScheduler: enableRegionThreading=false, scheduler dormant");
            return;
        }
        server = mcServer;
        // New session: retained final clocks belong to the previous scheduler
        // lifetime's island ids (shutdown-order fix, 2026-07-21).
        TickDomains.clearFinalClocks();
        grid = new RegionGrid(RegionGrid.DEFAULT_SHIFT);
        int n = FabroliaConfig.getRegionWorkerCount();
        pool = new RegionWorkerPool(n);
        pool.start();
        active = true;
        // Bootstrap the region-owned entity index from the current EntityTickList contents.
        // At SERVER_STARTING there are no levels yet (index fills naturally as entities
        // load); on a runtime /fabrolia regions on toggle this picks up everything alive.
        int bootstrapped = 0;
        for (ServerLevel lvl : mcServer.getAllLevels()) {
            final int[] count = {0};
            lvl.entityTickList.forEach(e -> { onEntityAdd(e); count[0]++; });
            bootstrapped += count[0];
            // F1 islands: chunks already FULL never re-fire updateChunkScheduling —
            // seed the populated counts from the visible holder map (runtime
            // /fabrolia regions on; at SERVER_STARTING there are no levels yet).
            RegionizedServer rs = forLevel(lvl);
            if (rs != null) {
                var rta = (RegionTickingAccess) lvl.getChunkSource().chunkMap;
                for (net.minecraft.server.level.ChunkHolder h : rta.fabrolia$visibleHolders()) {
                    if (net.minecraft.server.level.ChunkLevel.fullStatus(h.getTicketLevel())
                            .isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)) {
                        rs.onChunkFullTransition(h.getPos().pack(), true, h.getTickingChunk());
                    }
                }
            }
        }
        if (bootstrapped > 0) {
            LOGGER.info("RegionScheduler: bootstrapped {} entities into region index", bootstrapped);
        }
        LOGGER.warn("RegionScheduler: ACTIVE with {} workers, grid shift={} ({}x{} chunks/region) — " +
                "experimental Folia-style region threading. Report issues at " +
                "https://github.com/AxalotLDev/Fabrolia/issues with [region] tag",
                n, grid.shift, 1 << grid.shift, 1 << grid.shift);
    }

    public static synchronized void stop() {
        if (!active) {
            // No live pool: resolve queued transfers (see the note at the other
            // flushAll below), flush any straggler arrivals, then release refs.
            try {
                com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager
                        .drainPending();
            } catch (Throwable t) {
                LOGGER.error("portal transfer drain at stop failed", t);
            }
            try {
                // Explicit list: allLevels() is already dark here (!active).
                CrossDimArrivals.flushAll(new java.util.ArrayList<>(PER_LEVEL.values()));
            } catch (Throwable t) {
                LOGGER.error("cross-dim arrival flush at stop failed", t);
            }
            server = null;
            return;
        }
        // SHUTDOWN-ORDER FIX (2026-07-21): capture the level list and retain every
        // island's final logical clock BEFORE active=false makes the gated accessors
        // (allLevels/forLevel) go dark — the old order turned the shutdown collapse
        // into a silent no-op AND made the final save's tag resolution fail for
        // every island-tagged container ("cannot be resolved" flood; saved triggers
        // shifted by the island offset).
        java.util.List<RegionizedServer> stopLevels = new java.util.ArrayList<>(PER_LEVEL.values());
        active = false;
        try {
            // ORDER (2026-07-18 audit #2): stop workers FIRST — an in-flight
            // island unit can stage/seal cross-dim arrivals; flushing before
            // the pool is quiet let those land after the flush and be dropped
            // by PER_LEVEL.clear() with the entities already removed from
            // their source level (permanent loss on stop / regions off).
            if (pool != null) pool.stop();
            // Retain final clocks FIRST: the collapse below resolves tags through
            // resolveNow, whose live-registry path is already dark (active=false) —
            // the retained snapshot is what it resolves against. It also serves any
            // container that reaches the final save still tagged (chunks loading
            // mid-shutdown, a collapse throw) — including across dimension teardown.
            try {
                TickDomains.retainFinalClocks(stopLevels);
            } catch (Throwable t) {
                LOGGER.error("final-clock retention failed", t);
            }
            // Task #31: collapse every island-tagged tick container to WALL
            // while tags are still resolvable — the final world save (which
            // runs AFTER SERVER_STOPPING) then packs true remaining delays.
            // Skipping this was the production farm killer: island-L triggers
            // saved against the wall basis future-dated pending ticks by the
            // whole sprint/rate offset, permanently dedup-blocking positions.
            try {
                TickDomains.collapseAllToWall(stopLevels);
            } catch (Throwable t) {
                LOGGER.error("tick-domain shutdown collapse failed", t);
            }
            // task #26: RESOLVE QUEUED PORTAL TRANSFERS BEFORE the arrival flush.
            // A transfer sits in PortalTeleportationManager.PENDING between the
            // source worker's submit and main's next entity-batch epilogue. At
            // stop that epilogue never comes: shutdown() simply cleared the
            // queue — and because ParallelProcessor.stop() runs AFTER this
            // method, the drop happened after flushAll, i.e. after the only
            // thing that could have published the arrival. The source cart was
            // then saved at rest inside the portal with its cooldown already
            // armed by handlePortal, which vanilla's setAsInsidePortal re-arms
            // every tick on reload — a permanently trapped loader produced by
            // nothing but a server restart. Draining here executes the teleport
            // on main and stages the arrival, which the flushAll below then
            // publishes: the transfer completes instead of being lost.
            try {
                com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager
                        .drainPending();
            } catch (Throwable t) {
                LOGGER.error("portal transfer drain at stop failed", t);
            }
            try {
                // MUST use the captured stopLevels: active=false above makes
                // allLevels() return empty, which silently no-op'd this entire
                // flush and destroyed every in-flight arrival. Same reason
                // retainFinalClocks/collapseAllToWall take the list.
                CrossDimArrivals.flushAll(stopLevels);
            } catch (Throwable t) {
                LOGGER.error("cross-dim arrival flush at stop failed", t);
            }
            TemporalControl.drainPacer();
        } finally {
            for (RegionizedServer rs : PER_LEVEL.values()) {
                try { rs.regionizer().clear(); } catch (Throwable t) {
                    LOGGER.warn("clear() threw for {}", rs.level.dimension().identifier(), t);
                }
            }
            PER_LEVEL.clear();
            pool = null;
            grid = null;
            server = null;
            mainCurrentLevel = null;
        }
        LOGGER.info("RegionScheduler: stopped");
    }

    public static RegionizedServer forLevel(ServerLevel level) {
        if (!active) return null;
        return PER_LEVEL.computeIfAbsent(level, lvl -> new RegionizedServer(lvl, grid));
    }

    static Collection<RegionizedServer> allLevels() {
        return active ? PER_LEVEL.values() : Collections.emptyList();
    }

    /**
     * Region-owned tick entry point. Each non-empty region of {@code level} gets one task
     * that runs the full vanilla per-entity sequence over the region's OWN entity set —
     * main does no list walk, no classify, no grouping (see RegionizedServer.tickOwned).
     *
     * @return future the caller MUST barrier on with
     *         {@link ParallelProcessor#pumpUntilDone}.
     */
    public static CompletableFuture<Void> dispatchOwnedEntityTicks(ServerLevel level) {
        if (!active) return CompletableFuture.completedFuture(null);
        return forLevel(level).dispatchOwnedEntityTicks();
    }

    // ---------------------------------------------------------------------
    // Region-owned entity index maintenance (EntityTickListMixin forwards here)
    // ---------------------------------------------------------------------

    public static void onEntityAdd(Entity e) {
        if (!active) return;
        if (!(e.level() instanceof ServerLevel sl)) return;
        RegionizedServer rs = forLevel(sl);
        if (rs != null) rs.indexAdd(e);
    }

    public static void onEntityRemove(Entity e) {
        if (!active) return;
        if (!(e.level() instanceof ServerLevel sl)) return;
        RegionizedServer rs = PER_LEVEL.get(sl); // never create a level container on remove
        if (rs != null) rs.indexRemove(e);
    }

    /**
     * Per-region FULL-chunk population, driven by the loader's reliable chunk load/unload
     * event (Fabric {@code ServerChunkEvents}, NeoForge {@code ChunkEvent}). A LevelChunk
     * is exactly a FULL chunk, so its load/unload IS the FULL gain/loss — symmetric and
     * fired on every transition, unlike the c2me-bypassed vanilla updateChunkScheduling
     * seam (c2me's setTicketLevel throws; the decrement was silently lost, regions never
     * went empty, eviction reclaimed nothing — live 2026-06-27). Never creates a level
     * container (get, not computeIfAbsent).
     */
    public static void onChunkFullChange(ServerLevel level, long packedChunkPos, boolean nowFull,
                                         net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (!active) return;
        RegionizedServer rs = PER_LEVEL.get(level);
        if (rs != null) rs.onChunkFullTransition(packedChunkPos, nowFull, chunk);
    }

    // ---------------------------------------------------------------------
    // Main-thread sync queue: entities a region task may not tick (modded API
    // contract, UUID blacklist, players when enableRegionPlayerTicks=false).
    // Main drains this inside the barrier pump, overlapping the region work.
    // ---------------------------------------------------------------------

    private static final ConcurrentLinkedQueue<Entity> MAIN_SYNC =
            new ConcurrentLinkedQueue<>();

    static void queueMainSyncTick(Entity e) {
        MAIN_SYNC.add(e);
    }

    /** Main thread only. @return true if at least one entity was ticked. */
    public static boolean drainMainSyncTicks() {
        Entity e = MAIN_SYNC.poll();
        if (e == null) return false;
        do {
            if (!e.isRemoved() && e.level() instanceof ServerLevel sl) {
                ParallelProcessor.tickEntity(sl, e, false);
            }
        } while ((e = MAIN_SYNC.poll()) != null);
        return true;
    }

    /** Generic submit at (level, chunk). Inline when scheduler dormant. */
    public static void executeAt(ServerLevel level, ChunkPos pos, Runnable task) {
        if (!active) { task.run(); return; }
        RegionizedServer rs = forLevel(level);
        Region r = rs.regionFor(pos);
        // R4 color gate: regions outside this epoch's dispatch participant set
        // must not be claimed late. A DONE region may already have higher-color
        // neighbors running; a born-after-dispatch orphan was never ordered by
        // the color DAG at all. Run these tasks on the main-safe lane instead:
        // same tick, no region claim, tickLockOr falls back to global locks.
        if (shouldRunMainSafe(rs, r)) {
            enqueueGlobalMainSafe(task);
            wakeWorkers();
            return;
        }
        r.queue().submit(task);
        enqueueReady(r);
        wakeWorkers();
    }

    static boolean shouldRunMainSafe(RegionizedServer rs, Region r) {
        if (!FabroliaConfig.useRegionColorGate()) {
            return false;
        }
        // A2: evaluated in the REGION's timeline — an island region compared
        // against the global epoch would read as a permanent orphan and route
        // its queued work main-safe WHILE its unit phase runs (racing the
        // claim holder). "Done for its domain's current epoch" means no worker
        // will claim it again before the next unit dispatch, which cannot
        // start until the current drive completes — main-inline is safe.
        EpochDomain d = r.domain;
        // A2 ownership fix (2026-07-13 production log): an island-owned
        // region's queue must NEVER run on the claim-free main-safe lane while
        // async is live. The old "done for epoch + no unit in flight" reroute
        // was check-then-act: the island's next unit dispatches while the
        // rerouted task sits in the shared lane, and the task then executes
        // claim-free against the unit's phases — and on the EXECUTING thread's
        // timeline (a pumping coordinator of another island → detector
        // cross-domain trip on ordinary vanilla random ticks: SpreadingSnowy/
        // GrowingPlantHead/BuddingAmethyst storm at 05:01:08; main's stealOne →
        // the same race, silent). gate==1 arms are due at the island's next
        // unit: dispatchEntityPhase drains member queues under region claims,
        // in order, on the island's clock. Let them wait there.
        IslandRegistry.PIsland unitOwner = d.owner;
        if (unitOwner != null) {
            if (rs.asyncActive) {
                return false;
            }
            // Async collapsing/off with a unit still in flight: keep the old
            // conservative guard for the transition window.
            if (unitOwner.unitClaim.get()) {
                return false;
            }
        }
        long epoch = d.epoch;
        if (r.doneEpoch >= epoch) {
            return true;
        }
        // Dispatch has already stamped every region allowed to claim this epoch.
        // A region whose groupedEpoch is still old is an orphan: usually born by
        // indexMove or executeAt into empty territory after the snapshot. It may
        // be completed by healColorGate when empty, but queued work must not
        // claim it because lower-color orphans can appear after higher neighbors
        // have already passed eligible().
        return d.dispatchedEpoch == epoch && r.groupedEpoch != epoch;
    }

    public static void executeForEntity(Entity entity, Runnable task) {
        if (!active) { task.run(); return; }
        if (!(entity.level() instanceof ServerLevel sl)) { task.run(); return; }
        executeAt(sl, entity.chunkPosition(), task);
    }

    public static void executeGlobal(Runnable task) {
        task.run();
    }

    public static int regionCountFor(ServerLevel level) {
        RegionizedServer rs = active ? PER_LEVEL.get(level) : null;
        return rs == null ? 0 : rs.regionCount();
    }

    public static void wakeWorkers() {
        if (pool != null) pool.wakeAll();
    }

    /** R5 targeted wake: unpark up to {@code k} parked workers (see RegionWorkerPool.wake).
     *  Use at high-frequency submit sites; the no-arg wakeAll stays for bulk fan-outs. */
    public static void wakeWorkers(int k) {
        RegionWorkerPool p = pool;
        if (p != null) p.wake(k);
    }

    /** Push a region onto the worker pool's ready-queue (O(1) claim path). */
    public static void enqueueReady(Region r) {
        RegionWorkerPool p = pool;
        if (p != null) p.enqueueReady(r);
    }

    /** Submit a pool-level region-group work unit (dispatch coalescing). */
    public static void enqueueGlobal(Runnable task) {
        RegionWorkerPool p = pool;
        if (p != null) p.enqueueGlobal(task);
    }

    /** Submit a pool-level task that the barrier pump may also steal (no claim-spins). */
    public static void enqueueGlobalMainSafe(Runnable task) {
        RegionWorkerPool p = pool;
        if (p != null) p.enqueueGlobalMainSafe(task);
    }

    /** A2: submit an island tick-unit coordinator (dedicated lane — only the
     *  top-level worker loop starts units; coordinators never nest). */
    public static void enqueueUnit(Runnable task) {
        RegionWorkerPool p = pool;
        if (p != null) {
            p.enqueueUnit(task);
            p.wake(1);
        }
    }

    /** A8 clock rebase: which async level owns this LevelTicks instance?
     *  (LevelTicks carries no level reference; the async slot registers both
     *  lanes on the RegionizedServer each tick.) Null = not an async lane. */
    public static RegionizedServer forTicksInstance(Object scheduler) {
        if (!active) return null;
        for (RegionizedServer rs : PER_LEVEL.values()) {
            if (rs.asyncActive
                    && (rs.blockTicksAccess == scheduler || rs.fluidTicksAccess == scheduler)) {
                return rs;
            }
        }
        return null;
    }

    /** Hot-path async liveness flag (detector gate): maintained by the
     *  per-level mode latch; one static volatile read per setBlock. */
    private static volatile boolean asyncLive;

    public static boolean asyncLive() {
        return asyncLive;
    }

    static void updateAsyncLive() {
        asyncLive = anyAsyncActive();
    }

    /** Audit: async levels only (entity-save owner routing etc.). */
    public static java.util.List<RegionizedServer> allAsync() {
        if (!active) return java.util.Collections.emptyList();
        java.util.ArrayList<RegionizedServer> out = new java.util.ArrayList<>(2);
        for (RegionizedServer rs : PER_LEVEL.values()) {
            if (rs.asyncActive) out.add(rs);
        }
        return out;
    }

    /** A8: does ANY level currently run async islands? The main-context
     *  command fence keys on this (cheap: volatile reads over a tiny map). */
    public static boolean anyAsyncActive() {
        if (!active) return false;
        for (RegionizedServer rs : PER_LEVEL.values()) {
            if (rs.asyncActive) return true;
        }
        return false;
    }

    /** A8: service ONE island's pending SHARD lane opportunistically (idle
     *  worker or a parked waiter, claim-protected try — claim held means its
     *  coordinator cannot run, preserving the single-servicer invariant).
     *  SHARDS ONLY: cadence-bound island tasks (tick functions) run at unit
     *  start exclusively. @return true if work was done. */
    static boolean serviceOneIslandLane() {
        if (!active) return false;
        for (RegionizedServer rs : PER_LEVEL.values()) {
            if (!rs.asyncActive) continue;
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                boolean pending;
                synchronized (p.pendingCommandShards) {
                    pending = !p.pendingCommandShards.isEmpty();
                }
                if (pending && p.unitClaim.compareAndSet(false, true)) {
                    p.noteClaim("lane@" + Thread.currentThread().getName());
                    try {
                        CommandShards.serviceShards(rs, p);
                    } finally {
                        p.clearClaim();
                        p.unitClaim.set(false);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** A2: is the worker pool alive? Coordinators bound their barrier pump on
     *  this so a unit stranded across shutdown exits instead of spinning. */
    static boolean poolRunning() {
        RegionWorkerPool p = pool;
        return p != null && p.isRunning();
    }

    /** True when the calling thread is the coordinator currently running
     *  {@code owner}'s unit (sender redesign 2026-07-14: the unit's own
     *  packet-snapshot builds need no claim — they ARE the claim). */
    public static boolean isOwningCoordinator(IslandRegistry.PIsland owner) {
        return Thread.currentThread() instanceof RegionWorkerThread w
                && w.currentDomain != null && w.currentDomain.owner == owner;
    }

    /** A2 coordinator help-pump: one work step (region claim / help batch /
     *  global task — never another unit) on the CALLING worker thread while a
     *  unit coordinator waits on its own barrier. @return true if work ran. */
    static boolean workerHelpOnce() {
        RegionWorkerPool p = pool;
        return p != null
                && Thread.currentThread() instanceof RegionWorkerThread me
                && p.workOnce(me, false);
    }

    /** Barrier-pump steal: one non-blocking unit of region work on the main thread. */
    public static boolean stealOneRegionTask() {
        RegionWorkerPool p = pool;
        return p != null && p.stealOne();
    }

    /** F2b: registration-time cell stamp for the persistent BE index. Deliberately
     *  NOT gated on {@code active} — the index must stay fresh across scheduler
     *  toggles; a level that never built a RegionizedServer has nothing to maintain
     *  (its first partitioned pass bulk-builds). Called under LevelMixin's
     *  addBlockEntityTicker lock, so stash order matches the vanilla list order. */
    public static void onBlockEntityTickerAdded(net.minecraft.world.level.Level level,
                                                net.minecraft.world.level.block.entity.TickingBlockEntity ticker) {
        if (!(level instanceof ServerLevel sl)) return;
        RegionizedServer rs = PER_LEVEL.get(sl);
        if (rs != null) rs.stashPendingBeAdd(ticker);
    }

    /** Main thread: drain the main-safe lane to empty (tick boundary and
     *  before island phase fan-outs — see RegionWorkerPool.drainMainSafeAll). */
    public static void drainMainSafeNow() {
        RegionWorkerPool p = pool;
        if (p != null) p.drainMainSafeAll();
    }

    /**
     * R4 liveness backstop, called by the barrier pump while main idles.
     * Regions born MID-tick (an entity crossing into a fresh grid cell via
     * indexMove, an executeAt into empty territory) have no drain epilogue
     * and sit in no group — nobody else ever completes them, and ONE stale
     * lower-color orphan wedges every dependent transitively (live wedge
     * 2026-06-12: Server thread 203s at the entity barrier, one worker
     * spinning continuations, 29 parked). tryMarkDone carries every refusal
     * guard, so a blind pass over the snapshot is safe from any state; the
     * orphan's entities tick in their OLD region this tick (the grouped-where-
     * it-was rule), so completing it publishes no concurrent claim.
     */
    private static long lastHealNanos;
    /** Adaptive heal cadence: 2 ms while passes have effect (fresh dispatch, real
     *  orphans), doubling to a 16 ms ceiling while passes change nothing. The
     *  fixed 2 ms cadence cost ~1.8 ms/tick of snapshot copies + full region
     *  scans on the 2026-07-11 profiles — a liveness backstop for RARE orphans
     *  doing routine O(regions) work ~30×/tick. Worst-case orphan heal latency
     *  becomes 16 ms + snapshot staleness (≤50 ms) — bounded, and three orders
     *  of magnitude inside the 5 s stall watchdog. Reset on every dispatch. */
    private static long healIntervalNanos = 2_000_000L;

    /** Called at entity dispatch: a new epoch is exactly when new orphans can
     *  appear, so heal at full cadence again. */
    static void resetHealBackoff() {
        healIntervalNanos = 2_000_000L;
    }

    public static void healColorGate() {
        if (!active || !FabroliaConfig.useRegionColorGate()) return;
        // Main-thread caller only (the barrier pump) — plain fields suffice.
        long now = System.nanoTime();
        if (now - lastHealNanos < healIntervalNanos) return;
        lastHealNanos = now;
        boolean effect = false;
        boolean eventSched = FabroliaConfig.regionEventScheduler;
        for (RegionizedServer rs : PER_LEVEL.values()) {
            var regions = rs.healSnapshot(now);
            for (int i = 0, n = regions.size(); i < n; i++) {
                Region r = regions.get(i);
                if (r.state() != RegionState.ACTIVE) continue;
                // A2: every check runs in the REGION's own timeline — with async
                // islands live, one heal pass covers the global lane and every
                // in-flight island barrier alike (a wedged island region gets
                // re-armed even after the global barrier finished, and never
                // against the wrong epoch).
                EpochDomain d = r.domain;
                long epoch = d.epoch;
                if (d.dispatchedEpoch != epoch) continue; // pre-dispatch: undecidable
                boolean pending = eventSched
                        && d.entityBarrierArmedEpoch == epoch && d.entityPending.get() > 0;
                if (r.doneEpoch < epoch) {
                    ColorGate.tryMarkDone(r, rs);
                    if (r.doneEpoch >= epoch) effect = true;
                }
                // R5 liveness backstop: any owing region my push-analysis might have
                // missed gets re-armed here (duplicates benign — the claim + entityEpoch
                // guard makes double execution impossible), and a phase that ran without
                // contributing (crash path) is settled into the barrier.
                if (pending && rs.owesEntityPhase(r)) {
                    if (ColorGate.eligible(r, rs) && r.currentWorker() == null) {
                        enqueueReady(r);
                        wakeWorkers(1);
                        effect = true;
                    }
                } else if (pending) {
                    int before = d.entityPending.get();
                    rs.contributeEntityBarrier(r);
                    if (d.entityPending.get() != before) effect = true;
                }
                // PRODUCTION WEDGE FIX (xaprosmp thread dump 2026-07-13): a
                // region with QUEUE-ONLY pending work (spawn/block-tick tasks
                // whose future main is pumping) has no re-arm path when it is
                // not owed an entity phase — a ready entry consumed-and-
                // dropped in the claim race stranded the pump with all 30
                // workers idle-parked. Re-arm ANY active, unclaimed region
                // with pending work; duplicate ready entries are tolerated by
                // design (claim CAS + drain idempotence).
                if (r.currentWorker() == null
                        && (!r.queue().isEmpty() || !r.helpQueue.isEmpty())
                        // A2 ownership fix: island regions outside their unit
                        // window are QUEUED-BY-DESIGN (the next unit's entity
                        // phase is the consumer) — not stranded. Re-arming
                        // them here would spin claim-refusal churn forever.
                        && com.axalotl.fabrolia.common.regions.ColorGate.inUnitWindow(r, rs)) {
                    // Two consecutive heal sightings (≥2ms apart) — freshly
                    // submitted work is claimed within microseconds, so a
                    // second sighting means the ready entry was lost. Gating
                    // avoids re-arming hundreds of mid-flight regions every
                    // heal pass (the unpark-storm class R5 removed).
                    if (r.strandedQueueSightings++ >= 1) {
                        r.strandedQueueSightings = 0;
                        enqueueReady(r);
                        wakeWorkers(1);
                        effect = true;
                    }
                } else {
                    r.strandedQueueSightings = 0;
                }
                // Leaked-claim forensic: a claim whose holder has moved on
                // (currentRegion no longer this region) blocks tryMarkDone and
                // neighbor eligibility forever — the other wedge shape this
                // dump could not distinguish. Two consecutive sightings avoid
                // the benign clear-then-release window in the claim epilogue.
                // COHERENT SNAPSHOT (2026-07-13, user 07:21 report): with the
                // publication protocol (publish-before-claim, release-before-
                // unpublish), a reader that observes the claim CAS is
                // guaranteed to observe the matching publication — so a
                // mismatch here can no longer be a transition artifact. The
                // snapshot is validated by re-reading the holder AFTER the
                // publication read (holder changed = transition in flight =
                // skip), and a report additionally requires the SAME claim
                // GENERATION across two sightings: one acquisition that
                // persisted, with its acquire path and age from the ledger —
                // never two different claims blurred together.
                Thread holder = r.currentWorker();
                long gen = r.claimGen;
                Region published = holder instanceof RegionWorkerThread w0 ? w0.currentRegion : r;
                if (holder != r.currentWorker()) {
                    r.staleClaimSightings = 0;
                    r.staleClaimGenSeen = -1L;
                } else if (holder instanceof RegionWorkerThread hw && published != r) {
                    // DEAD-HOLDER RECOVERY (2026-07-13): a claim held by a thread
                    // that no longer exists can NEVER be released by its owner and
                    // provably cannot be used by it either — force-release is the
                    // one case where recovery cannot double-run the region. Live
                    // holders are only ever REPORTED: releasing under a live
                    // holder could put two threads in the same region.
                    if (!holder.isAlive()) {
                        LOGGER.error("[event-sched] DEAD WORKER CLAIM on region {} ({}): holder {} terminated "
                                + "without releasing (queue={} help={} owed={}) — force-releasing and re-arming",
                                Long.toHexString(r.gridKey), r.level.dimension().identifier(),
                                holder.getName(), r.queue().size(), r.helpQueue.size(),
                                rs.owesEntityPhase(r));
                        r.staleClaimSightings = 0;
                        r.staleClaimGenSeen = -1L;
                        ((RegionWorkerThread) holder).currentRegion = null;
                        r.stampRelease("heal-dead");
                        r.release(holder);
                        if (!r.queue().isEmpty() || !r.helpQueue.isEmpty()
                                || rs.owesEntityPhase(r)) {
                            enqueueReady(r);
                            wakeWorkers(1);
                        }
                        ColorGate.tryMarkDone(r, rs);
                        effect = true;
                    } else if (r.staleClaimGenSeen != gen) {
                        // First mismatch sighting for THIS acquisition.
                        r.staleClaimGenSeen = gen;
                        r.staleClaimSightings = 1;
                    } else if (++r.staleClaimSightings >= 2 && now - lastStaleClaimLogNanos > 10_000_000_000L) {
                        lastStaleClaimLogNanos = now;
                        StackTraceElement[] hs = holder.getStackTrace();
                        StringBuilder sb = new StringBuilder();
                        for (int si = 0; si < Math.min(hs.length, 14); si++) {
                            sb.append("\n      at ").append(hs[si]);
                        }
                        LOGGER.error("[event-sched] RETAINED CLAIM on region {} ({}): holder {} gen={} acquired via '{}' {}ms ago "
                                + "(lastRelease: gen={} via '{}') and is not working it "
                                + "(queue={} help={} owed={} holderRegion={} holderDomain={}) — same-generation across "
                                + "sightings under the coherent publication protocol: this is a REAL retention, "
                                + "not observer skew. Holder stack:{}",
                                Long.toHexString(r.gridKey), r.level.dimension().identifier(),
                                holder.getName(), gen, r.claimPath,
                                (now - r.claimAcquiredNanos) / 1_000_000L,
                                r.lastReleaseGen, r.lastReleasePath,
                                r.queue().size(), r.helpQueue.size(),
                                rs.owesEntityPhase(r),
                                published == null ? "-" : Long.toHexString(published.gridKey),
                                hw.currentDomain == null || hw.currentDomain.owner == null
                                        ? "-" : ("island#" + hw.currentDomain.owner.id),
                                sb);
                    }
                } else {
                    r.staleClaimSightings = 0;
                    r.staleClaimGenSeen = -1L;
                }
            }
            int owingTotal = rs.entityPendingCount();
            boolean barrierPending = eventSched && owingTotal > 0;
            long oldestArmedNanos = barrierPending ? now - rs.entityBarrierAgeNanos(now) : Long.MAX_VALUE;
            // A2: a pending ISLAND barrier wedging would hang the combined pump
            // just as silently — fold the oldest pending island domain into the
            // same age check (owesEntityPhase/diagnose below are region-relative
            // already, so the report is correct whichever domain is stuck).
            if (rs.asyncActive) {
                for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                    EpochDomain d = p.domain;
                    int ip = d.entityBarrierArmedEpoch == d.epoch ? d.entityPending.get() : 0;
                    if (ip > 0) {
                        barrierPending = eventSched;
                        owingTotal += ip;
                        oldestArmedNanos = Math.min(oldestArmedNanos, d.entityBarrierArmedNanos);
                    }
                }
            }
            // Stall forensics: the legacy group path logged after 50k zero-progress
            // passes; the event path has no polling loop to count, so log by age.
            // A CLAIMED region anywhere in the level means a phase is genuinely
            // executing (e.g. 18k TNT in one region legitimately runs for seconds —
            // 2026-07-02 mega-polygon) — that is WARN "long phase", not a wedge;
            // ERROR is reserved for nothing-running-and-nothing-progressing.
            if (barrierPending && now - oldestArmedNanos > 5_000_000_000L
                    && now - lastStallLogNanos > 5_000_000_000L) {
                lastStallLogNanos = now;
                boolean working = false;
                for (int i = 0, n = regions.size(); i < n; i++) {
                    Region r = regions.get(i);
                    if (r.state() == RegionState.ACTIVE && r.currentWorker() != null) {
                        working = true;
                        break;
                    }
                }
                for (int i = 0, n = regions.size(); i < n; i++) {
                    Region r = regions.get(i);
                    if (r.state() == RegionState.ACTIVE && rs.owesEntityPhase(r)) {
                        if (working) {
                            LOGGER.warn("[event-sched] entity barrier waiting {}s on a LONG RUNNING phase ({} owing — not a wedge); {}",
                                    (now - oldestArmedNanos) / 1_000_000_000L,
                                    owingTotal, ColorGate.diagnose(r, rs));
                        } else {
                            LOGGER.error("[event-sched] entity barrier STALLED {}s, {} owing, nothing running; {}",
                                    (now - oldestArmedNanos) / 1_000_000_000L,
                                    owingTotal, ColorGate.diagnose(r, rs));
                        }
                        break;
                    }
                }
            }
        }
        healIntervalNanos = effect ? 2_000_000L : Math.min(healIntervalNanos << 1, 16_000_000L);
    }

    private static long lastStallLogNanos;
    private static long lastStaleClaimLogNanos;

    /** Used by ParallelProcessor.isServerExecutionThread — region workers count as MC threads. */
    public static boolean isRegionWorker(Thread t) {
        return t instanceof RegionWorkerThread;
    }
}
