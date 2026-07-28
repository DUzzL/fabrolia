package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.EntityType;
import net.minecraft.server.level.ServerChunkCache;

/**
 * Per-{@link ServerLevel} region container + region-owned entity index + tick dispatcher.
 *
 * <p>Region-owned ticking (B1/B2): every region owns the live entity set for its grid cell
 * ({@link Region#entities}), maintained incrementally from EntityTickList add/remove plus
 * lazy cross-cell re-routing inside the tick loop. Per tick, {@link #dispatchOwnedEntityTicks}
 * submits one task per non-empty region; the task runs the COMPLETE vanilla per-entity
 * sequence for its own entities — frozen check, ticking-range/holder gate, vehicle skip,
 * {@code checkDespawn()} immediately before the tick (vanilla interleaving, more faithful
 * than the old global despawn pre-pass), then the tick itself. Main thread does no global
 * list walk, no classify loop, no per-tick grouping, and no sync-entity loop: items,
 * minecarts, projectiles, falling blocks tick serially inside their region task — region
 * serialization provides the same local ordering the old sync-blacklist enforced globally.
 *
 * <p>Entities that must still tick on the server thread (modded entities without
 * {@code @FabroliaCompatible} — API contract — and UUID-blacklisted ones) are queued to
 * {@link RegionScheduler#queueMainSyncTick} and drained by main inside the barrier pump.
 */
public final class RegionizedServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-RegionTick");

    public final ServerLevel level;
    private final ThreadedRegionizer regionizer;

    // NOTE: the per-level loadedChunks LevelChunk cache (+ pendingChunkUpdates queue) that
    // lived here was REMOVED 2026-07-01: the block-tick worker-resolve rewrite dropped its
    // last consumers, but the CHUNK_LOAD/UNLOAD producer kept enqueueing PendingChunk records
    // holding STRONG LevelChunk refs into a queue nothing drained — unloaded chunks were
    // pinned forever (monotonic heap leak). If the cache is ever wanted back, the tick-head
    // drainPendingChunks() call must come back WITH it.

    RegionizedServer(ServerLevel level, RegionGrid grid) {
        this.islandRegistry.dim = level.dimension().identifier().toString();
        this.level = level;
        this.regionizer = new ThreadedRegionizer(level, grid);
        this.regionizer.owner = this;   // epoch source for fresh-region doneEpoch stamping
    }

    public ThreadedRegionizer regionizer() { return regionizer; }

    /** A2: the level's default scheduling timeline. Sync mode: every region's
     *  {@link Region#domain} points here — behavior is field-for-field what the
     *  old RegionizedServer epoch/barrier state gave. Async mode: island member
     *  regions move to their island's domain; gap/unpopulated regions stay here,
     *  and this domain still runs one dispatch per level tick for them. */
    final EpochDomain globalDomain = new EpochDomain();
    /** Cross-dimension arrivals bound for this level's global lane. */
    final java.util.concurrent.ConcurrentLinkedQueue<CrossDimArrivals.StagedOp> globalArrivals =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** audit13 — global-lane ordering monitor: guards every BATCH append to
     *  {@link #globalArrivals} and publishGlobal's drain→requeue window. The
     *  cohort model identifies a machine cohort as a CONTIGUOUS run of one
     *  (sourceDomain, sourceTick) in the lane; ConcurrentLinkedQueue.addAll is
     *  per-element, so two concurrent sealers' batches could INTERLEAVE, and a
     *  route() landing between publishGlobal's poll() drain and its re-add of
     *  the gate-held head jumped AHEAD of the held ops — either way one cohort
     *  fragments and the End-platform handoff gate sees the fragments as
     *  successive cohorts (a machine cohort split). Leaf lock: only queue
     *  moves ever run under it — NEVER publication, world mutation, or any
     *  other lock acquisition (the tracker monitor inside the gated extract is
     *  itself a leaf). */
    final Object globalArrivalsLock = new Object();

    /** End-platform falling-block handoff tracker for THIS destination level
     *  (phase-drift fix 2026-07-27). Per LEVEL, not per island: player-driven
     *  topology churn retires/rebuilds islands constantly (islandsDirty
     *  latches on nearly every chunk full/unfull transition) and reroutes
     *  their arrival queues — a per-island tracker would forget the airborne
     *  cohort at exactly the moment the player-at-source failure mode
     *  strikes. Level identity is stable across all of that. "Settled" =
     *  the vanilla removal event: a FallingBlockEntity is removed on the
     *  same entity tick it places its block, breaks, or drops as an item. */
    final EndPlatformHandoff.Tracker platformHandoff = new EndPlatformHandoff.Tracker(
            h -> ((net.minecraft.world.entity.Entity) h).isRemoved(),
            System::nanoTime, EndPlatformHandoff.DEFAULT_TTL_NANOS);

    private static boolean failClosedMarkerChecked;

    /** A2 mode latch — coherent for a whole level tick: read/written ONLY at
     *  tick HEAD (onLevelTickStart), consumed by rebuild (domain assignment)
     *  and the entity-phase drive later the same tick. */
    public volatile boolean asyncActive;
    /** Regions currently carry island domains (forces one reassignment pass
     *  back to globalDomain after an ON→OFF flip). */
    private boolean domainsIslandized;

    public long epoch() { return globalDomain.epoch; }

    /** Is this domain THIS level's global timeline? (package identity check
     *  for the isHandlingTick redirect.) */
    public boolean ownsGlobalDomain(Object d) {
        return d == globalDomain;
    }

    /** Seal this level's global-lane cross-dimension stage (main batch end). */
    public void sealGlobalStage() {
        CrossDimArrivals.seal(globalDomain);
    }

    public long dispatchedEpoch() { return globalDomain.dispatchedEpoch; }

    /** A2: the domain a region created RIGHT NOW should join. A worker holding
     *  a claim (entity phase / queue drain) creates destination regions for
     *  index moves — those are spatially adjacent to the source, hence same
     *  island: inherit the claimed region's domain. A unit coordinator between
     *  claims publishes its island domain via currentDomain. Everything else
     *  (main-thread chunk loads, gap-area executeAt targets) joins the global
     *  domain until the next island rebuild adopts it. */
    EpochDomain domainForCreation() {
        return domainForCreation(Long.MIN_VALUE);
    }

    /** Domain for a region born at {@code gridKey} (MIN_VALUE = unknown).
     *  Priority: creating worker's claimed/current domain (mid-phase index
     *  moves stay in their own timeline — a foreign-halo destination would
     *  imply two islands ≤2 cells apart, which the gap rule already merges) →
     *  published core/halo cell domain → global. */
    EpochDomain domainForCreation(long gridKey) {
        if (Thread.currentThread() instanceof RegionWorkerThread w) {
            // CROSS-LEVEL GUARD (2026-07-14 sand-duper cold-End loss): a worker
            // mid-End-portal-teleport CREATES destination regions in the OTHER
            // level. Its claimed/current domain belongs to the SOURCE level —
            // stamping it onto a destination region parks that region in a
            // domain no drive of the destination level ever ticks (arrival
            // entities stranded until the next rebuild adopts the region).
            // Worker context applies only to regions of ITS OWN level.
            Region cur = w.currentRegion;
            if (cur != null && cur.domain != null && sameLevelDomain(cur.domain)) return cur.domain;
            EpochDomain d = w.currentDomain;
            if (d != null && sameLevelDomain(d)) return d;
        }
        if (gridKey != Long.MIN_VALUE && asyncActive) {
            var snap = cellDomainSnapshot;
            if (snap != null) {
                EpochDomain d = snap.get(gridKey);
                if (d != null) return d;
            }
        }
        return globalDomain;
    }

    private boolean sameLevelDomain(EpochDomain d) {
        if (d == globalDomain) return true;
        IslandRegistry.PIsland o = d.owner;
        return o != null && o.level == level;
    }

    /** CAS-gated ENTITY-phase push: at most one ready-lane seed/re-arm per epoch.
     *  Eligibility is monotonic within an epoch (doneEpoch only advances; regions born
     *  post-dispatch are stamped done at creation), so a pushed region can never regress
     *  to ineligible — the pop side may still drop it on a lost claim CAS, which the
     *  claim holder's release epilogue re-arms (see RegionWorkerPool). */
    boolean tryEnqueueEntity(Region r) {
        long e = r.domain.epoch;
        long prev = r.entityEnqueuedEpoch.get();
        if (prev >= e || !r.entityEnqueuedEpoch.compareAndSet(prev, e)) {
            return false;
        }
        RegionScheduler.enqueueReady(r);
        return true;
    }

    /** Does {@code r} still owe its entity phase this epoch (of ITS domain)? */
    boolean owesEntityPhase(Region r) {
        long e = r.domain.epoch;
        return r.groupedEpoch == e && r.entityEpoch < e;
    }

    /**
     * R5: run the owed entity phase under an ALREADY-HELD claim (caller = claim holder,
     * {@code currentRegion} published). Gated on ColorGate eligibility — a pre-dispatch
     * claim holder whose region got stamped mid-drain must NOT tick entities ahead of
     * its lower-color blockers; the last blocker's markDone re-arms it instead.
     * A Throwable inside the phase is contained (logged, phase counted as ran) so one
     * broken entity can never wedge the barrier — same net behavior as the legacy
     * group path's complete-on-failure.
     */
    void runOwedEntityPhase(Region r) {
        if (!owesEntityPhase(r) || !ColorGate.eligible(r, this)) {
            return;
        }
        long t0 = System.nanoTime();
        r.lastFanoutBatches = 0;   // serial path leaves these zeroed; the dense
        r.lastBatchMaxNanos = 0;   // path overwrites them after fan-in
        r.lastOrderedNanos = 0;
        try {
            tickOwned(r);
        } catch (Throwable t) {
            ThrottledLog.error(LOGGER, "entityPhase", t, "Entity phase failed for region {} ({})",
                    Long.toHexString(r.gridKey), level.dimension().identifier());
        }
        // LPT feed: EWMA (α=1/4) of the phase's measured cost. Entity count alone
        // under-weighs BE/AI-heavy regions; the measured tail region ran 1.5–1.7×
        // the pool average on the 2026-07-07/11 profiles, so dispatch order by
        // observed cost starts the true long pole first. Scheduling-order only —
        // correctness is carried by the color gate, not by this hint.
        long dt = System.nanoTime() - t0;
        long ema = r.emaPhaseNanos;
        r.emaPhaseNanos = ema == 0 ? dt : ema + ((dt - ema) >> 2);
        r.lastPhaseNanos = dt;
        r.entityEpoch = r.domain.epoch;
    }

    /** R5: contribute {@code r}'s barrier decrement — exactly once per epoch of ITS
     *  domain, and only once its entity phase has actually run. Any thread may call
     *  this blindly. */
    void contributeEntityBarrier(Region r) {
        EpochDomain d = r.domain;
        long e = d.epoch;
        if (d.entityBarrierArmedEpoch != e) return;
        if (r.groupedEpoch != e || r.entityEpoch < e) return;
        long prev = r.entityBarrierEpoch.get();
        if (prev >= e || !r.entityBarrierEpoch.compareAndSet(prev, e)) return;
        if (d.entityPending.decrementAndGet() == 0) {
            CompletableFuture<Void> b = d.entityBarrier;
            if (b != null) b.complete(null);
        }
    }

    /** Heal/watchdog view: owing regions left this GLOBAL-domain epoch (0 = barrier
     *  done). Island domains pump their own barriers inside the tick unit. */
    int entityPendingCount() {
        return globalDomain.entityBarrierArmedEpoch == globalDomain.epoch
                ? globalDomain.entityPending.get() : 0;
    }

    long entityBarrierAgeNanos(long now) {
        return now - globalDomain.entityBarrierArmedNanos;
    }

    public void onLevelTickStart() {
        RegionScheduler.mainCurrentLevel = level;
        // A2 mode latch: async mode requires the event scheduler + color gate
        // (the unit's dispatch/eligibility model IS the R5 event path). A flip
        // takes effect exactly here — before rebuild (domain assignment) and
        // the entity drive of the same tick — never mid-tick.
        if (!failClosedMarkerChecked) {
            failClosedMarkerChecked = true;
            // Crash/trip state is SESSION-ONLY (2026-07-18 policy): a marker
            // file from an older build must never gate async on later boots.
            // Delete stale ones so admins aren't confused by leftovers.
            try {
                java.nio.file.Path stale = java.nio.file.Paths.get("config", "fabrolia-failclosed.marker");
                if (java.nio.file.Files.deleteIfExists(stale)) {
                    LOGGER.info("[async-islands] deleted stale fail-closed marker from an older "
                            + "Fabrolia — crash state no longer persists across restarts.");
                }
            } catch (Throwable ignored) {
            }
        }
        boolean wantAsync = FabroliaConfig.asyncIslands
                && !FabroliaConfig.asyncFailClosedTripped
                && FabroliaConfig.regionEventScheduler
                && FabroliaConfig.useRegionColorGate()
                && FabroliaConfig.enableRegionThreading
                && !FabroliaConfig.disabled;
        if (FabroliaConfig.asyncFailClosedTripped && asyncActive) {
            // FAIL-CLOSED CONTAINMENT (2026-07-14 rate-40 wedge): the old rule
            // kept async RUNNING post-trip ("no live collapse") — so the very
            // writer that tripped the detector KEPT WRITING cross-domain every
            // tick; 48s later a rate change doubled its frequency and main
            // wedged inside a concurrently-corrupted global structure. The live
            // OFF collapse is the SAME code path `/fabrolia regions async`
            // exercises (quiesce → domain collapse → carry-state unstash),
            // hardened since (claimWanted fences, migration drains) and proven
            // by the a8toggle battery — so containment now actually contains:
            // collapse to SYNC at this tick head; the marker still forces SYNC
            // on the next boot until the operator clears it.
            LOGGER.error("[async-islands] FAIL-CLOSED containment: collapsing to SYNC now "
                    + "(this tick head) for {} — async stays refused for the rest of this session "
                    + "(a restart clears it).",
                    level.dimension().identifier());
        }
        if (wantAsync != asyncActive) {
            asyncActive = wantAsync;
            RegionScheduler.updateAsyncLive();
            islandsDirty = true;   // force a rebuild → domain (re)assignment pass
            if (!wantAsync) {
                // ASYNC→SYNC (task #13): collapse every loaded container's
                // island tag NOW, while the registry still holds the live
                // islands (the rebuild below retires them) — the same
                // delay-preserving translation the shutdown path runs. The
                // per-region rebase in setRegionDomain only covers regions
                // that get a domain reassignment; containers in territory
                // outside any reassigned region kept island tags into the
                // sync session, where the wall lane never heals them and
                // schedule-time deltas rot against the frozen island clock
                // (the 6gt QC trencher stall zone starting at a static seam).
                TickDomains.collapseAllToWall(java.util.List.of(this));
            }
            LOGGER.info("[async-islands] {} for {} (applies this tick)",
                    wantAsync ? "ENABLED" : "DISABLED", level.dimension().identifier());
            if (wantAsync) {
                LOGGER.info("[async-islands] compatibility envelope: vanilla mechanics island-isolated by construction; "
                        + "players main-owned (packets serialized with player state); commands exactly-once "
                        + "(local=island cadence, global=fenced); mods writing across islands are detected "
                        + "(crossDOMAIN counter) and fail-closed{}; command-block minecarts + posless custom "
                        + "tickers run at world cadence.",
                        FabroliaConfig.asyncFailClosed ? " (auto-disable)" : " (override active)");
            }
        }
        // Shadow clocks only advance in sync mode — in async mode the tick units
        // advance PIsland.logicalTick/lag for real (IslandTickUnit.complete).
        if (!asyncActive) {
            islandRegistry.advanceShadow(this, level.getGameTime());
        }
        globalDomain.epoch++;
        // R4 stage B groundwork: periodically report the interior/border split of ticked
        // entities (measurement only — see FabroliaConfig.fabroliaOptimisticBorderMetric). The
        // border FRACTION is the input that decides whether dropping the 4-wave ColorGate for
        // a single-wave interior-parallel scheduler pays off. Ratio is window-size-invariant,
        // so the frequent reset across levels doesn't skew the percentage.
        if (FabroliaConfig.fabroliaOptimisticBorderMetric && (globalDomain.epoch % 600L) == 0L) {
            long b = METRIC_BORDER.getAndSet(0);
            long i = METRIC_INTERIOR.getAndSet(0);
            long t = b + i;
            if (t > 0) {
                LOGGER.info("[border-metric] interior={} border={} -> {}% border (margin={}b, region={}b)",
                        i, b, String.format("%.1f", 100.0 * b / t),
                        FabroliaConfig.fabroliaOptimisticBorderMargin, 1L << (regionizer.grid.shift + 4));
            }
        }
    }

    /** Interior/border classifier counters (metric only). Static: the ratio is what matters. */
    private static final AtomicLong METRIC_INTERIOR = new AtomicLong();
    private static final AtomicLong METRIC_BORDER = new AtomicLong();

    /** True if {@code e} sits within {@code fabroliaOptimisticBorderMargin} blocks of its region's
     *  edge — i.e. its tick MIGHT write a block/entity in a neighbour region. Interior entities
     *  (false) are provably confined to their own region and could tick fully parallel without
     *  the ColorGate. Cheap: 4 double compares, no allocation. */
    private boolean isBorderForMetric(Region region, Entity e) {
        int shift = regionizer.grid.shift;
        long size = 1L << (shift + 4);
        double margin = FabroliaConfig.fabroliaOptimisticBorderMargin;
        int rx = (int) region.gridKey;
        int rz = (int) (region.gridKey >>> 32);
        double relX = e.getX() - (double) (((long) rx) << (shift + 4));
        double relZ = e.getZ() - (double) (((long) rz) << (shift + 4));
        return relX < margin || relZ < margin || (size - relX) < margin || (size - relZ) < margin;
    }


    // ---------------------------------------------------------------------
    // F1 islands — connected components of loaded space
    // ---------------------------------------------------------------------

    /** One island: a maximal set of populated regions no other island can
     *  affect within a tick. Separation invariant: populated regions of two
     *  different islands are Chebyshev ≥3 apart (connectivity radius 2), so
     *  ≥2 unpopulated regions (≥16 chunks of unloaded void) lie between any
     *  two islands — a single-tick effect chain cannot bridge it, and a
     *  region freshly loaded mid-tick is adjacent to at most ONE island. */
    public record Island(int id, ArrayList<Region> members, long entityLoad) {}

    private volatile boolean islandsDirty = true;
    private volatile List<Island> islands = List.of();

    /** Main thread (updateChunkScheduling seam). Region created eagerly: a
     *  freshly populated cell must exist to be claimable by the rebuild. */
    /** Redundant-delivery counters (fabric event + vanilla seam feed the same
     *  idempotent funnel; overlaps are expected and harmless — these exist so
     *  popcheck can show which source is actually firing). */
    public final java.util.concurrent.atomic.AtomicLong popIgnoredAdds = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong popIgnoredRemoves = new java.util.concurrent.atomic.AtomicLong();

    /** One-shot latch for the population-thread invariant below. */
    private static final java.util.concurrent.atomic.AtomicBoolean POP_THREAD_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    public void onChunkFullTransition(long packedChunkPos, boolean nowFull, LevelChunk chunk) {
        // INVARIANT (audit 2026-07-26): this runs on the main thread only, which is
        // what lets Region.fullChunks be a plain LongOpenHashSet while the derived
        // counter is atomic. Verified against real 26.2 + Fabric API: the FULL
        // status task is dispatched on context.mainThreadExecutor()
        // (ChunkStatusTasks.full) and CHUNK_UNLOAD fires from ChunkMap's main-thread
        // unload pass. But that is an assumption about EVERY chunk-system mod in the
        // stack — c2me reshapes chunk loading — and if it is ever violated a fastutil
        // set corrupts silently: lost population, islands that never split or never
        // merge. One reference comparison turns that into a diagnosable line.
        net.minecraft.server.MinecraftServer srv = level.getServer();
        if (srv != null && Thread.currentThread() != srv.getRunningThread()
                && !POP_THREAD_WARNED.getAndSet(true)) {
            LOGGER.error("INVARIANT VIOLATED: chunk population event delivered off-main "
                            + "(thread={}, level={}, chunk={},{}). Region.fullChunks is not "
                            + "thread-safe; island topology may be corrupt. Please report this "
                            + "with your mod list — logged once per session.",
                    Thread.currentThread().getName(), level.dimension().identifier(),
                    net.minecraft.world.level.ChunkPos.getX(packedChunkPos),
                    net.minecraft.world.level.ChunkPos.getZ(packedChunkPos));
        }
        Region r = regionizer.regionForGridKey(regionizer.grid.keyFor(packedChunkPos));
        // Idempotent membership: the set is truth, the counter is derived. Any
        // combination of duplicate/missing/racing-source events converges.
        if (nowFull) {
            if (r.fullChunks.add(packedChunkPos)) {
                r.fullChunkCount.incrementAndGet();
                islandsDirty = true;
            } else {
                popIgnoredAdds.incrementAndGet();
            }
        } else {
            if (r.fullChunks.remove(packedChunkPos)) {
                r.fullChunkCount.decrementAndGet();
                islandsDirty = true;
            } else {
                popIgnoredRemoves.incrementAndGet();
            }
        }
    }

    /** Defensive self-check (/fabrolia regions popcheck): compare the population
     *  sets against the visible holder map's ground truth. Main thread. */
    public String popCheck() {
        var rta = (RegionTickingAccess) level.getChunkSource().chunkMap;
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap truth = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        int truthTotal = 0;
        for (net.minecraft.server.level.ChunkHolder h : rta.fabrolia$visibleHolders()) {
            if (net.minecraft.server.level.ChunkLevel.fullStatus(h.getTicketLevel())
                    .isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)) {
                truth.addTo(regionizer.grid.keyFor(h.getPos().pack()), 1);
                truthTotal++;
            }
        }
        int tracked = 0, mismatched = 0;
        StringBuilder detail = new StringBuilder();
        for (Region r : regionizer.snapshot()) {
            tracked += r.fullChunks.size();
            int t = truth.get(r.gridKey);
            if (r.fullChunks.size() != t) {
                mismatched++;
                if (detail.length() < 300) {
                    detail.append(String.format(" cell(%d,%d) set=%d truth=%d;",
                            (int) r.gridKey, (int) (r.gridKey >>> 32), r.fullChunks.size(), t));
                }
            }
        }
        return String.format("popcheck %s: tracked=%d truth=%d mismatchedRegions=%d ignoredAdds=%d ignoredRemoves=%d%s",
                mismatched == 0 && tracked == truthTotal ? "OK" : "DRIFT",
                tracked, truthTotal, mismatched, popIgnoredAdds.get(), popIgnoredRemoves.get(),
                detail.length() == 0 ? "" : " |" + detail);
    }

    public List<Island> islands() { return islands; }

    /**
     * gridKey → islandId lookup table, rebuilt together with {@link #islands} in
     * {@link #rebuildIslandsIfDirty}. Faithful replica of
     * {@link IslandScheduledTicks#islandOfChunk} over the rebuild-time state: every
     * populated region's own cell maps to its island, and each of its 8 neighbour
     * cells joins the same island (unambiguous — two populated cells adjacent to the
     * same empty cell are Chebyshev ≤2 apart, hence one island by construction).
     * Misses return -1 = isolated (serial main lane), exactly like the live probe.
     *
     * <p>Why: the partitioners (BE + scheduled ticks) resolved islands through
     * {@code peekByGridKey} → Long2ObjectConcurrentHashMap.get per chunk (up to 9
     * probes on fallback) — 4.7% of the main thread on the 27-player 2026-07-07
     * profile, the single hottest Java method. This table is a few hundred entries
     * in a plain fastutil map: one arithmetic keyFor + one get, no volatile chains.
     * Main-thread only (built at tick HEAD, read inside the level tick).
     */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap gridToIsland =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(512);
    { gridToIsland.defaultReturnValue(-1); }

    /** Immutable cell→domain view (core + 1-cell halo), published at every
     *  rebuild for ANY-THREAD region creation (chunk loads on c2me/main create
     *  regions off the worker context; without this they were born global even
     *  inside island frontiers — the 2026-07-14 fluid cross-domain window). */
    private volatile it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain> cellDomainSnapshot;

    /** Island of a chunk via the rebuild-time table. Main thread only. */
    public int islandForChunk(long packedChunkPos) {
        return gridToIsland.get(regionizer.grid.keyFor(packedChunkPos));
    }

    /** A1 async-islands stage 1: persistent island identities + shadow clocks
     *  (see IslandRegistry / docs/ASYNC-ISLANDS.md). Main-thread only. */
    public final IslandRegistry islandRegistry = new IslandRegistry();
    /** Final-L snapshots for islands no longer in the registry (recorded at
     *  every rebuild + retirement) — tag resolution for late saves of chunks
     *  whose containers still name a retired island. Guarded by its own
     *  monitor (writers: main rebuild; readers: save threads). */
    public final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap islandLastL =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    public IslandRegistry islandRegistry() { return islandRegistry; }

    /** Removed BE tickers awaiting the amortized post-barrier sweep
     *  (main-only; drained and applied by IslandBlockEntityTicks.sweepRemoved). */
    // ------------------------------------------------------------------
    // A2 increment 4 — scheduled-tick plumbing for island units. The vanilla
    // block/fluid LevelTicks.tick calls (main slot) register their scheduler,
    // ticker callback and per-call budget here each tick; units submitted
    // later the same tick collect THEIR due containers from the shared
    // concurrent maps at their own island time and run them through the same
    // vanilla-replica slice. Volatile: written on main pre-submission,
    // read by coordinators (submission is the happens-before edge anyway).
    // ------------------------------------------------------------------
    volatile Object blockTicksAccess;
    volatile Object fluidTicksAccess;
    volatile java.util.function.BiConsumer<?, ?> blockTicker;
    volatile java.util.function.BiConsumer<?, ?> fluidTicker;
    /** Temporal-control sessions (rate overrides + sprint obligations),
     *  lineage-mapped by cell set — see TemporalControl. */
    public final java.util.List<TemporalControl.Session> temporalSessions =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    // (2026-07-14) readyChunkPackets retired with the take-once snapshot
    // store — ChunkDelivery owns a refcounted shared cache instead.

    final java.util.concurrent.atomic.AtomicInteger blockTickBudget =
            new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger fluidTickBudget =
            new java.util.concurrent.atomic.AtomicInteger();

    /** A2 deadline drive — arm-seam decision for chunk-phase work targeting
     *  {@code r} (ServerChunkCacheMixin.groupAndSubmit). 0 = arm + main awaits
     *  the task future (global lane / async off — the pre-A2 behavior);
     *  1 = arm, but main must NOT await (island region: its unit is the
     *  barrier); 2 = skip entirely (island still owes the previous arm —
     *  missed cycle, vanilla-lag semantics). Main thread only. */
    public int armGate(Region r) {
        if (!asyncActive) return 0;
        EpochDomain d = r.domain;
        IslandRegistry.PIsland owner = d != null ? d.owner : null;
        if (owner == null) return 0;
        long gt = level.getGameTime();
        if (owner.armStampGameTime == gt
                || owner.armStampGameTime == owner.armConsumedGameTime) {
            owner.armStampGameTime = gt;
            return 1;
        }
        return 2;
    }

    /** A2: exclusive claim of EVERY island's unit (topology rebuilds, save-all).
     *  Spins per island until its in-flight unit (if any) completes; coordinators
     *  never need main, so this always terminates. Main thread only; paired with
     *  {@link #releaseAllIslands}. */
    private final ArrayList<IslandRegistry.PIsland> quiescedIslands = new ArrayList<>();
    /** True while MAIN holds every island claim (rebuild / save-all window).
     *  Owner-safe save hooks skip their own claiming then — re-claiming a
     *  self-held claim would deadlock (player save) or silently skip every
     *  chunk (incremental save inside saveAllChunks). Main-only. */
    /** Read off-main by IslandTickUnit.complete, the rate pacer, and the chunk
     *  packet builder (which SKIPS its island claim when this is true) — a stale
     *  read there builds a packet while the owner unit mutates the chunk. volatile
     *  since audit 2026-07-26. */
    private volatile boolean mainHoldsAllClaims;

    /** task #19 arrival spacing chain for the global lane (main-only). */
    public final java.util.HashMap<Long, long[]> globalArrivalChains = new java.util.HashMap<>(4);

    public boolean mainHoldsAllClaims() {
        return mainHoldsAllClaims;
    }

    /** A8: the cross-level command fence (IslandCommands.quiesceAllExcept)
     *  marks the window itself — same reentrancy contract as the save-all
     *  quiesce. Main thread only. */
    public void markMainHoldsAllClaims(boolean held) {
        mainHoldsAllClaims = held;
    }

    private long lastQuiesceWarnNanos;

    public boolean quiesceAllIslands() {
        long q0 = System.nanoTime();
        boolean took = quiesceAllIslandsInner();
        long dt = System.nanoTime() - q0;
        if (took && dt > 100_000_000L && q0 - lastQuiesceWarnNanos > 10_000_000_000L) {
            lastQuiesceWarnNanos = q0;
            LOGGER.warn("[async-islands] save/topology quiesce took {} ms on {} — an island's units are long; "
                    + "check /fabrolia regions islands for the overloaded island",
                    dt / 1_000_000, level.dimension().identifier());
        }
        return took;
    }

    private boolean quiesceAllIslandsInner() {
        // Reentrancy: a console `save-all` arrives INSIDE the command fence
        // (CommandsMixin) — main already holds every claim; taking them again
        // would spin on ourselves forever (the 2026-07-12 a8cmd watchdog
        // crash, stack at this line). The outer window's exclusivity covers
        // us; report took=false so the caller skips its release.
        if (mainHoldsAllClaims) {
            return false;
        }
        // A8: a claim can be held by a coordinator PARKED on a routed command
        // — it will not release until main executes the batch, and topology/
        // save windows genuinely need the claim (the island must FINISH its
        // unit, not merely be stable). Release everything, run the batch (the
        // waiter resumes and completes its unit), retry. Converges: each
        // batch completes at least one waiting command and chains are finite.
        retry:
        while (true) {
            for (IslandRegistry.PIsland p : islandRegistry.islands()) {
                long spinStart = System.nanoTime();
                int stuckReports = 0;
                p.claimWanted = true; // rate/sprint pacing yields to the quiesce
                while (!p.unitClaim.compareAndSet(false, true)) {
                    if (System.nanoTime() - spinStart > 5_000_000_000L) {
                        spinStart = System.nanoTime();
                        stuckReports++;
                        LOGGER.error("[async-islands] quiesce stuck {}x5s (tasks={} shards={} L={} asyncActive={}) — retained-claim forensics: {}",
                                stuckReports, p.pendingIslandTasks.size(), p.pendingCommandShards.size(),
                                p.logicalTick, asyncActive, IslandCommands.claimForensics(p));
                        // FORCED RECOVERY (fail-closed continuation): a claim
                        // held with no coordinator running, no waiter, no
                        // pending work, and async collapsing means the holder
                        // is gone — reclaim it rather than watchdog the whole
                        // server. Only ever reached after an invariant already
                        // fired (world safety > liveness of a dead claim).
                        if (stuckReports >= 3 && !asyncActive
                                && !p.waitingOnRoutedCommand) {
                            LOGGER.error("[async-islands] FORCED claim recovery on island#{} — treating holder as dead (post-fail-closed collapse)", p.id);
                            p.unitClaim.set(true);
                            break;
                        }
                    }
                    if (p.waitingOnRoutedCommand) {
                        // The waiter may be blocked on a SHARD whose target
                        // island's claim WE already hold (its coordinator can
                        // never service it) — apply those lanes ourselves:
                        // a main-held island is fully quiescent, so main-side
                        // application is exactly the fence argument. Then run
                        // the routed batch and retry from scratch (a8shard
                        // merge deadlock, 2026-07-12: rebuild quiesce vs a
                        // parked shard-waiter).
                        for (int i = 0, n = quiescedIslands.size(); i < n; i++) {
                            CommandShards.serviceShards(this, quiescedIslands.get(i));
                        }
                        for (int i = 0, n = quiescedIslands.size(); i < n; i++) {
                            quiescedIslands.get(i).clearClaim();
                            quiescedIslands.get(i).unitClaim.set(false);
                        }
                        quiescedIslands.clear();
                        IslandCommands.drainRoutedBatch();
                        continue retry;
                    }
                    IslandCommands.drainMainChunkServices();
                    java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
                }
                p.claimWanted = false;
                // Leak-proof publication (task #25): registration failure must
                // release, never strand the flag outside releaseAllIslands.
                try {
                    p.noteClaim("quiesceAll");
                    quiescedIslands.add(p);
                } catch (Throwable t) {
                    p.clearClaim();
                    p.unitClaim.set(false);
                    throw t;
                }
            }
            break;
        }
        mainHoldsAllClaims = true;
        return true;
    }

    public void releaseAllIslands() {
        mainHoldsAllClaims = false;
        for (int i = 0, n = quiescedIslands.size(); i < n; i++) {
            quiescedIslands.get(i).clearClaim();
            quiescedIslands.get(i).unitClaim.set(false);
        }
        quiescedIslands.clear();
    }

    /** A2 deadline drive — DOMAIN-SCOPED liveness backstop, run by an idle
     *  unit coordinator while it waits on its own barrier. Main's healColorGate
     *  used to cover every strand class from the pump loop, but under the
     *  deadline drive main pumps only its (short) global await — a stranded
     *  island barrier waited for main's NEXT tick, one full 50 ms unit stall
     *  (the a2lag starvation: cost=50.05ms, offset/miss climbing on an idle
     *  island). Mirrors healColorGate's per-region body: phantom blockers
     *  born mid-dispatch get swept done, missed owing regions re-armed,
     *  ran-without-contributing settled into the barrier. */
    void healIslandBarrier(List<Region> members, EpochDomain d) {
        long epoch = d.epoch;
        if (d.dispatchedEpoch != epoch) return;
        boolean pending = d.entityBarrierArmedEpoch == epoch && d.entityPending.get() > 0;
        for (int i = 0, n = members.size(); i < n; i++) {
            Region r = members.get(i);
            if (r.state() != RegionState.ACTIVE) continue;
            if (r.doneEpoch < epoch) {
                ColorGate.tryMarkDone(r, this);
            }
            if (pending && owesEntityPhase(r)) {
                if (ColorGate.eligible(r, this) && r.currentWorker() == null) {
                    RegionScheduler.enqueueReady(r);
                    RegionScheduler.wakeWorkers(1);
                }
            } else if (pending) {
                contributeEntityBarrier(r);
            }
        }
        // Phantom blockers are usually NOT members (fresh cells adjacent to
        // members, created mid-dispatch before dispatchedEpoch was stamped →
        // doneEpoch=0 in OUR timeline): sweep the members' same-domain
        // neighbors too — tryMarkDone carries every refusal guard, so a blind
        // pass is correct.
        ThreadedRegionizer reg = regionizer;
        for (int i = 0, n = members.size(); i < n; i++) {
            Region m = members.get(i);
            int rx = (int) m.gridKey;
            int rz = (int) (m.gridKey >>> 32);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Region nb = reg.peekByGridKey(
                            ((rx + dx) & 0xFFFFFFFFL) | (((long) (rz + dz) & 0xFFFFFFFFL) << 32));
                    if (nb != null && nb.domain == d && nb.state() == RegionState.ACTIVE
                            && nb.doneEpoch < epoch) {
                        ColorGate.tryMarkDone(nb, this);
                    }
                }
            }
        }
    }

    /** Audit: identity handle of this level's entity manager (mixin-side
     *  matching without a level field on the manager). */
    public Object entityManagerHandle() {
        return ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                .fabrolia$entityManager();
    }

    /** A2 save integrity: the island whose unit owns this chunk's timeline,
     *  or null (gap/global/async-off). Any thread (concurrent region map). */
    public IslandRegistry.PIsland islandOwnerOfChunk(long packedChunkPos) {
        Region r = regionizer.peekByGridKey(regionizer.grid.keyFor(packedChunkPos));
        EpochDomain d = r != null ? r.domain : null;
        return d != null ? d.owner : null;
    }

    /** A2: dead tickers collected by island units' BE passes (workers), drained
     *  into {@link #pendingBeSweep} by main's post-slot sweep. */
    public final java.util.concurrent.ConcurrentLinkedQueue<net.minecraft.world.level.block.entity.TickingBlockEntity> unitRemovedBes =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public final java.util.HashSet<net.minecraft.world.level.block.entity.TickingBlockEntity> pendingBeSweep =
            new java.util.HashSet<>();

    // ---- F2b: persistent per-cell block-entity index (2026-07-12) --------------
    //
    // The per-tick partition loop used to walk EVERY wrapper in
    // Level.blockEntityTickers (getPos + ChunkPos.pack + table get + bucket add,
    // ~5.6 ms/tick at ~20k tickers on the 2026-07-11 profiles). Block entities
    // never change chunk, so the cell a wrapper belongs to is a REGISTRATION-TIME
    // constant: index once at addBlockEntityTicker, keep per-cell lists alive
    // across ticks, and the per-tick partition collapses to iterating a few
    // hundred cell lists. Ordering: within a cell, registration order == the
    // relative order the same tickers have in Level.blockEntityTickers (both are
    // appended under LevelMixin's add lock), so per-cell order is exactly vanilla.
    // Cells are stored in an ordered map and iterated ascending by key, so the
    // cross-cell order WITHIN an island is deterministic and stable across island
    // rebuilds (vanilla itself only guarantees chunk-load order here, which varies
    // per session). Execution stays island-granular — one thread per island — so
    // cross-chunk BE interactions inside an island remain serial, exactly as the
    // island isolation model requires.
    //
    // Lifecycle: writes are main-only except the registration stash (workers add
    // tickers via worker-side setBlock; the stash is a lock-free queue). Removal
    // rides the existing amortized sweep, grouped per cell via beCellOf. Sleeping
    // (Lithium) rebinds mutate the wrapper's inner ticker only — list membership
    // and cell are untouched. If the index missed adds (created after tickers
    // already existed), beCellsValid=false forces one bulk rebuild from the
    // vanilla list, which stays authoritatively maintained for mod compat.
    /** A2 deadline-drive safety: CONCURRENT map + copy-on-write cell lists.
     *  Units read their cells across slots once the deadline drive overlaps
     *  units with main's maintenance windows; mutators (beIndexAdd,
     *  sweepRemoved — main only) REPLACE lists instead of editing them, so a
     *  reader mid-pass sees a stale-but-valid list (removed wrappers are
     *  isRemoved-skipped, late adds tick next unit — vanilla pending-flush
     *  semantics). Iteration order is no longer sorted — every ticking path
     *  collects and sorts keys explicitly for deterministic cell order. */
    public final com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap<ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity>> beCells =
            new com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap<>();
    /** Registration sequence per live wrapper (task #14 seam-order fix):
     *  vanilla's BE phase runs the flat list in REGISTRATION order, and for
     *  same-tick moving-piston pairs that mutual order is load-bearing (a 6gt
     *  QC engine straddling a static seam stalls when the two moving_piston
     *  BEs tick in cell-key order instead of creation order — direction-
     *  asymmetric because one travel direction happens to match cell order).
     *  Assigned at beIndexAdd (rebuild walks the vanilla list in order, so
     *  seq order == vanilla order); read by slice mergers on workers —
     *  ConcurrentHashMap for safe concurrent reads against main's fold; the
     *  boxed Long is allocated once per registration. Pruned with beCellOf. */
    public final java.util.concurrent.ConcurrentHashMap<net.minecraft.world.level.block.entity.TickingBlockEntity, Long> beSeqOf =
            new java.util.concurrent.ConcurrentHashMap<>();
    private long beSeqCounter;

    public final it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap<net.minecraft.world.level.block.entity.TickingBlockEntity> beCellOf =
            new it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap<>();
    { beCellOf.defaultReturnValue(Long.MAX_VALUE); } // 0 is a real cell key
    private final java.util.concurrent.ConcurrentLinkedQueue<PendingBeAdd> pendingBeAdds =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger pendingBeAddCount =
            new java.util.concurrent.atomic.AtomicInteger();
    public volatile boolean beCellsValid = false;

    private record PendingBeAdd(net.minecraft.world.level.block.entity.TickingBlockEntity ticker, long cellKey) {}

    /** Cell key for a BE position (pure arithmetic on the fixed grid). */
    public long beCellKeyFor(net.minecraft.core.BlockPos pos) {
        return regionizer.grid.keyFor(net.minecraft.world.level.ChunkPos.pack(pos));
    }

    /** Any thread (under LevelMixin's addBlockEntityTicker lock): stash a fresh
     *  registration for the next partition pass. Pos is captured NOW — after
     *  removal the wrapper rebinds to NULL_TICKER (pos=ZERO) and after Lithium
     *  sleep getPos() is null, so routing later would misfile it. */
    /** A8 hybrid (first increment): tickers whose tick EXECUTES COMMANDS must
     *  never run on an island coordinator — commands reach global state
     *  (scoreboards, @e across islands, /tp) that no single island owns. They
     *  file under the isolated/posless cell and tick on MAIN at W cadence.
     *  Checked once at registration; zero hot-path cost. */
    private static boolean fabroliaIsMainOnlyTicker(net.minecraft.world.level.block.entity.TickingBlockEntity t) {
        // A8: command blocks tick IN their island unit (island cadence, island
        // clock); their EXECUTION is routed per invocation by IslandCommands
        // (island-local inline, everything else exactly-once on the fenced
        // server thread). Only the GameTest harness blocks stay main-only.
        String type = t.getType();
        return "minecraft:test_block".equals(type) || "minecraft:test_instance_block".equals(type);
    }

    public void stashPendingBeAdd(net.minecraft.world.level.block.entity.TickingBlockEntity ticker) {
        net.minecraft.core.BlockPos pos = ticker.getPos();
        pendingBeAdds.add(new PendingBeAdd(ticker,
                pos == null || fabroliaIsMainOnlyTicker(ticker) ? Long.MIN_VALUE : beCellKeyFor(pos)));
        // A2: a BE created from inside an island's own unit (its entity phase
        // placing a block, etc.) must tick THIS tick — vanilla's direct-list
        // add gives exactly that. The persistent index only adopts it at the
        // next maintenance pass, so hand it to the island's same-tick pending
        // list, drained by the unit's BE pass.
        if (asyncActive && !fabroliaIsMainOnlyTicker(ticker)
                && Thread.currentThread() instanceof RegionWorkerThread w) {
            Region cur = w.currentRegion;
            EpochDomain d = cur != null ? cur.domain : w.currentDomain;
            IslandRegistry.PIsland p = d != null ? d.owner : null;
            // ROUTE BY POSITION, not creating context (2026-07-17 fail-closed:
            // a flying machine's B36 created by island A's cascade in a cell
            // that a rebuild then gave island B stayed in A's same-tick list —
            // and the carry kept A ticking it cross-domain against B's live
            // unit). Same-island position → vanilla same-tick parity via the
            // pending list; anything else (other island / frontier / unresolved
            // / posless) waits for the fold and ticks with its true owner.
            if (p != null && pos != null) {
                IslandRegistry.PIsland posOwner = islandOwnerOfChunk(
                        net.minecraft.world.level.ChunkPos.pack(pos));
                if (posOwner != p) {
                    p = null;
                }
            }
            if (p != null) {
                synchronized (p.pendingUnitBes) {
                    p.pendingUnitBes.add(ticker);
                }
            }
        }
        // Backstop for long vanilla-fallback stretches (partitioned path toggled
        // off = nobody drains): past this bound, drop the stash and force one
        // bulk rebuild from the vanilla list on the next partitioned pass.
        if (pendingBeAddCount.incrementAndGet() > 65_536) {
            beCellsValid = false;
        }
    }

    /** Main, partition start: fold stashed registrations into the cell index.
     *  Long.MIN_VALUE (posless custom tickers) lands in the isolated lane's cell. */
    void drainPendingBeAdds() {
        PendingBeAdd p;
        while ((p = pendingBeAdds.poll()) != null) {
            pendingBeAddCount.decrementAndGet();
            beIndexAdd(p.ticker, p.cellKey);
        }
    }

    private void beIndexAdd(net.minecraft.world.level.block.entity.TickingBlockEntity t, long cellKey) {
        // Copy-on-write (see beCells doc): never mutate a published list.
        ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity> list = beCells.get(cellKey);
        ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity> next =
                list == null ? new ArrayList<>(8) : new ArrayList<>(list);
        next.add(t);
        beCells.put(cellKey, next);
        beCellOf.put(t, cellKey);
        beSeqOf.putIfAbsent(t, beSeqCounter++);
    }

    /** Main: one-time (re)build of the cell index from the authoritative vanilla
     *  list. Runs after the pending flush so the list is complete; clears the
     *  stash (its entries are already in the list). A ticker asleep at rebuild
     *  time (pos=null) files under the isolated cell — it still ticks (no-op
     *  while sleeping) and wakes correctly; only its lane placement is off. */
    void rebuildBeCells(java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> src) {
        beCells.clear();
        beCellOf.clear();
        beSeqOf.clear();
        beSeqCounter = 0;
        pendingBeAdds.clear();
        pendingBeAddCount.set(0);
        for (int i = 0, m = src.size(); i < m; i++) {
            net.minecraft.world.level.block.entity.TickingBlockEntity t = src.get(i);
            net.minecraft.core.BlockPos pos = t.getPos();
            beIndexAdd(t, pos == null || fabroliaIsMainOnlyTicker(t)
                    ? Long.MIN_VALUE : beCellKeyFor(pos));
        }
        beCellsValid = true;
    }

    /** Island of a grid CELL key (the raw table read; islandForChunk wraps this
     *  with the chunk→cell conversion). Main thread only. */
    public int islandForCell(long gridKey) {
        return gridToIsland.get(gridKey);
    }

    /** Diagnostic (/fabrolia regions beindex): cross-check the persistent cell
     *  index against the authoritative vanilla list. Main thread, between phases.
     *  Stash backlog (registered but not yet drained into cells) is expected and
     *  reported, not drift. */
    public String beIndexVerify() {
        if (!beCellsValid) {
            return "beindex: not built yet (first partitioned pass pending)";
        }
        java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> src = level.blockEntityTickers;
        java.util.IdentityHashMap<net.minecraft.world.level.block.entity.TickingBlockEntity, Boolean> inCells =
                new java.util.IdentityHashMap<>(Math.max(16, beCellOf.size() * 2));
        int cellEntries = 0;
        int cellOfMismatch = 0;
        for (var e : beCells.long2ObjectEntrySet()) {
            long cellKey = e.getLongKey();
            java.util.ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity> list = e.getValue();
            for (int i = 0, m = list.size(); i < m; i++) {
                var t = list.get(i);
                cellEntries++;
                inCells.put(t, Boolean.TRUE);
                if (beCellOf.getLong(t) != cellKey) cellOfMismatch++;
            }
        }
        int missing = 0;
        java.util.IdentityHashMap<net.minecraft.world.level.block.entity.TickingBlockEntity, Boolean> inList =
                new java.util.IdentityHashMap<>(Math.max(16, src.size() * 2));
        for (int i = 0, m = src.size(); i < m; i++) {
            var t = src.get(i);
            inList.put(t, Boolean.TRUE);
            if (!inCells.containsKey(t)) missing++;
        }
        // Cell entries absent from the vanilla list: REMOVED ones are the amortized
        // sweep's pending backlog (vanilla's fallback pass prunes its own list
        // inline while our sweep waits for the batch trigger) — behaviorally
        // invisible, self-healing, benign. A LIVE ticker absent from the list is
        // real drift.
        int staleRemoved = 0;
        int staleLive = 0;
        for (var e : beCells.long2ObjectEntrySet()) {
            java.util.ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity> list = e.getValue();
            for (int i = 0, m = list.size(); i < m; i++) {
                var t = list.get(i);
                if (!inList.containsKey(t)) {
                    if (t.isRemoved()) staleRemoved++; else staleLive++;
                }
            }
        }
        int stash = pendingBeAddCount.get();
        boolean ok = cellOfMismatch == 0 && missing <= stash && staleLive == 0;
        return String.format("beindex %s: list=%d cells=%d(%d) missing=%d stash=%d staleLive=%d staleRemoved=%d cellOfMismatch=%d sweepPending=%d",
                ok ? "OK" : "DRIFT", src.size(), cellEntries, beCells.size(), missing, stash, staleLive,
                staleRemoved, cellOfMismatch, pendingBeSweep.size());
    }

    // ---- heal snapshot cache (main-only; see RegionScheduler.healColorGate) ----
    // The liveness backstop ran a synchronized ArrayList copy of the whole region
    // map every 2 ms (0.9 ms/tick of copies at 60 ms ticks, 2026-07-11 profiles).
    // Orphan regions it exists to catch appear on region CREATION; a snapshot a
    // few dozen ms stale only delays healing a brand-new orphan by that much —
    // harmless next to the 5 s stall watchdog. 50 ms reuse window.
    private java.util.List<Region> healSnapshot;
    private long healSnapshotNanos;

    public java.util.List<Region> healSnapshot(long now) {
        if (healSnapshot == null || now - healSnapshotNanos > 50_000_000L) {
            healSnapshot = regionizer.snapshot();
            healSnapshotNanos = now;
        }
        return healSnapshot;
    }

    /** Main thread, level tick HEAD (after the epoch bump). BFS over the
     *  populated set with radius-2 connectivity; ~450 regions × 25 lock-free
     *  peeks — µs-scale, and only on ticket-transition ticks. */
    public void rebuildIslandsIfDirty() {
        if (!islandsDirty) return;
        islandsDirty = false;
        // A2 deadline drive: units may be in flight at any slot — topology
        // work (BFS, unstash, registry diff, domain reassignment) requires
        // every unit quiescent. Sync drive: claims are always free, so this
        // is a no-op pass of CAS successes.
        // Quiesce whenever units could be in flight: async ON, or the
        // ON→OFF collapse pass (asyncActive already false but last tick's
        // units may still run — the fail-closed rig caught the un-quiesced
        // collapse racing them, 2026-07-12).
        boolean quiesced = (asyncActive || domainsIslandized) && quiesceAllIslands();
        try {
            rebuildIslandsBody();
        } finally {
            if (quiesced) releaseAllIslands();
        }
    }

    private void rebuildIslandsBody() {
        List<Region> regions = regionizer.snapshot();
        ArrayList<Region> populated = new ArrayList<>(regions.size());
        // Box-free local grid index for the BFS below. rebuildIslandsIfDirty runs
        // single-threaded on the server thread at level-tick HEAD, so a plain
        // fastutil map is safe and its get(long) does NOT box the key. The previous
        // BFS called regionizer.peekByGridKey (→ Long2ObjectConcurrentHashMap.get →
        // Long.valueOf) for all 24 neighbours of every populated region: 14.6% of
        // ALL server-thread allocation on the 49-player profile (VPNF4kBFWS, 2026-06-27),
        // because islandsDirty latches on nearly every chunk full/unfull transition.
        // Only populated regions are indexed, so the per-neighbour state/full checks
        // collapse into a single map hit.
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Region> byGrid =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(regions.size() * 2);
        for (int i = 0, n = regions.size(); i < n; i++) {
            Region r = regions.get(i);
            if (r.state() == RegionState.ACTIVE && r.fullChunkCount.get() > 0) {
                populated.add(r);
                byGrid.put(r.gridKey, r);
            } else {
                r.islandId = -1;
            }
        }
        ArrayList<Island> result = new ArrayList<>();
        java.util.HashSet<Region> visited = new java.util.HashSet<>(populated.size() * 2);
        java.util.ArrayDeque<Region> frontier = new java.util.ArrayDeque<>();
        for (int i = 0, n = populated.size(); i < n; i++) {
            Region seed = populated.get(i);
            if (!visited.add(seed)) continue;
            int id = result.size();
            ArrayList<Region> members = new ArrayList<>(8);
            long load = 0;
            frontier.clear();
            frontier.add(seed);
            while (!frontier.isEmpty()) {
                Region cur = frontier.poll();
                cur.islandId = id;
                members.add(cur);
                load += cur.entityCount.get();
                int rx = (int) cur.gridKey;
                int rz = (int) (cur.gridKey >>> 32);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        long nKey = ((rx + dx) & 0xFFFFFFFFL) | (((long) (rz + dz) & 0xFFFFFFFFL) << 32);
                        Region nb = byGrid.get(nKey);   // box-free; only populated regions present
                        if (nb != null && visited.add(nb)) {
                            frontier.add(nb);
                        }
                    }
                }
            }
            result.add(new Island(id, members, load));
        }
        islands = result;

        // Refresh the gridKey → islandId table (see field docs). Own cells first,
        // then the 8-neighbour closure — putIfAbsent so a populated cell's own id
        // always wins over a neighbour's spill.
        gridToIsland.clear();
        for (int ii = 0, in = result.size(); ii < in; ii++) {
            Island isl = result.get(ii);
            List<Region> members = isl.members();
            for (int mi = 0, mn = members.size(); mi < mn; mi++) {
                gridToIsland.put(members.get(mi).gridKey, isl.id());
            }
        }
        for (int ii = 0, in = result.size(); ii < in; ii++) {
            Island isl = result.get(ii);
            List<Region> members = isl.members();
            for (int mi = 0, mn = members.size(); mi < mn; mi++) {
                long gk = members.get(mi).gridKey;
                int rx = (int) gk;
                int rz = (int) (gk >>> 32);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        long nKey = ((rx + dx) & 0xFFFFFFFFL) | (((long) (rz + dz) & 0xFFFFFFFFL) << 32);
                        gridToIsland.putIfAbsent(nKey, isl.id());
                    }
                }
            }
        }
        // A2: topology is about to re-shape — collect undelivered per-island
        // carry-state BEFORE ownership changes and re-deliver it to the NEW
        // owners AFTER (2026-07-13 flying-machine merge fix). The old shape
        // returned stashed block events to the shared queue, which bounced
        // EVERY island's pending piston events by one tick on EVERY rebuild
        // (main's next slot had to re-partition them) — with bots churning
        // population, that was a machine hiccup on every topology change.
        // Pending deferrals → the global queue (main replays this tick);
        // mid-unit BE registrations are re-delivered to the new owner so the
        // vanilla same-tick first-tick guarantee survives the migration.
        java.util.ArrayList<IslandRegistry.PIsland.StampedEvent> unstashedEvs = null;
        java.util.ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity> unstashedBes = null;
        java.util.ArrayList<Long> unstashedSnapshotKeys = null;
        // audit13: pre-rebuild island set, captured for the ARRIVALS carry that
        // now runs AFTER the topology commit (see the reroute loop below) —
        // applyRebuild removes retired identities from the registry, so a
        // post-commit walk of islandRegistry.islands() would miss exactly the
        // islands whose queues most need carrying.
        java.util.ArrayList<IslandRegistry.PIsland> arrivalCarryIslands = null;
        if (asyncActive || domainsIslandized) {
            arrivalCarryIslands = new java.util.ArrayList<>(islandRegistry.islands());
            for (IslandRegistry.PIsland p : islandRegistry.islands()) {
                var evs = p.takeAllStampedEvents(level.getGameTime());
                if (evs != null) {
                    if (unstashedEvs == null) unstashedEvs = new java.util.ArrayList<>();
                    unstashedEvs.addAll(evs);
                }
                Runnable op;
                while ((op = p.domain.deferredOps.poll()) != null) {
                    globalDomain.deferredOps.add(op);
                }
                synchronized (p.pendingUnitBes) {
                    if (!p.pendingUnitBes.isEmpty()) {
                        if (unstashedBes == null) unstashedBes = new java.util.ArrayList<>();
                        unstashedBes.addAll(p.pendingUnitBes);
                        p.pendingUnitBes.clear();
                    }
                }
                // Chunk-packet snapshot requests (2026-07-14 stream stall):
                // requests queued to a RETIRING island were stranded forever
                // (no unit ever drains a retired island — production showed
                // unitBuilds at HALF of requests under 50-bot topology churn).
                // Collect them; re-delivered to current owners post-assignment.
                // (Cross-dim ARRIVALS are deliberately NOT carried here any
                // more — see the audit13 reroute loop after the topology
                // commit below.)
                Long snapKey;
                while ((snapKey = p.pendingChunkSnapshots.poll()) != null) {
                    p.pendingSnapshotKeys.remove(snapKey);
                    if (unstashedSnapshotKeys == null) {
                        unstashedSnapshotKeys = new java.util.ArrayList<>();
                    }
                    unstashedSnapshotKeys.add(snapKey);
                }
                // A8 Phase 2: pending command shards re-partition by CURRENT
                // ownership (invocation identity preserved; committed shards
                // untouched; no duplication — the original is consumed).
                java.util.ArrayList<Object> drained = null;
                synchronized (p.pendingCommandShards) {
                    if (!p.pendingCommandShards.isEmpty()) {
                        drained = new java.util.ArrayList<>(p.pendingCommandShards);
                        p.pendingCommandShards.clear();
                    }
                }
                if (drained != null) {
                    CommandShards.repartitionAt(this, p, drained);
                }
            }
        }

        // A1: keep persistent island identities + shadow clocks in step with the
        // fresh BFS result (merge/split/born/retire events, stable ids).
        islandRegistry.applyRebuild(islands, level.getGameTime());

        // A2: (re)point every region at its timeline. Safe point by construction:
        // this runs at level-tick HEAD on the server thread — in the sync-drive
        // increment no unit is ever in flight here; the worker-drive stage adds
        // an explicit quiesce before rebuild. Merges move absorbed members onto
        // the survivor's domain, splits move fragment members onto the fresh
        // fragment domain; either way assignDomain resets every epoch stamp
        // into the new timeline.
        if (asyncActive) {
            for (int ii = 0, in = result.size(); ii < in; ii++) {
                List<Region> members = result.get(ii).members();
                IslandRegistry.PIsland p = members.isEmpty()
                        ? null : islandRegistry.byCell(members.get(0).gridKey);
                if (p != null) p.level = level;
                EpochDomain d = p == null ? globalDomain : p.domain;
                for (int mi = 0, mn = members.size(); mi < mn; mi++) {
                    assignDomain(members.get(mi), d);
                }
            }
            // FRONTIER HALO (2026-07-14 fluid cross-domain fix): regions in
            // the 1-cell closure around an island's populated core carry the
            // ISLAND's domain, not global. Vanilla edge mechanics (fluid
            // spreadTo, fire, piston heads) legally mutate one chunk past the
            // core — and a frontier chunk can be promoted to FULL mid-tick
            // (fresh generation), after this rebuild already ran. With the
            // halo the write target is in the island's own timeline either
            // way. Halo regions contribute no tick work (their containers
            // stay dormant per vanilla non-ticking rules; a container that
            // becomes ticking implies a FULL chunk → popChanged already
            // latched islandsDirty → adopted as CORE at the next tick head,
            // i.e. at most one tick of vanilla-style promotion delay).
            // gridToIsland already holds exactly this closure (putIfAbsent:
            // core id wins; disjointness of two islands' halos is implied by
            // the ≤2-cell merge rule). Everything else stays global.
            for (IslandRegistry.PIsland p : islandRegistry.islands()) {
                p.haloCells.clear();
            }
            var cellDomains = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain>(
                    gridToIsland.size() * 2);
            for (int i = 0, n = regions.size(); i < n; i++) {
                Region r = regions.get(i);
                if (r.islandId != -1) continue;
                int hid = gridToIsland.get(r.gridKey);
                IslandRegistry.PIsland hp = null;
                if (hid >= 0 && hid < result.size()) {
                    List<Region> hm = result.get(hid).members();
                    hp = hm.isEmpty() ? null : islandRegistry.byCell(hm.get(0).gridKey);
                }
                if (hp != null) {
                    r.islandId = hid;
                    assignDomain(r, hp.domain);
                } else {
                    assignDomain(r, globalDomain);
                }
            }
            // haloCells must be CELL-complete (every closure cell, region
            // present or not): the per-unit done-stamp walks these cells, and
            // a region BORN later in a halo cell must be stamped from the
            // same walk (04:50 watchdog: region-present-only tracking left
            // newborn frontier regions permanently blocking core eligibility).
            for (var e : gridToIsland.long2IntEntrySet()) {
                int id = e.getIntValue();
                if (id < 0 || id >= result.size()) continue;
                List<Region> hm = result.get(id).members();
                IslandRegistry.PIsland hp = hm.isEmpty() ? null : islandRegistry.byCell(hm.get(0).gridKey);
                if (hp != null && !hp.cells.contains(e.getLongKey())) {
                    hp.haloCells.add(e.getLongKey());
                }
            }
            // Publish the creation-time cell→domain view (core + halo — also
            // cells with no region object yet, so a region BORN mid-tick in a
            // frontier cell joins the island timeline immediately).
            for (var e : gridToIsland.long2IntEntrySet()) {
                int id = e.getIntValue();
                if (id < 0 || id >= result.size()) continue;
                List<Region> hm = result.get(id).members();
                IslandRegistry.PIsland hp = hm.isEmpty() ? null : islandRegistry.byCell(hm.get(0).gridKey);
                if (hp != null) cellDomains.put(e.getLongKey(), hp.domain);
            }
            cellDomainSnapshot = cellDomains;
            domainsIslandized = true;
            synchronized (islandLastL) {
                for (IslandRegistry.PIsland p : islandRegistry.islands()) {
                    islandLastL.put(p.id, p.logicalTick);
                }
            }
        } else if (domainsIslandized) {
            // ON→OFF flip: collapse every region back onto the global timeline
            // before the next global dispatch stamps anything. Carry-state was
            // collected above (this branch previously skipped the unstash
            // entirely — stashed piston events and mid-unit BE registrations
            // were STRANDED on the dormant islands: machines froze mid-stroke
            // on toggle, 2026-07-13). assignDomain also clock-rebases each
            // region's pending scheduled ticks from island L to real time.
            for (int i = 0, n = regions.size(); i < n; i++) {
                assignDomain(regions.get(i), globalDomain);
            }
            cellDomainSnapshot = null;
            for (IslandRegistry.PIsland p : islandRegistry.islands()) {
                p.haloCells.clear();
            }
            domainsIslandized = false;
        }
        // audit13 — ARRIVALS carry, AFTER the topology commit (applyRebuild set
        // `retired`, the assignment passes above re-pointed every region's
        // domain, the cell snapshot is republished). The old pre-applyRebuild
        // reroute re-resolved each op against the OLD topology: a retiring or
        // merge-absorbed island was still the resolved owner of its own cells
        // and `retired` was still false, so route() re-enqueued the drained
        // queue straight back onto the dying island — stranded forever once
        // applyRebuild removed it (no unit drains a retired island;
        // publishLevelPending iterates only live ones). It also left audit11's
        // monitor re-check without its ordering leg: a cross-level route()
        // between the drain and the LATER retired=true write legitimately saw
        // retired==false and landed on the soon-dead island. With the flag
        // committed BEFORE this drain, a racing writer either enqueues before
        // the drain (carried here) or observes `retired` under the monitor and
        // falls through to the destination's global lane — exactly once either
        // way. Covers the async rebuild AND the ON→OFF collapse (where every
        // op re-resolves ownerless onto the global lane, as before).
        if (arrivalCarryIslands != null) {
            for (int i = 0, n = arrivalCarryIslands.size(); i < n; i++) {
                CrossDimArrivals.reroute(this, arrivalCarryIslands.get(i));
            }
        }
        // Re-deliver carry-state under the NEW ownership: events straight to
        // their owner's stash (same-tick execution at the owner's next unit —
        // no shared-queue bounce); ownerless (gap / async-off) events to the
        // shared queue, which main's block-event slot drains later THIS tick
        // (rebuild runs at tick head). Pending BEs re-bridge to the new owner
        // so a mid-migration moving piston keeps its same-tick first tick;
        // ownerless ones are already covered by the vanilla list + index
        // dual-write.
        if (unstashedEvs != null) {
            BlockEventQueueAccess acc = (BlockEventQueueAccess) level;
            for (int i = 0, n = unstashedEvs.size(); i < n; i++) {
                IslandRegistry.PIsland.StampedEvent ev = unstashedEvs.get(i);
                IslandRegistry.PIsland owner = asyncActive
                        ? islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(ev.ev().pos()))
                        : null;
                if (owner != null) {
                    // STAMP-PRESERVING carry (2026-07-16 audit: the old
                    // restamp collapsed every pending generation into one
                    // on each topology rebuild — cursor/due-pacing defeat).
                    owner.stashStamped(ev);
                } else {
                    synchronized (acc.fabrolia$blockEventLock()) {
                        acc.fabrolia$blockEvents().add(ev.ev());
                    }
                }
            }
        }
        if (unstashedSnapshotKeys != null && asyncActive) {
            // Delivery rewrite: leases stranded on a retiring/reshaped island
            // re-resolve to the CURRENT owner (region domain, halo-aware) —
            // never lost, never duplicated, no sender-side recollection.
            for (int i = 0, n = unstashedSnapshotKeys.size(); i < n; i++) {
                ChunkDelivery.requeueToCurrentOwner(this, unstashedSnapshotKeys.get(i));
            }
        }
        // Temporal sessions re-resolve against the fresh topology (merge
        // expansion / split inheritance — TemporalControl lineage rules).
        if (asyncActive) {
            TemporalControl.remap(this);
        }
        if (unstashedBes != null && asyncActive) {
            for (int i = 0, n = unstashedBes.size(); i < n; i++) {
                net.minecraft.world.level.block.entity.TickingBlockEntity tbe = unstashedBes.get(i);
                IslandRegistry.PIsland owner =
                        islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(tbe.getPos()));
                if (owner != null) {
                    synchronized (owner.pendingUnitBes) {
                        owner.pendingUnitBes.add(tbe);
                    }
                }
            }
        }
    }

    /** A2: move {@code r} onto timeline {@code d}, translating its epoch stamps
     *  into the new domain's present: complete for d's CURRENT epoch, owing
     *  nothing — the next dispatch that includes it bumps the epoch first and
     *  stamps it afresh. MUST only run at a safe point (no dispatch/unit in
     *  flight in either domain); the CAS-gate resets below would otherwise
     *  race live contributions. */
    private static void assignDomain(Region r, EpochDomain d) {
        if (r.domain == d) return;
        // DOMAIN-MIGRATION DRAIN (2026-07-13 watchdog wedge): a region born on
        // the global lane may carry cf-AWAITED arm tasks (gate 0: main pumps
        // the future) when the rebuild islandizes it — after the flip, its
        // queue belongs to the unit's entity-phase drain, the ready-lane claim
        // is refused (ColorGate.inUnitWindow), and main wedges on the cf until
        // the watchdog fires (repro: 50 bot spawns into fresh terrain, 22:58
        // run). Settle the queue NOW, at the rebuild safe point: claim + drain
        // runs the pending arm inline (pre-migration main-cadence semantics
        // for exactly one arm) and completes its futures. A claim we cannot
        // take means a worker is mid-drain and will empty the queue itself;
        // work submitted after this drain is futureless by construction
        // (executeAt / gated arms) and correctly waits for the next unit.
        if (d.owner != null && !r.queue().isEmpty()
                && r.tryClaim(Thread.currentThread())) {
            r.stampAcquire("migrate");
            try {
                r.queue().drain(Integer.MAX_VALUE);
            } finally {
                r.stampRelease("migrate");
                r.release(Thread.currentThread());
            }
        }
        // CLOCK REBASE (2026-07-13, flying machines break at merge/toggle):
        // pending scheduled block/fluid ticks in this region's chunks carry
        // trigger times in the OLD domain's logical clock. Islands tick on
        // independent L-clocks that lag real time under load, so migrating a
        // region without translating its triggers makes every pending
        // repeater/observer/piston tick read as N-ticks-overdue (burst-fires
        // in one phase — machines tear or REVERSE when engine pulses collapse)
        // or N-ticks-future (machines freeze). Vanilla's own save format
        // stores ticks RELATIVE for exactly this reason. Translate every
        // pending tick by (newNow − oldNow): a tick due in 2 ticks on the old
        // clock stays due in 2 ticks on the new one — order (triggerTick,
        // subTickOrder) preserved exactly. Safe point: main, both domains
        // quiescent, so getGameTime() here reads the RAW counter.
        // Tag-driven per-container rebase (task #31 rework): each container is
        // translated from ITS OWN tagged domain — not from an assumed
        // region-wide old domain — so containers that entered this region in a
        // different domain (freshly loaded chunks, missed transitions) land
        // correctly too. Old-owner final L is snapshotted first so any
        // container still tagged to a dying island stays resolvable.
        EpochDomain old = r.domain;
        RegionizedServer rsSelf = RegionScheduler.forLevel(r.level);
        if (old != null && old.owner != null && rsSelf != null) {
            synchronized (rsSelf.islandLastL) {
                rsSelf.islandLastL.put(old.owner.id, old.owner.logicalTick);
            }
        }
        long newNow = d.owner != null
                ? d.owner.logicalTick : r.level.getGameTime();
        long targetId = d.owner != null ? d.owner.id
                : com.axalotl.fabrolia.common.regions.FabroliaTickDomain.WALL;
        long targetGen = d.owner != null ? d.owner.gen : -1;
        int moved = 0;
        {
            LevelTicksIslandAccess<?> blocks = (LevelTicksIslandAccess<?>) r.level.getBlockTicks();
            LevelTicksIslandAccess<?> fluids = (LevelTicksIslandAccess<?>) r.level.getFluidTicks();
            it.unimi.dsi.fastutil.longs.LongIterator itc = r.fullChunks.iterator();
            while (itc.hasNext()) {
                long chunkKey = itc.nextLong();
                Object bc = blocks.fabrolia$allContainers().get(chunkKey);
                if (bc instanceof net.minecraft.world.ticks.LevelChunkTicks<?> c) {
                    moved += TickDomains.rebaseTo(r.level, c, targetId, targetGen, newNow);
                }
                Object fc = fluids.fabrolia$allContainers().get(chunkKey);
                if (fc instanceof net.minecraft.world.ticks.LevelChunkTicks<?> c) {
                    moved += TickDomains.rebaseTo(r.level, c, targetId, targetGen, newNow);
                }
            }
        }
        if (moved > 0) {
            FlightRecorder.record("REBASE " + r.level.dimension().identifier() + " region=" + Long.toHexString(r.gridKey)
                    + " -> " + (d.owner != null ? "island#" + d.owner.id : "global")
                    + "(now=" + newNow + ") ticks=" + moved);
        }
        r.domain = d;
        // TOGGLE-DEADLOCK FIX (2026-07-13, user repro: /fabrolia regions
        // async OFF wedged the very next tick): stamps must anchor STRICTLY
        // BEFORE the next dispatch in the new timeline. The OFF-collapse runs
        // AFTER the tick-head global epoch bump but BEFORE that same tick's
        // sync dispatch at epoch E — resetting stamps to E made every region
        // read as already-enqueued/contributed/ticked for E: seeding CAS
        // refused, barrier armed with pending=N and zero workers ever given
        // the work, contribution CAS refused, owed=false so no heal path —
        // the pump hung forever. Anchoring at E-1 leaves every gate open for
        // E; no-work regions are swept done by the dispatch as usual. Island
        // assignments are equally safe: units bump their epoch before
        // dispatching, so E-1 is strictly before E+1 too.
        long e = d.epoch - 1;
        r.doneEpoch = e;
        r.groupedEpoch = e;
        r.entityEpoch = e;
        r.entityEnqueuedEpoch.set(e);
        r.entityBarrierEpoch.set(e);
    }

    private long evictedTotal;
    private int evictLogThrottle;

    /** Queue a cross-region effect (see {@link CrossRegionGuard}) for post-barrier
     *  replay, routed by TARGET position: effects landing in an island go to that
     *  island's timeline (replayed by its unit coordinator, island-internally
     *  ordered exactly like the old main replay); everything else — gap regions,
     *  unknown territory — goes to the global queue main drains. Sync mode: every
     *  region is on the global domain, so this is exactly the old behavior. The
     *  region map is concurrent, so the routing lookup is worker-safe. */
    public void deferCrossRegion(Runnable op, net.minecraft.core.BlockPos target) {
        Region r = regionizer.peekByGridKey(
                regionizer.grid.keyFor(target.getX() >> 4, target.getZ() >> 4));
        EpochDomain d = r != null && r.domain != null ? r.domain : globalDomain;
        d.deferredOps.add(op);
    }

    /** Target-less fallback: route by the CALLING context's timeline (a worker
     *  mid-phase defers into its own island; anything else goes global). */
    public void deferCrossRegion(Runnable op) {
        EpochDomain d = null;
        if (Thread.currentThread() instanceof RegionWorkerThread w) {
            Region r = w.currentRegion;
            d = r != null ? r.domain : w.currentDomain;
        }
        (d != null ? d : globalDomain).deferredOps.add(op);
    }

    /** Replay GLOBAL-domain deferred effects on the server thread AFTER the region
     *  phase barrier, when every region is quiescent. Replays run on main, so
     *  CrossRegionGuard sees no claimed worker and lets them propagate fully and
     *  single-threaded. Island-domain queues are drained by their unit
     *  coordinators (see IslandTickUnit). */
    public void drainDeferredCrossRegion() {
        drainDeferredOps(globalDomain);
    }

    /** Drain one timeline's deferral queue in submission order (single drainer
     *  per domain per tick by construction). */
    static void drainDeferredOps(EpochDomain d) {
        Runnable op;
        while ((op = d.deferredOps.poll()) != null) {
            try {
                op.run();
            } catch (Throwable t) {
                LOGGER.error("[cross-region] deferred effect threw", t);
            }
        }
    }


    /**
     * Reclaim idle regions (server thread, level-tick HEAD). Lazily-created regions
     * were never removed, so the region map — and every O(regions) scan over it
     * (island rebuild, worker dispatch) — grew without bound as players explored,
     * climbing mspt monotonically. See {@link ThreadedRegionizer#evictIdle()}.
     */
    public void evictIdleRegions() {
        if (FabroliaConfig.disabled || !FabroliaConfig.fabroliaRegionEviction) {
            return;
        }
        int removed = regionizer.evictIdle(this);
        if (removed > 0) {
            // Evicting a blocker re-armed (enqueued) its higher-colour dependents via
            // ColorGate.markDone — wake the pool so those re-armed regions are actually
            // claimed before the chunk phase's stale-spawn-future await can park on them.
            RegionScheduler.wakeWorkers();
            evictedTotal += removed;
            // Throttle: a long flight reclaims a steady trickle; surface a heartbeat
            // roughly once a minute so the operator can see it working without spam.
            if (++evictLogThrottle >= 1200) {
                evictLogThrottle = 0;
                LOGGER.info("[region-evict] {} reclaimed this pass, {} total, {} live ({})",
                        removed, evictedTotal, regionizer.regionCount(),
                        level.dimension().identifier());
            }
        }
    }

    public Region regionFor(ChunkPos pos) {
        return regionizer.regionForChunk(pos);
    }

    /** Item-sync monitor for the region owning this chunk (create-if-absent; skips the
     *  synchronized noteChunk debug bookkeeping of {@link #regionFor(ChunkPos)} — this is
     *  called per item interaction). See {@link FabroliaLocks#itemLockOr}. */
    public Object itemMonitor(long packedChunkPos) {
        return regionizer.regionForGridKey(regionizer.grid.keyFor(packedChunkPos)).lock;
    }

    public Region regionFor(long packedChunkPos) {
        return regionizer.regionForChunk(packedChunkPos);
    }

    public List<Region> snapshot() {
        return regionizer.snapshot();
    }

    public int regionCount() {
        return regionizer.regionCount();
    }

    // ---------------------------------------------------------------------
    // Region-owned entity index
    // ---------------------------------------------------------------------

    void indexAdd(Entity e) {
        long key = regionizer.grid.keyFor(e.chunkPosition().pack());
        Region r = regionizer.regionForGridKey(key);
        if (r.entities.add(e)) r.entityCount.incrementAndGet();
        ((RegionKeyed) e).fabrolia$setRegionKey(key);
        // VANILLA ADD-DURING-ITERATION DEFERRAL (2026-07-22 duper lifecycle
        // parity, pushtrace-proven): vanilla's EntityTickList never visits an
        // entity added while the tick pass is iterating — its first tick is
        // NEXT tick. The region drain iterated the live skip-list, so entities
        // created mid-drain (falling-block → item conversions, mid-phase
        // spawns) ticked the SAME tick: their squeeze-escape impulse fired one
        // tick early (measured t=416 vs vanilla t=417 on the End duper) — the
        // documented \"may tick this tick or next\" relaxation, now closed.
        // Consuming the current epoch's tick token makes the in-flight drain
        // skip the newcomer; it first-ticks next epoch, exactly vanilla.
        // Phase-head arrivals (CrossDimArrivals) publish BEFORE regions are
        // claimed — currentWorker is null there, so End-portal copies keep
        // their vanilla same-server-tick first tick.
        //
        // HANDOFF-PHASE INVARIANT ENFORCEMENT (operator spec 2026-07-24): a
        // cross-dimension PUBLISH must make the arrival eligible in exactly this
        // (publication) epoch — never deferred to a later one. Normally the
        // publish runs at the unit head with currentWorker null, so the deferral
        // below is already skipped; this guard makes it robust against ANY path
        // that publishes while a worker is recorded on the region (a partially
        // claimed region mid-rebuild): a cross-dim arrival is never token-consumed
        // out of its own publication epoch, so it ticks exactly once at the
        // defined boundary and its arrival velocity is applied without an
        // off-phase gap. Non-publish mid-iteration adds keep the vanilla
        // "added-during-tick ⇒ ticks next tick" deferral.
        if (r.currentWorker() != null && r.domain != null
                && !CrossDimArrivals.PUBLISHING.get()) {
            ((RegionKeyed) e).fabrolia$tryMarkRegionTick(r.domain.tickToken());
        }
    }

    void indexRemove(Entity e) {
        RegionKeyed keyed = (RegionKeyed) e;
        long key = keyed.fabrolia$getRegionKey();
        if (key == RegionKeyed.NO_REGION) return;
        Region r = regionizer.peekByGridKey(key);
        if (r != null && r.entities.remove(e)) r.entityCount.decrementAndGet();
        keyed.fabrolia$setRegionKey(RegionKeyed.NO_REGION);
    }

    private void indexMove(Entity e, Region from, long newKey) {
        if (from.entities.remove(e)) from.entityCount.decrementAndGet();
        Region to = regionizer.regionForGridKey(newKey);
        if (to.entities.add(e)) to.entityCount.incrementAndGet();
        ((RegionKeyed) e).fabrolia$setRegionKey(newKey);
        // audit10: an island MERGE keeps the survivor's tick identity, so entities
        // re-indexed in from a merged-away island still carry that island's token
        // and would read as un-ticked under the survivor — a second tick in the same
        // wall tick. Carry the mark across, atomically, and only when it is really
        // the predecessor's current token (a CAS, so an entity that has not ticked
        // this epoch is never marked and never loses a tick it is owed).
        if (from.domain != null && to.domain != null && from.domain != to.domain) {
            ((RegionKeyed) e).fabrolia$carryRegionTickMark(
                    from.domain.tickToken(), to.domain.tickToken());
        }
    }

    private static void dropFromRegion(Region r, Entity e) {
        if (r.entities.remove(e)) r.entityCount.decrementAndGet();
    }

    // ---------------------------------------------------------------------
    // Dispatch
    // ---------------------------------------------------------------------

    /**
     * Region-GROUP dispatch (coalescing): instead of one task+future per region
     * (3×regionCount tasks/tick — measurable scheduler and GC overhead at ~450 regions),
     * regions are greedily bin-packed by entityCount into ~2×workers balanced groups.
     * A group task processes its regions back-to-back: spin-claim → drain the region's
     * queue inline (its spawn/random tasks, preserving the per-area
     * [spawn][random][entities] FIFO) → tick entities → release. Dense regions
     * (> regionDenseFanoutThreshold) get a group of their own so the intra-region
     * fan-out can recruit helpers.
     */
    /** (region, load) pair with the entity count FROZEN at dispatch time. Sorting by the
     *  live AtomicLong violated the comparator contract — workers migrate entities between
     *  regions mid-sort, TimSort threw "Comparison method violates its general contract"
     *  and took the whole tick down (live crash 2026-06-10 05:17). */
    private record RegionLoad(Region region, long count) {}

    public CompletableFuture<Void> dispatchOwnedEntityTicks() {
        // Cross-dimension arrival cohorts for this level's global lane:
        // published BEFORE any dispatch — the vanilla-equivalent boundary.
        CrossDimArrivals.publishGlobal(this);
        if (asyncActive) {
            // Adopt anything populated DURING this tick's chunk phase before
            // dispatch: a freshly populated region adjacent to an island must
            // never entity-tick in the global lane concurrently with that
            // island's unit (adjacent regions in different domains would slip
            // the color gate). Population events are main-thread-only, so
            // after this call no un-adopted island-adjacent region can exist
            // until we return — dispatch is airtight.
            // A8: routed commands from last tick's units execute HERE — main,
            // no claims held, one fence for the whole batch, waiter futures
            // completed after release. ≤1 tick latency for cross-island
            // command-block commands.
            IslandCommands.drainRoutedBatch();
            rebuildIslandsIfDirty();
            TpsHud.tick(level.getServer(), level);
            if (net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension())) {
                TemporalControl.tickAcks();
                ChunkDelivery.stressTick();
                if (level.getGameTime() % 20 == 0) {
                    ChunkDelivery.liveness();
                }
            }
            // BE index visibility for this tick's units (vanilla flush + fold)
            // — main is quiescent, the one legal mutation window outside the
            // BE slot.
            IslandBlockEntityTicks.preUnitMaintenance(level, this);

            // DEADLINE DRIVE: submit only islands whose deadline is due,
            // heaviest first (LPT — the long pole starts earliest), and do
            // NOT await them — each unit's own coordinator is its barrier;
            // an overrunning island reschedules from completion and slips
            // below 20 TPS ALONE. Main awaits only the global remainder lane
            // (plus global-scoped chunk-phase futures — see armGate).
            long now = System.nanoTime();
            ArrayList<IslandRegistry.PIsland> due = new ArrayList<>(islandRegistry.islands());
            // freeze the key before sorting: costNanos is written by
            // completing units CONCURRENTLY with this sort — TimSort throws
            // "Comparison method violates its general contract" when a key
            // moves mid-sort (production crash 2026-07-17 05:39).
            for (int di = 0, dn = due.size(); di < dn; di++) {
                IslandRegistry.PIsland p = due.get(di);
                p.driveSortCost = p.costNanos;
            }
            due.sort((a, b) -> Long.compare(b.driveSortCost, a.driveSortCost));
            // GLOBAL SPRINT LOCKSTEP (/tick sprint): exact synchronized barrier
            // ticks — every island runs exactly ONE complete unit per main
            // sprint tick, awaited HERE at the tick barrier (after complete
            // units, never between phases). Local sprint chaining pauses
            // (TemporalControl.onUnitComplete) so counts stay exact. Topology
            // committed above at the tick head = the sprint-tick boundary.
            if (level.getServer().tickRateManager().isSprinting()) {
                ArrayList<java.util.concurrent.CompletableFuture<Void>> barriers =
                        new ArrayList<>(due.size());
                for (int i = 0, n = due.size(); i < n; i++) {
                    barriers.add(IslandTickUnit.submitUnit(this, due.get(i)));
                }
                for (int i = 0, n = barriers.size(); i < n; i++) {
                    // Task #33 (2026-07-28 sprint wedge): serviceRoutedCommands —
                    // an awaited unit's command block may be parked on the routed
                    // batch only MAIN can execute; without the drain this await is
                    // a main ⇄ coordinator deadlock (the nudge sits on the MS task
                    // queue pumpUntilDone deliberately never polls). Main holds no
                    // claims here and the global lane is not dispatched yet — this
                    // is the drive-head serialization point re-entered mid-tick.
                    com.axalotl.fabrolia.common.ParallelProcessor.pumpUntilDone(barriers.get(i), true);
                }
                List<Region> sprintRegions = regionizer.snapshot();
                ArrayList<Region> sprintGlobals = new ArrayList<>(8);
                for (int i = 0, n = sprintRegions.size(); i < n; i++) {
                    Region r = sprintRegions.get(i);
                    if (r.domain == globalDomain) sprintGlobals.add(r);
                }
                return dispatchEntityPhase(globalDomain, sprintGlobals);
            }
            for (int i = 0, n = due.size(); i < n; i++) {
                IslandRegistry.PIsland p = due.get(i);
                // /tick freeze: units are gated at submitUnit; skip the pacing
                // branches entirely. Command-time cross-dim arrivals still
                // deliver NOW — vanilla applies command mutations synchronously
                // while frozen, and with units gated the claim is free, so main
                // publishes against the quiescent island (regen-before-add
                // FIFO preserved by publishIsland itself).
                if (!level.getServer().tickRateManager().runsNormally()) {
                    if (!p.pendingArrivals.isEmpty()
                            && p.unitClaim.compareAndSet(false, true)) {
                        try {
                            CrossDimArrivals.publishIsland(p);
                        } finally {
                            p.unitClaim.set(false);
                        }
                    }
                    continue;
                }
                // Local sprint sessions own tick production for their islands
                // (self-chaining at unit completion) — the drive kick-starts a
                // stalled session but never double-drives an active one.
                if (TemporalControl.isSprinting(this, p)) {
                    if (!p.unitClaim.get()) {
                        IslandTickUnit.submitUnit(this, p);
                    }
                    continue;
                }
                // Coherence pin: exactly one unit per WALL tick, released at
                // this fixed drive point, never ahead of the wall clock —
                // L tracks W 1:1 (phase + count parity with the global lane;
                // catch-up when behind chains at unit completion instead).
                if (TemporalControl.isCoherent(this, p)) {
                    if (p.logicalTick < level.getGameTime() && !p.unitClaim.get()) {
                        IslandTickUnit.submitUnit(this, p);
                    }
                    continue;
                }
                long deadline = p.nextDeadlineNanos;
                // 10ms slack: the deadline of a healthy island sits ε around
                // the drive point (scheduling jitter); without slack an ε-late
                // deadline skips a whole drive. Double-fire is impossible —
                // the +50ms roll moves the deadline a full tick, and the unit
                // claim CAS refuses overlap regardless.
                if (deadline == 0 || deadline <= now + 10_000_000L) {
                    IslandTickUnit.submitUnit(this, p);
                }
            }
            // Global remainder lane: gap regions holding executeAt work and
            // regions born on the global domain since the rebuild above.
            List<Region> regions = regionizer.snapshot();
            ArrayList<Region> globals = new ArrayList<>(Math.max(8, regions.size() / 4));
            for (int i = 0, n = regions.size(); i < n; i++) {
                Region r = regions.get(i);
                if (r.domain == globalDomain) {
                    globals.add(r);
                } else if (r.state() == RegionState.ACTIVE
                        && (r.domain == null
                            || r.domain.owner == null
                            || r.domain.owner.retired)) {
                    // GAP-LANE STALE-DOMAIN ADOPTION (task #26, 2026-07-23 —
                    // the counterpart of IslandTickUnit's core-cell heal): an
                    // ACTIVE region whose domain points at a RETIRED island
                    // (or at no owner at all) is ticked by NOBODY — no live
                    // island's cells reach it, and this identity filter used
                    // to exclude it forever. A cross-dimension arrival whose
                    // destination island retired the same instant froze here
                    // until the next topology change (production: the portal
                    // minecart stuck at the boundary until a player visit).
                    // Adopt it into the global lane through the FULL domain
                    // migration (assignDomain: queue settle + tag-driven
                    // scheduled-tick clock rebase — the same primitive the
                    // async-off flip uses); rebuilds re-island the cell later
                    // if population returns. Main thread, dead source domain,
                    // global target owned by this thread: the documented
                    // assignDomain safe point.
                    FlightRecorder.record("GAP-DOMAIN-ADOPT " + level.dimension().identifier()
                            + " cell=" + Long.toHexString(r.gridKey)
                            + " oldDomain=" + (r.domain == null ? "null"
                                    : (r.domain.owner == null ? "<ownerless>"
                                    : "island#" + r.domain.owner.id + "/g" + r.domain.owner.gen
                                      + (r.domain.owner.retired ? "(retired)" : "(LIVE?)")))
                            + " entities=" + r.entityCount.get());
                    assignDomain(r, globalDomain);
                    globals.add(r);
                }
            }
            return dispatchEntityPhase(globalDomain, globals);
        }
        return dispatchEntityPhase(globalDomain, regionizer.snapshot());
    }

    /**
     * A2 core: dispatch one entity phase for {@code regions} against timeline
     * {@code d} — arm d's barrier, stamp who owes, sweep no-work regions done,
     * seed the ready lane. Caller contract: {@code d.epoch} was bumped for this
     * drive and every region in {@code regions} has {@code r.domain == d}
     * (single-domain dispatch; the ColorGate ignores cross-domain neighbors).
     * The legacy (non-event) group path remains global-domain-only — async mode
     * requires the event scheduler (enforced by the tick-head mode latch).
     */
    CompletableFuture<Void> dispatchEntityPhase(EpochDomain d, List<Region> regions) {
        RegionScheduler.resetHealBackoff(); // new epoch = new orphan window: full heal cadence
        final boolean eventSched = FabroliaConfig.regionEventScheduler;
        ArrayList<RegionLoad> active = new ArrayList<>(regions.size());
        for (int i = 0, n = regions.size(); i < n; i++) {
            Region r = regions.get(i);
            if (r.state() == RegionState.ACTIVE
                    && (!r.entities.isEmpty() || !r.queue().isEmpty())) {
                // Load weight: measured EWMA of the region's entity phase when we have
                // one (captures BE/AI-heavy regions entity counts under-weigh — the
                // profiled tail region ran 1.5–1.7× pool average), scaled to pseudo-
                // entity units; count+queue heuristic as cold-start fallback.
                long ema = r.emaPhaseNanos;
                long weight = ema > 0
                        ? ema / 10_000L + (r.queue().isEmpty() ? 0 : 96)
                        : r.entityCount.get() + (r.queue().isEmpty() ? 0 : 96);
                active.add(new RegionLoad(r, weight));
            }
        }
        final long epoch = d.epoch;
        CompletableFuture<Void> eventBarrier = null;
        if (eventSched) {
            // R5: arm the barrier BEFORE stamping groupedEpochs — a pre-dispatch claim
            // holder's release epilogue may run an owed entity phase the instant its
            // region is stamped, and its barrier contribution must see this counter.
            eventBarrier = new CompletableFuture<>();
            d.entityPending.set(active.size());
            d.entityBarrier = eventBarrier;
            d.entityBarrierArmedEpoch = epoch;
            d.entityBarrierArmedNanos = System.nanoTime();
        }
        for (int i = 0, n = active.size(); i < n; i++) {
            active.get(i).region.groupedEpoch = epoch;   // owes an entity phase this epoch
        }
        // Dispatch decision is now committed: groupedEpochs stamped above.
        // Completion marks become legal from this point (ColorGate.tryMarkDone
        // refuses anything earlier — see dispatchedEpoch).
        d.dispatchedEpoch = epoch;
        // R4 sweep: regions with no work this epoch are trivially complete —
        // without this, their higher-color neighbors would wait forever.
        // tryMarkDone carries all the guards (grouped regions refuse until
        // their entity phase, claimed regions defer to the holder's epilogue,
        // a queue that filled in the race window refuses) so one blind pass
        // over the snapshot is correct.
        if (com.axalotl.fabrolia.common.config.FabroliaConfig.useRegionColorGate()) {
            for (int i = 0, n = regions.size(); i < n; i++) {
                Region r = regions.get(i);
                if (r.state() == RegionState.ACTIVE) {
                    // wakeOnPush=false: hundreds of sweep completions on main each
                    // unparking a worker was 680ms/30s of Unsafe.unpark (2026-07-02
                    // profile); the single flag-scan wake after the seed loop below
                    // covers every region the sweep pushed.
                    ColorGate.tryMarkDone(r, this, false);
                    // ISLAND ORPHAN BLACK HOLE (production 2026-07-16 04:15,
                    // island#66 post-split, repeating BARRIER-LEAK + 3s main
                    // stalls): a member whose queue was empty at the grouping
                    // instant (grouped=n) and FILLED before this sweep can
                    // neither run this epoch (the orphan rule refuses the
                    // claim to protect color order) nor pass tryMarkDone
                    // (queue non-empty) — its same-domain neighbors then wait
                    // on its done stamp until the force-complete belt. Main
                    // arms halo/core gates concurrently with units, so the
                    // fill race is routine. An UNGROUPED member owes nothing
                    // this epoch and its queued work is the NEXT unit's by
                    // design (identical to the documented outside-window
                    // semantics) — stamp it done directly, exactly like the
                    // pre-parity unconditional halo stamp. Island lanes only:
                    // the global lane's queues hold work main awaits THIS
                    // tick and must keep the strict run-when-filled path.
                    if (d.owner != null && r.groupedEpoch != epoch && r.doneEpoch < epoch) {
                        r.doneEpoch = epoch;
                    }
                }
            }
        }
        if (active.isEmpty()) {
            if (eventBarrier != null) eventBarrier.complete(null);
            return CompletableFuture.completedFuture(null);
        }

        if (eventSched) {
            // R5 event-driven path: regions ARE the tasks. Heaviest first (dense solos
            // lead, mirroring the LPT dense-prefix of the legacy grouping); seed the
            // ready lane with the regions that are eligible RIGHT NOW — every other
            // owing region is pushed by ColorGate.markDone at the exact moment its
            // last lower-color blocker completes (event, not poll: the legacy group
            // continuations burned ~a core in 20µs park slices at low load and added
            // up to a poll-slice of latency per DAG level, 2026-07-01 profile).
            // Execution lives in RegionWorkerPool.workerLoop's claim path (drain →
            // owed entity phase → markDone → barrier contribution).
            active.sort((a, b) -> Long.compare(b.count, a.count));
            for (int i = 0, n = active.size(); i < n; i++) {
                Region r = active.get(i).region;
                if (ColorGate.eligible(r, this)) {
                    tryEnqueueEntity(r);
                }
            }
            // ONE wake for everything the sweep + seed pushed: the flag-scan variant
            // unparks only genuinely parked workers (busy ones are skipped for free),
            // so this is strictly cheaper than the legacy per-submit wakeAll.
            RegionScheduler.wakeWorkers(Integer.MAX_VALUE);
            return eventBarrier;
        }

        final int denseThreshold = FabroliaConfig.regionDenseFanoutThreshold;
        // Largest first, then greedy into the lightest group — classic LPT balancing.
        // Frozen counts: stable comparator AND a guaranteed dense-prefix ordering.
        // workers×3 groups: finer grains balance the tail better than ×2 (stragglers
        // dominated the barrier on the 50-bot load), at ~16 extra futures per tick.
        active.sort((a, b) -> Long.compare(b.count, a.count));
        int groupCount = Math.max(1, Math.min(RegionScheduler.workerCount() * 3, active.size()));

        ArrayList<ArrayList<Region>> groups = new ArrayList<>(groupCount);
        long[] groupLoad = new long[groupCount];
        int created = 0;
        for (int i = 0, n = active.size(); i < n; i++) {
            Region r = active.get(i).region;
            long count = active.get(i).count;
            if (denseThreshold > 0 && count > denseThreshold) {
                // Own group — parallelTickOwned will fan out with helpers.
                ArrayList<Region> solo = new ArrayList<>(1);
                solo.add(r);
                groups.add(solo);
                continue;
            }
            if (created < groupCount) {
                ArrayList<Region> g = new ArrayList<>(8);
                g.add(r);
                groups.add(g);
                groupLoad[created++] = count;
                continue;
            }
            int best = 0;
            for (int gi = 1; gi < created; gi++) {
                if (groupLoad[gi] < groupLoad[best]) best = gi;
            }
            // Dense solos sit at the front of `groups`; the load array tracks only the
            // `created` shared groups, whose list indices are offset by the solo count.
            groups.get(groups.size() - created + best).add(r);
            groupLoad[best] += count;
        }

        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(groups.size());
        for (int gi = 0, gn = groups.size(); gi < gn; gi++) {
            final ArrayList<Region> group = groups.get(gi);
            CompletableFuture<Void> cf = new CompletableFuture<>();
            futures.add(cf);
            // Lower colors first: a group's own early entries unblock its later
            // ones; cross-group blockers are handled by the continuation requeue.
            group.sort((ra, rb) -> Integer.compare(ra.color, rb.color));
            RegionScheduler.enqueueGlobal(() -> processGroup(group, 0, cf));
        }
        RegionScheduler.wakeWorkers();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    /**
     * Gated group processing (R4 color-DAG). Eligible regions are claimed and
     * ticked in order; a region whose lower-color neighbors are still running
     * is SKIPPED and the remainder is re-enqueued as a continuation task —
     * the worker is never parked inside a group, so blocker groups always
     * find a free worker (deadlock-free by construction; colors are a DAG).
     */
    private void processGroup(ArrayList<Region> group, int depth, CompletableFuture<Void> cf) {
        RegionWorkerThread me = (RegionWorkerThread) Thread.currentThread();
        ArrayList<Region> deferred = null;
        try {
            for (int ri = 0, rn = group.size(); ri < rn; ri++) {
                Region r = group.get(ri);
                if (!ColorGate.eligible(r, this)) {
                    if (deferred == null) deferred = new ArrayList<>(rn - ri);
                    deferred.add(r);
                    continue;
                }
                // Spin-claim with backoff: competing holders are queue drains
                // (executeAt / early-armed spawn tasks). Usually sub-µs, but a
                // spawn drain can block on a cold getChunk — park in 10µs slices
                // instead of burning the core. No hold-and-wait: we never hold
                // one region's claim while acquiring the next (release per
                // iteration below), and claim holders never wait on group tasks.
                int spins = 0;
                me.currentRegion = r; // coherent protocol: publish BEFORE claim
                while (!r.tryClaim(me)) {
                    if (++spins > 500) {
                        LockSupport.parkNanos(10_000L);
                    } else {
                        Thread.onSpinWait();
                    }
                }
                r.stampAcquire("group");
                // audit11: portal transfers staged by this claim's entity phase
                // publish ONCE, below, at claim release — never per entity —
                // so main's drain cannot bisect one activation into batches
                // that land at different destination epochs.
                com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager
                        .beginRegionClaimFrame();
                try {
                    r.queue().drain(Integer.MAX_VALUE); // spawn/random first (FIFO)
                    tickOwned(r);
                    r.entityEpoch = r.domain.epoch;
                } finally {
                    // Publish the claim's whole transfer frame (one atomic queue
                    // element) — in the finally, so a throwing tick still publishes.
                    com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager
                            .endRegionClaimFrame();
                    r.stampRelease("group");
                    r.release(me);
                    me.currentRegion = null; // unpublish AFTER release
                    // Re-arm, mirroring the workerLoop claim path: a task submitted
                    // while we held the claim (e.g. the offloaded spawn/random
                    // grouping landing mid-tick) had its ready entry dropped by the
                    // failed-claim path — without this, that task strands and its
                    // future wedges the entity barrier (live deadlock 2026-06-10).
                    if (!r.queue().isEmpty() || !r.helpQueue.isEmpty()) {
                        RegionScheduler.enqueueReady(r);
                    }
                    ColorGate.tryMarkDone(r, this);
                }
            }
        } catch (Throwable t) {
            // Never lose the barrier: complete on unexpected failure.
            cf.complete(null);
            throw t;
        }
        if (deferred == null) {
            cf.complete(null);
        } else {
            final ArrayList<Region> rest = deferred;
            if (rest.size() == group.size()) {
                // Zero progress this pass: the blockers are mid-drain elsewhere
                // or orphans awaiting the barrier pump's heal sweep. Chew one
                // unit of real work if any exists, else breathe 20µs — bounds
                // the requeue churn without parking inside the group (the
                // deadlock-free continuation shape stays intact).
                if (!RegionScheduler.stealOneRegionTask()) {
                    LockSupport.parkNanos(20_000L);
                }
                // ~1s of zero progress (50k passes × 20µs) = something is
                // genuinely wedged — say WHO instead of parking silently
                // (the 2026-06-12 wedge was 203s of anonymous spinning).
                if (depth > 0 && depth % 50_000 == 0) {
                    LOGGER.error("[color-gate] group stalled {} passes; {}",
                            depth, ColorGate.diagnose(rest.get(0), this));
                }
            }
            RegionScheduler.enqueueGlobal(() -> processGroup(rest, depth + 1, cf));
        }
    }

    /**
     * The per-region tick body. Below the dense threshold: vanilla's entityTickList.forEach
     * lambda, scoped to this region's own entities, serial on the claiming worker (id order
     * — vanilla insertion order, TNT-cannon safe). Above it: intra-region fan-out, see
     * {@link #parallelTickOwned}.
     */
    private void tickOwned(Region region) {
        // Region-scoped precompute hook (Oxidizium cramming candidates etc.) —
        // fires on the coordinator BEFORE any entity of the region ticks, so
        // dense-path helpers observe its writes via the helpQueue publication.
        RegionTickHooks.firePre(region);
        try {
            final int threshold = FabroliaConfig.regionDenseFanoutThreshold;
            if (threshold > 0 && region.entityCount.get() > threshold) {
                parallelTickOwned(region);
                return;
            }
            final ServerLevel lvl = level;
            final TickRateManager trm = lvl.tickRateManager();
            final ServerChunkCache chunkSource = lvl.getChunkSource();
            final DistanceManager dm = chunkSource.chunkMap.getDistanceManager();
            final boolean playersInRegions = FabroliaConfig.enableRegionPlayerTicks;

            // Per-region-task entity-ticking gate memo — see fabrolia$chunkGate.
            final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap gate =
                    new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(16);
            Entity prevGap = null;
            long gapT0 = System.nanoTime();
            for (Entity e : region.entities) {
                tickOne(region, e, lvl, trm, chunkSource, dm, playersInRegions, gate, true);
                long t1 = System.nanoTime();
                if (t1 - gapT0 > 100_000_000L) {
                    // COHORT_PHASE_SPLIT forensics: name the exact entity
                    // whose tick blocked ≥100ms mid-phase (the wall that,
                    // with the old belt, split a teleport cohort in two).
                    FlightRecorder.record("PHASE-GAP region=" + Long.toHexString(region.gridKey)
                            + " ms=" + (t1 - gapT0) / 1_000_000L
                            + " in=" + e.getType() + "#" + e.getId()
                            + " after=" + (prevGap == null ? "-" : prevGap.getType() + "#" + prevGap.getId())
                            + " L=" + (region.domain != null ? region.domain.unitGameTime : -1));
                }
                prevGap = e;
                gapT0 = t1;
            }
        } finally {
            RegionTickHooks.firePost(region);
        }
    }

    /**
     * Combined per-chunk entity-ticking gate ({@code inEntityTickingRange} + a live
     * ticking chunk on the holder), memoized PER REGION TASK: one coherent answer per
     * chunk per tick, mirroring vanilla — which evaluates this on main where nothing
     * mutates ticket state mid-phase. Evaluated live per entity on a worker it raced
     * (a) distance-tracker propagation waves from ChunkMap.move on OTHER workers
     * (region player ticks: remove-source decay then re-add — intermediate levels are
     * visible in the concurrent maps mid-wave) and (b) main's barrier pump applying
     * ticket updates mid-phase. A read landing in the decay window sees the chunk
     * transiently OUT of entity-ticking range and the entity silently skips a whole
     * tick — items freezing/jittering in sorter water streams near moving players,
     * load-scaled (2026-07-06). One memoized read per chunk is also cheaper than the
     * old two-probe-plus-holder-deref per entity.
     *
     * <p>{@code writable=false} on the dense-path shared memo (prebuilt by the
     * coordinator, read concurrently by helper batches): an entity that crossed a
     * chunk line mid-phase misses the memo and gets one live read, uncached — one
     * coherent value per entity, same as vanilla gives a mover.
     */
    private byte fabrolia$chunkGate(long packed, it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap memo,
                                 boolean writable, ServerChunkCache chunkSource, DistanceManager dm) {
        byte g = memo.get(packed);   // 0 = unknown, 1 = tick, 2 = skip
        if (g != 0) {
            return g;
        }
        boolean ok = dm.inEntityTickingRange(packed);
        if (ok) {
            ChunkHolder holder = chunkSource.getVisibleChunkIfPresent(packed);
            ok = holder != null && holder.getTickingChunk() != null;
        }
        g = ok ? (byte) 1 : (byte) 2;
        if (writable) {
            memo.put(packed, g);
        }
        return g;
    }

    /**
     * Dense-region intra-region fan-out (the user's "регион = домен синхронизации" model).
     * The claiming worker becomes the COORDINATOR: it splits the region's entities into
     * sub-batches on the region's {@link Region#helpQueue} (any region worker picks them
     * up under THIS region's lock discipline — currentRegion is set by the help protocol,
     * so tickLockOr scopes contention to this region instead of global statics), ticks its
     * own share, helps drain leftovers, runs the ordered lane, then waits region-locally
     * on a plain countdown — the fan-in never touches main.
     *
     * <p>Ordered lane: entity types the user listed in synchronizedEntities (TNT, items…)
     * are extracted into a single id-ordered serial chain run by the coordinator — TNT
     * cannons keep their exact mutual tick order even inside a dense region. Everything
     * else gives up within-region ordering, same as the old flat-pool mode this load ran
     * under before regions existed.
     */
    private void parallelTickOwned(Region region) {
        final ServerLevel lvl = level;
        final TickRateManager trm = lvl.tickRateManager();
        final ServerChunkCache chunkSource = lvl.getChunkSource();
        final DistanceManager dm = chunkSource.chunkMap.getDistanceManager();
        final boolean playersInRegions = FabroliaConfig.enableRegionPlayerTicks;

        ArrayList<Entity> parallel = new ArrayList<>((int) Math.min(Integer.MAX_VALUE, region.entityCount.get() + 16));
        ArrayList<Entity> ordered = null;
        // Prebuilt per-region-task gate memo (see fabrolia$chunkGate): resolved once per
        // chunk HERE on the coordinator, then shared READ-ONLY by helper batches — every
        // entity of a chunk gets the same coherent answer regardless of which batch or
        // thread ticks it. Mid-phase movers miss the memo and take one uncached live read.
        final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap gate =
                new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(64);
        for (Entity e : region.entities) {
            if (e == null) continue;
            fabrolia$chunkGate(e.chunkPosition().pack(), gate, true, chunkSource, dm);
            // synchronizedEntities types keep exact mutual order (the TNT-cannon
            // contract) — id-ordered serial lane on the coordinator. The flat-pool
            // class blacklist rides the same lane: those classes write OTHER
            // entities' state when they tick (cart/boat mutual pushes, projectile
            // hits), which is only safe serially — racing batches lost cart
            // separation impulses (2026-07-10 splitter pile-up). The segmented TNT
            // lane treats them as exact-order serial barriers, so R6 stays intact.
            if (FabroliaConfig.isEntitySynchronized(EntityType.getKey(e.getType()))
                    || ParallelProcessor.requiresOrderedLane(e)) {
                if (ordered == null) ordered = new ArrayList<>(16);
                ordered.add(e);
            } else {
                parallel.add(e);
            }
        }

        int n = parallel.size();
        int workers = Math.max(1, RegionScheduler.workerCount());
        // Batch sizing MEASURED (2026-07-12 dense-pen, 600 packed zombies, 30
        // workers): max(32, n/workers) -> phase 4.8 ms mean / 8.9 p99; halving
        // to max(16, n/(2*workers)) -> 5.1 mean / 10.8 p99 — WORSE. Packed pens
        // parallelize at ~13% efficiency (shared entity-section reads + neighbor
        // deltaMovement writes contend), so smaller batches buy contention, not
        // speedup. Keep the coarse batches.
        int batchSize = Math.max(32, (n + workers - 1) / workers);
        int batchCount = n == 0 ? 0 : (n + batchSize - 1) / batchSize;
        AtomicInteger latch = new AtomicInteger(batchCount);
        final AtomicLong batchMax = new AtomicLong(); // /fabrolia regions top

        // Publish batches 1..K-1 for helpers; coordinator ticks batch 0 itself.
        for (int b = 1; b < batchCount; b++) {
            final int from = b * batchSize;
            final int to = Math.min(from + batchSize, n);
            region.helpQueue.add(() -> {
                long bt0 = System.nanoTime();
                try {
                    for (int j = from; j < to; j++) {
                        tickOne(region, parallel.get(j), lvl, trm, chunkSource, dm, playersInRegions, gate, false);
                    }
                } finally {
                    latch.decrementAndGet();
                    batchMax.accumulateAndGet(System.nanoTime() - bt0, Math::max);
                }
            });
            RegionScheduler.enqueueReady(region);
        }
        if (batchCount > 1) RegionScheduler.wakeWorkers();

        if (batchCount > 0) {
            long bt0 = System.nanoTime();
            try {
                int to = Math.min(batchSize, n);
                for (int j = 0; j < to; j++) {
                    tickOne(region, parallel.get(j), lvl, trm, chunkSource, dm, playersInRegions, gate, false);
                }
            } finally {
                latch.decrementAndGet();
                batchMax.accumulateAndGet(System.nanoTime() - bt0, Math::max);
            }
        }

        // Help-drain whatever no other worker picked up — guarantees progress even if
        // every other worker is busy (worst case degenerates to the serial path).
        Runnable help;
        while ((help = region.helpQueue.poll()) != null) {
            help.run();
        }

        // Region-local fan-in MUST complete BEFORE the ordered lane runs.
        //
        // Reproducible bug 2026-06-14 (item sorters): the previous layout
        // ticked ordered (items/TNT/exp orbs) while non-item batches were
        // still in flight on helper workers. A mob in a parallel batch
        // calling Entity.collide → Entity.push(Entity) writes the item's
        // deltaMovement at the same moment item.tick reads it on the
        // coordinator. Two writers on item.deltaMovement (and on item
        // position via the collision push-back path) garbles the item's
        // motion — observed as shulker-box drops ending up at the piston
        // wall / outside the sorter instead of along the sorter path.
        // Vanilla never has this race because everything ticks serially on
        // main. The overlap saved ~stragglers-wait of wall clock but at
        // the cost of vanilla-correct motion arithmetic for
        // synchronizedEntities — exactly the contract that listing them
        // there asks us to keep.
        long spinUntil = System.nanoTime() + 30_000L;
        while (latch.get() > 0) {
            if (System.nanoTime() < spinUntil) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(25_000L);
            }
        }

        region.lastFanoutBatches = batchCount;
        region.lastBatchMaxNanos = batchMax.get();
        long ord0 = System.nanoTime();
        // Ordered lane: every parallel mob has settled its position/motion
        // pushes by now, so item.tick reads a stable mob population (and no
        // mob.push is racing item.deltaMovement). Vanilla mutual order kept.
        if (ordered != null) {
            if (FabroliaConfig.fabroliaTntSegmentedLane) {
                fabrolia$tickOrderedSegmented(region, ordered, lvl, trm, chunkSource, dm, playersInRegions, gate);
            } else {
                for (int i = 0, m = ordered.size(); i < m; i++) {
                    tickOne(region, ordered.get(i), lvl, trm, chunkSource, dm, playersInRegions, gate, false);
                }
            }
        }
        region.lastOrderedNanos = System.nanoTime() - ord0;
    }

    /**
     * R6 TNT lane: the ordered lane with EXPLOSION-SEGMENTED parallelism. The vanilla
     * contract behind synchronizedEntities is the exact MUTUAL order of interacting
     * entities — and vanilla-exact TNT interacts with its peers through exactly one
     * channel: an EXPLOSION (block destruction + knockback writes into later entities'
     * state). A PrimedTnt that does NOT explode this tick (fuse is deterministic —
     * it detonates when getFuse()==1 enters tick) reads only level/block state and
     * writes only itself: no entity-entity coupling, so a CONSECUTIVE RUN of
     * non-exploding vanilla TNT is order-independent and legal to tick in parallel —
     * the same worker machinery, gates and striped random the parallel mob phase
     * already uses. Everything else is a SERIAL BARRIER in exact list order: items,
     * exp orbs, exploding TNT, removed/odd entries, and PrimedTnt SUBCLASSES (a mod
     * override may add entity coupling we can't see). An explosion therefore observes
     * every earlier entity's completed move and mutates later ones before they move —
     * bit-identical to the serial lane. 18k TNT in one region was a 15s serial wall
     * (1 TPS, 2026-07-02); this turns the movement share into ~workers-way parallel.
     */
    private void fabrolia$tickOrderedSegmented(Region region, ArrayList<Entity> ordered,
                                            ServerLevel lvl, TickRateManager trm,
                                            ServerChunkCache chunkSource, DistanceManager dm,
                                            boolean playersInRegions,
                                            it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap gate) {
        final int n = ordered.size();
        int i = 0;
        while (i < n) {
            // Accumulate a run of parallel-safe TNT.
            int runStart = i;
            while (i < n && fabrolia$isParallelSafeTnt(ordered.get(i))) {
                i++;
            }
            int runLen = i - runStart;
            if (runLen > 0) {
                if (runLen < 64) {
                    // Short run: fan-out overhead exceeds the win — serial.
                    for (int j = runStart; j < i; j++) {
                        tickOne(region, ordered.get(j), lvl, trm, chunkSource, dm, playersInRegions, gate, false);
                    }
                } else {
                    fabrolia$tickTntRunParallel(region, ordered, runStart, i, lvl, trm, chunkSource, dm, playersInRegions, gate);
                }
            }
            if (i < n) {
                // Barrier entity (item / orb / exploding or modded TNT): exact serial order.
                tickOne(region, ordered.get(i), lvl, trm, chunkSource, dm, playersInRegions, gate, false);
                i++;
            }
        }
    }

    /** Parallel-safe = EXACTLY vanilla PrimedTnt (subclasses may couple entities) that is
     *  alive and will NOT detonate this tick (vanilla: fuse decrements, then <=0 explodes —
     *  so getFuse() > 1 cannot reach the explode branch). Fuse is written only by the
     *  entity's own tick / commands (main, between ticks) — stable within the lane. */
    private static boolean fabrolia$isParallelSafeTnt(Entity e) {
        return e != null
                && e.getClass() == net.minecraft.world.entity.item.PrimedTnt.class
                && !e.isRemoved()
                && ((net.minecraft.world.entity.item.PrimedTnt) e).getFuse() > 1;
    }

    /** Fan a non-exploding TNT run out to helper workers (same helpQueue + region-lock
     *  discipline + latch fan-in as the main parallel phase); the coordinator ticks its
     *  own share and help-drains, so progress never depends on free workers. */
    private void fabrolia$tickTntRunParallel(Region region, ArrayList<Entity> ordered, int from, int to,
                                          ServerLevel lvl, TickRateManager trm,
                                          ServerChunkCache chunkSource, DistanceManager dm,
                                          boolean playersInRegions,
                                          it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap gate) {
        int len = to - from;
        int workers = Math.max(1, RegionScheduler.workerCount());
        int batchSize = Math.max(32, (len + workers - 1) / workers);
        int batchCount = (len + batchSize - 1) / batchSize;
        AtomicInteger latch = new AtomicInteger(batchCount);
        for (int b = 1; b < batchCount; b++) {
            final int s = from + b * batchSize;
            final int e = Math.min(s + batchSize, to);
            region.helpQueue.add(() -> {
                try {
                    for (int j = s; j < e; j++) {
                        tickOne(region, ordered.get(j), lvl, trm, chunkSource, dm, playersInRegions, gate, false);
                    }
                } finally {
                    latch.decrementAndGet();
                }
            });
            RegionScheduler.enqueueReady(region);
        }
        if (batchCount > 1) RegionScheduler.wakeWorkers();
        try {
            int e0 = Math.min(from + batchSize, to);
            for (int j = from; j < e0; j++) {
                tickOne(region, ordered.get(j), lvl, trm, chunkSource, dm, playersInRegions, gate, false);
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

    /** One entity through the full vanilla gate sequence + tick. Shared by the serial
     *  region drain and the dense fan-out batches (thread-safe: index ops are concurrent,
     *  the per-entity region tick token dedups any same-epoch double-visit). */
    private void tickOne(Region region, Entity e, ServerLevel lvl, TickRateManager trm,
                         ServerChunkCache chunkSource,
                         DistanceManager dm, boolean playersInRegions,
                         it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap gate, boolean gateWritable) {
        if (e == null) return;
        if (e.isRemoved()) {
            // Lazy cleanup: removal callbacks normally pull entities out, but a stale
            // RegionKeyed key (cross-thread move race) can strand one here.
            dropFromRegion(region, e);
            return;
        }
        long packed = e.chunkPosition().pack();
        long curKey = regionizer.grid.keyFor(packed);
        if (curKey != region.gridKey) {
            // Crossed a grid line since last tick: hand off to the new owner but still
            // tick it HERE this tick (same "ticks where it was grouped" rule as the old
            // grouped dispatch). If the new owner's drain also reaches it this tick,
            // the region tick token below turns one of the two into a no-op.
            indexMove(e, region, curKey);
        }

        RegionKeyed keyed = (RegionKeyed) e;
        if (!keyed.fabrolia$tryMarkRegionTick(region.domain.tickToken())) {
            return;
        }

        if (trm.isEntityFrozen(e)) return;

        // task #19: a queued main-owned portal teleport = this entity is
        // causally GONE from the source (vanilla teleports the tick portal-
        // time fires). Ticking it while the handoff waits produced source-
        // side double-motion and extra source epochs under main backlog.
        if (keyed instanceof com.axalotl.fabrolia.common.ParallelProcessor.TickGuard tg
                && tg.fabrolia$isPortalPending()) {
            return;
        }

        boolean isPlayer = e instanceof ServerPlayer;
        if (!isPlayer) {
            // Ticking-readiness gate, ONE coherent answer per chunk per region task (see
            // fabrolia$chunkGate — live per-entity reads raced distance-tracker propagation
            // and missed ticks). The memo's live resolution MUST use the
            // getVisibleChunkIfPresent holder path (worker-safe: c2me's lock-guarded
            // getHolder + the holder's volatile ticking future) — never a main-thread-only
            // cache. A prior "optimization" that read the since-removed loadedChunks cache
            // here was a data race, reverted 2026-06-29.
            if (fabrolia$chunkGate(packed, gate, gateWritable, chunkSource, dm) != 1) {
                // VANILLA ORDER (26.2 ServerLevel guarded entity loop, bytecode-
                // verified): checkDespawn runs BEFORE the entity-ticking range
                // gate, for every tick-list entity — a mob parked beyond the
                // gate still hard-despawns (>128) and a /kill corpse there
                // still discards; only the TICK is range-gated. Skipping the
                // whole entity froze far mobs alive-and-censused (2026-07-22
                // mob-switch A/B: vanilla band=0 instant despawn vs regions
                // band=120 frozen; after /kill the frozen corpses held the
                // monster cap forever — Z3=0). The despawn check is chunk-
                // independent (player distance + own removal), safe under this
                // region's claim like the gate-passing path's call below.
                try {
                    e.checkDespawn();
                } catch (Exception ex) {
                    LOGGER.error("checkDespawn failed for {} ({})", e.getType(), e.getUUID(), ex);
                }
                return;
            }
        }

        Entity vehicle = e.getVehicle();
        if (vehicle != null) {
            if (!vehicle.isRemoved() && vehicle.hasPassenger(e)) return;
            e.stopRiding();
        }

        if ((isPlayer && !playersInRegions) || ParallelProcessor.requiresMainThread(e)) {
            RegionScheduler.queueMainSyncTick(e);
            return;
        }

        try {
            e.checkDespawn();
        } catch (Exception ex) {
            LOGGER.error("checkDespawn failed for {} ({})", e.getType(), e.getUUID(), ex);
        }
        if (e.isRemoved()) {
            dropFromRegion(region, e);
            return;
        }
        if (FabroliaConfig.fabroliaOptimisticBorderMetric) {
            if (isBorderForMetric(region, e)) METRIC_BORDER.incrementAndGet();
            else METRIC_INTERIOR.incrementAndGet();
        }
        ParallelProcessor.tickEntity(lvl, e, true);
    }
}
