package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Atomic cross-dimension arrival cohorts (2026-07-15).
 *
 * <p>Vanilla guarantees — purely by ticking dimensions sequentially on one
 * thread — that every entity teleported during one source tick is inserted
 * into the destination BEFORE the destination's entity pass of that same
 * server tick: a multi-entity activation (the ~12-sand End duper) arrives as
 * ONE cohort. Under regional ticking the vanilla teleport ran synchronously
 * on the source island's worker, racing the destination's live entity-set
 * iteration — a microsecond boundary split cohorts into "8 tick now, 4 tick
 * next", and the per-teleport End-platform regen (destroyBlock item pops,
 * with its clearing writes deferred) re-harvested the same stale block once
 * per remaining member: visible two-level arrivals, excess output.
 *
 * <p>Model: destination-side effects of a cross-dimension teleport (platform
 * regen, entity insertion) are STAGED on the source execution domain while
 * the source tick runs vanilla-exact (copy created, original removed, the
 * source-side forced duplicate continuation untouched). At source-phase end
 * the stage SEALS into one ordered batch = one activation cohort, routed to
 * the destination owner (island arrivals queue / level global queue). The
 * owner PUBLISHES the whole batch at its next phase head — before scheduled
 * ticks, before its entity phase — so every member becomes tickable at the
 * same destination phase boundary, exactly one regen executes against stale
 * state, and no destination structure is ever mutated off-owner.
 */
public final class CrossDimArrivals {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-XDim");

    /** One staged destination-side operation, in vanilla execution order.
     *  {@code sourceTick} is the SOURCE simulation epoch (island L, or wall
     *  for main-context stages; -1 = unknown → deliver immediately) and
     *  {@code sourceDomain} names the CLOCK it was read from (island gen, or
     *  0 = the wall clock). Ticks are comparable ONLY within one domain —
     *  every island's L has an arbitrary birth offset from wall, so a single
     *  cross-source number line mis-orders transfers (task #20: two source
     *  islands with different offsets feeding one destination made every
     *  interleave look like a timeline reset and compressed delivery).
     *  {@code entity} is the arrival copy for {@code kind=="entity"} ops
     *  (null for regens) — read ONLY by the End-platform handoff gate to
     *  recognize falling-block cohorts; never mutated pre-publish. */
    public record StagedOp(ServerLevel dest, long chunkKey, Runnable op, String kind,
                           long sourceTick, long sourceDomain, Entity entity) {
        public StagedOp(ServerLevel dest, long chunkKey, Runnable op, String kind,
                        long sourceTick, long sourceDomain) {
            this(dest, chunkKey, op, kind, sourceTick, sourceDomain, null);
        }
    }

    /** Re-entrancy latch: publish-time execution must run the REAL vanilla
     *  paths (the staging mixins pass through while set). */
    public static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);

    /** task #19/#20: source simulation {epoch, clock domain} carried across
     *  the main-deferred portal handoff (set by PortalTeleportationManager
     *  around the drain) — the drain runs in MAIN context, which would stamp
     *  every burst member with one wall tick in the wall domain. */
    public static final ThreadLocal<long[]> CARRIED_SOURCE = new ThreadLocal<>();

    // counters
    public static final AtomicLong STAGED_ENTITIES = new AtomicLong();
    public static final AtomicLong STAGED_REGENS = new AtomicLong();
    public static final AtomicLong COHORTS_SEALED = new AtomicLong();
    public static final AtomicLong COHORT_MEMBERS = new AtomicLong();
    public static final AtomicLong PUBLISHED_BATCHES = new AtomicLong();
    public static final AtomicLong PUBLISHED_OPS = new AtomicLong();
    public static final AtomicLong MAX_COHORT = new AtomicLong();
    public static final AtomicLong REROUTED = new AtomicLong();
    public static final AtomicLong FLUSHED_AT_SAVE = new AtomicLong();

    private CrossDimArrivals() {}

    /** Is this destination chunk owned by an ISLAND domain (vs global/main)?
     *  Used by the staging mixin's main-thread decision (package access to
     *  Region/EpochDomain internals). */
    public static boolean islandOwnedDestination(RegionizedServer destRs, long chunkKey) {
        Region region = destRs.regionizer().peekByGridKey(destRs.regionizer().grid.keyFor(chunkKey));
        return region != null && region.domain != null && region.domain.owner != null;
    }

    /** Stage one destination-side op on the CURRENT execution context's
     *  domain buffer (worker: its island/current domain; main: the source
     *  level's global domain). Order within a domain buffer is preserved. */
    public static void stage(ServerLevel dest, long chunkKey, Runnable op, String kind) {
        stage(dest, chunkKey, op, kind, null);
    }

    /** As {@link #stage(ServerLevel, long, Runnable, String)}, additionally
     *  carrying the arrival entity so the End-platform handoff gate can
     *  recognize falling-block cohorts ({@link EndPlatformHandoff}). */
    public static void stage(ServerLevel dest, long chunkKey, Runnable op, String kind,
                             Entity entity) {
        EpochDomain d = currentSourceDomain();
        if (d == null) {
            // No scheduler context (boot, datagen, plugin thread): run the
            // vanilla path inline — nothing is concurrently ticking.
            runPublished(op);
            return;
        }
        long[] carried = CARRIED_SOURCE.get();
        long srcTick;
        long srcDomain;
        if (carried != null) {
            // task #19: a main-deferred portal teleport carries the epoch of
            // the SOURCE island tick that actually fired portal-time — the
            // main drain's wall tick would collapse a whole backlog burst
            // into one apparent source tick.
            srcTick = carried[0];
            srcDomain = carried[1];
        } else {
            srcTick = com.axalotl.fabrolia.common.regions.IslandClock.contextTime();
            srcDomain = domainKeyOf(d);
            if (srcTick < 0) {
                // main / global context: the wall clock IS its simulation epoch
                srcTick = IslandClock.wallTime(dest);
                srcDomain = 0;
            }
        }
        d.crossDimStage.add(new StagedOp(dest, chunkKey, op, kind, srcTick, srcDomain, entity));
        if ("entity".equals(kind)) {
            STAGED_ENTITIES.incrementAndGet();
        } else {
            STAGED_REGENS.incrementAndGet();
        }
    }

    /** Clock-domain key for spacing comparability: the owning island's gen
     *  (unique per island incarnation — a reborn island is a NEW clock), or
     *  0 for the wall/global lane. */
    public static long domainKeyOf(EpochDomain d) {
        return d != null && d.owner != null ? d.owner.gen : 0L;
    }

    /** Clock-domain key of the CURRENT execution context (worker: its
     *  island; main/global: 0) — for callers that capture stamps early. */
    public static long currentDomainKey() {
        return domainKeyOf(currentSourceDomain());
    }

    private static EpochDomain currentSourceDomain() {
        Thread t = Thread.currentThread();
        if (t instanceof RegionWorkerThread w) {
            Region cur = w.currentRegion;
            if (cur != null && cur.domain != null) return cur.domain;
            return w.currentDomain;
        }
        var server = RegionScheduler.getServer();
        if (server != null && server.isSameThread()) {
            // main: stage on the currently-ticking source level's global
            // domain; sealed at that level's entity-batch end.
            ServerLevel src = RegionScheduler.mainCurrentLevel;
            if (src != null) {
                RegionizedServer rs = RegionScheduler.forLevel(src);
                if (rs != null) return rs.globalDomain;
            }
        }
        return null;
    }

    /** Seal the domain's open stage into one cohort batch and route it to
     *  the destination owner. Called at source-phase end (island unit tail /
     *  main entity-batch end) — MUST be reachable from the unit's finally so
     *  a contained phase throwable cannot strand a batch. */
    public static void seal(EpochDomain d) {
        if (d == null || d.crossDimStage.isEmpty()) {
            return;
        }
        ArrayList<StagedOp> batch = new ArrayList<>(8);
        StagedOp op;
        while ((op = d.crossDimStage.poll()) != null) {
            batch.add(op);
        }
        if (batch.isEmpty()) {
            return;
        }
        COHORTS_SEALED.incrementAndGet();
        COHORT_MEMBERS.addAndGet(batch.size());
        MAX_COHORT.accumulateAndGet(batch.size(), Math::max);
        route(batch);
    }

    private static void route(ArrayList<StagedOp> batch) {
        // ONE owner bucket per destination level, resolved ONCE from the
        // batch's first op for that level. Per-op resolution split a cohort
        // when the destination island formed mid-loop (2026-07-15 sprint A/B:
        // publishedBatches=24 for 22 seals, cohort 1 double-placed) — a
        // cohort must cross exactly one publish boundary. Multi-level batches
        // split per level only (spatially independent destinations).
        java.util.HashMap<ServerLevel, ArrayList<StagedOp>> byLevel = new java.util.HashMap<>(2);
        for (StagedOp op : batch) {
            byLevel.computeIfAbsent(op.dest(), l -> new ArrayList<>(batch.size())).add(op);
        }
        for (var e : byLevel.entrySet()) {
            ArrayList<StagedOp> ops = e.getValue();
            RegionizedServer destRs = RegionScheduler.forLevel(e.getKey());
            IslandRegistry.PIsland owner = null;
            if (destRs != null && destRs.asyncActive) {
                long cellKey = destRs.regionizer().grid.keyFor(ops.get(0).chunkKey());
                Region region = destRs.regionizer().peekByGridKey(cellKey);
                owner = region != null && region.domain != null ? region.domain.owner : null;
                if (owner == null) {
                    owner = destRs.islandRegistry.byCell(cellKey);
                }
            }
            if (owner != null && enqueueUnlessRetired(owner, ops)) {
                // delivered to the live island's arrival queue
            } else if (destRs != null) {
                appendGlobalLane(destRs.globalArrivals, destRs.globalArrivalsLock, ops);
            } else {
                for (StagedOp op : ops) {
                    runPublished(op.op()); // scheduler-less destination: inline
                }
            }
        }
    }

    /**
     * audit11 — retire-in-route-window cohort loss: the {@code owner.retired}
     * check used to run OUTSIDE any ordering guarantee with {@link #reroute}.
     * Ops enqueued after reroute's drain landed in a dead island's queue and
     * were unreachable forever ({@code publishLevelPending} iterates only live
     * islands). Re-checking {@code retired} INSIDE the {@code pendingArrivals}
     * monitor — the same monitor reroute drains under — closes the race: either
     * the ops are in the list when reroute drains them, or the writer observes
     * {@code retired} and the caller falls through to the destination's global
     * lane. Package-private for the unit test.
     *
     * @return true if the ops were queued on a live island; false = the island
     *         retired first and the caller must deliver via the global lane.
     */
    static boolean enqueueUnlessRetired(IslandRegistry.PIsland owner,
                                        java.util.List<StagedOp> ops) {
        synchronized (owner.pendingArrivals) {
            if (owner.retired) {
                return false;
            }
            owner.pendingArrivals.addAll(ops);
            return true;
        }
    }

    /** Publish every arrival queued for this island. Called at the unit head
     *  UNDER THE UNIT CLAIM, before scheduled ticks and the entity phase —
     *  the vanilla-equivalent boundary (arrivals precede the destination's
     *  whole tick). */
    /** Backlog-compression guard (tasks #19/#20). Transfers that originated
     *  on DISTINCT source simulation ticks must not receive simulation
     *  progress in one destination epoch just because a stall, a burst drain
     *  or a save batched their delivery; transfers from the SAME source tick
     *  (a teleport's [regen, entity] pair; genuinely simultaneous portal
     *  entries) deliver together — vanilla same-tick semantics, cohort
     *  atomicity and FIFO unchanged.
     *
     *  <p>Release rule: at most ONE distinct source cohort per clock domain
     *  per destination epoch. No source-delta preservation: preserving full
     *  deltas turned any backlog into standing historical debt that NEVER
     *  converged when the feed rate matched the drain rate (the 2026-07-19
     *  production lesson that forced pacing out entirely, and task #20's
     *  permanent post-stall lag). The 1-epoch floor is the whole invariant:
     *  distinct source ticks land on distinct destination epochs, a backlog
     *  drains at ≥1 cohort/epoch (debt converges), and no clamp constant or
     *  timeline-reset heuristic is needed.
     *
     *  <p>Chains are PER SOURCE CLOCK DOMAIN ({@link StagedOp#sourceDomain}):
     *  ticks from different islands are incomparable (arbitrary birth
     *  offsets), so a shared chain saw every cross-source interleave as a
     *  timeline reset and released it un-spaced (task #20's repeated stacked
     *  placements). A domain's first cohort after idle delivers immediately
     *  (no wall-clock debt); the map resets when the queue drains. */
    /**
     * Delegates to {@link ArrivalReleaseRule}. See that class for why the release
     * rule is causal rather than rate-paced: holding a completed source cohort
     * back while the destination ticks already-published falling blocks lets a
     * later {@code createEndPlatform} destroy blocks those entities just placed.
     */
    private static int fabrolia$eligiblePrefix(java.util.List<StagedOp> ops,
                                               long destNow,
                                               java.util.HashMap<Long, long[]> chains) {
        return ArrivalReleaseRule.prefix(new ArrivalReleaseRule.Queue() {
            @Override public int size() { return ops.size(); }
            @Override public long sourceDomain(int i) { return ops.get(i).sourceDomain(); }
            @Override public long sourceTick(int i) { return ops.get(i).sourceTick(); }
        }, destNow, chains);
    }

    /** Adapter: a released-prefix window of staged ops for the handoff gate.
     *  {@code landingHandle} is non-null only for falling-block entity
     *  arrivals — the one entity class whose landing the gate observes. */
    private static EndPlatformHandoff.Ops fabrolia$gateView(java.util.List<StagedOp> ops, int take) {
        return new EndPlatformHandoff.Ops() {
            @Override public int size() { return take; }
            @Override public long sourceDomain(int i) { return ops.get(i).sourceDomain(); }
            @Override public long sourceTick(int i) { return ops.get(i).sourceTick(); }
            @Override public long chunkKey(int i) { return ops.get(i).chunkKey(); }
            @Override public boolean isRegen(int i) { return "regen".equals(ops.get(i).kind()); }
            @Override public Object landingHandle(int i) {
                StagedOp op = ops.get(i);
                return op.entity() instanceof net.minecraft.world.entity.item.FallingBlockEntity
                        ? op.entity() : null;
            }
        };
    }

    /**
     * PHASE-DRIFT FIX (2026-07-27): extract the publishable part of the
     * causally-released prefix, applying the End-platform falling-block
     * handoff gate ({@link EndPlatformHandoff}). Ops the gate holds stay at
     * the FRONT of {@code pending} in their original order — they are simply
     * re-offered at the next head, by which time the previous cohort's landing
     * phase has advanced. Everything else (the overwhelmingly common case: no
     * machine cohort in flight) releases exactly as before, so unrelated
     * arrivals are never delayed. Caller holds the queue's ordering monitor.
     *
     * @return the ops to publish now, or null if none
     */
    private static ArrayList<StagedOp> fabrolia$gatedExtract(java.util.List<StagedOp> pending,
                                                             int take,
                                                             EndPlatformHandoff.Tracker tracker) {
        if (tracker == null) {
            ArrayList<StagedOp> ops = new ArrayList<>(pending.subList(0, take));
            pending.subList(0, take).clear();
            return ops;
        }
        boolean[] release = EndPlatformHandoff.selectReleasable(
                fabrolia$gateView(pending, take), tracker);
        ArrayList<StagedOp> released = new ArrayList<>(take);
        ArrayList<StagedOp> held = null;
        for (int i = 0; i < take; i++) {
            if (release[i]) {
                released.add(pending.get(i));
            } else {
                if (held == null) {
                    held = new ArrayList<>(take - i);
                }
                held.add(pending.get(i));
            }
        }
        pending.subList(0, take).clear();
        if (held != null) {
            pending.addAll(0, held); // held ops keep their queue position
        }
        return released.isEmpty() ? null : released;
    }

    public static void publishIsland(IslandRegistry.PIsland island) {
        // Delivery point: the unit head, under the claim, before scheduled
        // ticks and the entity phase — the vanilla-equivalent boundary
        // (arrivals precede the destination's whole tick). Transport safety
        // (exactly-once, cohort integrity, arrival-before-tick ordering,
        // endpoint leases, retirement rerouting, shutdown flush) lives in
        // route/seal/flush; this method only applies the release rule above.
        ArrayList<StagedOp> ops = null;
        synchronized (island.pendingArrivals) {
            if (!island.pendingArrivals.isEmpty()) {
                long destNow = island.logicalTick;
                int take = fabrolia$eligiblePrefix(island.pendingArrivals, destNow, island.arrivalChains);
                if (take > 0) {
                    RegionizedServer destRs = RegionScheduler.forLevel(island.domainLevel());
                    ops = fabrolia$gatedExtract(island.pendingArrivals, take,
                            destRs != null ? destRs.platformHandoff : null);
                }
            } else {
                island.arrivalChains.clear(); // idle: next arrival re-anchors
            }
        }
        publish(ops);
    }

    /** audit13 — atomic global-lane batch append (package-private for the unit
     *  test). Every producer of the lane MUST come through here: a bare
     *  {@code ConcurrentLinkedQueue.addAll} is per-element, so two concurrent
     *  sealers' batches interleave and a batch appended inside publishGlobal's
     *  drain→requeue window overtakes the gate-held head — both fragment the
     *  contiguous (sourceDomain, sourceTick) runs the cohort model depends on. */
    static void appendGlobalLane(java.util.concurrent.ConcurrentLinkedQueue<StagedOp> lane,
                                 Object lock, java.util.List<StagedOp> ops) {
        synchronized (lock) {
            lane.addAll(ops);
        }
    }

    /**
     * audit13 — the drain → release-rule → gate → requeue step of the global
     * lane, as ONE atomic window under {@code lock} (the same monitor every
     * batch append takes). Returns the ops to publish now (null = none); held
     * head + untaken tail are back in the lane, FIFO preserved, before the
     * monitor is released. The lock is NEVER held across publication — the
     * caller publishes the returned batch afterwards. Package-private for the
     * unit test (cohort-integrity battery drives it against concurrent
     * appends with plain collections).
     */
    static ArrayList<StagedOp> takeGlobalBatch(
            java.util.concurrent.ConcurrentLinkedQueue<StagedOp> lane, Object lock,
            java.util.HashMap<Long, long[]> chains, long destNow,
            EndPlatformHandoff.Tracker tracker) {
        synchronized (lock) {
            if (lane.isEmpty()) {
                chains.clear(); // idle: next arrival re-anchors
                return null;
            }
            ArrayList<StagedOp> pending = new ArrayList<>(8);
            StagedOp op;
            while ((op = lane.poll()) != null) {
                pending.add(op);
            }
            int take = fabrolia$eligiblePrefix(pending, destNow, chains);
            ArrayList<StagedOp> ops = null;
            if (take > 0) {
                // Handoff gate for the global lane too: an End platform in a
                // global-owned (island-less) destination has the same airborne-
                // cohort hazard. Held ops stay at the head of `pending`.
                ops = fabrolia$gatedExtract(pending, take, tracker);
            }
            for (int i = 0; i < pending.size(); i++) {
                lane.add(pending.get(i)); // FIFO preserved: held head + tail re-queued
            }
            return ops;
        }
    }

    /** Publish the level's global-lane arrivals. Main thread, pre-dispatch. */
    public static void publishGlobal(RegionizedServer rs) {
        long destNow = IslandClock.wallTime(rs.level);
        ArrayList<StagedOp> ops = takeGlobalBatch(rs.globalArrivals, rs.globalArrivalsLock,
                rs.globalArrivalChains, destNow, rs.platformHandoff);
        publish(ops);
    }

    private static void publish(ArrayList<StagedOp> ops) {
        if (ops == null || ops.isEmpty()) {
            return;
        }
        PUBLISHED_BATCHES.incrementAndGet();
        PUBLISHED_OPS.addAndGet(ops.size());
        for (StagedOp op : ops) {
            runPublished(op.op());
        }
    }

    /** Package-private for the unit test (nested-publish flag scope). */
    static void runPublished(Runnable op) {
        // audit11: the finally used to RESET the flag to FALSE rather than
        // restore it — a nested publish cleared PUBLISHING for its caller, so
        // the remainder of the outer op re-entered the staging mixins and got
        // re-queued: a machine-generated cohort split. Save and restore.
        Boolean prevPublishing = PUBLISHING.get();
        PUBLISHING.set(Boolean.TRUE);
        try {
            op.run();
        } catch (Throwable t) {
            LOGGER.error("cross-dimension arrival op failed", t);
        } finally {
            PUBLISHING.set(prevPublishing);
        }
    }

    /** End index (inclusive) of the cohort starting at {@code i}: the maximal
     *  contiguous run sharing (sourceDomain, sourceTick); unknown epochs
     *  (negative sourceTick) are singletons — the same grouping the release
     *  rule and the handoff gate use. Package-private for the unit test. */
    static int cohortEnd(java.util.List<StagedOp> ops, int i) {
        long tick = ops.get(i).sourceTick();
        if (tick < 0) {
            return i;
        }
        long dom = ops.get(i).sourceDomain();
        int j = i;
        while (j + 1 < ops.size() && ops.get(j + 1).sourceTick() == tick
                && ops.get(j + 1).sourceDomain() == dom) {
            j++;
        }
        return j;
    }

    /** Topology rebuild carry: a retiring/re-shaped island's queued arrivals
     *  re-route by current ownership — never lost, order preserved.
     *
     *  <p>MUST run AFTER the rebuild has committed the new topology (retired
     *  flags set, region domains re-pointed, cell snapshot republished) — see
     *  the audit13 ordering note at the call site in
     *  {@link RegionizedServer#rebuildIslandsIfDirty}: rerouting BEFORE the
     *  commit re-resolved every op to the OLD owner (the drained island
     *  itself, {@code retired} still false), so a retiring/absorbed island's
     *  queue was drained and re-enqueued onto itself, then retired — stranded
     *  forever.
     *
     *  <p>audit13: routes COHORT-WISE, not the whole drained queue as one
     *  batch. {@code route()} resolves ONE owner per destination level from a
     *  batch's FIRST op — correct for a sealed cohort (one activation, one
     *  chunk area) but wrong for a drained queue spanning many chunks: after
     *  a SPLIT, ops belonging to the other fragment were delivered to — and
     *  published under — the wrong island's claim, an off-owner mutation of
     *  the true owner's structures. Per-cohort routing preserves both the
     *  one-publish-boundary-per-cohort invariant and per-owner FIFO. */
    public static void reroute(RegionizedServer rs, IslandRegistry.PIsland retiring) {
        ArrayList<StagedOp> ops = null;
        synchronized (retiring.pendingArrivals) {
            if (!retiring.pendingArrivals.isEmpty()) {
                ops = new ArrayList<>(retiring.pendingArrivals);
                retiring.pendingArrivals.clear();
            }
        }
        if (ops != null) {
            REROUTED.addAndGet(ops.size());
            int i = 0;
            while (i < ops.size()) {
                int j = cohortEnd(ops, i);
                route(new ArrayList<>(ops.subList(i, j + 1)));
                i = j + 1;
            }
        }
    }

    /**
     * IN-FLIGHT LEASE (2026-07-18 portal-link architecture): every pending
     * arrival pins its destination chunk with the SAME ticket vanilla's
     * synchronous teleport places (PORTAL, radius 3, wall-timed), re-armed
     * each main tick while the cohort waits. Vanilla never has a gap between
     * departure and arrival; the async model reintroduces one whenever the
     * destination island lags — without the lease, wall-clock ticket expiry
     * could unload the endpoint under the pending cohort (portal chunk
     * loaders with one lagging side died exactly this way: renewal traffic
     * ran on island cadence, expiry on wall cadence). The lease holds for as
     * long as — and ONLY as long as — traffic is actually in flight, so no
     * cadence coupling and no cross-island stalls; cycles of links each pin
     * only their own endpoints. Main-only.
     */
    public static void rearmPendingLeases(RegionizedServer rs) {
        // Handoff-tracker housekeeping (main, wall-paced, cheap): purge settled
        // members even if the machine never queries that chunk again, so a
        // stopped duper's last cohort is not strongly retained. Unconditional —
        // tracked state may outlive an async ON→OFF flip.
        rs.platformHandoff.sweep();
        if (!rs.asyncActive) return;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet done =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(8);
        for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
            java.util.ArrayList<StagedOp> pend = p.pendingArrivals;
            // isEmpty() must be read under the same monitor every mutator uses; a
            // stale size skipped the whole island's PORTAL lease re-arm for a tick,
            // which is exactly the expiry this lease exists to prevent.
            synchronized (pend) {
                if (pend.isEmpty()) continue;
                for (StagedOp op : pend) {
                    if (done.add(op.chunkKey())) {
                        op.dest().getChunkSource().addTicketWithRadius(
                                net.minecraft.server.level.TicketType.PORTAL,
                                new net.minecraft.world.level.ChunkPos(net.minecraft.world.level.ChunkPos.getX(op.chunkKey()), net.minecraft.world.level.ChunkPos.getZ(op.chunkKey())), 3);
                    }
                }
            }
        }
        // task #26 lease completion (portal-minecart audit): the contract is
        // "every pending arrival pins its destination chunk" — but only the
        // ISLAND lanes were walked. Two uncovered in-flight populations:
        //
        // (a) The GLOBAL lane. Its paced tail (publishGlobal re-queues what
        //     the release rule holds back) waits on wall epochs with no
        //     renewal of its own — a global-owned destination (a player-less
        //     nether portal pad) could expire and unload UNDER the queued
        //     entity, which then publishes into an unloaded chunk and goes
        //     inert in pending-entity storage until a player wanders by.
        for (StagedOp op : rs.globalArrivals) {
            if (op.dest() == rs.level && done.add(op.chunkKey())) {
                rs.level.getChunkSource().addTicketWithRadius(
                        net.minecraft.server.level.TicketType.PORTAL,
                        new net.minecraft.world.level.ChunkPos(
                                net.minecraft.world.level.ChunkPos.getX(op.chunkKey()),
                                net.minecraft.world.level.ChunkPos.getZ(op.chunkKey())), 3);
            }
        }
        // (b) The SOURCE chunk of every queued PTM handoff: the entity is
        //     frozen portal-pending IN the source world until main drains the
        //     transfer — an unload there tears the transaction (the entity
        //     saves to disk mid-handoff and the transfer silently aborts).
        //     Vanilla has no such window (teleport is synchronous); the pin
        //     restores the vanilla guarantee for exactly as long as the
        //     window exists.
        com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager
                .forEachPendingSourceChunk(rs.level, key -> {
                    if (done.add(key)) {
                        rs.level.getChunkSource().addTicketWithRadius(
                                net.minecraft.server.level.TicketType.PORTAL,
                                new net.minecraft.world.level.ChunkPos(
                                        net.minecraft.world.level.ChunkPos.getX(key),
                                        net.minecraft.world.level.ChunkPos.getZ(key)), 3);
                    }
                });
        // (c) task #26 async-island fix: the chunk of every PORTAL-ACTIVE entity
        //     (inside a portal, or on cooldown between crossings). A portal
        //     loader's destination chunk is held only by the PORTAL ticket the
        //     last crossing placed (300 MAIN ticks); the NEXT crossing that would
        //     renew it is produced by the loader ISLAND'S tick, so a starved /
        //     below-wall-rate island lets the wall-timed ticket lapse and unload
        //     the chunk under the cart — the async-islands-only portal-loader
        //     break (async off ticks the whole level per main tick, so the rate
        //     gap never opens). Re-arming here (main, wall-paced) keeps the chunk
        //     loaded while any entity is actively portaling, independent of the
        //     island's rate — vanilla's guarantee restored.
        com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager
                .forEachPortalActiveChunk(rs.level, key -> {
                    if (done.add(key)) {
                        rs.level.getChunkSource().addTicketWithRadius(
                                net.minecraft.server.level.TicketType.PORTAL,
                                new net.minecraft.world.level.ChunkPos(
                                        net.minecraft.world.level.ChunkPos.getX(key),
                                        net.minecraft.world.level.ChunkPos.getZ(key)), 3);
                    }
                });
    }

    /** Save/stop/collapse flush: staged or queued entities exist in NO level
     *  and are invisible to entity saving — publish everything synchronously.
     *
     *  <p>PRECONDITION: the caller is the EXCLUSIVE owner of every level (all
     *  island claims held, or the worker pool stopped). Only the stop and
     *  topology-rebuild paths satisfy this. The per-level autosave path does
     *  NOT — it holds one level's claims — and must use {@link #flushLevel}
     *  instead (task #26: publishing another level's arrivals here executed
     *  {@code addDuringTeleport} into an un-quiesced destination from main
     *  while that level's worker ticked it — a cross-thread mutation of the
     *  destination entity lookup/sections that half-registered the arrival:
     *  visible and island-ticked, but not correctly in its chunk section, so a
     *  portal minecart could not engage its rail and drifted to rest inside
     *  the portal, where vanilla's cooldown re-arm traps it until the chunk
     *  reloads from NBT). */
    /**
     * Publish every in-flight arrival across {@code levels}.
     *
     * <p>The level list is a REQUIRED PARAMETER, deliberately. This used to read
     * {@code RegionScheduler.allLevels()} itself, and both of its call sites are
     * inside {@code RegionScheduler.stop()} AFTER {@code active = false} — at
     * which point that accessor returns an empty collection by design. So both
     * loops iterated nothing and the entire shutdown flush was a silent no-op:
     * staged arrivals were dropped by the {@code PER_LEVEL.clear()} in stop()'s
     * finally, while their source copies had already been removed with
     * {@code CHANGED_DIMENSION} (which vanilla does not save). That is permanent
     * entity loss on every stop, and on {@code /fabrolia regions off}.
     *
     * <p>The 2026-07-21 shutdown-order fix captured {@code stopLevels} for exactly
     * this reason and threaded it into {@code retainFinalClocks} and
     * {@code collapseAllToWall} — this method was missed. Taking the list as an
     * argument (rather than restoring a no-arg convenience overload) is what stops
     * the footgun coming back.
     *
     * <p>Two passes so delivery is independent of level iteration order: a
     * cross-level op staged by a later-iterated level, destined for an earlier
     * one, would otherwise route into that destination's queue AFTER it had
     * already published — stranded. Safe to seal every domain up front here: the
     * caller owns all levels exclusively (no worker races the seals).
     */
    public static void flushAll(java.util.Collection<RegionizedServer> levels) {
        long before = FLUSHED_AT_SAVE.get();
        for (RegionizedServer rs : levels) {
            seal(rs.globalDomain);
            for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
                seal(p.domain);
            }
        }
        for (RegionizedServer rs : levels) {
            publishLevelPending(rs);
        }
        long flushed = FLUSHED_AT_SAVE.get() - before;
        if (flushed > 0) {
            LoggerFactory.getLogger("Fabrolia-XDim").info(
                    "Published {} in-flight cross-dimension arrival(s) across {} level(s) at "
                            + "shutdown/teardown", flushed, levels.size());
        }
    }

    /** Publish exactly ONE level's in-flight arrivals — used by the per-level
     *  save hook, which quiesces only that level. Seals only THIS level's own
     *  source-side staging (safe: this level is quiesced, no worker races the
     *  seal) and publishes only THIS level's destination arrivals (safe:
     *  {@code addDuringTeleport} mutates only the quiesced level). Arrivals
     *  this level STAGED for other destinations route to those destinations'
     *  pending queues and are published under their own save/quiesce — never
     *  cross-thread. Durability holds: every level materializes its own
     *  destination arrivals during its own save pass of the same autosave
     *  cycle; anything still staged elsewhere is rewind-safe (a crash reverts
     *  to the pre-teleport source entity in the last save) and is delivered by
     *  normal publication within a few ticks. */
    public static void flushLevel(RegionizedServer rs) {
        // Seal this level's own open stage (source-side) into its destinations'
        // pending queues. Concurrent-collection moves only — route() never
        // mutates world/entity structures (its sole inline branch is for
        // scheduler-less levels, which have no islands to save). Sealing only
        // THIS level's domains is what keeps it race-free: this level is
        // quiesced, so no worker is concurrently sealing the same domains.
        seal(rs.globalDomain);
        for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
            seal(p.domain);
        }
        publishLevelPending(rs);
    }

    /** Publish (into their now-quiesced destination level) every arrival queued
     *  for {@code rs} — its islands' pending cohorts and its global lane. No
     *  sealing: callers seal first (per their ownership scope). */
    private static void publishLevelPending(RegionizedServer rs) {
        for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
            ArrayList<StagedOp> ops = null;
            synchronized (p.pendingArrivals) {
                if (!p.pendingArrivals.isEmpty()) {
                    ops = new ArrayList<>(p.pendingArrivals);
                    p.pendingArrivals.clear();
                }
            }
            if (ops != null) {
                FLUSHED_AT_SAVE.addAndGet(ops.size());
                // audit13: a flush publishes gate-HELD machine cohorts by
                // design (delivery beats entity loss under a quiesced save) —
                // but their falling-block members must still be REGISTERED as
                // airborne, or the machine's NEXT cohort finds no record and
                // releases mid-fall (window ≈ one hold per save per level).
                EndPlatformHandoff.trackAll(fabrolia$gateView(ops, ops.size()),
                        rs.platformHandoff);
                publish(ops);
            }
            synchronized (p.pendingArrivals) {
                p.arrivalChains.clear();
            }
        }
        // Global lane force-drain: the paced publishGlobal may hold a spacing
        // tail — a flush must deliver EVERYTHING (entities queued here exist in
        // no level and would be lost by a save). Drained under the lane's
        // ordering monitor so a concurrent route() batch (another level's
        // worker sealing into this level) is taken whole or left whole —
        // never a fragment of a cohort.
        ArrayList<StagedOp> gops = new ArrayList<>(8);
        synchronized (rs.globalArrivalsLock) {
            StagedOp gop;
            while ((gop = rs.globalArrivals.poll()) != null) {
                gops.add(gop);
            }
            rs.globalArrivalChains.clear();
        }
        if (!gops.isEmpty()) {
            FLUSHED_AT_SAVE.addAndGet(gops.size());
            EndPlatformHandoff.trackAll(fabrolia$gateView(gops, gops.size()),
                    rs.platformHandoff);
            publish(gops);
        }
    }

    public static String status() {
        return "xdim: stagedEntities=" + STAGED_ENTITIES.get()
                + " stagedRegens=" + STAGED_REGENS.get()
                + " cohortsSealed=" + COHORTS_SEALED.get()
                + " cohortMembers=" + COHORT_MEMBERS.get()
                + " maxCohort=" + MAX_COHORT.get()
                + " publishedBatches=" + PUBLISHED_BATCHES.get()
                + " publishedOps=" + PUBLISHED_OPS.get()
                + " rerouted=" + REROUTED.get()
                + " flushedAtSave=" + FLUSHED_AT_SAVE.get();
    }
}
