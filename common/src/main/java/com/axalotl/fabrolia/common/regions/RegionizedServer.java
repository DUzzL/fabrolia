package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.utils.FabroliaDebug;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    /** Identity check used to reject a delayed Region from a retired scheduler generation. */
    boolean ownsRegion(Region region) {
        return region != null && region.level == level
                && regionizer.peekByGridKey(region.gridKey) == region;
    }

    /** A2: the level's default scheduling timeline. Sync mode: every region's
     *  {@link Region#domain} points here — behavior is field-for-field what the
     *  old RegionizedServer epoch/barrier state gave. Async mode: island member
     *  regions move to their island's domain; gap/unpopulated regions stay here,
     *  and this domain still runs one dispatch per level tick for them. */
    final EpochDomain globalDomain = new EpochDomain();
    /**
     * Epoch for which the global dispatch is deliberately queue-only.
     *
     * <p>The event worker path derives an entity obligation from
     * {@link Region#groupedEpoch}.  Exact player-order mode still has to stamp that
     * field so the colour-gated queue drain can reach a fixed point, but it must not
     * let an already-ready worker interpret the stamp as permission to call
     * {@link #tickOwned(Region)}.  Publish this epoch before the first grouped stamp
     * and retain it until the next level epoch; {@link #owesEntityPhase(Region)} is
     * the single admission predicate used by every opportunistic worker path.
     */
    private volatile long queueOnlyEntityEpoch = Long.MIN_VALUE;
    /** Global-owned arrivals and unresolved topology transport awaiting Main's cut. */
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
     *  successive cohorts (a machine cohort split). Arrival routing takes the
     *  topology admission read side, then this monitor, then an island queue
     *  or the leaf handoff tracker. No receipt waits, destination callbacks,
     *  or world mutation run under those monitors. */
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
            h -> ((net.minecraft.world.entity.Entity) h).isRemoved());

    private static boolean failClosedMarkerChecked;

    /** A2 mode latch — coherent for a whole level tick: read/written ONLY at
     *  tick HEAD (onLevelTickStart), consumed by rebuild (domain assignment)
     *  and the entity-phase drive later the same tick. */
    public volatile boolean asyncActive;
    /** One wall-tick v2 batch. Non-null only between entity dispatch and the
     *  per-level barrier immediately before vanilla block-entity ticking. */
    volatile RegionalPlayerEntityTicksV2.LevelBatch playerTickV2Batch;
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

    /** Domain for a newly materialized target cell. The immutable topology
     *  snapshot, not the publishing worker's claim or coordinator context,
     *  owns the destination. A cold gap stays global until a rebuild adopts it.
     *  Creation READ admission is paired with topology publication's WRITE cut. */
    EpochDomain domainForCreation(long gridKey) {
        if (asyncActive) {
            var snap = cellDomainSnapshot;
            if (snap != null) {
                EpochDomain d = snap.get(gridKey);
                if (d != null) return d;
            }
        }
        return globalDomain;
    }

    /** CAS-gated ENTITY-phase push: at most one ready-lane seed/re-arm per epoch.
     *  Eligibility is monotonic within an epoch (doneEpoch only advances; regions born
     *  post-dispatch are stamped done at creation), so a pushed region can never regress
     *  to ineligible — the pop side may still drop it on a lost claim CAS, which the
     *  claim holder's release epilogue re-arms (see RegionWorkerPool). */
    boolean tryEnqueueEntity(Region r) {
        return RegionScheduler.callSuccessBooleanIfHealthy(() -> {
            long e = r.domain.epoch;
            long prev = r.entityEnqueuedEpoch.get();
            if (prev >= e || !r.entityEnqueuedEpoch.compareAndSet(prev, e)) {
                return false;
            }
            RegionScheduler.enqueueEntityReady(r);
            return true;
        });
    }

    /** Does {@code r} still owe its entity phase this epoch (of ITS domain)? */
    boolean owesEntityPhase(Region r) {
        long e = r.domain.epoch;
        if (r.domain == globalDomain && queueOnlyEntityEpoch == e) return false;
        return r.groupedEpoch == e && r.entityEpoch < e;
    }

    /**
     * Exact queue-only dispatch identity used by ColorGate completion.  A
     * grouped queue-only participant owes no entity body, but it may complete
     * only after its physical normal/help queues are empty.  Keep the global
     * domain identity in this predicate: an ownerless look-alike domain must
     * never borrow the level's exact-player dispatch decision.
     */
    boolean isQueueOnlyEntityEpoch(EpochDomain expectedDomain, long expectedEpoch) {
        return expectedDomain == globalDomain
                && expectedDomain.epoch == expectedEpoch
                && queueOnlyEntityEpoch == expectedEpoch;
    }

    /**
     * R5: run the owed entity phase under an ALREADY-HELD claim (caller = claim holder,
     * {@code currentRegion} published). Requires this epoch's accepted event
     * protocol and ColorGate eligibility: a pre-dispatch claimant may have read
     * the old receipt before dispatch stamped its region mid-drain.
     * A Throwable inside the phase propagates unchanged.  In particular, the
     * completion epoch is published only after the complete entity body returns;
     * a broken vanilla tick must never satisfy the entity barrier as successful.
     */
    boolean runOwedEntityPhase(Region r) {
        EpochDomain d = r.domain;
        long epoch = d.epoch;
        // Read acceptance AFTER the mode witness: armedEpoch is published after
        // the new OFFERING receipt, so an old accepted receipt cannot authorize it.
        if (!d.isEventEntityEpoch(epoch) || !d.entityDispatchPublication.isAccepted()
                || !owesEntityPhase(r) || !ColorGate.eligible(r, this)) {
            return false;
        }
        tickOwned(r);
        return true;
    }

    /**
     * Publish the claim-private entity completion only after every part of the
     * phase transaction (notably the portal visibility frame) has succeeded.
     * The epoch is deliberately the last write: barrier observers can never see
     * a completed epoch without its corresponding diagnostics.
     */
    void commitEntityPhase(Region r) {
        long dt = r.stagedPhaseNanos;
        long ema = r.emaPhaseNanos;
        r.emaPhaseNanos = ema == 0 ? dt : ema + ((dt - ema) >> 2);
        r.lastPhaseNanos = dt;
        r.entityEpoch = r.domain.epoch;
    }

    /** R5: contribute {@code r}'s barrier decrement — exactly once per epoch of ITS
     *  domain, and only after its entity phase and a claim/queue-linearized done
     *  seal both published. Any successful tail may call this blindly; this lets
     *  the last duplicate/no-op claimant close work committed by its predecessor. */
    void contributeEntityBarrier(Region r) {
        try {
            AtomicReference<CompletableFuture<Void>> completion =
                    new AtomicReference<>();
            Runnable commit = () -> {
                EpochDomain d = r.domain;
                long e = d.epoch;
                if (!d.isEventEntityEpoch(e)) return;
                if (r.groupedEpoch != e || r.entityEpoch < e
                        || !r.hasSealedDone(e)) return;
                long prev = r.entityBarrierEpoch.get();
                if (prev >= e || !r.entityBarrierEpoch.compareAndSet(prev, e)) return;
                if (d.entityPending.decrementAndGet() == 0) {
                    completion.set(d.entityBarrier);
                }
            };
            if (RegionScheduler.isActive()) {
                RegionScheduler.commitSuccessIfHealthy(commit);
            } else {
                commit.run();
            }
            CompletableFuture<Void> barrier = completion.get();
            if (barrier != null) {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    if (!barrier.complete(null)) {
                        throw new IllegalStateException(
                                "entity barrier completed more than once");
                    }
                });
            }
        } catch (Throwable completionFailure) {
            RegionizedServer.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(completionFailure));
        }
    }

    /** Heal/watchdog view: owing regions left this GLOBAL-domain epoch (0 = barrier
     *  done). Island domains pump their own barriers inside the tick unit. */
    int entityPendingCount() {
        return globalDomain.isEventEntityEpoch(globalDomain.epoch)
                ? globalDomain.entityPending.get() : 0;
    }

    long entityBarrierAgeNanos(long now) {
        return now - globalDomain.entityBarrierArmedNanos;
    }

    public void onLevelTickStart() {
        RegionScheduler.mainCurrentLevel = level;
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
            } catch (java.io.IOException | SecurityException markerFailure) {
                // This callback is part of the admitted level-tick HEAD. It may
                // not swallow a failure and continue into vanilla simulation.
                RegionizedServer.<RuntimeException>rethrow(
                        RegionScheduler.publishFatalAndSelectWinner(markerFailure));
            }
        }
        boolean wantAsync = RegionScheduler.asyncModeWantedAtLevelHead()
                && RegionalPlayerEntityTicksV2.asyncActivationAllowedAtLevelHead();
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
            // collapse to SYNC at this tick head; the trip is session-only.
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
                        + "A5 exact real players and audited Carpet fakes use their autonomous island owner "
                        + "when /fabrolia regions playerticks is enabled; unsupported player states fail per-player to Main; "
                        + "commands exactly-once "
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

    /**
     * Populated static cells which can still contain physical-Main scheduled
     * work. Main-thread confined: the FULL-status seam is the producer and the
     * block/fluid LevelTicks slots are the consumers.
     *
     * <p>This is intentionally a CELL population index, not a mirror of
     * nextTickForContainer. A stored scheduler key in a non-FULL cell cannot
     * pass vanilla's tickCheck; the exact 0 -> 1 population boundary adds all
     * 64 possible keys at once. Once topology adopts a cell, the owner lane
     * discovers it from PIsland.cells/haloCells and Main removes the candidate
     * lazily. Consequently discovery cost follows newly populated global cells,
     * never the scheduler map's historical capacity.</p>
     */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet scheduledTickDiscoveryCells =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** Main thread (updateChunkScheduling seam). Region created eagerly: a
     *  freshly populated cell must exist to be claimable by the rebuild. */
    /** Redundant-delivery counters (fabric event + vanilla seam feed the same
     *  idempotent funnel; overlaps are expected and harmless — these exist so
     *  popcheck can show which source is actually firing). */
    public final java.util.concurrent.atomic.AtomicLong popIgnoredAdds = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong popIgnoredRemoves = new java.util.concurrent.atomic.AtomicLong();

    public void onChunkFullTransition(long packedChunkPos, boolean nowFull, LevelChunk chunk) {
        // INVARIANT (audit 2026-07-26): this runs on the main thread only, which is
        // what lets Region.fullChunks be a plain LongOpenHashSet while the derived
        // counter is atomic. Verified against real 26.2 + Fabric API: the FULL
        // status task is dispatched on context.mainThreadExecutor()
        // (ChunkStatusTasks.full) and CHUNK_UNLOAD fires from ChunkMap's main-thread
        // unload pass. But that is an assumption about EVERY chunk-system mod in the
        // stack — c2me reshapes chunk loading — and if it is ever violated a fastutil
        // set corrupts silently: lost population, islands that never split or never
        // merge. Fail before touching either plain set; continuing would turn a
        // scheduler bug into nondeterministic skipped simulation.
        scheduledTickDiscoveryCellsMainThread("chunk population transition");
        Region r = regionizer.regionForGridKey(regionizer.grid.keyFor(packedChunkPos));
        // The set remains authoritative and advances on every accepted chunk
        // transition. The island graph, however, sees only populated CELLS:
        // fullChunkCount > 0. Rebuilding/quiescing every island for chunk 2..64
        // in an already-populated 8x8 cell cannot change connectivity and only
        // churns tracking/queues. A rebuild is required exactly at 0 <-> 1.
        Region.FullChunkTransition transition =
                r.applyFullChunkTransition(packedChunkPos, nowFull);
        if (transition == Region.FullChunkTransition.POPULATION_BOUNDARY) {
            if (nowFull) {
                scheduledTickDiscoveryCells.add(r.gridKey);
            } else {
                scheduledTickDiscoveryCells.remove(r.gridKey);
            }
            islandsDirty = true;
        } else if (transition == Region.FullChunkTransition.REDUNDANT) {
            if (nowFull) popIgnoredAdds.incrementAndGet();
            else popIgnoredRemoves.incrementAndGet();
        }
    }

    /** Main-only mutable view used by the physical LevelTicks slots. */
    it.unimi.dsi.fastutil.longs.LongOpenHashSet scheduledTickDiscoveryCellsMainThread(
            String operation) {
        net.minecraft.server.MinecraftServer server = level.getServer();
        Thread expected = server == null ? null : server.getRunningThread();
        Thread current = Thread.currentThread();
        if (expected != null && current == expected) {
            return scheduledTickDiscoveryCells;
        }
        throw new IllegalStateException("scheduled-tick cell index " + operation
                + " off main: current=" + current.getName()
                + " expected=" + (expected == null ? "<missing>" : expected.getName())
                + " level=" + level.dimension().identifier());
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

    /** Capture the committed core+halo ownership cut, not async activation
     *  intent: OFF may be requested while the old island units are still live.
     *  Published maps are immutable. Arrival offers share the topology routing
     *  admission cut so retirement and snapshot replacement cannot bisect an
     *  offer; transition-time foreign offers retain their whole cohort. */
    it.unimi.dsi.fastutil.longs.Long2ObjectMap<EpochDomain> arrivalOwnerSnapshot() {
        return cellDomainSnapshot;
    }

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
     *  {@code r} (ServerChunkCacheMixin.groupAndSubmit). GLOBAL = arm + main
     *  awaits the task future (global lane / async off — the pre-A2 behavior);
     *  an island token = arm without a main wait (the unit is the barrier);
     *  SKIP = the island still owes the previous arm (vanilla-lag semantics).
     *  Main thread only. The returned island token is stable and allocation-free. */
    public IslandRegistry.ArmGate armGate(Region r) {
        if (!asyncActive) return IslandRegistry.ArmGate.global();
        EpochDomain d = r.domain;
        IslandRegistry.PIsland owner = d != null ? d.owner : null;
        if (owner == null) return IslandRegistry.ArmGate.global();
        return owner.reserveChunkArm(level.getGameTime());
    }

    /**
     * Attach one exact island-arm reservation to a region task. The pool's
     * failed-generation sweep calls the cancellation hook without entering the
     * vanilla body; a started body releases in its terminal path. Either edge
     * closes the same reservation exactly once.
     */
    public static Runnable armReservedTask(IslandRegistry.ArmGate gate, Runnable body) {
        if (gate == null || !gate.isIslandArm() || body == null) {
            throw new IllegalArgumentException("island arm task requires an island gate and body");
        }
        return new ArmReservedTask(gate.owner(), body);
    }

    private static final class ArmReservedTask
            implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final IslandRegistry.PIsland owner;
        private final Runnable body;
        private int state = QUEUED;

        private ArmReservedTask(IslandRegistry.PIsland owner, Runnable body) {
            this.owner = owner;
            this.body = body;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (state == CANCELLED) return;
                if (state != QUEUED) {
                    throw new IllegalStateException(
                            "island chunk-arm task executed more than once");
                }
                state = RUNNING;
            }
            try {
                body.run();
            } finally {
                try {
                    owner.finishChunkArm();
                } finally {
                    synchronized (this) {
                        state = TERMINAL;
                    }
                }
            }
        }

        @Override
        public void cancelQueuedBeforeStart() {
            cancel();
        }

        @Override
        public void cancelQueuedBeforeStartWithFailure(Throwable primary) {
            cancel();
        }

        private void cancel() {
            synchronized (this) {
                if (state != QUEUED) return;
                state = CANCELLED;
            }
            owner.finishChunkArm();
        }
    }

    /** A2: exclusive claim of EVERY island's unit (topology rebuilds, save-all).
     *  Spins per island until its in-flight unit (if any) completes; coordinators
     *  never need main, so this always terminates. Main thread only; paired with
     *  {@link #releaseAllIslands}. */
    private final ArrayList<IslandRegistry.PIsland> quiescedIslands = new ArrayList<>();
    /** Exact publication that MAIN owns an exclusive window over every island
     *  in this level.  The immutable tuple prevents a reader from observing a
     *  new receipt with an old thread identity.  In particular, this is never
     *  published merely because a global fence has started acquiring claims:
     *  ownership-sensitive callers use
     *  {@link #currentThreadHoldsAllClaims()} as authority to skip their own
     *  claim and therefore require an already-acquired window owned by that
     *  exact thread, not acquisition intent. */
    private static final class MainAllClaimsOwnership {
        final Object receipt;
        final Thread owner;
        final boolean globalFence;

        MainAllClaimsOwnership(Object receipt, Thread owner,
                               boolean globalFence) {
            this.receipt = receipt;
            this.owner = owner;
            this.globalFence = globalFence;
        }
    }

    /** Read off-main by packet/save/pacing paths; immutable publication is
     *  carried by this one volatile edge. */
    private volatile MainAllClaimsOwnership mainAllClaimsOwnership;

    /** Global-fence acquisition/release intent.  It only stops autonomous
     *  successor publication; it grants no permission to read or mutate an
     *  island without its claim.  Exact token identity closes timeout/ABA
     *  cleanup against a later fence. */
    private volatile Object globalFenceIntent;

    /** Exact receipt owned by this level's save/topology quiesce. Main-only. */
    private Object localAllClaimsReceipt;

    /**
     * Admission cut for tracker ownership decisions which may originate on VMP
     * workers.  A topology rebuild takes the write side only to publish/retire
     * its exact receipt; it never waits for a tracker monitor while holding the
     * closed writer phase.  Consequently a worker may enter with tracker ->
     * striped read admission while Main can still drain every pre-publication
     * reader before snapshotting the old cadence sets without creating a
     * tracker/admission inversion.
     */
    private final TrackingTopologyAdmissionGate trackingTopologyAdmission =
            new TrackingTopologyAdmissionGate();

    /** Immutable identity of one snapshot -> retirement -> reroute transaction. */
    static final class TrackingTopologyTransitionReceipt {
        final RegionizedServer runtime;
        final Object allClaimsReceipt;
        final Thread owner;
        final long generation;
        final TrackerTopologySnapshot<IslandTrackedEntityAccess, IslandRegistry.PIsland> cadences;

        TrackingTopologyTransitionReceipt(
                RegionizedServer runtime, Object allClaimsReceipt,
                Thread owner, long generation,
                TrackerTopologySnapshot<IslandTrackedEntityAccess, IslandRegistry.PIsland> cadences) {
            this.runtime = runtime;
            this.allClaimsReceipt = allClaimsReceipt;
            this.owner = owner;
            this.generation = generation;
            this.cadences = cadences;
        }
    }

    /** One volatile edge publishes the complete immutable transition identity. */
    private volatile TrackingTopologyTransitionReceipt trackingTopologyTransition;
    /** Main/closed-writer-phase only; zero is never a valid generation. */
    private long trackingTopologyTransitionGeneration;

    /** Server-wide admission fence used while one level establishes an exact
     * global-sprint boundary. Unlike TickRateManager's live sprint bit, this
     * cannot reopen halfway through the boundary when a routed command stops
     * the sprint. Ordinary submit/chaining reads it under unitAdmissionLock. */
    volatile boolean globalSprintAdmissionFenced;
    /** Exact cleanup identity for {@link #globalSprintAdmissionFenced}. */
    private volatile Object globalSprintAdmissionFenceOwner;

    /** task #19 arrival spacing chain for the global lane (main-only). */
    public final java.util.HashMap<Long, long[]> globalArrivalChains = new java.util.HashMap<>(4);

    public boolean mainHoldsAllClaims() {
        return mainAllClaimsOwnership != null;
    }

    /** Acquisition intent OR an acquired all-claims window.  This is for the
     *  pacer/completion admission paths only; ownership-sensitive callers must
     *  use {@link #currentThreadHoldsAllClaims()}. */
    boolean mainClaimsFenceActive() {
        return globalFenceIntent != null || mainAllClaimsOwnership != null;
    }

    /** Exact global-fence acquisition token observed by a coordinator which
     *  is about to park at a command-result boundary.  The token grants no
     *  ownership; it is only the rendezvous identity for that coordinator's
     *  explicit parked acknowledgement. */
    Object globalFenceIntentReceipt() {
        return globalFenceIntent;
    }

    boolean globalFenceIntentMatches(Object receipt) {
        return receipt != null && globalFenceIntent == receipt;
    }

    /** Exact-owner predicate used by nested fence recognition and by callers
     *  deciding whether THIS thread may enter without taking an island claim.
     *  The thread-blind {@link #mainHoldsAllClaims()} is only an ownership-state
     *  observation; it is never a lease for a foreign packet/save worker. */
    public boolean currentThreadHoldsAllClaims() {
        MainAllClaimsOwnership ownership = mainAllClaimsOwnership;
        return ownership != null && ownership.owner == Thread.currentThread();
    }

    /**
     * Enter one tracker-routing decision.  The read side spans the complete
     * detach/attach/schedule choice, so publishing a topology receipt on the
     * write side drains every decision which began against the old owner map.
     * The receipt grants no island or world access; it is only an admission cut.
     */
    void enterTrackingTopologyRouting() {
        trackingTopologyAdmission.readLock();
    }

    /** Exact mate of {@link #enterTrackingTopologyRouting()}. */
    void exitTrackingTopologyRouting() {
        trackingTopologyAdmission.readUnlock();
    }

    /** Immutable observation used only while choosing a tracker successor. */
    TrackingTopologyTransitionReceipt trackingTopologyTransitionReceipt() {
        TrackingTopologyTransitionReceipt receipt = trackingTopologyTransition;
        if (receipt != null
                && trackingTopologyAdmission.getReadHoldCount() == 0
                && !(receipt.owner == Thread.currentThread()
                && currentThreadHoldsAllClaims())) {
            throw new IllegalStateException(
                    "tracking topology receipt observed without routing admission");
        }
        return receipt;
    }

    /**
     * Publish the snapshot/retirement transaction after exact claim acquisition.
     * Closing the writer phase first is the old-route drain: after this returns,
     * every new foreign router observes this exact receipt and may only retain an
     * existing A5 carry or transfer its obligation to physical Main.
     */
    private TrackingTopologyTransitionReceipt beginTrackingTopologyTransition() {
        MainAllClaimsOwnership claims = mainAllClaimsOwnership;
        if (claims == null || claims.owner != Thread.currentThread()
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(
                level.getServer())) {
            throw new IllegalStateException(
                    "tracking topology transition without exact physical-Main all-claims ownership");
        }
        if (trackingTopologyAdmission.getReadHoldCount() != 0) {
            throw new IllegalStateException(
                    "tracking topology transition attempted a read-to-write upgrade");
        }
        trackingTopologyAdmission.writeLock();
        try {
            if (mainAllClaimsOwnership != claims) {
                throw new IllegalStateException(
                        "tracking topology transition lost its all-claims receipt");
            }
            if (trackingTopologyTransition != null) {
                throw new IllegalStateException(
                        "tracking topology transition already active");
            }
            long generation = ++trackingTopologyTransitionGeneration;
            if (generation <= 0L) {
                throw new IllegalStateException(
                        "tracking topology transition generation overflow");
            }
            TrackingTopologyTransitionReceipt receipt =
                    new TrackingTopologyTransitionReceipt(
                            this, claims.receipt, claims.owner, generation,
                            TrackerTopologySnapshot.capture(
                                    islandRegistry.islands(), island -> island.pendingEntityUpdates));
            trackingTopologyTransition = receipt;
            return receipt;
        } finally {
            trackingTopologyAdmission.writeUnlock();
        }
    }

    /**
     * Compare-clear only after every snapshotted tracker has rerouted.  The
     * closed writer phase drains transition-time foreign routers before
     * fresh-owner admission is reopened; stale/foreign cleanup is an invariant
     * failure, never a blind null.
     */
    private void completeTrackingTopologyTransition(
            TrackingTopologyTransitionReceipt receipt) {
        if (receipt == null) {
            throw new IllegalStateException(
                    "tracking topology transition completion lost its receipt");
        }
        if (trackingTopologyAdmission.getReadHoldCount() != 0) {
            throw new IllegalStateException(
                    "tracking topology completion attempted a read-to-write upgrade");
        }
        trackingTopologyAdmission.writeLock();
        try {
            MainAllClaimsOwnership claims = mainAllClaimsOwnership;
            if (trackingTopologyTransition != receipt
                    || receipt.runtime != this
                    || receipt.owner != Thread.currentThread()
                    || claims == null
                    || claims.owner != receipt.owner
                    || claims.receipt != receipt.allClaimsReceipt) {
                throw new IllegalStateException(
                        "tracking topology transition cleanup refused a stale or foreign receipt");
            }
            trackingTopologyTransition = null;
        } finally {
            trackingTopologyAdmission.writeUnlock();
        }
    }

    /** A local save/topology window may satisfy a nested global fence only on
     *  its exact owner thread.  An orphaned global receipt is never accepted
     *  through this fallback. */
    boolean currentThreadHoldsLocalAllClaims() {
        MainAllClaimsOwnership ownership = mainAllClaimsOwnership;
        return ownership != null && !ownership.globalFence
                && ownership.owner == Thread.currentThread();
    }

    void installGlobalFenceIntent(Object receipt) {
        if (receipt == null) throw new NullPointerException("receipt");
        if (globalFenceIntent != null) {
            throw new IllegalStateException(
                    "level already has a global-fence acquisition intent");
        }
        if (mainAllClaimsOwnership != null) {
            throw new IllegalStateException(
                    "global-fence intent attempted over an owned all-claims window");
        }
        globalFenceIntent = receipt;
    }

    void clearGlobalFenceIntent(Object receipt) {
        Object current = globalFenceIntent;
        if (current == null) return;
        if (current != receipt) {
            throw new IllegalStateException(
                    "global-fence intent cleanup refused a foreign receipt");
        }
        globalFenceIntent = null;
    }

    void publishGlobalAllClaimsOwnership(Object receipt) {
        publishAllClaimsOwnership(receipt, true);
    }

    void clearGlobalAllClaimsOwnership(Object receipt) {
        clearAllClaimsOwnership(receipt, true);
    }

    private void publishLocalAllClaimsOwnership(Object receipt) {
        publishAllClaimsOwnership(receipt, false);
    }

    private void clearLocalAllClaimsOwnership(Object receipt) {
        clearAllClaimsOwnership(receipt, false);
    }

    private void publishAllClaimsOwnership(Object receipt, boolean global) {
        if (receipt == null) throw new NullPointerException("receipt");
        if (mainAllClaimsOwnership != null) {
            throw new IllegalStateException(
                    "all-claims ownership already published for level");
        }
        if (global && globalFenceIntent != receipt) {
            throw new IllegalStateException(
                    "global all-claims ownership lost its acquisition intent");
        }
        mainAllClaimsOwnership = new MainAllClaimsOwnership(
                receipt, Thread.currentThread(), global);
    }

    private void clearAllClaimsOwnership(Object receipt, boolean global) {
        MainAllClaimsOwnership ownership = mainAllClaimsOwnership;
        if (ownership == null) return;
        if (ownership.receipt != receipt || ownership.globalFence != global) {
            throw new IllegalStateException(
                    "all-claims cleanup refused a foreign receipt");
        }
        if (ownership.owner != Thread.currentThread()) {
            throw new IllegalStateException(
                    "all-claims cleanup entered on a non-owner thread");
        }
        mainAllClaimsOwnership = null;
    }

    private long lastQuiesceWarnNanos;

    public boolean quiesceAllIslands() {
        boolean took = false;
        Throwable failure = null;
        long q0 = System.nanoTime();
        // Prepare the exact ownership identity before the first claim CAS.
        // Allocation failure is therefore a definite pre-acquisition failure.
        Object receipt = new Object();
        try {
            took = quiesceAllIslandsInner(receipt);
            long dt = System.nanoTime() - q0;
            if (took && dt > 100_000_000L
                    && q0 - lastQuiesceWarnNanos > 10_000_000_000L) {
                lastQuiesceWarnNanos = q0;
                LOGGER.warn("[async-islands] save/topology quiesce took {} ms on {} — an island's units are long; "
                                + "check /fabrolia regions islands for the overloaded island",
                        dt / 1_000_000, level.dimension().identifier());
            }
        } catch (Throwable primary) {
            failure = RegionScheduler.publishFatalAndSelectWinner(primary);
        }
        if (failure != null) {
            // quiesceAllIslandsInner releases any unregistered current claim.
            // This outer envelope also covers diagnostics after a successful
            // acquisition, so no exceptional return can retain the claim list.
            failure = releaseAllIslands(failure);
            RegionizedServer.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
        }
        return took;
    }

    private boolean quiesceAllIslandsInner(Object receipt) {
        // Reentrancy: a console `save-all` arrives INSIDE the command fence
        // (CommandsMixin) — main already holds every claim; taking them again
        // would spin on ourselves forever (the 2026-07-12 a8cmd watchdog
        // crash, stack at this line). The outer window's exclusivity covers
        // us; report took=false so the caller skips its release.
        if (mainHoldsAllClaims()) {
            if (!currentThreadHoldsAllClaims()) {
                throw new IllegalStateException(
                        "all-island quiesce attempted to borrow a foreign owner");
            }
            return false;
        }
        // A8: a claim can be held by a coordinator PARKED on a routed command
        // — it will not release until main executes the batch, and topology/
        // save windows need the unit to FINISH, not merely park. Release physical
        // claims to run its exact Main body, but retain the admission cut: a
        // finished unit must not chain a successor while another unit still
        // needs routed progress. The entered work then has a finite terminal set.
        IslandRegistry.PIsland current = null;
        boolean currentClaimed = false;
        AllIslandClaimIntents intentIslands = null;
        Throwable failure = null;
        try {
            retry:
            while (true) {
                // TWO-PHASE CLAIM INTENT: fence every island's pacer/chaining
                // before waiting on even the first live unit. Publishing intent
                // one island at a time made a 100-island quiesce wait roughly the
                // SUM of unit tails: while Main waited for island A, B..Z were
                // still free to chain another unit. With the stable snapshot
                // fenced first, all in-flight tails drain concurrently and the
                // claim pass waits approximately for their MAX instead.
                intentIslands = publishAllIslandClaimIntents(islandRegistry.islands());
                for (IslandRegistry.PIsland p : intentIslands.islands) {
                    current = p;
                    currentClaimed = false;
                    long spinStart = System.nanoTime();
                    int stuckReports = 0;
                    while (!p.unitClaim.compareAndSet(false, true)) {
                        if (System.nanoTime() - spinStart > 5_000_000_000L) {
                            spinStart = System.nanoTime();
                            stuckReports++;
                            LOGGER.error("[async-islands] quiesce stuck {}x5s (tasks={} L={} asyncActive={}) — retained-claim forensics: {}",
                                    stuckReports, p.pendingIslandTasks.size(),
                                    p.logicalTick, asyncActive, IslandCommands.claimForensics(p));
                            // A claim which survives the forensic threshold is an
                            // invariant failure, never evidence that Main may forge
                            // ownership. The outer fail-stop envelope releases only
                            // claims this quiesce actually acquired.
                            if (stuckReports >= 3 && !asyncActive
                                    && !p.waitingOnRoutedCommand) {
                                throw new IllegalStateException(
                                        "island claim remained held after forensic threshold: "
                                                + IslandCommands.claimForensics(p));
                            }
                        }
                        if (p.waitingOnRoutedCommand) {
                            Throwable retryFailure =
                                    clearAllIslandClaimIntents(intentIslands, null);
                            intentIslands = null;
                            retryFailure = releaseAllIslands(retryFailure);
                            if (retryFailure != null) {
                                RegionizedServer.<RuntimeException>rethrow(
                                        RegionScheduler.publishFatalAndSelectWinner(
                                                retryFailure));
                            }
                            IslandCommands.drainRoutedBatch();
                            continue retry;
                        }
                        IslandCommands.drainMainChunkServices();
                        java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
                    }
                    currentClaimed = true;
                    // Leak-proof publication (task #25): registration failure must
                    // release, never strand the flag outside releaseAllIslands. The
                    // outer cleanup also releases every earlier registered island.
                    p.noteClaim("quiesceAll");
                    quiescedIslands.add(p);
                    currentClaimed = false;
                    current = null;
                }
                Throwable intentCleanupFailure =
                        clearAllIslandClaimIntents(intentIslands, null);
                intentIslands = null;
                if (intentCleanupFailure != null) {
                    RegionizedServer.<RuntimeException>rethrow(
                            RegionScheduler.publishFatalAndSelectWinner(
                                    intentCleanupFailure));
                }
                break;
            }
        } catch (Throwable primary) {
            failure = RegionScheduler.publishFatalAndSelectWinner(primary);
        }
        if (failure != null) {
            // W is selected before cleanup, preserving the generation-wide
            // first-failure cut. This attempt's intents are restored before either the
            // current claim or the registered claim list is released.
            failure = clearAllIslandClaimIntents(intentIslands, failure);
            intentIslands = null;
            if (currentClaimed && current != null) {
                failure = releaseIslandClaim(current, failure);
            }
            failure = releaseAllIslands(failure);
            RegionizedServer.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
        }
        if (localAllClaimsReceipt != null) {
            throw new IllegalStateException(
                    "local all-island ownership receipt already active");
        }
        localAllClaimsReceipt = receipt;
        try {
            publishLocalAllClaimsOwnership(receipt);
        } catch (Throwable publicationFailure) {
            localAllClaimsReceipt = null;
            RegionizedServer.<RuntimeException>rethrow(publicationFailure);
        }
        return true;
    }

    private static final class AllIslandClaimIntents {
        final ArrayList<IslandRegistry.PIsland> islands;
        final boolean[] priorClaimWanted;
        int publishedCount;

        AllIslandClaimIntents(java.util.Collection<IslandRegistry.PIsland> islands) {
            this.islands = new ArrayList<>(islands);
            this.priorClaimWanted = new boolean[this.islands.size()];
        }
    }

    private static AllIslandClaimIntents publishAllIslandClaimIntents(
            java.util.Collection<IslandRegistry.PIsland> islands) {
        AllIslandClaimIntents snapshot = new AllIslandClaimIntents(islands);
        try {
            for (int i = 0, n = snapshot.islands.size(); i < n; i++) {
                IslandRegistry.PIsland island = snapshot.islands.get(i);
                snapshot.priorClaimWanted[i] = island.claimWanted;
                snapshot.publishedCount = i + 1;
                island.claimWanted = true;
            }
            return snapshot;
        } catch (Throwable primary) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(primary);
            failure = clearAllIslandClaimIntents(snapshot, failure);
            RegionizedServer.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
            throw new AssertionError("unreachable");
        }
    }

    /** Idempotent, exhaustive whole-attempt cleanup. The already-selected
     * primary remains authoritative; a cleanup failure is merged behind it and
     * never skips later intent slots or physical claim release. */
    private static Throwable clearAllIslandClaimIntents(
            AllIslandClaimIntents snapshot, Throwable primary) {
        Throwable failure = primary;
        if (snapshot == null) return failure;
        int count = snapshot.publishedCount;
        snapshot.publishedCount = 0;
        for (int i = count - 1; i >= 0; i--) {
            try {
                snapshot.islands.get(i).claimWanted = snapshot.priorClaimWanted[i];
            } catch (Throwable cleanup) {
                failure = publishCleanupFailure(failure, cleanup);
            }
        }
        return failure;
    }

    public void releaseAllIslands() {
        Throwable failure = releaseAllIslands(null);
        if (failure != null) {
            RegionizedServer.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
        }
    }

    public Throwable releaseAllIslands(Throwable primary) {
        // Publish an entered-operation primary before mandatory claim cleanup.
        // Releasing a large island set is not a window in which another root may
        // start and beat the actual first failure into the shared authority slot.
        Throwable failure = primary != null && RegionScheduler.isActive()
                ? RegionScheduler.publishFatalAndSelectWinner(primary)
                : primary;
        Object receipt = localAllClaimsReceipt;
        if (receipt != null) {
            try {
                // Revoke skip-claim authority before the first physical claim
                // is released. Readers may briefly block on a remaining claim,
                // but can never run claimless in a partially released window.
                clearLocalAllClaimsOwnership(receipt);
            } catch (Throwable cleanup) {
                failure = publishCleanupFailure(failure, cleanup);
            }
            localAllClaimsReceipt = null;
        }
        for (int i = 0, n = quiescedIslands.size(); i < n; i++) {
            IslandRegistry.PIsland island;
            try {
                island = quiescedIslands.get(i);
            } catch (Throwable cleanup) {
                failure = publishCleanupFailure(failure, cleanup);
                continue;
            }
            failure = releaseIslandClaim(island, failure);
        }
        try {
            quiescedIslands.clear();
        } catch (Throwable cleanup) {
            failure = publishCleanupFailure(failure, cleanup);
        }
        return failure;
    }

    private static Throwable releaseIslandClaim(IslandRegistry.PIsland island,
                                                 Throwable primary) {
        Throwable failure = primary;
        try {
            island.clearClaim();
        } catch (Throwable cleanup) {
            failure = publishCleanupFailure(failure, cleanup);
        }
        try {
            island.unitClaim.set(false);
        } catch (Throwable cleanup) {
            failure = publishCleanupFailure(failure, cleanup);
        }
        return failure;
    }

    private static Throwable publishCleanupFailure(Throwable primary,
                                                   Throwable cleanup) {
        return RegionScheduler.publishFatalAndSelectWinner(
                mergeFailure(primary, cleanup));
    }

    /**
     * Main-thread shutdown boundary.  Convert an async level through the same
     * quiesce/carry/domain-collapse path as the live async-off transition before
     * the worker pool starts its final queue drain.  Region queues that were
     * intentionally waiting for the next island unit are then ordinary global-
     * domain obligations; queued unit coordinators observe {@code asyncActive=false}
     * at their head and retire without beginning a simulation phase.
     */
    void prepareForSchedulerStop() {
        if (!asyncActive && !domainsIslandized) return;
        asyncActive = false;
        RegionScheduler.updateAsyncLive();
        islandsDirty = true;
        rebuildIslandsIfDirty();
        if (asyncActive || domainsIslandized || mainHoldsAllClaims()) {
            throw new IllegalStateException("async level did not reach the shutdown wall-domain boundary: "
                    + level.dimension().identifier());
        }
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
    int healIslandBarrier(List<Region> members, EpochDomain d) {
        RegionScheduler.throwWorkerFailureIfAny();
        long epoch = d.epoch;
        if (d.dispatchedEpoch != epoch) return 0;
        int acceptedRecoveries = 0;
        boolean pending = d.entityBarrierArmedEpoch == epoch && d.entityPending.get() > 0;
        for (int i = 0, n = members.size(); i < n; i++) {
            RegionScheduler.throwWorkerFailureIfAny();
            Region r = members.get(i);
            if (r.state() != RegionState.ACTIVE) continue;
            ColorGate.tryMarkDone(r, this);
            if (pending && owesEntityPhase(r)) {
                if (ColorGate.eligible(r, this) && r.currentWorker() == null) {
                    if (RegionScheduler.enqueueEntityReady(r)) {
                        acceptedRecoveries++;
                    }
                    RegionScheduler.wakeWorkers(1);
                }
            } else if (pending && r.hasSealedDone(epoch)) {
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
            RegionScheduler.throwWorkerFailureIfAny();
            Region m = members.get(i);
            int rx = (int) m.gridKey;
            int rz = (int) (m.gridKey >>> 32);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    RegionScheduler.throwWorkerFailureIfAny();
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
        return acceptedRecoveries;
    }

    /** Audit: identity handle of this level's entity manager (mixin-side
     *  matching without a level field on the manager). */
    public Object entityManagerHandle() {
        return ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                .fabrolia$entityManager();
    }

    /** Destination timeline owner, not execution authority. Existing Regions
     * remain authoritative through transitions; absent cells use the immutable
     * creation-domain snapshot without materializing a Region. */
    public IslandRegistry.PIsland islandOwnerOfChunk(long packedChunkPos) {
        long gridKey = regionizer.grid.keyFor(packedChunkPos);
        Region r = regionizer.peekByGridKey(gridKey);
        if (r != null) {
            EpochDomain d = r.domain;
            return d != null ? d.owner : null;
        }
        var committed = cellDomainSnapshot;
        EpochDomain d = committed == null ? null : committed.get(gridKey);
        IslandRegistry.PIsland owner = d != null ? d.owner : null;
        return owner != null && !owner.retired && owner.domain == d ? owner : null;
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
    public volatile com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap<BlockEntityTickerList> beCells =
            new com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap<>();
    /** Registration sequence per live wrapper (task #14 seam-order fix):
     *  vanilla's BE phase runs the flat list in REGISTRATION order, and for
     *  same-tick moving-piston pairs that mutual order is load-bearing (a 6gt
     *  QC engine straddling a static seam stalls when the two moving_piston
     *  BEs tick in cell-key order instead of creation order — direction-
     *  asymmetric because one travel direction happens to match cell order).
     *  Assigned at beIndexAdd (rebuild walks the vanilla list in order).
     *  Immutable cell lists carry their own primitive stamps, so indexed
     *  traversal never consults this map. Retained for rebuild continuity and
     *  generic/foreign merge inputs. Pruned with beCellOf. */
    public final java.util.concurrent.ConcurrentHashMap<net.minecraft.world.level.block.entity.TickingBlockEntity, Long> beSeqOf =
            new java.util.concurrent.ConcurrentHashMap<>();
    private long beSeqCounter;

    public final it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap<net.minecraft.world.level.block.entity.TickingBlockEntity> beCellOf =
            new it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap<>();
    { beCellOf.defaultReturnValue(Long.MAX_VALUE); } // 0 is a real cell key
    private static final int MAX_PENDING_BE_ADDS = 65_536;
    private final BoundedPendingQueue<PendingBeAdd> pendingBeAdds =
            new BoundedPendingQueue<>(MAX_PENDING_BE_ADDS);
    public volatile boolean beCellsValid = false;

    private record PendingBeAdd(net.minecraft.world.level.block.entity.TickingBlockEntity ticker, long cellKey) {}

    /** Cell key for a BE position (pure arithmetic on the fixed grid). */
    public long beCellKeyFor(net.minecraft.core.BlockPos pos) {
        return regionizer.grid.keyFor(net.minecraft.world.level.ChunkPos.pack(pos));
    }

    private static boolean fabroliaIsMainOnlyTicker(net.minecraft.world.level.block.entity.TickingBlockEntity t) {
        String type = t.getType();
        return "minecraft:test_block".equals(type) || "minecraft:test_instance_block".equals(type);
    }

    /** Any thread under the registration lock: capture the position before
     *  removal/sleep can rebind the native wrapper to a positionless stub. */
    public void stashPendingBeAdd(net.minecraft.world.level.block.entity.TickingBlockEntity ticker) {
        net.minecraft.core.BlockPos pos = RegionScheduler.resolveBlockEntityTickerPosition(ticker);
        boolean pendingOverflow = pendingBeAdds.offer(new PendingBeAdd(ticker,
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
            if (p != null && pos != null) {
                synchronized (p.pendingUnitBes) {
                    p.pendingUnitBes.add(ticker);
                }
            }
        }
        // Backstop for long/cold stretches. BoundedPendingQueue has already
        // dropped the old backlog on overflow; mark the derived index invalid
        // so the next partitioned pass rebuilds from the authoritative list.
        if (pendingOverflow) {
            beCellsValid = false;
        }
    }

    /** Main, partition start: fold stashed registrations into the cell index.
     *  Long.MIN_VALUE (posless custom tickers) lands in the isolated lane's cell. */
    void drainPendingBeAdds() {
        for (PendingBeAdd p : pendingBeAdds.drain()) {
            beIndexAdd(p.ticker, p.cellKey);
        }
    }

    /**
     * Main, after the vanilla BE fallback completed: the vanilla ticker list
     * is authoritative and the derived index is unused. Drop the whole index
     * instead of retaining removed wrappers (and a duplicate reference to
     * every live wrapper) for an arbitrarily long fallback session.
     */
    void releaseBeIndexForVanillaFallback() {
        beCellsValid = false;
        pendingBeAdds.clear();
        BlockEntityIndexRetention.clear(
                beCells, beSeqOf, beCellOf, pendingBeSweep, unitRemovedBes);
        beSeqCounter = 0;
    }

    /** Cold/frozen path: the cell map may still be read by an older async
     * unit, but the registration backlog itself is never needed while the
     * next live partition pass will bulk-rebuild from the vanilla list. */
    void discardPendingBeAdds() {
        pendingBeAdds.clear();
    }

    private void beIndexAdd(net.minecraft.world.level.block.entity.TickingBlockEntity t, long cellKey) {
        // Copy-on-write: sequence and ticker are one immutable publication.
        BlockEntityTickerList list = beCells.get(cellKey);
        long sequence = beSequenceFor(t);
        BlockEntityTickerList next = list == null
                ? BlockEntityTickerList.singleton(t, sequence) : list.append(t, sequence);
        beCellOf.put(t, cellKey);
        beCells.put(cellKey, next);
    }

    private long beSequenceFor(net.minecraft.world.level.block.entity.TickingBlockEntity ticker) {
        Long previous = beSeqOf.putIfAbsent(ticker, beSeqCounter++);
        return previous == null ? beSeqCounter - 1 : previous;
    }

    /** Main: one-time (re)build of the cell index from the authoritative vanilla
     *  list. Runs after the pending flush so the list is complete; clears the
     *  stash (its entries are already in the list).
     *
     *  <p>Universal-crafter fix (2026-08-21): a fully sleeping Lithium wrapper
     *  currently reports {@code getPos()==null}, but it is NOT positionless.
     *  Filing it under {@code Long.MIN_VALUE} left it in the main BE lane even
     *  after wake; a main-lane hopper could then mutate an island-owned crafter
     *  concurrently with its scheduled craft tick. Resolve through the stable
     *  registration stamp so sleeping hoppers/crafters remain in their owner
     *  cell. Only genuinely positionless and Main-cadence harness tickers use
     *  the isolated lane; global native bodies retain their island cadence. */
    void rebuildBeCells(java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> src) {
        // Registration's level-local monitor excludes new authoritative adds.
        // Older units retain complete lists with their own immutable sequence stamps.
        var builders = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockEntityTickerList.Builder>();
        var nextCells = new com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap<BlockEntityTickerList>();
        beCellOf.clear();
        pendingBeAdds.clear();
        for (int i = 0, m = src.size(); i < m; i++) {
            net.minecraft.world.level.block.entity.TickingBlockEntity t = src.get(i);
            net.minecraft.core.BlockPos pos = RegionScheduler.resolveBlockEntityTickerPosition(t);
            long cellKey = pos == null || fabroliaIsMainOnlyTicker(t)
                    ? Long.MIN_VALUE : beCellKeyFor(pos);
            BlockEntityTickerList.Builder cell = builders.get(cellKey);
            if (cell == null) {
                cell = new BlockEntityTickerList.Builder(8);
                builders.put(cellKey, cell);
            }
            cell.add(t, beSequenceFor(t));
            beCellOf.put(t, cellKey);
        }
        for (var entry : builders.long2ObjectEntrySet()) {
            nextCells.put(entry.getLongKey(), entry.getValue().build());
        }
        beSeqOf.keySet().removeIf(t -> !beCellOf.containsKey(t));
        beCells = nextCells;
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
        int misroutedPosless = 0;
        for (var e : beCells.long2ObjectEntrySet()) {
            long cellKey = e.getLongKey();
            BlockEntityTickerList list = e.getValue();
            for (int i = 0, m = list.size(); i < m; i++) {
                var t = list.get(i);
                cellEntries++;
                inCells.put(t, Boolean.TRUE);
                if (beCellOf.getLong(t) != cellKey) cellOfMismatch++;
                if (!t.isRemoved() && cellKey == Long.MIN_VALUE
                        && RegionScheduler.resolveBlockEntityTickerPosition(t) != null
                        && !fabroliaIsMainOnlyTicker(t)) {
                    misroutedPosless++;
                }
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
            BlockEntityTickerList list = e.getValue();
            for (int i = 0, m = list.size(); i < m; i++) {
                var t = list.get(i);
                if (!inList.containsKey(t)) {
                    if (t.isRemoved()) staleRemoved++; else staleLive++;
                }
            }
        }
        int stash = pendingBeAdds.size();
        boolean ok = cellOfMismatch == 0 && missing <= stash && staleLive == 0
                && misroutedPosless == 0;
        return String.format("beindex %s: list=%d cells=%d(%d) missing=%d stash=%d staleLive=%d staleRemoved=%d cellOfMismatch=%d misroutedPosless=%d sweepPending=%d",
                ok ? "OK" : "DRIFT", src.size(), cellEntries, beCells.size(), missing, stash, staleLive,
                staleRemoved, cellOfMismatch, misroutedPosless, pendingBeSweep.size());
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
        // A2 deadline drive: units may be in flight at any slot — topology
        // work (BFS, unstash, registry diff, domain reassignment) requires
        // every unit quiescent. Sync drive: claims are always free, so this
        // is a no-op pass of CAS successes.
        // Quiesce whenever units could be in flight: async ON, or the
        // ON→OFF collapse pass (asyncActive already false but last tick's
        // units may still run — the fail-closed rig caught the un-quiesced
        // collapse racing them, 2026-07-12).
        boolean collapsingToWall = !asyncActive && domainsIslandized;
        boolean trackingTopologyRequired = asyncActive || domainsIslandized;
        boolean quiesced = trackingTopologyRequired && quiesceAllIslands();
        boolean[] committed = new boolean[1];
        TrackingTopologyTransitionReceipt[] trackingTransition = new
                TrackingTopologyTransitionReceipt[1];
        Throwable failure = null;
        try {
            // Quiesced island claims establish ownership, but they are not the
            // generation-wide W-vs-body cut. Keep dirty retirement, A5 handoff
            // mutation and the complete topology rebuild in one admitted body.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                if (trackingTopologyRequired) {
                    // This successor-state publication belongs to the same admitted
                    // generation as the complete rebuild. The write side drains
                    // every old-map tracker decision before the first cadence
                    // snapshot or owner retirement. Ordinary save-all never enters
                    // this topology-only receipt.
                    trackingTransition[0] = beginTrackingTopologyTransition();
                }
                islandsDirty = false;
                if (collapsingToWall) {
                    // A5 owners are identities of the old autonomous topology.  The
                    // topology rebuild below retires those identities, so detach every
                    // player while their old island claims are still held.  Otherwise an
                    // async ON -> OFF transition leaves the listener believing the old
                    // island still owns it: physical Main skips listener.tick(), Netty
                    // keeps appending to an island FIFO which no unit will ever drain,
                    // and the player silently freezes.  clearIsland opens the ordered
                    // MAIN_HANDOFF before the old registry entries disappear.
                    for (IslandRegistry.PIsland island : islandRegistry.islands()) {
                        AsyncIslandPlayerTicks.clearIsland(island);
                    }
                }
                rebuildIslandsBody();
                if (trackingTransition[0] != null) {
                    // Begin, every topology mutation/reroute, and compare-clear
                    // are one already-admitted transaction. A peer W may prevent
                    // entry, but cannot expose a half-open fresh-owner map.
                    completeTrackingTopologyTransition(trackingTransition[0]);
                }
                committed[0] = true;
            });
        } catch (Throwable primary) {
            failure = RegionScheduler.publishFatalAndSelectWinner(primary);
        }
        if (quiesced) {
            failure = releaseAllIslands(failure);
        }
        // A fresh topology must not be armed until its quiesce claims are
        // visible as released. Failed/partial rebuilds deliberately do not
        // publish pacing authority for an uncommitted shape, and a failed
        // release cannot publish a successor against retained ownership.
        if (failure == null && committed[0]) {
            try {
                // Claim cleanup is not a healthy-success publication. Pacing
                // starts only after the scope which actually owned them releases.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
                RegionScheduler.runSuccessBodyIfHealthy(
                        () -> TemporalControl.onTopologyCommitted(this));
            } catch (Throwable completionFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(completionFailure);
            }
        }
        if (failure != null) {
            RegionizedServer.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
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
        java.util.ArrayList<net.minecraft.server.level.ChunkHolder> unstashedBlockUpdates = null;
        TrackingTopologyTransitionReceipt trackerTransition = trackingTopologyTransitionReceipt();
        TrackerTopologySnapshot<IslandTrackedEntityAccess, IslandRegistry.PIsland> trackerCadences =
                trackerTransition == null ? null : trackerTransition.cadences;
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
                if (!p.pendingBlockUpdates.isEmpty()) {
                    if (unstashedBlockUpdates == null) unstashedBlockUpdates = new java.util.ArrayList<>();
                    unstashedBlockUpdates.addAll(p.pendingBlockUpdates);
                    p.pendingBlockUpdates.clear();
                }
            }
        }

        ArrayList<Region> topologyRebaseBatch = new ArrayList<>();
        java.util.Set<Region> topologyRebaseMembers =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable topologyDomainFailure = null;
        try {
        // Close the union of OLD island cells and NEW core/halo cells before
        // publishing islands/registry/domain mutations.  This is the missing
        // newborn-global-halo half of quiesceAllIslands(): existing island unit
        // claims are already held, while these per-region cuts retire global
        // claim bodies and their post-release tails without holding admissionLock.
        // The creation WRITE closes the map generation: a creator already in
        // domainForCreation/map publication finishes before this snapshot; a
        // later creator waits or is found by the fixed-point passes below.
        regionizer.lockTopologyCreationWrite();
        try {
            List<Region> cutSnapshot = regionizer.snapshot();
            for (int i = 0, n = cutSnapshot.size(); i < n; i++) {
                Region r = cutSnapshot.get(i);
                EpochDomain oldDomain = r.domain;
                boolean oldIslandCell = oldDomain != null && oldDomain.owner != null;
                boolean newIslandCell = asyncActive && gridToIsland.get(r.gridKey) >= 0;
                if (oldIslandCell || newIslandCell) {
                    prepareTopologyCandidateCut(r, topologyRebaseBatch);
                    topologyRebaseMembers.add(r);
                }
            }
        } finally {
            regionizer.unlockTopologyCreationWrite();
        }
        // A release-successor tail is allowed to create another Region, so the
        // writer must be released before waiting. Such a Region is collected by
        // completeTopologyDomainRebaseFixedPoint after target domains exist.
        awaitPreparedDomainRebaseBatch(topologyRebaseBatch);

        // A1: keep persistent island identities + shadow clocks in step with the
        // fresh BFS result only after every affected Region transaction retired.
        islands = result;
        islandRegistry.applyRebuild(result, level.getGameTime());

        // A2: (re)point every region at its timeline. Merges move absorbed
        // members onto the survivor's domain, splits move fragments onto fresh
        // domains; the complete affected-cell union stays claim-closed until
        // all assignments and the immutable cell snapshot have committed.
        if (asyncActive) {
            for (int ii = 0, in = result.size(); ii < in; ii++) {
                List<Region> members = result.get(ii).members();
                IslandRegistry.PIsland p = members.isEmpty()
                        ? null : islandRegistry.byCell(members.get(0).gridKey);
                if (p != null) p.level = level;
            }
            // FRONTIER HALO (2026-07-14 fluid cross-domain fix): regions in
            // the 1-cell closure around an island's populated core carry the
            // ISLAND's domain, not global. Vanilla edge mechanics (fluid
            // spreadTo, fire, piston heads) legally mutate one chunk past the
            // core — and a frontier chunk can be promoted to FULL mid-tick
            // (fresh generation), after this rebuild already ran. With the
            // halo the write target is in the island's own timeline either
            // way. Empty halo regions contribute no tick work. If one becomes
            // FULL after the tick-head rebuild, owner-local scheduled discovery
            // consumes it immediately in this same domain until popChanged's
            // next rebuild adopts it as CORE; its ticks never wait on Main.
            // gridToIsland already holds exactly this closure (putIfAbsent:
            // core id wins; disjointness of two islands' halos is implied by
            // the ≤2-cell merge rule). Everything else stays global.
            for (IslandRegistry.PIsland p : islandRegistry.islands()) {
                p.haloCells.clear();
            }
            var cellDomains = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain>(
                    gridToIsland.size() * 2);
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
                if (hp != null) cellDomains.put(e.getLongKey(), hp.domain);
            }
            // Settle the initially captured map generation. Queue bodies run
            // without the creation WRITE and may create more regions; the
            // fixed-point helper captures and settles every such successor.
            applyPreparedTopologyTargets(
                    topologyRebaseBatch, 0, cellDomains, true);
            completeTopologyDomainRebaseFixedPoint(
                    topologyRebaseBatch, topologyRebaseMembers,
                    cellDomains, true);
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
            applyPreparedTopologyTargets(
                    topologyRebaseBatch, 0, null, false);
            completeTopologyDomainRebaseFixedPoint(
                    topologyRebaseBatch, topologyRebaseMembers,
                    null, false);
            for (IslandRegistry.PIsland p : islandRegistry.islands()) {
                p.haloCells.clear();
            }
        }
        } catch (Throwable domainFailure) {
            topologyDomainFailure = RegionScheduler.publishFatalAndSelectWinner(
                    domainFailure);
        }
        topologyDomainFailure = finishDomainRebaseBatch(
                topologyRebaseBatch, topologyDomainFailure);
        if (topologyDomainFailure != null) {
            RegionizedServer.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(
                            topologyDomainFailure));
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
            CommandShards.finishTopology(this, arrivalCarryIslands);
            for (int i = 0, n = arrivalCarryIslands.size(); i < n; i++) {
                CrossDimArrivals.reroute(this, arrivalCarryIslands.get(i));
            }
            RaidIslandDispatch.reassignAfterTopology(this, arrivalCarryIslands);
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
                    // Carry keeps both the source stamp and canonical pending identity.
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
        if (unstashedBlockUpdates != null && asyncActive) {
            for (int i = 0, n = unstashedBlockUpdates.size(); i < n; i++) {
                net.minecraft.server.level.ChunkHolder holder = unstashedBlockUpdates.get(i);
                IslandRegistry.PIsland owner = islandOwnerOfChunk(holder.getPos().pack());
                if (owner != null && !owner.retired) owner.pendingBlockUpdates.add(holder);
            }
        }
        if (trackerCadences != null) {
            for (int i = 0, n = trackerCadences.size(); i < n; i++) {
                // reroute always detaches the exact tracker from its retired
                // owner first.  When async was disabled by this rebuild it
                // deliberately declines to reattach, preventing the tracker
                // from retaining the retired topology across OFF -> ON.
                IslandEntityTracking.reroute(this, trackerCadences.tracker(i));
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
                if (tbe.isRemoved()) continue;
                net.minecraft.core.BlockPos pos =
                        RegionScheduler.resolveBlockEntityTickerPosition(tbe);
                // Registration already dual-wrote the Main/index pending lane.
                // Unresolved wrappers remain there rather than guessing an owner.
                if (pos == null || fabroliaIsMainOnlyTicker(tbe)) continue;
                IslandRegistry.PIsland owner =
                        islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(pos));
                if (owner != null) {
                    synchronized (owner.pendingUnitBes) {
                        owner.pendingUnitBes.add(tbe);
                    }
                }
            }
        }
        // Task #43 — cross-dimension link detection + deadline-anchor cadence
        // coupling. Deliberately INSIDE the rebuild quiesce window (review
        // D7): this level's island claims are main-held here, so link/anchor
        // writes on this side cannot race a completing unit; the partner
        // level's members are touched only under a try-CAS of their claim
        // (failure defers the anchor to the drive-head tick pass). Runs for
        // the ON→OFF collapse too — CrossDimLinks drops every link once the
        // both-levels-async precondition is gone.
        CrossDimLinks.onRebuild(this);
    }

    /**
     * Commit the target domain/island id for a drained slice of the topology
     * batch.  This deliberately runs without ThreadedRegionizer's creation
     * WRITE: the finite old-domain FIFO is allowed to execute arbitrary vanilla
     * callbacks, including callbacks which lazily create another Region.
     */
    private void applyPreparedTopologyTargets(
            ArrayList<Region> batch, int fromIndex,
            it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain> targetCells,
            boolean islandizedTarget) {
        for (int i = fromIndex, n = batch.size(); i < n; i++) {
            Region r = batch.get(i);
            EpochDomain target = topologyTargetDomain(
                    r.gridKey, targetCells, islandizedTarget);
            if (r.domain != target) {
                commitPreparedDomainRebase(r, target);
            }
            r.islandId = target == globalDomain ? -1 : gridToIsland.get(r.gridKey);
        }
    }

    /**
     * Fixed-point close/await/commit protocol for Regions born while topology
     * work was in progress.
     *
     * <p>Each WRITE pass drains all pre-existing creators, snapshots the actual
     * map and closes every previously unseen old-island/new-island candidate.
     * If it found any, WRITE is released before waiting for claim tails or
     * running migration callbacks.  Those callbacks may create more Regions,
     * so another pass follows.  The only stable pass retains WRITE through the
     * creation-time domain snapshot publication; a creator therefore belongs
     * wholly to the old captured generation or starts against the new snapshot.
     */
    private void completeTopologyDomainRebaseFixedPoint(
            ArrayList<Region> batch, java.util.Set<Region> members,
            it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain> targetCells,
            boolean islandizedTarget) {
        for (;;) {
            int firstNew = batch.size();
            boolean published = false;
            regionizer.lockTopologyCreationWrite();
            try {
                List<Region> current = regionizer.snapshot();
                for (int i = 0, n = current.size(); i < n; i++) {
                    Region r = current.get(i);
                    if (members.contains(r)) continue;
                    EpochDomain target = topologyTargetDomain(
                            r.gridKey, targetCells, islandizedTarget);
                    EpochDomain old = r.domain;
                    boolean oldIslandCell = old != null && old.owner != null;
                    boolean newIslandCell = target != globalDomain;
                    if (!oldIslandCell && !newIslandCell) continue;
                    prepareTopologyCandidateCut(r, batch);
                    members.add(r);
                }
                if (batch.size() == firstNew) {
                    // Linearization point for creators queued behind this WRITE.
                    // Domain/clock commits for every map member are already done
                    // and every pre-publication member stays claim-closed until
                    // finishDomainRebaseBatch completes outside the writer.
                    cellDomainSnapshot = islandizedTarget ? targetCells : null;
                    domainsIslandized = islandizedTarget;
                    published = true;
                }
            } finally {
                regionizer.unlockTopologyCreationWrite();
            }
            if (published) return;

            // Never wait or execute a pre-cut queue body while holding WRITE:
            // both a release-successor tail and an arbitrary queue callback may
            // enter regionForGridKey.  The next writer pass captures the result.
            for (int i = firstNew, n = batch.size(); i < n; i++) {
                awaitClaimTransactionsForDomainRebase(batch.get(i));
            }
            applyPreparedTopologyTargets(
                    batch, firstNew, targetCells, islandizedTarget);
        }
    }

    private EpochDomain topologyTargetDomain(
            long gridKey,
            it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain> targetCells,
            boolean islandizedTarget) {
        if (islandizedTarget && targetCells != null) {
            EpochDomain target = targetCells.get(gridKey);
            if (target != null) return target;
        }
        return globalDomain;
    }

    /** A2: move {@code r} onto timeline {@code d}, translating its epoch stamps
     *  into the new domain's present: complete for d's CURRENT epoch, owing
     *  nothing — the next dispatch that includes it bumps the epoch first and
     *  stamps it afresh. MUST only run at a safe point (no dispatch/unit in
     *  flight in either domain); the CAS-gate resets below would otherwise
     *  race live contributions. */
    private static void assignDomain(Region r, EpochDomain d) {
        ArrayList<Region> one = new ArrayList<>(1);
        Throwable failure = null;
        try {
            assignDomain(r, d, one);
        } catch (Throwable primary) {
            failure = RegionScheduler.publishFatalAndSelectWinner(primary);
        }
        failure = finishDomainRebaseBatch(one, failure);
        if (failure != null) {
            RegionizedServer.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
        }
    }

    /**
     * Close this region's exact old-domain claim transaction, settle its finite
     * pre-cut FIFO, translate clocks and publish the new domain.  Claim admission
     * remains closed on return; the enclosing topology rebuild reopens the whole
     * batch only after every neighbor/domain snapshot has committed.
     */
    private static void assignDomain(
            Region r, EpochDomain d, ArrayList<Region> rebaseBatch) {
        prepareDomainRebaseCut(r, d, rebaseBatch);
        if (r.domain == d) return;
        awaitClaimTransactionsForDomainRebase(r);
        commitPreparedDomainRebase(r, d);
    }

    /** Phase 1 for a topology batch: close every changing region before any
     * member publishes its new domain, eliminating mixed old/new neighbors. */
    private static void prepareDomainRebaseCut(
            Region r, EpochDomain d, ArrayList<Region> rebaseBatch) {
        if (r.domain == d) return;
        prepareTopologyCandidateCut(r, rebaseBatch);
    }

    private static void prepareTopologyCandidateCut(
            Region r, ArrayList<Region> rebaseBatch) {
        EpochDomain migrationDomain = r.domain;
        synchronized (r.admissionLock) {
            if (!r.tryCloseClaimTransactionsForDomainRebase()) {
                throw new IllegalStateException(
                        "concurrent region domain rebase request: "
                                + Long.toHexString(r.gridKey));
            }
        }
        try {
            rebaseBatch.add(r);
        } catch (Throwable publicationFailure) {
            Throwable failure = cleanupFailedDomainRebaseCut(
                    r, RegionScheduler.publishFatalAndSelectWinner(
                            publicationFailure));
            RegionizedServer.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
        }
        // Domain identity is immutable while the cut is owned; this early read
        // exists only to make accidental mutation in the preparation phase loud.
        if (r.domain != migrationDomain) {
            throw new IllegalStateException(
                    "region source domain changed while publishing its rebase cut");
        }
    }

    private static void awaitPreparedDomainRebaseBatch(
            ArrayList<Region> rebaseBatch) {
        for (int i = 0, n = rebaseBatch.size(); i < n; i++) {
            awaitClaimTransactionsForDomainRebase(rebaseBatch.get(i));
        }
    }

    /** Phase 2: the complete batch is closed and tail-quiescent. */
    private static void commitPreparedDomainRebase(Region r, EpochDomain d) {
        if (r.domain == d) {
            throw new IllegalStateException(
                    "prepared domain rebase target became current before commit");
        }
        if (!r.claimTransactionsDrainedForDomainRebase()) {
            throw new IllegalStateException(
                    "prepared domain rebase committed before claim-tail terminal");
        }
        EpochDomain migrationDomain = r.domain;
        long migrationEpoch = migrationDomain != null
                ? migrationDomain.epoch : Long.MIN_VALUE;

        Throwable failure = null;
        try {
            if (r.domain != migrationDomain
                    || (migrationDomain != null
                        && migrationDomain.epoch != migrationEpoch)) {
                throw new IllegalStateException(
                        "region source domain changed behind its rebase cut: "
                                + Long.toHexString(r.gridKey));
            }
            // A real tick-head safe point cannot abandon an accepted old-domain
            // entity obligation.  Existing island units are fully quiesced and
            // the previous global barrier is complete before rebuild entry.
            if (migrationDomain != null
                    && migrationDomain.entityBarrierArmedEpoch == migrationEpoch
                    && r.groupedEpoch == migrationEpoch
                    && r.entityEpoch < migrationEpoch) {
                throw new IllegalStateException(
                        "domain rebase fenced an unconsumed entity obligation: "
                                + Long.toHexString(r.gridKey));
            }

            // DOMAIN-MIGRATION DRAIN (2026-07-13 watchdog wedge): a newborn
            // global region may carry a cf-AWAITED arm when it becomes an island
            // core/halo.  CLOSED_FOR_REBASE sends every later offer to the
            // post-cut lane, so this queue is now finite.  Respect a sealed old
            // epoch: such retained work is deliberately next-epoch/futureless
            // and migrates without early execution.
            boolean migrationClaimed = false;
            boolean migrationStamped = false;
            if (d.owner != null && !r.queue().isEmpty()) {
                synchronized (r.admissionLock) {
                    migrationClaimed = r.tryClaimForDomainRebase(
                            Thread.currentThread(), migrationDomain, migrationEpoch);
                }
            }
            if (migrationClaimed) {
                try {
                    r.stampAcquire("migrate-cut");
                    migrationStamped = true;
                    r.queue().drain(Integer.MAX_VALUE);
                } catch (Throwable drainFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(drainFailure);
                } finally {
                    if (migrationStamped) {
                        try {
                            r.stampRelease("migrate-cut");
                        } catch (Throwable cleanupFailure) {
                            failure = RegionScheduler.publishFatalAndSelectWinner(
                                    mergeFailure(failure, cleanupFailure));
                        }
                    }
                    try {
                        r.release(Thread.currentThread());
                    } catch (Throwable cleanupFailure) {
                        failure = RegionScheduler.publishFatalAndSelectWinner(
                                mergeFailure(failure, cleanupFailure));
                    }
                }
            }
            if (failure != null) {
                RegionizedServer.<RuntimeException>rethrow(failure);
            }

            synchronized (r.admissionLock) {
                if (!r.claimTransactionsDrainedForDomainRebase()
                        || !r.tryBeginDomainRebaseSeal()) {
                    throw new IllegalStateException(
                            "domain rebase lost its closed/drained exclusive point: "
                                    + Long.toHexString(r.gridKey));
                }
                boolean sealHeld = true;
                try {
                    rebaseScheduledTickClocksUnderSeal(r, d);
                    RedstoneTorchHistory.rebaseAndCommit(r, d);
                } catch (Throwable commitFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(commitFailure);
                } finally {
                    if (sealHeld) {
                        try {
                            r.releaseDomainRebaseSeal();
                            sealHeld = false;
                        } catch (Throwable releaseFailure) {
                            failure = RegionScheduler.publishFatalAndSelectWinner(
                                    mergeFailure(failure, releaseFailure));
                        }
                    }
                }
            }
            if (failure != null) {
                RegionizedServer.<RuntimeException>rethrow(failure);
            }
            return;
        } catch (Throwable primary) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    mergeFailure(failure, primary));
        }
        RegionizedServer.<RuntimeException>rethrow(
                RegionScheduler.publishFatalAndSelectWinner(failure));
    }

    /** Wait for body + release-successor/barrier/ReadyEntry receipt tails. */
    static void awaitClaimTransactionsForDomainRebase(Region r) {
        long warnAt = System.nanoTime() + 5_000_000_000L;
        int warnings = 0;
        while (!r.claimTransactionsDrainedForDomainRebase()) {
            RegionScheduler.throwWorkerFailureIfAny();
            IslandCommands.drainMainChunkServices();
            long now = System.nanoTime();
            if (now >= warnAt) {
                warnAt = now + 5_000_000_000L;
                Thread holder = r.currentWorker();
                LOGGER.error("[async-islands] domain rebase waiting {}x5s on region {} "
                                + "transactions={} holder={} alive={} claimPath={} queue={} help={}",
                        ++warnings, Long.toHexString(r.gridKey),
                        r.activeClaimTransactions(),
                        holder == null ? "<post-release-tail>" : holder.getName(),
                        holder != null && holder.isAlive(), r.claimPath,
                        r.queue().size(), r.helpQueue.size());
            }
            LockSupport.parkNanos(20_000L);
        }
        RegionScheduler.throwWorkerFailureIfAny();
    }

    private static Throwable cleanupFailedDomainRebaseCut(
            Region r, Throwable primary) {
        Throwable failure = primary;
        synchronized (r.admissionLock) {
            if (r.claimRebaseClosed()
                    && r.claimTransactionsDrainedForDomainRebase()) {
                try {
                    r.flushDomainRebaseDeferredAdmissions();
                } catch (Throwable cleanupFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            mergeFailure(failure, cleanupFailure));
                }
                try {
                    r.reopenClaimTransactionsAfterDomainRebase();
                } catch (Throwable cleanupFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            mergeFailure(failure, cleanupFailure));
                }
            }
        }
        return failure;
    }

    /** Reopen a completely committed topology batch, then publish fresh ready
     * nodes only after every member is claimable in the same new snapshot. */
    private static Throwable finishDomainRebaseBatch(
            ArrayList<Region> batch, Throwable primary) {
        Throwable failure = primary;
        boolean[] ready = new boolean[batch.size()];
        for (int i = 0, n = batch.size(); i < n; i++) {
            Region r = batch.get(i);
            synchronized (r.admissionLock) {
                try {
                    ready[i] = r.flushDomainRebaseDeferredAdmissions();
                    r.reopenClaimTransactionsAfterDomainRebase();
                } catch (Throwable cleanupFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            mergeFailure(failure, cleanupFailure));
                }
            }
        }
        if (failure == null) {
            for (int i = 0, n = batch.size(); i < n; i++) {
                if (!ready[i]) continue;
                Region r = batch.get(i);
                try {
                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        RegionScheduler.enqueueReady(r);
                        RegionScheduler.wakeWorkers(1);
                    });
                } catch (Throwable publicationFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            mergeFailure(failure, publicationFailure));
                    break;
                }
            }
        }
        batch.clear();
        return failure;
    }

    private static void rebaseScheduledTickClocksUnderSeal(Region r, EpochDomain d) {
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
    }

    /**
     * Publish one coherent domain baseline at a topology/unit safe point.
     * Task offers share {@link Region#admissionLock}; the CAS marker excludes
     * delayed claimants which made their eligibility decision in the old
     * domain.  A live holder disproves the safe-point contract and is fatal.
     */
    static void rebaseDomainEpochAtSafePoint(Region r, EpochDomain d) {
        Throwable failure = null;
        synchronized (r.admissionLock) {
            if (r.claimRebaseClosed() || r.activeClaimTransactions() != 0
                    || !r.tryBeginDomainRebaseSeal()) {
                throw new IllegalStateException(
                        "region domain rebase encountered a live claim/tail/seal: "
                                + Long.toHexString(r.gridKey)
                                + " transactions=" + r.activeClaimTransactions());
            }
            try {
                r.commitDomainRebaseSeal(d, d.epoch - 1);
            } catch (Throwable commitFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(commitFailure);
            }
            try {
                r.releaseDomainRebaseSeal();
            } catch (Throwable releaseFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        mergeFailure(failure, releaseFailure));
            }
        }
        if (failure != null) RegionizedServer.<RuntimeException>rethrow(failure);
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
        while (RegionScheduler.callSuccessBooleanIfHealthy(() -> {
            // Poll and enter the exact cross-region replay in one admission.
            // A separate poll followed by runSuccessBodyIfHealthy could detach
            // the accepted obligation and then have the nested permit refused
            // by a peer W, leaving no owner able to terminalize that item.
            Runnable op = d.deferredOps.poll();
            if (op == null) return false;
            op.run();
            return true;
        })) {
            // The permit terminal observes a W selected while this op ran, so
            // the loop cannot detach or enter the next FIFO member afterwards.
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
        // A level-tick HEAD is a quiescent boundary only in wall/sync mode. An async
        // island unit may concurrently move an entity into an otherwise idle destination
        // Region without publishing a task there; task admissionLock cannot serialize that
        // direct index mutation. Reclamation therefore remains fail-closed until async mode
        // provides an explicit all-island quiescence handshake for eviction.
        if (asyncActive || domainsIslandized) {
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

    /** Pure v2 ownership predicate: never creates/notices a region as a side effect. */
    boolean regionMatches(Region region, ChunkPos pos) {
        return region != null && region.gridKey == regionizer.grid.keyFor(pos);
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

    /**
     * Reconcile the auxiliary region-owned index after the canonical
     * {@link net.minecraft.world.level.entity.EntityTickList} pass.
     *
     * <p>The ordinary region path performs this hand-off lazily in {@code tickOne}
     * before each entity body. Exact-ordinal player mode deliberately bypasses that
     * path and walks Mojang's list instead, so moved entities would otherwise remain
     * assigned to yesterday's cell indefinitely. The exact-turn pool fence is held
     * while this method runs; the second list walk is read-only with respect to list
     * membership and includes entities added through Mojang's active/passive COW.
     */
    void reconcileCanonicalEntityIndex() {
        // Rebuild, do not merely move live members: this also purges a stale/duplicate
        // member left by an earlier exceptional hand-off. No worker can observe the
        // clear/repopulate interval because the exact-turn fence is still held.
        for (Region region : regionizer.snapshot()) {
            region.entities.clear();
            region.entityCount.set(0L);
        }
        level.entityTickList.forEach(entity -> {
            if (!entity.isRemoved() && entity.level() == level) indexAdd(entity);
        });
    }

    private static void dropFromRegion(Region r, Entity e) {
        // isRemoved also exposes a claim-local departure view. A still-live
        // reserved source must remain tick-indexed so a refused transition can
        // resume it. Real removal callbacks/reasons retain their normal cleanup.
        if (e.getRemovalReason() == null
                && com.axalotl.fabrolia.common.regions.NetherPortalTransfers.isSourceMutationBlocked(e)) return;
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
     * [spawn][random][entities] FIFO) → tick entities serially → release.
     */
    /** (region, load) pair with the entity count FROZEN at dispatch time. Sorting by the
     *  live AtomicLong violated the comparator contract — workers migrate entities between
     *  regions mid-sort, TimSort threw "Comparison method violates its general contract"
     *  and took the whole tick down (live crash 2026-06-10 05:17). */
    private record RegionLoad(Region region, long count) {}

    /** Purely prepared entity-dispatch state; no executable root is visible yet. */
    private record EntityDispatchPlan(
            long epoch,
            boolean eventSched,
            ArrayList<RegionLoad> active,
            CompletableFuture<Void> eventBarrier,
            CompletableFuture<Void> result,
            ArrayList<Runnable> roots,
            EpochDomain.EntityDispatchPublication publication) {}

    /** Ownership ledger for the runtime-wide half of one strict sprint fence.
     * The snapshot and this object are prepared before the first flag write. */
    private static final class GlobalSprintRuntimeFence {
        private final RegionizedServer[] runtimes;
        private int installed;

        private GlobalSprintRuntimeFence() {
            ArrayList<RegionizedServer> snapshot =
                    new ArrayList<>(RegionScheduler.allLevels());
            runtimes = snapshot.toArray(RegionizedServer[]::new);
        }

        void install() {
            if (installed != 0) {
                throw new IllegalStateException(
                        "global sprint runtime fence was installed twice");
            }
            for (int i = 0; i < runtimes.length; i++) {
                RegionizedServer runtime = runtimes[i];
                if (runtime.globalSprintAdmissionFenced
                        || runtime.globalSprintAdmissionFenceOwner != null) {
                    throw new IllegalStateException(
                            "global sprint runtime fence overlapped for "
                                    + runtime.level.dimension().identifier());
                }
                // Cleanup ownership precedes the first state write for this
                // runtime, including an asynchronous Throwable at either write.
                installed = i + 1;
                runtime.globalSprintAdmissionFenceOwner = this;
                runtime.globalSprintAdmissionFenced = true;
            }
        }

        void clearSuccess() {
            for (int i = installed - 1; i >= 0; i--) {
                RegionizedServer runtime = runtimes[i];
                if (!runtime.globalSprintAdmissionFenced
                        || runtime.globalSprintAdmissionFenceOwner != this) {
                    throw new IllegalStateException(
                            "global sprint runtime fence lost ownership for "
                                    + runtime.level.dimension().identifier());
                }
                runtime.globalSprintAdmissionFenced = false;
                runtime.globalSprintAdmissionFenceOwner = null;
            }
            installed = 0;
        }

        Throwable clearAfterFailure(Throwable primary) {
            Throwable failure = primary;
            for (int i = installed - 1; i >= 0; i--) {
                try {
                    RegionizedServer runtime = runtimes[i];
                    if (runtime.globalSprintAdmissionFenceOwner == this) {
                        runtime.globalSprintAdmissionFenced = false;
                        runtime.globalSprintAdmissionFenceOwner = null;
                    } else if (runtime.globalSprintAdmissionFenceOwner != null
                            || runtime.globalSprintAdmissionFenced) {
                        failure = RegionScheduler.publishFatalAndSelectWinner(
                                mergeFailure(failure, new IllegalStateException(
                                        "global sprint failure cleanup refused a foreign runtime fence for "
                                                + runtime.level.dimension().identifier())));
                    }
                } catch (Throwable cleanupFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            mergeFailure(failure, cleanupFailure));
                }
            }
            installed = 0;
            return failure;
        }
    }

    public CompletableFuture<Void> dispatchOwnedEntityTicks() {
        return dispatchOwnedEntityTicks(true);
    }

    /**
     * Drain the region-owned chunk/spawn/random work which vanilla orders before the
     * entity list, but deliberately leave every entity body to the canonical
     * EntityTickList pass.  Exact player mode uses this as its quiescence boundary.
     */
    public CompletableFuture<Void> dispatchPreEntityWork() {
        if (asyncActive) {
            throw new IllegalStateException(
                    "exact player ordinal mode cannot drain autonomous async islands");
        }
        return dispatchOwnedEntityTicks(false);
    }

    /**
     * One exact global {@code /tick sprint} frame for every current island.
     * Ordinary pacer/chained publication is fenced first; older publications
     * are drained at a claim boundary; only then is each exact fromL captured.
     * A strict request can terminate normally only in the sole L++ commit.
     */
    private void runGlobalSprintIslandFrame(
            ArrayList<IslandRegistry.PIsland> due) {
        IslandTickUnit.GlobalSprintRequest[] requests = null;
        int[] installedFences = null;
        AtomicReference<GlobalSprintRuntimeFence> runtimeFenceOwner = null;
        boolean quiesced = false;
        Throwable failure = null;
        try {
            IslandTickUnit.GlobalSprintRequest[] frameRequests =
                    new IslandTickUnit.GlobalSprintRequest[due.size()];
            requests = frameRequests;
            int[] frameFenceCount = new int[1];
            installedFences = frameFenceCount;
            AtomicReference<GlobalSprintRuntimeFence> frameRuntimeFence =
                    new AtomicReference<>();
            runtimeFenceOwner = frameRuntimeFence;
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                // Snapshot/allocation and installation are one admitted body.
                // Publish cleanup ownership before the first runtime flag write.
                GlobalSprintRuntimeFence prepared =
                        new GlobalSprintRuntimeFence();
                frameRuntimeFence.set(prepared);
                prepared.install();
            });
            for (int i = 0, n = due.size(); i < n; i++) {
                IslandRegistry.PIsland island = due.get(i);
                int installedCount = i + 1;
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    IslandTickUnit.installGlobalSprintFence(this, island);
                    // Ownership must become cleanup-visible inside the admitted
                    // body. A peer W may win after this body returns but before
                    // its permit terminalizes; a caller-side increment would then
                    // miss this already-installed fence during failure cleanup.
                    frameFenceCount[0] = installedCount;
                });
            }

            // `/tick sprint` is server-wide but MAIN_SYNC is one global queue.
            // Quiescing only this level leaves a pre-existing ordinary unit in
            // another dimension free to publish a late Main body after our empty
            // observation. Process levels one at a time (never retain rs1 claims
            // while rs2 may be routed/sharded into it), and while each claim set
            // is held also drain every already-accepted pre-claim unit root. The
            // runtime flags above prevent a processed level from starting a new
            // ordinary unit after its claims are released.
            drainPreexistingServerSprintUnits(frameRuntimeFence.get());

            quiesced = quiesceAllIslands();
            if (!quiesced || !currentThreadHoldsAllClaims()) {
                throw new IllegalStateException(
                        "global sprint entered without its all-island claim boundary");
            }

            // A completed ordinary unit can have published a MAIN_SYNC entity
            // child which is not part of its region fan-in. The unit's L and claim
            // may therefore be terminal while that accepted vanilla body is still
            // queued on physical Main. Capture fromL only after those older bodies
            // have run at this held-all-claims boundary. A drained body may publish
            // a routed command; that batch owns its own global fence, so temporarily
            // release this level's claims, drain it, reacquire, and repeat to a true
            // fixed point. The per-island sprint fences remain installed throughout,
            // so no ordinary unit can enter in either release window.
            while (true) {
                RegionScheduler.runSuccessBodyIfHealthy(
                        this::drainPreSprintAcceptedMainBodies);
                if (!IslandCommands.hasRoutedPending()) break;

                Throwable routedReleaseFailure = releaseAllIslands(null);
                quiesced = false;
                if (routedReleaseFailure != null) {
                    RegionizedServer.<RuntimeException>rethrow(routedReleaseFailure);
                }
                IslandCommands.drainRoutedBatch();
                quiesced = quiesceAllIslands();
                if (!quiesced || !currentThreadHoldsAllClaims()) {
                    throw new IllegalStateException(
                            "global sprint lost its all-island claim boundary after routed drain");
                }
            }
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                awaitFencedOrdinaryUnitPublications(due);
                for (int i = 0, n = due.size(); i < n; i++) {
                    frameRequests[i] = IslandTickUnit.prepareGlobalSprintRequest(
                            this, due.get(i));
                }
            });

            Throwable releaseFailure = releaseAllIslands(null);
            quiesced = false;
            if (releaseFailure != null) {
                RegionizedServer.<RuntimeException>rethrow(releaseFailure);
            }

            for (IslandTickUnit.GlobalSprintRequest request : frameRequests) {
                request.start();
            }
            for (IslandTickUnit.GlobalSprintRequest request : frameRequests) {
                // Main owns no claims here. Routed commands are part of the
                // awaited unit and must be serviced in this same sprint frame.
                com.axalotl.fabrolia.common.ParallelProcessor.pumpUntilDone(
                        request.receipt, true);
            }

            // ADVANCED is committed with L++, after the coordinator has released
            // unitClaim, but a region ordinal from that same strict unit may have
            // handed its vanilla body to MAIN_SYNC. A receipt which proves L alone
            // therefore is not yet the complete sprint frame. Reacquire every claim
            // and drain those accepted children (plus any routed descendants) before
            // validating or opening any per-island sprint fence.
            quiesced = quiesceAllIslands();
            if (!quiesced || !currentThreadHoldsAllClaims()) {
                throw new IllegalStateException(
                        "global sprint could not fence its terminal Main children");
            }
            while (true) {
                RegionScheduler.runSuccessBodyIfHealthy(
                        this::drainPreSprintAcceptedMainBodies);
                if (!IslandCommands.hasRoutedPending()) break;

                Throwable routedReleaseFailure = releaseAllIslands(null);
                quiesced = false;
                if (routedReleaseFailure != null) {
                    RegionizedServer.<RuntimeException>rethrow(routedReleaseFailure);
                }
                IslandCommands.drainRoutedBatch();
                quiesced = quiesceAllIslands();
                if (!quiesced || !currentThreadHoldsAllClaims()) {
                    throw new IllegalStateException(
                            "global sprint lost its terminal-child claim boundary after routed drain");
                }
            }
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                for (IslandTickUnit.GlobalSprintRequest request : frameRequests) {
                    IslandTickUnit.closeGlobalSprintRequest(request);
                }
            });

            Throwable terminalReleaseFailure = releaseAllIslands(null);
            quiesced = false;
            if (terminalReleaseFailure != null) {
                RegionizedServer.<RuntimeException>rethrow(terminalReleaseFailure);
            }
            GlobalSprintRuntimeFence completedRuntimeFence =
                    frameRuntimeFence.get();
            RegionScheduler.runSuccessBodyIfHealthy(
                    completedRuntimeFence::clearSuccess);
            frameRuntimeFence.set(null);
            return;
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }

        // W is already selected. Never wait for a RUNNING strict publication
        // while Main retains a claim it could need to reach its own failure
        // check/finally. Runtime/per-island admission fences remain installed,
        // and the fatal pool gate forbids every new simulation body.
        if (quiesced) {
            failure = releaseAllIslands(failure);
            quiesced = false;
        }
        if (requests != null) {
            for (int i = requests.length - 1; i >= 0; i--) {
                IslandTickUnit.GlobalSprintRequest request = requests[i];
                if (request != null) {
                    failure = IslandTickUnit.cancelGlobalSprintRequest(request, failure);
                }
            }
        }
        if (installedFences != null) {
            for (int i = installedFences[0] - 1; i >= 0; i--) {
                failure = IslandTickUnit.clearGlobalSprintFence(due.get(i), failure);
            }
        }
        if (runtimeFenceOwner != null) {
            GlobalSprintRuntimeFence ownedRuntimeFence = runtimeFenceOwner.get();
            if (ownedRuntimeFence != null) {
                failure = ownedRuntimeFence.clearAfterFailure(failure);
            }
        }
        RegionizedServer.<RuntimeException>rethrow(
                RegionScheduler.publishFatalAndSelectWinner(failure));
    }

    /** Retire every pre-fence ordinary unit/root in every level without ever
     * holding two levels' claim sets at once (cross-level routed/shard safety). */
    private void drainPreexistingServerSprintUnits(
            GlobalSprintRuntimeFence runtimeFence) {
        if (runtimeFence == null) {
            throw new IllegalStateException(
                    "global sprint lost its runtime-fence ownership");
        }
        for (RegionizedServer runtime : runtimeFence.runtimes) {
            boolean held = false;
            Throwable failure = null;
            try {
                held = runtime.quiesceAllIslands();
                if (!held || !runtime.currentThreadHoldsAllClaims()) {
                    throw new IllegalStateException(
                            "global sprint could not quiesce server level "
                                    + runtime.level.dimension().identifier());
                }
                ArrayList<IslandRegistry.PIsland> current =
                        new ArrayList<>(runtime.islandRegistry.islands());
                runtime.awaitFencedOrdinaryUnitPublications(current);
            } catch (Throwable boundaryFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        boundaryFailure);
            } finally {
                if (held) failure = runtime.releaseAllIslands(failure);
            }
            if (failure != null) {
                RegionizedServer.<RuntimeException>rethrow(
                        RegionScheduler.publishFatalAndSelectWinner(failure));
            }
        }
    }

    /** Physical Main owns every island claim in this level. Drain every older
     * accepted MAIN_SYNC body before the strict sprint request captures fromL. */
    private void drainPreSprintAcceptedMainBodies() {
        while (RegionScheduler.drainMainSyncTicks()) {
            RegionScheduler.throwWorkerFailureIfAny();
        }
        RegionScheduler.throwWorkerFailureIfAny();
    }

    /** Main owns every unitClaim while this waits. An older publication can
     *  only retire its pre-claim reservation; it cannot begin a tick body. */
    private void awaitFencedOrdinaryUnitPublications(
            ArrayList<IslandRegistry.PIsland> due) {
        for (int i = 0, n = due.size(); i < n; i++) {
            IslandRegistry.PIsland island = due.get(i);
            // The runtime flag is volatile, but its writer does not own this
            // per-island monitor. A publisher may have read false while holding
            // unitAdmissionLock and then paused immediately before submitQueued
            // CAS. Acquiring the same monitor after the flag write is the missing
            // admission handshake: that old decision must finish its CAS before
            // this point; every later decision must observe the installed fence.
            synchronized (island.unitAdmissionLock) {
                if (!currentThreadHoldsAllClaims() || !island.unitClaim.get()
                        || (!island.globalSprintFenced
                        && !globalSprintAdmissionFenced)
                        || island.globalSprintRequest != null) {
                    throw new IllegalStateException(
                            "global sprint ordinary-publication boundary lost its claim/fence");
                }
            }
            long waitStart = System.nanoTime();
            int stuckReports = 0;
            while (island.submitQueued.get()) {
                RegionScheduler.throwWorkerFailureIfAny();
                if (!RegionScheduler.poolRunning()) {
                    throw new IllegalStateException(
                            "global sprint lost the unit pool while draining its fence");
                }
                IslandCommands.drainMainChunkServices();
                if (System.nanoTime() - waitStart > 5_000_000_000L) {
                    waitStart = System.nanoTime();
                    if (++stuckReports >= 3) {
                        throw new IllegalStateException(
                                "global sprint ordinary publication did not drain: "
                                        + IslandCommands.claimForensics(island));
                    }
                }
                java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
            }
            // A zero-iteration observation is still a terminal boundary, not a
            // license to release this claim set after a concurrently selected W.
            RegionScheduler.throwWorkerFailureIfAny();
        }
    }

    private CompletableFuture<Void> dispatchOwnedEntityTicks(boolean tickEntities) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (playerTickV2Batch != null) {
                throw new IllegalStateException("regional player level batch was not sealed");
            }
            playerTickV2Batch = RegionalPlayerEntityTicksV2.beginLevel(this);
        });
        // Cross-dimension arrival cohorts for this level's global lane:
        // published BEFORE any dispatch — the vanilla-equivalent boundary.
        CrossDimArrivals.publishGlobal(this);
        // Main TPS/MSPT remains useful in wall-tick mode (including the exact
        // player actor, which intentionally requires autonomous islands OFF).
        // The HUD itself renders Island: SYNC when this level is not async.
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> TpsHud.tick(level.getServer(), level));
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
            if (net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension())) {
                RegionScheduler.runSuccessBodyIfHealthy(TemporalControl::tickAcks);
                // Task #43: coupling maintenance — shared-rate refresh,
                // deferred-anchor retries, operator-session eligibility, and
                // the phase heal. Geometry is rebuild-only.
                RegionScheduler.runSuccessBodyIfHealthy(CrossDimLinks::mainTick);
                RegionScheduler.runSuccessBodyIfHealthy(ChunkDelivery::stressTick);
                if (level.getGameTime() % 20 == 0) {
                    RegionScheduler.runSuccessBodyIfHealthy(ChunkDelivery::liveness);
                }
            }
            // BE index visibility for this tick's units (vanilla flush + fold)
            // — main is quiescent, the one legal mutation window outside the
            // BE slot.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> IslandBlockEntityTicks.preUnitMaintenance(level, this));

            // PACER WATCHDOG: Main preserves the existing topology/control
            // ordering, but ordinary tick production is autonomous. This pass
            // only seeds a missing/stale ticket; it never directly submits an
            // ordinary unit and never awaits one. Main still awaits only the
            // global remainder lane (plus global-scoped chunk-phase futures).
            AtomicReference<ArrayList<IslandRegistry.PIsland>> dueRef =
                    new AtomicReference<>();
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                ArrayList<IslandRegistry.PIsland> prepared =
                        new ArrayList<>(islandRegistry.islands());
                // Freeze the key before sorting: costNanos is written by
                // completing units CONCURRENTLY with this sort — TimSort throws
                // "Comparison method violates its general contract" when a key
                // moves mid-sort (production crash 2026-07-17 05:39).
                for (int di = 0, dn = prepared.size(); di < dn; di++) {
                    IslandRegistry.PIsland p = prepared.get(di);
                    p.driveSortCost = p.costNanos;
                }
                prepared.sort((a, b) ->
                        Long.compare(b.driveSortCost, a.driveSortCost));
                dueRef.set(prepared);
            });
            ArrayList<IslandRegistry.PIsland> due = dueRef.get();
            // GLOBAL SPRINT LOCKSTEP (/tick sprint): exact synchronized barrier
            // ticks — every island runs exactly ONE complete unit per main
            // sprint tick, awaited HERE at the tick barrier (after complete
            // units, never between phases). Local sprint chaining pauses
            // (TemporalControl.onUnitComplete) so counts stay exact. Topology
            // committed above at the tick head = the sprint-tick boundary.
            if (level.getServer().tickRateManager().isSprinting()) {
                runGlobalSprintIslandFrame(due);
                AtomicReference<ArrayList<Region>> sprintGlobalsRef =
                        new AtomicReference<>();
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    List<Region> sprintRegions =
                            regionizer.snapshotAtEntityDispatchCut(globalDomain);
                    ArrayList<Region> sprintGlobals = new ArrayList<>(8);
                    for (int i = 0, n = sprintRegions.size(); i < n; i++) {
                        Region r = sprintRegions.get(i);
                        if (r.domain == globalDomain) sprintGlobals.add(r);
                    }
                    sprintGlobalsRef.set(sprintGlobals);
                });
                return dispatchEntityPhase(
                        globalDomain, sprintGlobalsRef.get(), tickEntities);
            }
            for (int i = 0, n = due.size(); i < n; i++) {
                IslandRegistry.PIsland p = due.get(i);
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    // /tick freeze: units are gated at submitUnit; skip the pacing
                    // branches entirely. Command-time cross-dim arrivals still
                    // deliver NOW — vanilla applies command mutations synchronously
                    // while frozen, and with units gated the claim is free, so main
                    // publishes against the quiescent island (regen-before-add
                    // FIFO preserved by publishIsland itself).
                    if (!level.getServer().tickRateManager().runsNormally()) {
                        if (!p.pendingArrivals.isEmpty()) {
                            boolean arrivalClaimed = false;
                            Throwable arrivalFailure = null;
                            try {
                                if (p.tryClaimAutonomousWork()) {
                                    // Arm cleanup ownership before the ledger or
                                    // arrival body can fail.
                                    arrivalClaimed = true;
                                    p.noteClaim("frozen-arrival");
                                    CrossDimArrivals.publishIsland(p);
                                }
                            } catch (Throwable primary) {
                                arrivalFailure = RegionScheduler
                                        .publishFatalAndSelectWinner(primary);
                            }
                            if (arrivalClaimed) {
                                try {
                                    p.clearClaim();
                                } catch (Throwable cleanupFailure) {
                                    arrivalFailure = RegionScheduler
                                            .publishFatalAndSelectWinner(
                                                    mergeFailure(arrivalFailure,
                                                            cleanupFailure));
                                }
                                try {
                                    p.unitClaim.set(false);
                                } catch (Throwable cleanupFailure) {
                                    arrivalFailure = RegionScheduler
                                            .publishFatalAndSelectWinner(
                                                    mergeFailure(arrivalFailure,
                                                            cleanupFailure));
                                }
                            }
                            if (arrivalFailure != null) {
                                RegionizedServer.<RuntimeException>rethrow(arrivalFailure);
                            }
                        }
                        return;
                    }
                    // Local sprint sessions own tick production for their islands
                    // (self-chaining at unit completion) — the drive kick-starts a
                    // stalled session but never double-drives an active one.
                    if (TemporalControl.isSprinting(this, p)) {
                        if (!p.unitClaim.get()) {
                            IslandTickUnit.submitUnit(this, p);
                        }
                        return;
                    }
                    // Coherence pin: exactly one unit per WALL tick, released at
                    // this fixed drive point, never ahead of the wall clock —
                    // L tracks W 1:1 (phase + count parity with the global lane;
                    // catch-up when behind chains at unit completion instead).
                    if (TemporalControl.isCoherent(this, p)) {
                        if (p.logicalTick < level.getGameTime() && !p.unitClaim.get()) {
                            IslandTickUnit.submitUnit(this, p);
                        }
                        return;
                    }
                    TemporalControl.ensurePaced(this, p);
                });
            }
            // Global remainder lane: gap regions holding executeAt work and
            // regions born on the global domain since the rebuild above.
            List<Region> regions = regionizer.snapshotAtEntityDispatchCut(globalDomain);
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
                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        // Revalidate after winning admission: domain retirement and
                        // this success cut must not leave a stale check-to-migration edge.
                        if (r.state() != RegionState.ACTIVE) return;
                        if (r.domain == globalDomain) {
                            globals.add(r);
                            return;
                        }
                        if (r.domain != null && r.domain.owner != null
                                && !r.domain.owner.retired) return;
                        FlightRecorder.record("GAP-DOMAIN-ADOPT "
                                + level.dimension().identifier()
                                + " cell=" + Long.toHexString(r.gridKey)
                                + " oldDomain=" + (r.domain == null ? "null"
                                : (r.domain.owner == null ? "<ownerless>"
                                : "island#" + r.domain.owner.id + "/g"
                                + r.domain.owner.gen
                                + (r.domain.owner.retired ? "(retired)" : "(LIVE?)")))
                                + " entities=" + r.entityCount.get());
                        assignDomain(r, globalDomain);
                        globals.add(r);
                    });
                }
            }
            return dispatchEntityPhase(globalDomain, globals, tickEntities);
        }
        return dispatchEntityPhase(
                globalDomain,
                regionizer.snapshotAtEntityDispatchCut(globalDomain),
                tickEntities);
    }

    /**
     * A2 core: dispatch one entity phase for {@code regions} against timeline
     * {@code d} — arm d's barrier, stamp who owes, sweep no-work regions done,
     * seed the ready lane. Caller contract: {@code d.epoch} was bumped, then
     * {@code regions} and {@link EpochDomain#entityDispatchCutEpoch} were
     * published under the region-creation WRITE cutoff; every member has
     * {@code r.domain == d} (single-domain dispatch; the ColorGate ignores
     * cross-domain neighbors).
     * The legacy (non-event) group path remains global-domain-only — async mode
     * requires the event scheduler (enforced by the tick-head mode latch).
     */
    CompletableFuture<Void> dispatchEntityPhase(EpochDomain d, List<Region> regions) {
        return dispatchEntityPhase(d, regions, true);
    }

    private CompletableFuture<Void> dispatchEntityPhase(EpochDomain d, List<Region> regions,
                                                         boolean tickEntities) {
        AtomicReference<EntityDispatchPlan> planRef = new AtomicReference<>();
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (d.entityDispatchCutEpoch != d.epoch) {
                throw new IllegalStateException(
                        "entity dispatch started without an exact region-creation cut");
            }
            planRef.set(prepareEntityDispatchPlan(d, regions, tickEntities));
        });
        EntityDispatchPlan plan = planRef.get();
        final long epoch = plan.epoch;
        final boolean eventSched = plan.eventSched;
        ArrayList<RegionLoad> active = plan.active;
        CompletableFuture<Void> eventBarrier = plan.eventBarrier;
        CompletableFuture<Void> result = plan.result;
        ArrayList<Runnable> roots = plan.roots;
        EpochDomain.EntityDispatchPublication publication = plan.publication;

        try {
            // Publication ownership is itself a successor state mutation and must
            // not begin after a peer selected W during local plan preparation.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> d.entityDispatchPublication = publication);
            // Offer every obligation while consumers are fenced on OFFERING.
            if (eventSched) {
                // Seed every participant. Ineligible colors simply drop this
                // first entry after acceptance and are re-armed by their last
                // lower-color blocker; over-seeding removes fallible sweep pushes.
                for (int i = 0, n = active.size(); i < n; i++) {
                    tryEnqueueEntity(active.get(i).region);
                }
            } else if (roots != null) {
                for (int i = 0, n = roots.size(); i < n; i++) {
                    RegionScheduler.enqueueGlobal(roots.get(i));
                }
            }

            if (!active.isEmpty()) {
                // Wake first: awakened consumers stop on OFFERING until the
                // final receipt CAS below.
                RegionScheduler.wakeWorkers(Integer.MAX_VALUE);
            }
            // Prepare the complete epoch decision while every offered consumer
            // is fenced on OFFERING. This variable/fallible work runs outside
            // lifecycleLock; only the final receipt CAS is the success commit.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                if (!tickEntities) queueOnlyEntityEpoch = epoch;
                if (eventSched) {
                    d.entityPending.set(active.size());
                    d.entityBarrier = eventBarrier;
                    d.entityBarrierArmedEpoch = epoch;
                    d.entityBarrierArmedNanos = System.nanoTime();
                }
                for (int i = 0, n = active.size(); i < n; i++) {
                    active.get(i).region.groupedEpoch = epoch;
                }
                d.dispatchedEpoch = epoch;
                if (com.axalotl.fabrolia.common.config.FabroliaConfig.useRegionColorGate()) {
                    for (int i = 0, n = regions.size(); i < n; i++) {
                        Region region = regions.get(i);
                        if (region.state() != RegionState.ACTIVE) continue;
                        ColorGate.tryMarkDoneWithoutPush(region, this);
                    }
                }
            });
            RegionScheduler.commitSuccessIfHealthy(publication::accept);
            if (eventBarrier != null && active.isEmpty()) {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    if (!eventBarrier.complete(null)) {
                        throw new IllegalStateException(
                                "empty entity dispatch barrier completed more than once");
                    }
                });
            }
            return result;
        } catch (Throwable dispatchFailure) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(dispatchFailure);
            try {
                publication.cancel();
            } catch (Throwable cleanupFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        mergeFailure(failure, cleanupFailure));
            }
            if (roots != null) {
                for (int i = 0, n = roots.size(); i < n; i++) {
                    Runnable root = roots.get(i);
                    if (root instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                        try {
                            cancellable.cancelQueuedBeforeStartWithFailure(failure);
                        } catch (Throwable cleanupFailure) {
                            failure = RegionScheduler.publishFatalAndSelectWinner(
                                    mergeFailure(failure, cleanupFailure));
                        }
                    }
                }
            }
            if (eventBarrier != null) {
                try {
                    eventBarrier.completeExceptionally(failure);
                } catch (Throwable cleanupFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            mergeFailure(failure, cleanupFailure));
                }
            }
            RegionizedServer.<RuntimeException>rethrow(failure);
            return result;
        }
    }

    private EntityDispatchPlan prepareEntityDispatchPlan(
            EpochDomain d, List<Region> regions, boolean tickEntities) {
        // New epoch = new orphan window: full heal cadence. This and every plan
        // read/allocation below belong to one admitted preparation body.
        RegionScheduler.resetHealBackoff();
        // The event scheduler's workerLoop owns the entity-phase callback implicitly.
        // Queue-only exact mode therefore uses the explicit group path, where the
        // tickEntities flag is carried with the submitted continuation.
        final boolean eventSched = tickEntities && FabroliaConfig.regionEventScheduler;
        ArrayList<RegionLoad> active = new ArrayList<>(regions.size());
        for (int i = 0, n = regions.size(); i < n; i++) {
            Region r = regions.get(i);
            if (r.state() == RegionState.ACTIVE
                    && hasEntityDispatchWork(r, tickEntities)) {
                // Load weight: measured EWMA of the region's entity phase when we have
                // one (captures BE/AI-heavy regions entity counts under-weigh — the
                // profiled tail region ran 1.5–1.7× pool average), scaled to pseudo-
                // entity units; count+queue heuristic as cold-start fallback.
                long ema = r.emaPhaseNanos;
                boolean hasQueuedWork = !r.queue().isEmpty()
                        || !r.helpQueue.isEmpty();
                long weight = ema > 0
                        ? ema / 10_000L + (hasQueuedWork ? 96 : 0)
                        : r.entityCount.get() + (hasQueuedWork ? 96 : 0);
                active.add(new RegionLoad(r, weight));
            }
        }
        final long epoch = d.epoch;
        if (!tickEntities && d != globalDomain) {
            throw new IllegalStateException(
                    "queue-only entity dispatch requires the global domain");
        }

        // Build the complete dispatch plan before publishing a single epoch
        // stamp or executable root.
        CompletableFuture<Void> eventBarrier = eventSched ? new CompletableFuture<>() : null;
        CompletableFuture<Void> result;
        ArrayList<Runnable> roots = null;
        EpochDomain.EntityDispatchPublication publication =
                new EpochDomain.EntityDispatchPublication();

        if (active.isEmpty()) {
            result = eventBarrier != null
                    ? eventBarrier : CompletableFuture.completedFuture(null);
        } else if (eventSched) {
            active.sort((a, b) -> Long.compare(b.count, a.count));
            result = eventBarrier;
        } else {
            active.sort((a, b) -> Long.compare(b.count, a.count));
            int groupCount = Math.max(1,
                    Math.min(RegionScheduler.workerCount() * 3, active.size()));
            ArrayList<ArrayList<Region>> groups = new ArrayList<>(groupCount);
            long[] groupLoad = new long[groupCount];
            int created = 0;
            for (int i = 0, n = active.size(); i < n; i++) {
                Region r = active.get(i).region;
                long count = active.get(i).count;
                if (created < groupCount) {
                    ArrayList<Region> group = new ArrayList<>(8);
                    group.add(r);
                    groups.add(group);
                    groupLoad[created++] = count;
                    continue;
                }
                int best = 0;
                for (int gi = 1; gi < created; gi++) {
                    if (groupLoad[gi] < groupLoad[best]) best = gi;
                }
                groups.get(best).add(r);
                groupLoad[best] += count;
            }
            ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(groups.size());
            roots = new ArrayList<>(groups.size());
            for (int gi = 0, gn = groups.size(); gi < gn; gi++) {
                ArrayList<Region> group = groups.get(gi);
                CompletableFuture<Void> future = new CompletableFuture<>();
                futures.add(future);
                group.sort((left, right) -> Integer.compare(left.color, right.color));
                roots.add(cancellableGroupRoot(group, 0, future, tickEntities,
                        publication));
            }
            result = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture<?>[0]));
        }
        return new EntityDispatchPlan(epoch, eventSched, active, eventBarrier,
                result, roots, publication);
    }

    /** Exact next-dispatch seed predicate, including a helper left behind by
     *  the just-closed queue/entity phase. */
    static boolean hasEntityDispatchWork(Region region, boolean tickEntities) {
        return (tickEntities && !region.entities.isEmpty())
                || !region.queue().isEmpty()
                || !region.helpQueue.isEmpty();
    }

    /**
     * Gated group processing (R4 color-DAG). Eligible regions are claimed and
     * ticked in order; a region whose lower-color neighbors are still running
     * is SKIPPED and the remainder is re-enqueued as a continuation task —
     * the worker is never parked inside a group, so blocker groups always
     * find a free worker (deadlock-free by construction; colors are a DAG).
     */
    private void processGroup(ArrayList<Region> group, int depth, CompletableFuture<Void> cf,
                              boolean tickEntities) {
        RegionWorkerThread me = (RegionWorkerThread) Thread.currentThread();
        ArrayList<Region> deferred = null;
        try {
            for (int ri = 0, rn = group.size(); ri < rn; ri++) {
                RegionScheduler.throwWorkerFailureIfAny();
                Region r = group.get(ri);
                EpochDomain claimDomain = r.domain;
                long claimEpoch = claimDomain != null
                        ? claimDomain.epoch : Long.MIN_VALUE;
                boolean claimEligible = ColorGate.eligible(r, this);
                // Token mismatch retires this OLD group obligation. Deferring
                // it would let the old root spin on, or enter, the replacement
                // domain whose own dispatch is now authoritative.
                if (r.domain != claimDomain
                        || (claimDomain != null
                            && claimDomain.epoch != claimEpoch)) {
                    continue;
                }
                if (!claimEligible) {
                    EpochDomain currentDomain = r.domain;
                    if (currentDomain == null
                            || !r.hasSealedDone(currentDomain.epoch)) {
                        if (deferred == null) deferred = new ArrayList<>(rn - ri);
                        deferred.add(r);
                    }
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
                boolean claimed = false;
                boolean staleGeneration = false;
                while (!(claimed = r.tryClaim(me, claimDomain, claimEpoch))) {
                    if (r.claimRebaseClosed()
                            || r.domain != claimDomain
                            || (claimDomain != null
                                && claimDomain.epoch != claimEpoch)) {
                        staleGeneration = true;
                        break;
                    }
                    if (!ColorGate.eligible(r, this)) break;
                    if (++spins > 500) {
                        LockSupport.parkNanos(10_000L);
                    } else {
                        Thread.onSpinWait();
                    }
                }
                if (!claimed) {
                    me.currentRegion = null;
                    // This group belongs to the old dispatch generation. The
                    // replacement domain's own dispatch is now authoritative;
                    // requeueing this root would spin or execute old work in it.
                    if (staleGeneration) continue;
                    EpochDomain currentDomain = r.domain;
                    if (currentDomain == null
                            || !r.hasSealedDone(currentDomain.epoch)) {
                        if (deferred == null) deferred = new ArrayList<>(rn - ri);
                        deferred.add(r);
                    }
                    continue;
                }
                boolean claimStamped = false;
                boolean entityPhaseRan = false;
                Throwable regionFailure = null;
                try {
                    // Claim acquisition may have waited while a peer selected
                    // W. Release the claim below, but enter no new phase body.
                    RegionScheduler.throwWorkerFailureIfAny();
                    // Required post-CAS half of DONE_SEAL. A claimant whose
                    // first eligibility read predates done publication owns no
                    // group body and releases through the same exact tail.
                    if (ColorGate.eligible(r, this)) {
                        r.stampAcquire("group");
                        claimStamped = true;
                        // The active predicate includes a retained helper even
                        // when no normal task/entity remains. Mirror the event
                        // worker path: the exact group claimant is then the
                        // orphan helper's only legal consumer, and must settle
                        // it before normal FIFO and queue-only completion.
                        drainGroupOrphanHelp(r);
                        r.queue().drain(Integer.MAX_VALUE); // spawn/random first (FIFO)
                        if (tickEntities) {
                            tickOwned(r);
                            entityPhaseRan = true;
                        }
                    }
                } catch (Throwable thrown) {
                    regionFailure = RegionScheduler.publishFatalAndSelectWinner(thrown);
                } finally {
                    if (regionFailure == null) {
                        regionFailure = RegionScheduler.workerFailureOrNull();
                    }
                    if (regionFailure == null) {
                        try {
                            final boolean commitEntity = entityPhaseRan;
                            RegionScheduler.commitSuccessIfHealthy(() -> {
                                if (commitEntity) commitEntityPhase(r);
                            });
                        } catch (Throwable completionFailure) {
                            regionFailure = RegionScheduler.publishFatalAndSelectWinner(
                                    completionFailure);
                        }
                    }
                    if (claimStamped) {
                        try {
                            r.stampRelease("group");
                        } catch (Throwable cleanupFailure) {
                            regionFailure = RegionScheduler.publishFatalAndSelectWinner(
                                    mergeFailure(regionFailure, cleanupFailure));
                        }
                    }
                    try {
                        r.release(me);
                    } catch (Throwable cleanupFailure) {
                        regionFailure = RegionScheduler.publishFatalAndSelectWinner(
                                mergeFailure(regionFailure, cleanupFailure));
                    } finally {
                        me.currentRegion = null; // unpublish AFTER release attempt
                    }
                    if (regionFailure == null) {
                        regionFailure = RegionScheduler.workerFailureOrNull();
                    }
                    if (regionFailure == null) {
                        try {
                            // Re-arm only a successful phase. A fatal generation
                            // has no legal successor simulation step.
                            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                                if (!r.queue().isEmpty() || !r.helpQueue.isEmpty()) {
                                    RegionScheduler.enqueueReady(r);
                                }
                                ColorGate.tryMarkDone(r, this);
                            });
                        } catch (Throwable completionFailure) {
                            regionFailure = RegionScheduler.publishFatalAndSelectWinner(
                                    mergeFailure(regionFailure, completionFailure));
                        }
                    }
                    try {
                        // tryClaim's transaction spans the release-to-ColorGate
                        // successor tail. Domain migration may proceed only
                        // after this exact terminal, never at currentWorker=null.
                        r.finishClaimTransaction();
                    } catch (Throwable cleanupFailure) {
                        regionFailure = RegionScheduler.publishFatalAndSelectWinner(
                                mergeFailure(regionFailure, cleanupFailure));
                    }
                }
                if (regionFailure != null) {
                    RegionizedServer.<RuntimeException>rethrow(regionFailure);
                }
            }
        } catch (Throwable t) {
            // Close the generation-wide start gate before publishing this group's
            // terminal. Completing the future first leaves a real race in which Main
            // (or another worker) can observe the local failure while an unrelated
            // queued simulation root still enters. The shared slot also selects a
            // concurrent earlier winner; this group failure is only suppressed context
            // in that case.
            Throwable winner = RegionScheduler.publishFatalAndSelectWinner(t);
            completeExceptionallyAndRethrow(cf, winner);
        }
        try {
            // The group-local body may have returned normally while another
            // worker selected W.  Keep both the normal future terminal and a
            // continuation publication behind the generation-wide fail-stop
            // gate; neither is legal after W.
            RegionScheduler.throwWorkerFailureIfAny();
            if (deferred == null) {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    if (!cf.complete(null)) {
                        throw new IllegalStateException(
                                "region group future completed more than once");
                    }
                });
            } else {
                final ArrayList<Region> rest = deferred;
                if (rest.size() == group.size()) {
                    // Zero progress this pass: the blockers are mid-drain elsewhere
                    // or orphans awaiting the barrier pump's heal sweep. Chew one
                    // unit of real work if any exists, else breathe 20µs — bounds
                    // the requeue churn without parking inside the group (the
                    // deadlock-free continuation shape stays intact).
                    RegionScheduler.throwWorkerFailureIfAny();
                    if (!RegionScheduler.stealOneRegionTask()) {
                        LockSupport.parkNanos(20_000L);
                    }
                    RegionScheduler.throwWorkerFailureIfAny();
                    // ~1s of zero progress (50k passes × 20µs) = something is
                    // genuinely wedged — say WHO instead of parking silently
                    // (the 2026-06-12 wedge was 203s of anonymous spinning).
                    if (depth > 0 && depth % 50_000 == 0) {
                        LOGGER.error("[color-gate] group stalled {} passes; {}",
                                depth, ColorGate.diagnose(rest.get(0), this));
                    }
                }
                RegionScheduler.throwWorkerFailureIfAny();
                RegionScheduler.enqueueGlobal(
                        cancellableGroupRoot(rest, depth + 1, cf, tickEntities,
                                rest.get(0).domain.entityDispatchPublication));
            }
        } catch (Throwable terminalFailure) {
            Throwable winner = RegionScheduler.publishFatalAndSelectWinner(terminalFailure);
            completeExceptionallyAndRethrow(cf, winner);
        }
    }

    /** Execute every accepted helper inherited by an exact legacy-group claim. */
    static void drainGroupOrphanHelp(Region region) {
        while (true) {
            RegionScheduler.throwWorkerFailureIfAny();
            Runnable help = region.helpQueue.poll();
            if (help == null) return;
            // The queued ExternalTask owns the exact W-vs-body admission.
            help.run();
            RegionScheduler.throwWorkerFailureIfAny();
        }
    }

    private Runnable cancellableGroupRoot(ArrayList<Region> group, int depth,
                                          CompletableFuture<Void> future,
                                          boolean tickEntities,
                                          EpochDomain.EntityDispatchPublication publication) {
        return RegionScheduler.cancellableRoot(
                () -> {
                    if (!publication.awaitAccepted()) {
                        RegionScheduler.throwWorkerFailureIfAny();
                        return;
                    }
                    processGroup(group, depth, future, tickEntities);
                },
                future::completeExceptionally);
    }

    /** Future notification is diagnostic/wakeup work and cannot replace the phase primary. */
    private static void completeExceptionallyAndRethrow(
            CompletableFuture<Void> future, Throwable primary) {
        Throwable failure = primary;
        try {
            future.completeExceptionally(primary);
        } catch (Throwable notificationFailure) {
            failure = mergeFailure(failure, notificationFailure);
        } finally {
            RegionizedServer.<RuntimeException>rethrow(failure);
        }
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                if (primary.getSuppressed().length >= 8) return primary;
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Suppression is diagnostics only; physical Main must receive
                // the exact first vanilla-phase failure under allocation stress.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * The per-region tick body: vanilla's entity loop, serial on the claiming
     * worker in entity-ID order. Independently eligible regions remain parallel.
     */
    private void tickOwned(Region region) {
        long phaseStart = System.nanoTime();
        try {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> RegionTickHooks.firePre(region));
            ServerLevel lvl = level;
            TickRateManager trm = lvl.tickRateManager();
            ServerChunkCache chunkSource = lvl.getChunkSource();
            DistanceManager dm = chunkSource.chunkMap.getDistanceManager();
            var gate = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(16);
            Entity prevGap = null;
            long gapT0 = System.nanoTime();
            for (Entity e : region.entities) {
                tickOne(region, e, lvl, trm, dm, gate);
                long t1 = System.nanoTime();
                if (t1 - gapT0 > 100_000_000L) {
                    FlightRecorder.record("PHASE-GAP region=" + Long.toHexString(region.gridKey)
                            + " ms=" + (t1 - gapT0) / 1_000_000L
                            + " in=" + e.getType() + "#" + e.getId()
                            + " after=" + (prevGap == null ? "-" : prevGap.getType() + "#" + prevGap.getId())
                            + " L=" + (region.domain != null ? region.domain.unitGameTime : -1));
                }
                prevGap = e;
                gapT0 = t1;
            }
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> RegionTickHooks.firePost(region));
            long phaseNanos = System.nanoTime() - phaseStart;
            RegionScheduler.commitSuccessIfHealthy(
                    () -> region.stagedPhaseNanos = phaseNanos);
        } catch (Throwable thrown) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            RegionizedServer.<RuntimeException>rethrow(failure);
        }
    }

    /**
     * Vanilla per-chunk entity-ticking gate ({@code inEntityTickingRange}), memoized
     * PER REGION TASK: one coherent answer per chunk per tick, mirroring vanilla —
     * which evaluates this on main where nothing mutates ticket state mid-phase.
     * Evaluated live per entity on a worker it raced
     * (a) distance-tracker propagation waves from ChunkMap.move on OTHER workers
     * (off-main movement callbacks: remove-source decay then re-add — intermediate levels are
     * visible in the concurrent maps mid-wave) and (b) main's barrier pump applying
     * ticket updates mid-phase. A read landing in the decay window sees the chunk
     * transiently OUT of entity-ticking range and the entity silently skips a whole
     * tick — items freezing/jittering in sorter water streams near moving players,
     * load-scaled (2026-07-06). One memoized read per chunk is also cheaper than the
     * old per-entity probes.
     *
     * <p>Do NOT add a live {@code ChunkHolder.getTickingChunk()} condition here.
     * Vanilla 26.2's guarded entity loop uses only {@code inEntityTickingRange}.
     * With c2me the holder's BLOCK_TICKING future changes asynchronously and can
     * temporarily be incomplete after our completed simulation snapshot already
     * says entity-ticking. The extra condition can skip minecarts/items for one
     * region unit while block events and BEs continue.
     *
     * <p>The memo belongs to the serial region owner; newly encountered chunks
     * are resolved lazily and cached for the rest of this phase.
     */
    private byte fabrolia$chunkGate(long packed, it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap memo,
                                 DistanceManager dm) {
        byte g = memo.get(packed);   // 0 = unknown, 1 = tick, 2 = skip
        if (g != 0) {
            return g;
        }
        boolean ok = dm.inEntityTickingRange(packed);
        g = ok ? (byte) 1 : (byte) 2;
        memo.put(packed, g);
        return g;
    }

    private void tickOne(Region region, Entity e, ServerLevel lvl, TickRateManager trm, DistanceManager dm,
                         it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap gate) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> tickOneAdmitted(region, e, lvl, trm, dm, gate));
    }

    private boolean fabrolia$fishingOwnerIsRemote(Region region, ServerPlayer owner) {
        if (owner.level() != level) return true;
        IslandRegistry.PIsland hookIsland = region.domain == null ? null : region.domain.owner;
        return hookIsland == null || hookIsland.retired
                || islandOwnerOfChunk(owner.chunkPosition().pack()) != hookIsland;
    }

    /** Admission for a globally fenced cross-owner entity body: re-check the live
     *  ticking gate after every island quiesced (the region task's per-chunk memo
     *  predates the fence). */
    private boolean fabrolia$admitFencedTick(Entity entity, ServerLevel sourceLevel) {
        ServerChunkCache source = sourceLevel.getChunkSource();
        long chunkKey = entity.chunkPosition().pack();
        while (source.mainThreadProcessor.pollTask()) {
            // drain queued main-thread chunk work before re-checking admission
        }
        return entity.level() == sourceLevel && !entity.isRemoved()
                && source.chunkMap.getDistanceManager().inEntityTickingRange(chunkKey);
    }

    /** One entity through vanilla admission and tick on the region owner.
     *  The per-entity tick token deduplicates same-epoch migration visits. */
    private void tickOneAdmitted(Region region, Entity e, ServerLevel lvl, TickRateManager trm, DistanceManager dm,
                                 it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap gate) {
        if (e == null) return;
        if (e.isRemoved()) {
            // Removal callbacks normally pull entities out; repair a stale key.
            dropFromRegion(region, e);
            return;
        }
        long packed = e.chunkPosition().pack();
        long curKey = regionizer.grid.keyFor(packed);
        if (curKey != region.gridKey) {
            // Tick where grouped; the token deduplicates a second migration visit.
            indexMove(e, region, curKey);
        }
        RegionKeyed keyed = (RegionKeyed) e;
        if (!keyed.fabrolia$tryMarkRegionTick(region.domain.tickToken())) return;
        if (trm.isEntityFrozen(e)) return;
        if (((ParallelProcessor.TickGuard) e).fabrolia$tickLease().isArrivalPending()) return;
        // A queued portal transfer is already causally gone from this source.
        if (keyed instanceof ParallelProcessor.TickGuard tg && tg.fabrolia$isPortalPending()) return;
        boolean directPlayer = e instanceof ServerPlayer;
        boolean containsPlayerTick = ParallelProcessor.containsServerPlayerTick(e);
        // Ticking-readiness gate, ONE coherent answer per chunk per region task (see
        // fabrolia$chunkGate — live per-entity reads raced distance-tracker propagation
        // and missed ticks). The completed DistanceManager view is the exact vanilla
        // authority; a second live holder-future probe is both non-vanilla and racy.
        if (!directPlayer && fabrolia$chunkGate(packed, gate, dm) != 1) {
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
            e.checkDespawn();
            return;
        }

        Entity vehicle = e.getVehicle();
        if (vehicle != null) {
            // PORTAL-HANDOFF (2026-08-06 minecart-passenger despawn): a vehicle
            // with a queued main-side portal teleport is NOT removed — vanilla
            // would carry its riders through teleportCrossDimension's
            // eject→recursive-passenger-teleport→re-seat. But the isRemoved
            // spoof (EntityMixin.fabrolia$removedWhileDeparted) made this gate
            // read the staged vehicle as removed and stopRiding() the
            // passenger on the SOURCE side: the mob then arrived as a separate
            // unmounted copy, lost requiresCustomPersistence()'s isPassenger
            // protection, and vanilla checkDespawn discarded it instantly in a
            // no-players-nearby destination. Skip the passenger exactly like
            // the vehicle itself (the portalPending early-out above) — main's
            // deferred teleport re-seats it on the destination copy.
            if (vehicle instanceof com.axalotl.fabrolia.common.ParallelProcessor.TickGuard vg
                    && vg.fabrolia$isPortalPending()) {
                return;
            }
            if (!vehicle.isRemoved() && vehicle.hasPassenger(e)) return;
            e.stopRiding();
        }

        // A5: a direct, supported player is owned by this autonomous island unit.
        // Preserve vanilla's checkDespawn-before-body order before irreversible A5
        // admission.  Unsupported subclasses/vehicle trees/session transitions simply
        // fall through to the established MAIN_SYNC compatibility lane.
        if (directPlayer) {
            e.checkDespawn();
            if (e.isRemoved()) {
                dropFromRegion(region, e);
                return;
            }
            if (AsyncIslandPlayerTicks.tryRunEarly(region, (ServerPlayer) e)) {
                return;
            }
        }

        if (e instanceof net.minecraft.world.entity.projectile.FishingHook hook
                && hook.getPlayerOwner() instanceof ServerPlayer serverOwner
                && fabrolia$fishingOwnerIsRemote(region, serverOwner)) {
            RegionScheduler.queueMainSyncFencedTick(e, () -> fabrolia$admitFencedTick(e, lvl), null);
            return;
        }
        // A sleeper whose bed lies in another island's domain: vanilla's own tick
        // reads (checkBedExists) and writes (stopSleeping → BedBlock.OCCUPIED) that
        // remote bed. The plain MAIN_SYNC lane claims only THIS island; the write
        // needs the bed's owner quiescent too, so tick under the global fence.
        if (ParallelProcessor.sleepsOutsideIsland(e, this, region.domain.owner)) {
            RegionScheduler.queueMainSyncFencedTick(e, () -> fabrolia$admitFencedTick(e, lvl), null);
            return;
        }
        if (containsPlayerTick || ParallelProcessor.requiresMainThread(e)) {
            RegionScheduler.queueMainSyncTick(e);
            return;
        }

        e.checkDespawn();
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
