package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
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

    /** Synthetic lease strength by purpose. Destination publication keeps
     * vanilla's radius-3 PORTAL semantics. A live portal-active entity needs
     * only its own chunk entity-ticking (radius 2); a frozen source handoff
     * needs loading only (radius 1). The narrower source radii must never
     * propagate ENTITY_TICKING into neighbouring stasis machinery. */
    static final int DESTINATION_LEASE_RADIUS = 3;
    static final int PORTAL_ACTIVE_LEASE_RADIUS = 2;
    static final int PENDING_SOURCE_LEASE_RADIUS = 1;

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
                           long sourceTick, long sourceDomain, Entity entity,
                           RouteReceipt routeReceipt) {
        public StagedOp {
            if (routeReceipt == null) routeReceipt = RouteReceipt.ACCEPTED_RECEIPT;
        }

        public StagedOp(ServerLevel dest, long chunkKey, Runnable op, String kind,
                        long sourceTick, long sourceDomain, Entity entity) {
            this(dest, chunkKey, op, kind, sourceTick, sourceDomain, entity,
                    RouteReceipt.ACCEPTED_RECEIPT);
        }

        public StagedOp(ServerLevel dest, long chunkKey, Runnable op, String kind,
                        long sourceTick, long sourceDomain) {
            this(dest, chunkKey, op, kind, sourceTick, sourceDomain, null,
                    RouteReceipt.ACCEPTED_RECEIPT);
        }

        StagedOp withRouteReceipt(RouteReceipt receipt) {
            return new StagedOp(dest, chunkKey, op, kind, sourceTick, sourceDomain,
                    entity, receipt);
        }
    }

    /** Queue visibility receipt shared by every destination bucket of one seal. */
    static final class RouteReceipt {
        private static final int OFFERING = 0;
        private static final int ACCEPTED = 1;
        private static final int CANCELLED = 2;
        private static final RouteReceipt ACCEPTED_RECEIPT =
                new RouteReceipt(ACCEPTED);

        private final java.util.concurrent.atomic.AtomicInteger state;

        private RouteReceipt() {
            this(OFFERING);
        }

        private RouteReceipt(int initial) {
            state = new java.util.concurrent.atomic.AtomicInteger(initial);
        }

        void accept() {
            if (!state.compareAndSet(OFFERING, ACCEPTED)) {
                throw new IllegalStateException("cross-dimension route was already decided");
            }
        }

        void cancel() {
            state.compareAndSet(OFFERING, CANCELLED);
        }

        boolean awaitAccepted() {
            int observed;
            while ((observed = state.get()) == OFFERING) {
                Thread.onSpinWait();
            }
            return observed == ACCEPTED;
        }
    }

    /**
     * A fully offered but not yet executable cohort.  Destination phase heads
     * may already hold its queue nodes, but every one waits on the shared
     * receipt until the source unit commits.  Cancellation therefore removes
     * the entire unentered cohort logically even after an offer-then-throw.
     */
    static final class PreparedSeal {
        private static final PreparedSeal EMPTY = new PreparedSeal(null, 0);

        private final RouteReceipt receipt;
        private final int members;
        private NetherSourceCohorts.Prepared<?> nether;

        private PreparedSeal(RouteReceipt receipt, int members) {
            this.receipt = receipt;
            this.members = members;
        }

        void commit() {
            if (nether != null) nether.commit();
            if (receipt == null) return;
            receipt.accept();
            COHORTS_SEALED.incrementAndGet();
            COHORT_MEMBERS.addAndGet(members);
            MAX_COHORT.accumulateAndGet(members, Math::max);
        }

        Throwable cancel(Throwable primary) {
            Throwable failure = selectFailure(primary);
            if (nether != null) failure = nether.cancel(failure);
            if (receipt != null) {
                try {
                    receipt.cancel();
                } catch (Throwable cleanup) {
                    failure = selectFailure(mergeFailure(failure, cleanup));
                }
            }
            return failure;
        }
    }

    /** Re-entrancy latch: publish-time execution must run the REAL vanilla
     *  paths (the staging mixins pass through while set). */
    public static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);

    /** Source simulation {epoch, clock domain} carried across an exact Nether
     *  body whose between-unit worker has no current region/domain. Nested
     *  staging must not replace this source identity with a global wall tick. */
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

    /**
     * Logout durability bridge for pearl arrival copies which already exist
     * but have not reached their destination phase head yet.
     *
     * <p>PRECONDITION: the caller owns the all-level island fence. Never
     * publish or reorder an arrival here: {@link #flushAll} is deliberately a
     * shutdown/teardown operation and bypasses the normal cohort release/gate.
     * A cross-dimension pearl copy can nevertheless sit in an open/sealed
     * arrival queue while its source has already been removed. Because its
     * owner is in another level, vanilla's destination-side setOwner lookup
     * cannot register that copy until its first tick. A concurrent player
     * logout would then serialize neither source nor destination.
     *
     * <p>The union also includes the hidden-aware loaded-pearl index. An
     * arrival may have been published into PSEM at unit head and then miss its
     * first entity tick because scheduled-tick admission held that unit; it is
     * no longer queued, but still has not run vanilla's owner-registration
     * renewal. Registering these already-created identities is side-effect-free
     * for the arrival queues and makes the ordinary playerdata save
     * authoritative. UWP removal marks a queued copy removed; its eventual
     * publication op observes that state and aborts. Incomplete portal tasks
     * have no arrival copy yet, so their still-live source remains the
     * serialized authority.
     */
    public static int registerPendingPearls(ServerPlayer owner) {
        int[] registered = new int[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> registered[0] = fabrolia$registerPendingPearlsEntered(owner));
        return registered[0];
    }

    private static int fabrolia$registerPendingPearlsEntered(ServerPlayer owner) {
        java.util.UUID ownerUuid = owner.getUUID();
        java.util.IdentityHashMap<ThrownEnderpearl, Boolean> seen =
                new java.util.IdentityHashMap<>();
        for (RegionizedServer rs : new java.util.ArrayList<>(RegionScheduler.allLevels())) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                Object manager =
                        ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor)
                                rs.level).fabrolia$entityManager();
                ((com.axalotl.fabrolia.common.parallelised.utils.LoadedPearlIndexAccess) manager)
                        .fabrolia$forEachResidentPearl(
                                pearl -> fabrolia$registerPearlIfHealthy(
                                        pearl, owner, ownerUuid, seen));
            });
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> fabrolia$registerPendingPearls(
                            rs.globalDomain.crossDimStage, owner, ownerUuid, seen));
            for (IslandRegistry.PIsland island : rs.islandRegistry.islands()) {
                RegionScheduler.runSuccessBodyIfHealthy(
                        () -> fabrolia$registerPendingPearls(
                                island.domain.crossDimStage, owner, ownerUuid, seen));
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    synchronized (island.pendingArrivals) {
                        fabrolia$registerPendingPearls(
                                island.pendingArrivals, owner, ownerUuid, seen);
                    }
                });
            }
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                synchronized (rs.globalArrivalsLock) {
                    fabrolia$registerPendingPearls(
                            rs.globalArrivals, owner, ownerUuid, seen);
                }
            });
        }
        return seen.size();
    }

    private static void fabrolia$registerPendingPearls(
            Iterable<StagedOp> ops, ServerPlayer owner, java.util.UUID ownerUuid,
            java.util.IdentityHashMap<ThrownEnderpearl, Boolean> seen) {
        for (StagedOp op : ops) {
            if (op.entity() instanceof ThrownEnderpearl pearl) {
                fabrolia$registerPearlIfHealthy(pearl, owner, ownerUuid, seen);
            }
        }
    }

    private static void fabrolia$registerPearlIfHealthy(
            ThrownEnderpearl pearl, ServerPlayer owner, java.util.UUID ownerUuid,
            java.util.IdentityHashMap<ThrownEnderpearl, Boolean> seen) {
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> fabrolia$registerPearl(pearl, owner, ownerUuid, seen));
    }

    private static void fabrolia$registerPearl(
            ThrownEnderpearl pearl, ServerPlayer owner, java.util.UUID ownerUuid,
            java.util.IdentityHashMap<ThrownEnderpearl, Boolean> seen) {
        if (pearl.isRemoved() || seen.containsKey(pearl)) {
            return;
        }
        EntityReference<Entity> reference =
                ((com.axalotl.fabrolia.common.mixin.entity.ProjectileOwnerAccessor)
                        (Object) pearl).fabrolia$getOwnerReference();
        if (reference == null || !ownerUuid.equals(reference.getUUID())) {
            return;
        }
        seen.put(pearl, Boolean.TRUE);
        owner.registerEnderPearl(pearl);
    }

    /** Is this destination chunk owned by an ISLAND domain (vs global/main)?
     *  Used by the staging mixin's main-thread decision (package access to
     *  Region/EpochDomain internals). */
    public static boolean islandOwnedDestination(RegionizedServer destRs, long chunkKey) {
        return destRs.islandOwnerOfChunk(chunkKey) != null;
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
     *  the destination owner. Called only from a healthy source-phase tail
     *  (island unit / main entity batch); a failed vanilla body never releases
     *  the partial stage as successor work. */
    public static void seal(EpochDomain d) {
        PreparedSeal prepared = prepareSeal(d);
        commitPreparedSeal(prepared);
    }

    /** Offer every bucket but keep it non-executable until {@link PreparedSeal#commit()}. */
    static PreparedSeal prepareSeal(EpochDomain d) {
        NetherSourceCohorts.Prepared<?> nether = NetherPortalTransfers.prepareSeal(d);
        if ((d == null || d.crossDimStage.isEmpty()) && nether.isEmpty()) return PreparedSeal.EMPTY;
        PreparedSeal prepared = null;
        try {
            ArrayList<StagedOp> batch = new ArrayList<>(8);
            if (d != null) {
                StagedOp op;
                while ((op = d.crossDimStage.poll()) != null) batch.add(op);
            }
            prepared = batch.isEmpty() ? new PreparedSeal(null, 0) : prepareRoute(batch);
            prepared.nether = nether;
            return prepared;
        } catch (Throwable thrown) {
            Throwable failure = selectFailure(thrown);
            if (prepared != null) failure = prepared.cancel(failure);
            failure = nether.cancel(failure);
            CrossDimArrivals.<RuntimeException>rethrow(failure);
            return PreparedSeal.EMPTY;
        }
    }


    /**
     * The offered cohort and its success telemetry become visible in the same
     * generation cut.  If W won first, cancel the still-OFFERING receipt and
     * rethrow the exact selected object; no destination body can enter.
     */
    private static void commitPreparedSeal(PreparedSeal prepared) {
        Throwable failure = null;
        try {
            if (RegionScheduler.isActive()) {
                RegionScheduler.commitSuccessIfHealthy(prepared::commit);
            } else {
                prepared.commit();
            }
            return;
        } catch (Throwable thrown) {
            failure = selectFailure(thrown);
        }
        failure = prepared.cancel(failure);
        CrossDimArrivals.<RuntimeException>rethrow(selectFailure(failure));
    }

    private record RouteTarget(IslandRegistry.PIsland owner, ArrayList<StagedOp> ops) {}

    private static PreparedSeal prepareRoute(ArrayList<StagedOp> batch) {
        RouteReceipt receipt = new RouteReceipt();
        try {
            ArrayList<StagedOp> offered = new ArrayList<>(batch.size());
            for (StagedOp op : batch) offered.add(op.withRouteReceipt(receipt));
            ArrayList<StagedOp> inline = offerRoutes(offered, false);
            // Queued destinations remain OFFERING until all inline work succeeds.
            if (inline != null) {
                for (StagedOp op : inline) {
                    RegionScheduler.runSuccessBodyIfHealthy(() -> runPublished(op.op()));
                }
            }
            return new PreparedSeal(receipt, batch.size());
        } catch (Throwable failure) {
            Throwable winner = selectFailure(failure);
            try {
                receipt.cancel();
            } catch (Throwable cleanup) {
                winner = selectFailure(mergeFailure(winner, cleanup));
            }
            CrossDimArrivals.<RuntimeException>rethrow(winner);
            return PreparedSeal.EMPTY;
        }
    }

    /**
     * A snapshot is a routing hint, not permission to run on Main. Re-resolve
     * each whole destination batch under the topology admission cut. A foreign
     * router observing an in-progress topology retains the batch for Main;
     * it never waits for the rebuild while holding its source claim.
     */
    private static ArrayList<StagedOp> offerRoutes(
            ArrayList<StagedOp> ordered, boolean prepend) {
        java.util.IdentityHashMap<ServerLevel, RegionizedServer> destinations =
                new java.util.IdentityHashMap<>();
        java.util.IdentityHashMap<RegionizedServer, ArrayList<StagedOp>> batches =
                new java.util.IdentityHashMap<>();
        ArrayList<StagedOp> inline = null;
        for (StagedOp op : ordered) {
            if (!destinations.containsKey(op.dest())) {
                destinations.put(op.dest(), RegionScheduler.forLevel(op.dest()));
            }
            RegionizedServer destination = destinations.get(op.dest());
            if (destination == null) {
                if (inline == null) inline = new ArrayList<>();
                inline.add(op);
            } else {
                batches.computeIfAbsent(destination, rs -> new ArrayList<>()).add(op);
            }
        }
        for (var entry : batches.entrySet()) {
            RegionizedServer destination = entry.getKey();
            destination.enterTrackingTopologyRouting();
            try {
                synchronized (destination.globalArrivalsLock) {
                    var transition = destination.trackingTopologyTransitionReceipt();
                    // Once a prefix is retained, later seals follow it. Otherwise
                    // fresh direct offers could overtake the older transport lane.
                    var current = (transition != null && transition.owner != Thread.currentThread())
                            || (!prepend && !destination.globalArrivals.isEmpty())
                            ? null : currentTargets(destination, entry.getValue());
                    ArrayList<StagedOp> global = current == null
                            ? entry.getValue() : offerCurrentTargets(current, prepend);
                    if (global != null) {
                        if (prepend) {
                            prependGlobalLane(destination.globalArrivals,
                                    destination.globalArrivalsLock, global);
                        } else {
                            appendGlobalLane(destination.globalArrivals,
                                    destination.globalArrivalsLock, global);
                        }
                    }
                }
            } finally {
                destination.exitTrackingTopologyRouting();
            }
        }
        return inline;
    }

    /** Caller holds routing admission and the level's transport monitor. */
    private static ArrayList<RouteTarget> currentTargets(
            RegionizedServer destination, ArrayList<StagedOp> ordered) {
        var snapshot = destination.arrivalOwnerSnapshot();
        java.util.IdentityHashMap<IslandRegistry.PIsland, ArrayList<StagedOp>> buckets =
                new java.util.IdentityHashMap<>();
        for (StagedOp op : ordered) {
            EpochDomain domain = snapshot == null ? null
                    : snapshot.get(destination.regionizer().grid.keyFor(op.chunkKey()));
            IslandRegistry.PIsland owner = domain == null ? null : domain.owner;
            // The retirement-to-snapshot window is not global ownership.
            if (owner != null && owner.retired) return null;
            buckets.computeIfAbsent(owner, p -> new ArrayList<>()).add(op);
        }
        // Bound even adversarial snapshot replacement: retain the WHOLE batch,
        // never retry a partially offered set or spin waiting for topology.
        if (destination.arrivalOwnerSnapshot() != snapshot) return null;
        ArrayList<RouteTarget> targets = new ArrayList<>(buckets.size());
        for (var entry : buckets.entrySet()) {
            targets.add(new RouteTarget(entry.getKey(), entry.getValue()));
        }
        return targets;
    }

    /** No receipt waits or destination bodies under either routing monitor. */
    private static ArrayList<StagedOp> offerCurrentTargets(
            ArrayList<RouteTarget> targets, boolean prepend) {
        ArrayList<StagedOp> global = null;
        for (RouteTarget target : targets) {
            IslandRegistry.PIsland owner = target.owner();
            if (owner == null) {
                global = target.ops();
            } else {
                synchronized (owner.pendingArrivals) {
                    if (prepend) owner.pendingArrivals.addAll(0, target.ops());
                    else if (!enqueueUnlessRetired(owner, target.ops())) {
                        throw new IllegalStateException("arrival owner retired inside topology admission");
                    }
                }
            }
        }
        return global;
    }

    /**
     * audit11 — retire-in-route-window cohort loss: the {@code owner.retired}
     * check used to run OUTSIDE any ordering guarantee with {@link #reroute}.
     * Ops enqueued after reroute's drain landed in a dead island's queue and
     * were unreachable forever ({@code publishLevelPending} iterates only live
     * islands). Re-checking {@code retired} INSIDE the {@code pendingArrivals}
     * monitor — the same monitor reroute drains under — closes the race: either
     * the ops are in the list when reroute drains them, or the writer observes
     * {@code retired} and the caller retains the cohort for re-resolution.
     * Package-private for the unit test.
     *
     * @return true if the ops were queued on a live island; false = the island
     *         retired first and the caller must re-resolve before publication.
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
        int[] published = new int[1];
        // Cohort detachment and every member's vanilla destination body are one
        // admitted unit.  A plain health read before pendingArrivals removal
        // could strand an accepted cohort when peer W wins in the gap; a second
        // per-member admission after removal could refuse the detached suffix.
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            ArrayList<StagedOp> ops = null;
            int capturedSize = -1;
            for (;;) {
                RouteReceipt offering;
                synchronized (island.pendingArrivals) {
                    if (capturedSize < 0) capturedSize = island.pendingArrivals.size();
                    // The claim excludes another consumer. Producers may append
                    // while we await an offer, but that tail belongs to the next head.
                    java.util.List<StagedOp> prefix =
                            island.pendingArrivals.subList(0, capturedSize);
                    offering = firstOffering(prefix);
                    if (offering == null) {
                        discardCancelledRoutes(prefix);
                        if (!prefix.isEmpty()) {
                            int take = fabrolia$eligiblePrefix(
                                    prefix, island.logicalTick, island.arrivalChains);
                            if (take > 0) {
                                RegionizedServer destRs =
                                        RegionScheduler.forLevel(island.domainLevel());
                                ops = fabrolia$gatedExtract(prefix, take,
                                        destRs != null ? destRs.platformHandoff : null);
                            }
                        } else {
                            island.arrivalChains.clear();
                        }
                        break;
                    }
                }
                // Never hold a queue monitor while waiting for the producer:
                // its remaining owner buckets may need that same monitor.
                offering.awaitAccepted();
            }
            published[0] = publishEntered(ops);
        });
        commitPublishMetrics(published[0]);
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

    private static void prependGlobalLane(ConcurrentLinkedQueue<StagedOp> lane,
                                          Object lock, java.util.List<StagedOp> ops) {
        if (ops.isEmpty()) return;
        synchronized (lock) {
            ArrayList<StagedOp> tail = new ArrayList<>(lane);
            lane.clear();
            lane.addAll(ops);
            lane.addAll(tail);
        }
    }

    private static RouteReceipt firstOffering(java.util.List<StagedOp> ops) {
        for (StagedOp op : ops) {
            if (op.routeReceipt().state.get() == RouteReceipt.OFFERING) return op.routeReceipt();
        }
        return null;
    }

    /**
     * Capture this head's prefix under the producer ordering monitor, await its
     * shared receipts outside the monitor, then release/gate/requeue exactly
     * that prefix. Main is the sole consumer; concurrent producer appends stay
     * behind held members and cannot enter this head's publication boundary.
     */
    static ArrayList<StagedOp> takeGlobalBatch(
            java.util.concurrent.ConcurrentLinkedQueue<StagedOp> lane, Object lock,
            java.util.HashMap<Long, long[]> chains, long destNow,
            EndPlatformHandoff.Tracker tracker) {
        return takeGlobalBatch(lane, lock, chains, destNow, tracker, null);
    }

    /**
     * Resolve the captured transport prefix BEFORE release-rule/handoff tracking.
     * A registered destination's owned members wait for their island head. The
     * runtime-free lane form above has no island ownership to resolve.
     */
    private static ArrayList<StagedOp> takeGlobalBatch(
            ConcurrentLinkedQueue<StagedOp> lane, Object lock,
            java.util.HashMap<Long, long[]> chains, long destNow,
            EndPlatformHandoff.Tracker tracker, RegionizedServer rs) {
        ArrayList<StagedOp> pending;
        synchronized (lock) {
            if (lane.isEmpty()) {
                chains.clear();
                return null;
            }
            pending = new ArrayList<>(lane);
        }
        int capturedSize = pending.size();
        discardCancelledRoutes(pending);
        if (rs != null) rs.enterTrackingTopologyRouting();
        try {
            synchronized (lock) {
                ArrayList<RouteTarget> targets = null;
                if (rs != null) {
                    var transition = rs.trackingTopologyTransitionReceipt();
                    if (transition != null && transition.owner != Thread.currentThread()) return null;
                    targets = currentTargets(rs, pending);
                    if (targets == null) return null;
                }
                for (int i = 0; i < capturedSize; i++) lane.remove();
                ArrayList<StagedOp> global = targets == null
                        ? pending : offerCurrentTargets(targets, false);
                if (global == null || global.isEmpty()) {
                    chains.clear();
                    return null;
                }
                int take = fabrolia$eligiblePrefix(global, destNow, chains);
                ArrayList<StagedOp> released = take > 0
                        ? fabrolia$gatedExtract(global, take, tracker) : null;
                prependGlobalLane(lane, lock, global);
                return released;
            }
        } finally {
            if (rs != null) rs.exitTrackingTopologyRouting();
        }
    }

    /** Publish only authoritatively global arrivals. Main thread, pre-dispatch. */
    public static void publishGlobal(RegionizedServer rs) {
        int[] published = new int[1];
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            long destNow = IslandClock.wallTime(rs.level);
            ArrayList<StagedOp> ops = takeGlobalBatch(rs.globalArrivals, rs.globalArrivalsLock,
                    rs.globalArrivalChains, destNow, rs.platformHandoff, rs);
            published[0] = publishEntered(ops);
        });
        commitPublishMetrics(published[0]);
    }

    /** Caller owns the one admission covering detachment and the whole cohort. */
    private static int publishEntered(ArrayList<StagedOp> ops) {
        if (ops == null || ops.isEmpty()) {
            return 0;
        }
        int published = 0;
        for (StagedOp op : ops) {
            if (!op.routeReceipt().awaitAccepted()) continue;
            // All members of one released activation cohort share the caller's
            // admission.  Do not insert a nested gate here: after detachment it
            // could refuse member N and split the vanilla-atomic cohort.
            runPublished(op.op());
            published++;
        }
        return published;
    }

    private static void commitPublishMetrics(int published) {
        // Success telemetry is a commit marker for the whole released cohort,
        // never a count of a prefix whose vanilla arrival body later failed.
        if (published != 0) {
            final int committed = published;
            if (RegionScheduler.isActive()) {
                RegionScheduler.commitSuccessIfHealthy(() -> {
                    PUBLISHED_BATCHES.incrementAndGet();
                    PUBLISHED_OPS.addAndGet(committed);
                });
            } else {
                PUBLISHED_BATCHES.incrementAndGet();
                PUBLISHED_OPS.addAndGet(committed);
            }
        }
    }

    /** Await each exact offer receipt and erase cancelled cohorts before any
     *  release-rule or handoff-tracker success state can observe them. */
    private static void discardCancelledRoutes(java.util.List<StagedOp> ops) {
        var it = ops.iterator();
        while (it.hasNext()) {
            if (!it.next().routeReceipt().awaitAccepted()) it.remove();
        }
    }

    /** Package-private for the unit test (nested-publish flag scope). */
    static void runPublished(Runnable op) {
        // audit11: the finally used to RESET the flag to FALSE rather than
        // restore it — a nested publish cleared PUBLISHING for its caller, so
        // the remainder of the outer op re-entered the staging mixins and got
        // re-queued: a machine-generated cohort split. Save and restore.
        Boolean prevPublishing = null;
        boolean previousRead = false;
        Throwable failure = null;
        try {
            prevPublishing = PUBLISHING.get();
            previousRead = true;
            PUBLISHING.set(Boolean.TRUE);
            op.run();
        } catch (Throwable thrown) {
            failure = selectFailure(thrown);
        }
        if (previousRead) {
            try {
                PUBLISHING.set(prevPublishing);
            } catch (Throwable cleanup) {
                failure = selectFailure(mergeFailure(failure, cleanup));
            }
        }
        if (failure != null) CrossDimArrivals.<RuntimeException>rethrow(failure);
    }

    private static Throwable selectFailure(Throwable candidate) {
        return RegionScheduler.isActive()
                ? RegionScheduler.publishFatalAndSelectWinner(candidate)
                : candidate;
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Diagnostic suppression cannot replace the first failure.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
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
     *  <p>Carry uses one topology cut for the entire old queue, preserving
     *  each inherited owner's FIFO and the ORIGINAL acceptance receipts.
     *  Inherited prefixes precede arrivals offered against the new topology.
     *  Main holds the affected claims; carry never waits for source acceptance
     *  or executes destination callbacks while holding queue monitors. */
    public static void reroute(RegionizedServer rs, IslandRegistry.PIsland retiring) {
        ArrayList<StagedOp> ops;
        synchronized (retiring.pendingArrivals) {
            if (retiring.pendingArrivals.isEmpty()) return;
            for (StagedOp op : retiring.pendingArrivals) {
                if (RegionScheduler.forLevel(op.dest()) != rs) {
                    throw new IllegalStateException("arrival carry lost its destination runtime");
                }
            }
            ops = new ArrayList<>(retiring.pendingArrivals);
            retiring.pendingArrivals.clear();
        }
        // Main owns the affected claims. Release the old queue before acquiring
        // routing admission / the transport monitor (producer order is the
        // opposite: admission -> transport -> destination queue).
        offerRoutes(ops, true);
        REROUTED.addAndGet(ops.size());
    }

    /**
     * IN-FLIGHT LEASE (2026-07-18 portal-link architecture): every pending
     * arrival pins its destination chunk with the SAME ticket vanilla's
     * synchronous teleport places (PORTAL, radius 3, wall-timed), re-armed
     * each main tick while the cohort waits. Synthetic source-side windows use
     * narrower radii below: they owe only the exact loading/simulation footprint
     * which vanilla's synchronous execution never has to preserve.
     *
     * <p>The async model reintroduces an endpoint gap whenever the destination
     * island lags. Without these leases wall-clock ticket expiry could unload
     * an endpoint under the pending cohort. Each lease holds for as long as —
     * and only as long as — its traffic is actually in flight, so there is no
     * cadence coupling or cross-island stall. Main-only.
     */
    public static void rearmPendingLeases(RegionizedServer rs) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$rearmPendingLeasesEntered(rs));
    }

    private static void fabrolia$rearmPendingLeasesEntered(RegionizedServer rs) {
        // Handoff-tracker housekeeping (main, wall-paced, cheap): purge settled
        // members even if the machine never queries that chunk again, so a
        // stopped duper's last cohort is not strongly retained. Unconditional —
        // tracked state may outlive an async ON→OFF flip.
        rs.platformHandoff.sweep();
        if (!rs.asyncActive) return;
        it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap armedRadius =
                new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(8);
        armedRadius.defaultReturnValue((byte) -1);
        for (IslandRegistry.PIsland p : rs.islandRegistry.islands()) {
            java.util.ArrayList<StagedOp> pend = p.pendingArrivals;
            // isEmpty() must be read under the same monitor every mutator uses; a
            // stale size skipped the whole island's PORTAL lease re-arm for a tick,
            // which is exactly the expiry this lease exists to prevent.
            java.util.ArrayList<StagedOp> pendingSnapshot;
            synchronized (pend) {
                if (pend.isEmpty()) continue;
                pendingSnapshot = new java.util.ArrayList<>(pend);
            }
            for (StagedOp op : pendingSnapshot) {
                RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$rearmLease(
                        armedRadius, op.dest(), op.chunkKey(), DESTINATION_LEASE_RADIUS));
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
            if (op.dest() == rs.level) {
                RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$rearmLease(
                        armedRadius, rs.level, op.chunkKey(), DESTINATION_LEASE_RADIUS));
            }
        }
        // (b) The SOURCE chunk of every queued PTM handoff: the entity is
        //     frozen portal-pending IN the source world until main drains the
        //     transfer — an unload there tears the transaction (the entity
        //     saves to disk mid-handoff and the transfer silently aborts).
        //     Vanilla has no such window, so this synthetic pin owes loading,
        //     not simulation. Radius 1 keeps the entry chunk plus its one-chunk
        //     footprint FULL without making any chunk ENTITY_TICKING.
        RegionScheduler.runSuccessBodyIfHealthy(() ->
                com.axalotl.fabrolia.common.regions.NetherPortalTransfers.forEachPendingSourceChunk(rs.level, key -> {
                    RegionScheduler.runSuccessBodyIfHealthy(() ->
                            fabrolia$rearmLease(armedRadius, rs.level, key,
                                    PENDING_SOURCE_LEASE_RADIUS));
                }));
        // (c) The chunk of every genuinely live PORTAL-ACTIVE entity. PTM
        //     excludes departed and portal-pending source identities: (b)
        //     already holds those frozen transactions. A live loader must keep
        //     ticking itself, but no vanilla invariant requires synthetic
        //     ENTITY_TICKING propagation into neighbouring chunks. Radius 2
        //     makes the exact entity chunk level 31 and its neighbours level 32.
        RegionScheduler.runSuccessBodyIfHealthy(() ->
                com.axalotl.fabrolia.common.regions.NetherPortalTransfers.forEachPortalActiveChunk(rs.level, key -> {
                    RegionScheduler.runSuccessBodyIfHealthy(() ->
                            fabrolia$rearmLease(armedRadius, rs.level, key,
                                    PORTAL_ACTIVE_LEASE_RADIUS));
                }));
    }

    private static void fabrolia$rearmLease(
            it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap armedRadius,
            ServerLevel level, long chunkKey, int radius) {
        int previousRadius = armedRadius.get(chunkKey);
        if (previousRadius >= radius) return;
        armedRadius.put(chunkKey, (byte) radius);
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.PORTAL,
                new net.minecraft.world.level.ChunkPos(
                        net.minecraft.world.level.ChunkPos.getX(chunkKey),
                        net.minecraft.world.level.ChunkPos.getZ(chunkKey)), radius);
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
            int[] published = new int[1];
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                ArrayList<StagedOp> ops = null;
                synchronized (p.pendingArrivals) {
                    if (!p.pendingArrivals.isEmpty()) {
                        ops = new ArrayList<>(p.pendingArrivals);
                        p.pendingArrivals.clear();
                    }
                }
                if (ops != null) {
                    discardCancelledRoutes(ops);
                    // audit13: a flush publishes gate-HELD machine cohorts by
                    // design (delivery beats entity loss under a quiesced save) —
                    // but their falling-block members must still be REGISTERED as
                    // airborne, or the machine's NEXT cohort finds no record and
                    // releases mid-fall (window ≈ one hold per save per level).
                    if (!ops.isEmpty()) {
                        EndPlatformHandoff.trackAll(fabrolia$gateView(ops, ops.size()),
                                rs.platformHandoff);
                        published[0] = publishEntered(ops);
                    }
                }
                synchronized (p.pendingArrivals) {
                    p.arrivalChains.clear();
                }
            });
            commitPublishMetrics(published[0]);
            if (published[0] != 0) {
                int committed = published[0];
                RegionScheduler.commitSuccessIfHealthy(
                        () -> FLUSHED_AT_SAVE.addAndGet(committed));
            }
        }
        // Global lane force-drain: the paced publishGlobal may hold a spacing
        // tail — a flush must deliver EVERYTHING (entities queued here exist in
        // no level and would be lost by a save). Drained under the lane's
        // ordering monitor so a concurrent route() batch (another level's
        // worker sealing into this level) is taken whole or left whole —
        // never a fragment of a cohort.
        int[] globalPublished = new int[1];
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            ArrayList<StagedOp> gops = new ArrayList<>(8);
            synchronized (rs.globalArrivalsLock) {
                StagedOp gop;
                while ((gop = rs.globalArrivals.poll()) != null) {
                    gops.add(gop);
                }
                rs.globalArrivalChains.clear();
            }
            if (!gops.isEmpty()) {
                discardCancelledRoutes(gops);
                if (!gops.isEmpty()) {
                    EndPlatformHandoff.trackAll(fabrolia$gateView(gops, gops.size()),
                            rs.platformHandoff);
                    globalPublished[0] = publishEntered(gops);
                }
            }
        });
        commitPublishMetrics(globalPublished[0]);
        if (globalPublished[0] != 0) {
            int committed = globalPublished[0];
            RegionScheduler.commitSuccessIfHealthy(
                    () -> FLUSHED_AT_SAVE.addAndGet(committed));
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
