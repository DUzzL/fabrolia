package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.entity.EntityNetherPortalInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * Nether requests leave their source tick as data, never as a Main teleport task.
 * The full source-unit success receipt makes a cohort executable. Top-level
 * workers try its entire ownership footprint and back out without waiting when
 * any owner or preparation receipt is unavailable. A cohort's first irreversible
 * teleport is entered only after every member has a transition under that cut.
 */
public final class NetherPortalTransfers {
    private static final int WAITING = 0;
    private static final int ENTERED = 1;
    private static final int BODY_DONE = 2;
    private static final int TERMINAL = 3;
    private static final int CANCELLED = 4;
    private static final ConcurrentHashMap<UUID, Transfer> SOURCES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ServerLevel, Source> FLAT_DOMAINS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<EpochDomain, Source> DOMAINS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ActiveChunk> ACTIVE_CHUNKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<IdentityHashMap<Entity, Transfer>> DEPARTURES =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static final ConcurrentHashMap<IslandRegistry.PIsland, Set<Transfer>> PRELUDES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> CLAIMING = new ThreadLocal<>();
    private static volatile MinecraftServer server;

    private NetherPortalTransfers() {}

    public static void init(MinecraftServer current) {
        if (server != null && server != current) throw new IllegalStateException("Nether runtime changed without settlement");
        server = current;
    }

    /** Returns true only for synchronous vanilla execution, never for queue acceptance. */
    public static boolean submit(Entity root, Portal portal, BlockPos entry, ServerLevel level) {
        if (SOURCES.containsKey(root.getUUID()) || NetherPortalPlayerAccess.requestForLifecycle(root) != null) return false;
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        // Physical Main can pump static MAIN_SYNC work before region workers finish.
        if (!RegionScheduler.isActive()
                && RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) {
            root.setPortalCooldown();
            TeleportTransition transition = portal.getPortalDestination(level, root, entry);
            if (transition != null && level.isAllowedToEnterPortal(transition.newLevel())
                    && (level.dimension() == transition.newLevel().dimension()
                    || root.canTeleport(level, transition.newLevel()))) root.teleport(transition);
            return true;
        }
        EpochDomain domain = runtime == null ? null : sourceDomain(runtime, root);
        Transfer transfer = new Transfer(root, level, domain,
                domain == null || domain.owner == null ? IslandClock.wallTime(level) : domain.unitGameTime);
        transfer.sourceChunks = new long[]{ChunkPos.pack(root.blockPosition())};
        transfer.capturing = true;
        try {
            if (!transfer.captureTree(root)) {
                transfer.cancel(null);
                return false;
            }
            if (transfer.state.get() != WAITING) return false;
            NetherPortalPlayerAccess.Reservation reservation = NetherPortalPlayerAccess.reserve(root, transfer);
            if (reservation == null) {
                transfer.cancel(null);
                return false;
            }
            synchronized (transfer) { transfer.players = reservation; }
            if (transfer.state.get() != WAITING) {
                reservation.cancel(transfer.cancellationFailure);
                return false;
            }
            if (!transfer.captureCustody()) {
                transfer.cancel(null);
                return false;
            }
            NetherPortalPreparation.Request preparation = NetherPortalPreparation.capture(root, entry, level);
            Source source = domain == null
                    ? FLAT_DOMAINS.computeIfAbsent(level, key -> new Source(null, key))
                    : DOMAINS.computeIfAbsent(domain, key -> new Source(key, level));
            boolean accepted;
            synchronized (transfer) {
                accepted = transfer.state.get() == WAITING;
                if (accepted) {
                    transfer.preparation = preparation;
                    transfer.queuedSource = source;
                    source.queue.stage(transfer);
                }
            }
            if (!accepted) {
                preparation.close();
                reservation.cancel(transfer.cancellationFailure);
                return false;
            }
            for (Entity entity : transfer.entities) DEPARTURES.get().put(entity, transfer);
            root.setPortalCooldown();
        } catch (Throwable thrown) {
            Throwable winner = RegionScheduler.publishFatalAndSelectWinner(thrown);
            transfer.cancel(winner);
            NetherPortalTransfers.<RuntimeException>rethrow(winner);
        } finally {
            transfer.finishCapture();
        }
        return false;
    }

    private static EpochDomain sourceDomain(RegionizedServer runtime, Entity root) {
        if (Thread.currentThread() instanceof RegionWorkerThread worker) {
            EpochDomain domain = worker.currentRegion == null ? worker.currentDomain : worker.currentRegion.domain;
            if (domain != null) return domain;
        }
        IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(root.chunkPosition().pack());
        // MAIN_SYNC is still a contribution to the source's open entity phase.
        if (runtime.asyncActive && owner != null && owner.domain.entityPending.get() != 0) return owner.domain;
        return runtime.globalDomain;
    }

    public static boolean hasDepartedThisTick(Object entity) {
        Transfer transfer = DEPARTURES.get().get(entity);
        return transfer != null && transfer.state.get() != CANCELLED && transfer.state.get() != TERMINAL;
    }

    public static void completeTickLocalDeparture(Entity entity) {
        DEPARTURES.get().clear();
    }

    public static void abortTickLocalTransfers() {
        Throwable failure = RegionScheduler.workerFailureOrNull();
        for (Transfer transfer : Set.copyOf(DEPARTURES.get().values())) transfer.cancel(failure);
        DEPARTURES.get().clear();
    }

    static NetherSourceCohorts.Prepared<?> prepareSeal(EpochDomain domain) {
        Source source = domain == null ? null : DOMAINS.get(domain);
        return source == null ? EMPTY_SOURCE.prepare() : source.queue.prepare();
    }

    static void seal(EpochDomain domain) {
        NetherSourceCohorts.Prepared<?> seal = prepareSeal(domain);
        try {
            RegionScheduler.commitSuccessIfHealthy(seal::commit);
        } catch (Throwable thrown) {
            Throwable failure = seal.cancel(RegionScheduler.publishFatalAndSelectWinner(thrown));
            NetherPortalTransfers.<RuntimeException>rethrow(failure);
        }
    }

    static void abortDomain(EpochDomain domain, Throwable failure) {
        Source source = DOMAINS.get(domain);
        if (source != null) source.queue.cancelOpen(failure);
    }

    private static final NetherSourceCohorts<Transfer> EMPTY_SOURCE =
            new NetherSourceCohorts<>(Transfer::cancel);

    private static final class Source {
        final EpochDomain domain;
        final ServerLevel level;
        final NetherSourceCohorts<Transfer> queue = new NetherSourceCohorts<>(Transfer::cancel);
        Source(EpochDomain domain, ServerLevel level) { this.domain = domain; this.level = level; }
    }

    /** Only the top-level worker loop calls this; never a region/help pump. */
    static boolean serviceOne() {
        if (!(Thread.currentThread() instanceof RegionWorkerThread worker)
                || worker.currentRegion != null || worker.currentDomain != null
                || ParallelProcessor.IN_ENTITY_TICK.get()) return false;
        RegionWorkerPool causalPool = RegionWorkerPool.currentThreadCausalOwnerOrNull();
        if (causalPool == null || !causalPool.mayServiceDetachedTransfers()) return false;
        NetherPortalPreparation.serviceClaimFree();
        refreshPreludes();
        for (Source source : DOMAINS.values()) {
            NetherSourceCohorts.Cohort<Transfer> cohort = source.queue.tryAcquireHead();
            if (cohort == null) continue;
            try (cohort) {
                if (tryCohort(cohort, false)) {
                    cohort.complete();
                    clearPrelude(cohort.items().getFirst());
                    return true;
                }
                publishPrelude(cohort.items());
            } finally {
                NetherPortalPreparation.serviceClaimFree();
            }
        }
        return false;
    }

    static void beforeUnitAdmission() {
        serviceOne();
    }

    /** Read under unitAdmissionLock; never takes a player or preparation monitor. */
    static boolean blocksAutonomousUnit(IslandRegistry.PIsland owner) {
        if (Boolean.TRUE.equals(CLAIMING.get())) return false;
        Set<Transfer> pending = PRELUDES.get(owner);
        return pending != null && !pending.isEmpty();
    }

    private static void refreshPreludes() {
        for (Source source : DOMAINS.values()) {
            NetherSourceCohorts.Cohort<Transfer> cohort = source.queue.tryAcquireHead();
            if (cohort == null) continue;
            try {
                publishPrelude(cohort.items());
            } catch (Throwable failure) {
                NetherPortalTransfers.<RuntimeException>rethrow(RegionScheduler.publishFatalAndSelectWinner(failure));
            } finally { cohort.close(); }
        }
    }

    private static void publishPrelude(List<Transfer> items) {
        Transfer token = items.getFirst();
        IdentityHashMap<IslandRegistry.PIsland, Boolean> desired = new IdentityHashMap<>();
        boolean ready = false;
        for (Transfer transfer : items) {
            if (transfer.state.get() != WAITING) continue;
            if (!transfer.players.ready() || !transfer.preparation.readyToAttempt()) {
                clearPrelude(token);
                return;
            }
            ArrayList<NetherPortalPreparation.ChunkRef> footprint = new ArrayList<>();
            if (!transfer.addFootprint(footprint, false) || !transfer.preparation.readyToAttempt()) {
                clearPrelude(token);
                return;
            }
            for (NetherPortalPreparation.ChunkRef ref : footprint) {
                RegionizedServer runtime = RegionScheduler.forLevel(ref.level());
                if (runtime == null || !runtime.asyncActive) continue;
                IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(ref.chunkKey());
                if (owner != null && !owner.retired) desired.put(owner, Boolean.TRUE);
            }
            ready = true;
        }
        if (!ready) { clearPrelude(token); return; }
        synchronized (PRELUDES) {
            Source registered = token.sourceDomain == null ? FLAT_DOMAINS.get(token.sourceLevel)
                    : DOMAINS.get(token.sourceDomain);
            boolean waiting = false;
            for (Transfer transfer : items) waiting |= transfer.state.get() == WAITING;
            if (registered == null || registered != token.queuedSource || !waiting) {
                clearPrelude(token);
                return;
            }
            List<IslandRegistry.PIsland> publication = List.copyOf(desired.keySet());
            for (IslandRegistry.PIsland owner : token.preludeOwners) {
                if (desired.containsKey(owner)) continue;
                synchronized (owner.unitAdmissionLock) {
                    Set<Transfer> pending = PRELUDES.get(owner);
                    if (pending != null) {
                        pending.remove(token);
                        if (pending.isEmpty()) PRELUDES.remove(owner, pending);
                    }
                }
            }
            for (IslandRegistry.PIsland owner : publication) {
                synchronized (owner.unitAdmissionLock) {
                    if (!owner.retired) PRELUDES.computeIfAbsent(owner,
                            ignored -> ConcurrentHashMap.newKeySet()).add(token);
                }
            }
            token.preludeOwners = publication;
        }
    }

    private static void clearPrelude(Transfer token) {
        synchronized (PRELUDES) {
            for (IslandRegistry.PIsland owner : token.preludeOwners) {
                synchronized (owner.unitAdmissionLock) {
                    Set<Transfer> pending = PRELUDES.get(owner);
                    if (pending != null) {
                        pending.remove(token);
                        if (pending.isEmpty()) PRELUDES.remove(owner, pending);
                    }
                }
            }
            token.preludeOwners = List.of();
        }
    }

    private static boolean tryCohort(NetherSourceCohorts.Cohort<Transfer> cohort, boolean quiescent) {
        ArrayList<Transfer> live = new ArrayList<>();
        ArrayList<Transfer> prepared = new ArrayList<>();
        ArrayList<Transfer> playerClaims = new ArrayList<>();
        ArrayList<NetherPortalPreparation.ChunkRef> footprint = new ArrayList<>();
        Claims claims = null;
        Throwable failure = null;
        try {
            for (Transfer transfer : cohort.items()) {
                if (!transfer.beginAttempt()) {
                    if (transfer.state.get() == WAITING) return false;
                    continue;
                }
                live.add(transfer);
                if (!transfer.players.ready()) return false;
                if (!transfer.addFootprint(footprint, quiescent)) return false;
            }
            if (live.isEmpty()) return true;
            claims = Claims.tryAcquire(footprint, quiescent);
            if (claims == null) return false;
            for (Transfer transfer : live) {
                ACTIVE.set(new Scope(transfer, claims));
                try {
                    if (!transfer.validateIndexedSources(claims)) return false;
                    if (!transfer.valid()) { transfer.cancel(null); continue; }
                    transfer.applyPortalPose();
                    prepared.add(transfer);
                    if (!transfer.preparation.resolve()) return false;
                    if (!transfer.prepareArrivals()) return false;
                } finally { ACTIVE.remove(); }
            }
            for (Transfer transfer : prepared) {
                if (transfer.state.get() != WAITING) continue;
                ACTIVE.set(new Scope(transfer, claims));
                try {
                    if (!transfer.players.reserveExecution()) return false;
                    playerClaims.add(transfer);
                } finally { ACTIVE.remove(); }
            }
            // Geometry, every player token, and all owners are ready together.
            // Late fences may refuse this whole body, but never revoke the
            // held authority needed to finish preparation or restore its poses.
            if (!claims.allowsEntry()) return false;
            RegionScheduler.throwWorkerFailureIfAny();
            Claims enteredClaims = claims;
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                for (Transfer transfer : prepared) {
                    if (transfer.state.get() != WAITING) continue;
                    Scope scope = new Scope(transfer, enteredClaims);
                    ACTIVE.set(scope);
                    try { transfer.execute(scope); }
                    finally { ACTIVE.remove(); }
                }
            });
            // Publish data-only tails before releasing claims. A Main consumer
            // may observe the queue now, but its try-all claim attempt backs out.
            for (Transfer transfer : prepared) {
                if (transfer.state.get() == BODY_DONE && !transfer.denied)
                    transfer.players.complete(transfer::release);
            }
            return true;
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            for (Transfer transfer : live) {
                try { transfer.cancel(failure); }
                catch (Throwable cleanup) { failure = merge(failure, cleanup); }
            }
            NetherPortalTransfers.<RuntimeException>rethrow(failure);
            return false;
        } finally {
            ACTIVE.remove();
            if (claims != null) {
                for (Transfer transfer : prepared) {
                    if (!transfer.bodyEntered && !transfer.resumePoses.isEmpty()) {
                        ACTIVE.set(new Scope(transfer, claims));
                        try { transfer.restoreResumePose(); }
                        catch (Throwable cleanup) { failure = RegionScheduler.publishFatalAndSelectWinner(merge(failure, cleanup)); }
                        finally { ACTIVE.remove(); }
                    }
                }
            }
            for (Transfer transfer : playerClaims) {
                if (!transfer.bodyEntered) {
                    try { transfer.players.releaseExecution(); }
                    catch (Throwable cleanup) { failure = RegionScheduler.publishFatalAndSelectWinner(merge(failure, cleanup)); }
                }
            }
            if (claims != null) {
                try { claims.close(); }
                catch (Throwable cleanup) { failure = RegionScheduler.publishFatalAndSelectWinner(merge(failure, cleanup)); }
            }
            for (Transfer transfer : live) {
                try {
                    if (transfer.denied) transfer.cancel(null);
                    transfer.endAttempt();
                } catch (Throwable cleanup) { failure = RegionScheduler.publishFatalAndSelectWinner(merge(failure, cleanup)); }
            }
            if (failure != null) NetherPortalTransfers.<RuntimeException>rethrow(failure);
        }
    }

    private static final class Transfer {
        final Entity root;
        final ServerLevel sourceLevel;
        final EpochDomain sourceDomain;
        final long sourceTick;
        final AtomicInteger state = new AtomicInteger(WAITING);
        final ArrayList<Entity> entities = new ArrayList<>();
        final ArrayList<Entity> custodyEntities = new ArrayList<>();
        final ArrayList<IndexedSource> indexedSources = new ArrayList<>();
        final ArrayList<NetherPortalPlayerAccess.SourceReference> metadataLocations = new ArrayList<>();
        final IdentityHashMap<Entity, Entity> vehicles = new IdentityHashMap<>();
        final IdentityHashMap<Entity, Position> portalPoses = new IdentityHashMap<>();
        // Published arrays are immutable; Main never traverses the capture-owned pose map.
        private volatile long[] sourceChunks = new long[0];
        final IdentityHashMap<Entity, Position> resumePoses = new IdentityHashMap<>();
        final IdentityHashMap<Entity, Boolean> arrivals = new IdentityHashMap<>();
        final IdentityHashMap<Entity, TeleportTransition> destinations = new IdentityHashMap<>();
        List<IslandRegistry.PIsland> preludeOwners = List.of();
        Source queuedSource;
        NetherPortalPreparation.Request preparation;
        NetherPortalPlayerAccess.Reservation players;
        Entity result;
        boolean denied;
        boolean bodyEntered;
        private boolean attempting;
        private boolean capturing;
        private Throwable cancellationFailure;

        synchronized boolean beginAttempt() {
            if (capturing || state.get() != WAITING) return false;
            if (attempting) throw new IllegalStateException("Nether request acquired by two attempts");
            attempting = true;
            return true;
        }

        void endAttempt() {
            boolean cancelled;
            synchronized (this) {
                attempting = false;
                cancelled = state.get() == CANCELLED;
            }
            if (cancelled) finishCancellation();
        }

        void finishCapture() {
            boolean cancelled;
            synchronized (this) {
                capturing = false;
                cancelled = state.get() == CANCELLED;
            }
            if (cancelled) finishCancellation();
        }

        Transfer(Entity root, ServerLevel sourceLevel, EpochDomain sourceDomain, long sourceTick) {
            this.root = root;
            this.sourceLevel = sourceLevel;
            this.sourceDomain = sourceDomain;
            this.sourceTick = sourceTick;
        }

        boolean captureTree(Entity entity) {
            if (!captureEntityTree(entity)) return false;
            long[] chunks = new long[entities.size()];
            for (int i = 0; i < chunks.length; i++)
                chunks[i] = ChunkPos.pack(BlockPos.containing(portalPoses.get(entities.get(i)).position));
            sourceChunks = chunks;
            return true;
        }

        private boolean captureEntityTree(Entity entity) {
            if (portalPoses.containsKey(entity)) throw new IllegalStateException("cyclic Nether passenger tree");
            SyncItemPickup.Guard guard = SyncItemPickup.guard(entity);
            // A source owner must never wait for a consumer which may itself
            // be waiting for Main's world fence. An own tick remains reentrant.
            if (guard != null && !guard.tryEnterReservation()) return false;
            try {
                if (state.get() != WAITING || entity.isRemoved()) return false;
                Transfer prior = SOURCES.putIfAbsent(entity.getUUID(), this);
                if (prior != null && prior != this) return false;
                entities.add(entity);
                portalPoses.put(entity, Position.capture(entity));
                vehicles.put(entity, entity.getVehicle());
                ((ParallelProcessor.TickGuard) entity).fabrolia$setPortalPending(true);
            } finally {
                if (guard != null) guard.exitReservation();
            }
            // Never retain the publication scope across passenger traversal.
            for (Entity passenger : entity.getPassengers()) {
                if (!captureEntityTree(passenger)) return false;
            }
            return true;
        }

        boolean captureCustody() {
            for (Entity entity : players.sourceReferences()) {
                if (portalPoses.containsKey(entity)) continue;
                SyncItemPickup.Guard guard = SyncItemPickup.guard(entity);
                if (guard != null && !guard.tryEnterReservation()) return false;
                try {
                    if (state.get() != WAITING || entity.isRemoved()) return false;
                    Transfer prior = SOURCES.putIfAbsent(entity.getUUID(), this);
                    if (prior != null && prior != this) return false;
                    custodyEntities.add(entity);
                    ((ParallelProcessor.TickGuard) entity).fabrolia$setPortalPending(true);
                } finally {
                    if (guard != null) guard.exitReservation();
                }
            }
            return true;
        }


        boolean addFootprint(List<NetherPortalPreparation.ChunkRef> footprint, boolean quiescent) {
            footprint.addAll(preparation.footprint());
            for (Entity entity : entities) {
                footprint.add(new NetherPortalPreparation.ChunkRef(sourceLevel,
                        ChunkPos.pack(BlockPos.containing(portalPoses.get(entity).position))));
            }
            indexedSources.clear();
            List<NetherPortalPlayerAccess.SourceReference> locations =
                    state.get() == BODY_DONE ? metadataLocations : players.sourceLocations();
            for (NetherPortalPlayerAccess.SourceReference reference : locations) {
                Entity entity = reference.entity();
                ServerLevel level = reference.level();
                RegionizedServer runtime = RegionScheduler.forLevel(level);
                long key = ((RegionKeyed) entity).fabrolia$getRegionKey();
                Region region = runtime == null || key == RegionKeyed.NO_REGION
                        ? null : runtime.regionizer().peekByGridKey(key);
                net.minecraft.world.level.entity.EntityInLevelCallback callback = null;
                long residentSection = EntityResidencyAccess.NO_SECTION;
                if (key == RegionKeyed.NO_REGION && runtime != null) {
                    callback = ((com.axalotl.fabrolia.common.mixin.entity.EntityLevelCallbackAccessor) entity)
                            .fabrolia$getLevelCallback();
                    if (callback instanceof EntityResidencyAccess residency) {
                        residentSection = residency.fabrolia$residentSection(entity);
                        if (residentSection != EntityResidencyAccess.NO_SECTION) {
                            long cell = runtime.regionizer().grid.keyFor(
                                    net.minecraft.core.SectionPos.x(residentSection),
                                    net.minecraft.core.SectionPos.z(residentSection));
                            region = runtime.regionizer().peekByGridKey(cell);
                        }
                    }
                }
                BlockPos indexedPosition;
                if (region == null) {
                    // A missing ticking index may still name a live FULL resident.
                    // Only genuinely removed post-body references can be omitted.
                    if (state.get() == BODY_DONE && key == RegionKeyed.NO_REGION && entity.isRemoved()) continue;
                    if (residentSection != EntityResidencyAccess.NO_SECTION) {
                        preparation.requireArrival(level, ChunkPos.pack(
                                net.minecraft.core.SectionPos.x(residentSection),
                                net.minecraft.core.SectionPos.z(residentSection)));
                    }
                    if (!quiescent || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) return false;
                    indexedPosition = entity.blockPosition();
                } else {
                    if (!runtime.ownsRegion(region)) return false;
                    int shift = runtime.regionizer().grid.shift + 4;
                    indexedPosition = new BlockPos((int) region.gridKey << shift, 0,
                            (int) (region.gridKey >>> 32) << shift);
                }
                footprint.add(new NetherPortalPreparation.ChunkRef(level, ChunkPos.pack(indexedPosition)));
                indexedSources.add(new IndexedSource(reference, key, runtime, region, indexedPosition,
                        callback, residentSection));
            }
            return true;
        }

        boolean validateIndexedSources(Claims claims) {
            for (IndexedSource indexed : indexedSources) {
                Entity entity = indexed.reference.entity();
                ServerLevel level = indexed.reference.level();
                if (((RegionKeyed) entity).fabrolia$getRegionKey() != indexed.key
                        || RegionScheduler.forLevel(level) != indexed.runtime
                        || !claims.owns(level, indexed.position)) return false;
                if (indexed.region != null && (indexed.runtime.regionizer().peekByGridKey(indexed.region.gridKey) != indexed.region
                        || !indexed.runtime.ownsRegion(indexed.region))) return false;
                if (!claims.quiescent && indexed.callback != null
                        && (((com.axalotl.fabrolia.common.mixin.entity.EntityLevelCallbackAccessor) entity)
                                .fabrolia$getLevelCallback() != indexed.callback
                        || !(indexed.callback instanceof EntityResidencyAccess residency)
                        || !residency.fabrolia$isResident(entity, indexed.residentSection))) return false;
                // Only the exact indexed-owner claim permits these live reads.
                if (entity.level() != level || entity.isRemoved()) return false;
                BlockPos live = entity.blockPosition();
                if (!claims.owns(level, live)) {
                    preparation.requireArrival(level, ChunkPos.pack(live));
                    return false;
                }
            }
            return true;
        }

        void captureMetadataLocations() {
            metadataLocations.clear();
            IdentityHashMap<Entity, Boolean> captured = new IdentityHashMap<>();
            for (Entity entity : players.sourceReferences()) {
                if (!entity.isRemoved() && captured.put(entity, Boolean.TRUE) == null)
                    metadataLocations.add(new NetherPortalPlayerAccess.SourceReference(entity, (ServerLevel) entity.level()));
            }
            for (Entity entity : arrivals.keySet()) {
                if (!entity.isRemoved() && captured.put(entity, Boolean.TRUE) == null)
                    metadataLocations.add(new NetherPortalPlayerAccess.SourceReference(entity, (ServerLevel) entity.level()));
            }
        }

        boolean prepareArrivals() {
            destinations.clear();
            TeleportTransition transition = preparation.transition();
            if (transition == null) return true;
            boolean ready = prepareArrival(root, transition);
            for (ServerPlayer player : players.references()) {
                if (players.isTeleportCandidate(player) && !destinations.containsKey(player))
                    ready &= prepareArrival(player, transition);
            }
            return ready && preparation.readyToAttempt();
        }

        private boolean prepareArrival(Entity entity, TeleportTransition transition) {
            destinations.put(entity, transition);
            Vec3 target = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(entity),
                    PositionMoveRotation.of(transition), transition.relatives()).position();
            ServerLevel destination = transition.newLevel();
            preparation.requireArrival(destination, ChunkPos.pack(BlockPos.containing(target)));
            var box = entity.getBoundingBox().move(target.subtract(entity.position()));
            if (entity instanceof ItemEntity) box = box.inflate(0.5);
            int minX = net.minecraft.util.Mth.floor(box.minX) >> 4;
            int maxX = net.minecraft.util.Mth.floor(box.maxX) >> 4;
            int minZ = net.minecraft.util.Mth.floor(box.minZ) >> 4;
            int maxZ = net.minecraft.util.Mth.floor(box.maxZ) >> 4;
            boolean ready = true;
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                BlockPos pos = new BlockPos(x << 4, 0, z << 4);
                if (!owns(destination, pos)) {
                    preparation.requireArrival(destination, ChunkPos.pack(x, z));
                    ready = false;
                }
            }
            if (!(entity instanceof ServerPlayer)) {
                for (Entity passenger : entity.getPassengers()) {
                    ready &= prepareArrival(passenger,
                            ((EntityNetherPortalInvoker) entity).fabrolia$passengerTransition(transition, passenger));
                }
            }
            return ready;
        }

        boolean valid() {
            if (state.get() != WAITING) return false;
            for (Entity entity : entities) {
                if (entity.getRemovalReason() != null || entity.level() != sourceLevel) {
                    if (entity instanceof ServerPlayer) return false;
                    throw new IllegalStateException("accepted Nether source lost: " + entity.getUUID());
                }
                if (entity.getVehicle() != vehicles.get(entity)) {
                    throw new IllegalStateException("accepted Nether ride topology changed: " + entity.getUUID());
                }
                if (SOURCES.get(entity.getUUID()) != this)
                    throw new IllegalStateException("Nether reservation lost exact identity");
            }
            return state.get() == WAITING;
        }

        void applyPortalPose() {
            for (Entity entity : entities) {
                resumePoses.put(entity, Position.capture(entity));
                portalPoses.get(entity).apply(entity);
            }
        }

        void restoreResumePose() {
            for (Entity entity : entities) {
                Position resume = resumePoses.get(entity);
                if (resume != null && entity.getRemovalReason() == null) resume.apply(entity);
            }
            resumePoses.clear();
        }

        void execute(Scope scope) {
            TeleportTransition transition = preparation.transition();
            if (transition == null || !sourceLevel.isAllowedToEnterPortal(transition.newLevel())
                    || sourceLevel.dimension() != transition.newLevel().dimension()
                    && !root.canTeleport(sourceLevel, transition.newLevel())) {
                restoreResumePose();
                state.set(BODY_DONE);
                denied = true;
                return;
            }
            long[] previousSource = CrossDimArrivals.CARRIED_SOURCE.get();
            CrossDimArrivals.CARRIED_SOURCE.set(new long[]{sourceTick, CrossDimArrivals.domainKeyOf(sourceDomain)});
            try (NetherPortalPlayerAccess.Scope ignored = players.enter()) {
                synchronized (this) {
                    if (state.get() != WAITING)
                        throw new IllegalStateException("Nether execution custody was cancelled after player admission");
                    state.set(ENTERED);
                    bodyEntered = true;
                }
                result = root.teleport(transition);
                root.portalProcess = null;
                captureMetadataLocations();
            } finally {
                if (previousSource == null) CrossDimArrivals.CARRIED_SOURCE.remove();
                else CrossDimArrivals.CARRIED_SOURCE.set(previousSource);
            }
            state.set(BODY_DONE);
        }

        void cancel(Throwable failure) {
            synchronized (this) {
                int previous = state.get();
                if (previous == CANCELLED || previous == TERMINAL) return;
                if (failure != null) failure = RegionScheduler.publishFatalAndSelectWinner(failure);
                if (failure == null && (previous == ENTERED || previous == BODY_DONE && !denied))
                    throw new IllegalStateException("lifecycle cancelled an entered Nether body");
                cancellationFailure = failure;
                state.set(CANCELLED);
                // Construction owns the mutable capture lists and the root lease until
                // submit unwinds. Cleanup must not race it or strand a later passenger.
                if (capturing || attempting) return;
            }
            finishCancellation();
        }

        private void finishCancellation() {
            Throwable primary = cancellationFailure;
            // Retire the world reservation first, while the listener exclusion
            // still prevents a competing player body. Cancellation may dispatch
            // a real disconnect synchronously on Main.
            try { release(); }
            catch (Throwable cleanup) { primary = RegionScheduler.publishFatalAndSelectWinner(merge(primary, cleanup)); }
            try { if (players != null) players.cancel(primary); }
            catch (Throwable cleanup) { primary = RegionScheduler.publishFatalAndSelectWinner(merge(primary, cleanup)); }
            if (primary != null && primary != cancellationFailure) NetherPortalTransfers.<RuntimeException>rethrow(primary);
        }

        void release() {
            synchronized (this) {
                if (state.getAndSet(TERMINAL) == TERMINAL) return;
            }
            Throwable failure = null;
            for (Entity entity : entities) {
                try {
                    releaseSource(entity);
                } catch (Throwable cleanup) { failure = merge(failure, cleanup); }
            }
            for (Entity entity : custodyEntities) {
                try {
                    releaseSource(entity);
                } catch (Throwable cleanup) { failure = merge(failure, cleanup); }
            }
            if (entities.isEmpty()) SOURCES.remove(root.getUUID(), this);
            try { if (preparation != null) preparation.close(); }
            catch (Throwable cleanup) { failure = merge(failure, cleanup); }
            if (failure != null) NetherPortalTransfers.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
        }

        private void releaseSource(Entity entity) {
            SyncItemPickup.Guard guard = SyncItemPickup.guard(entity);
            // Pending stays published while waiting, so every native entrant
            // rechecks and leaves without work. No transfer/camera monitor or
            // merge lane is held across this acquisition.
            if (guard != null) guard.enterActivity();
            try {
                if (SOURCES.remove(entity.getUUID(), this))
                    ((ParallelProcessor.TickGuard) entity).fabrolia$setPortalPending(false);
            } finally {
                if (guard != null) guard.exitActivity();
            }
        }
    }

    private record IndexedSource(NetherPortalPlayerAccess.SourceReference reference, long key,
                                 RegionizedServer runtime, Region region, BlockPos position,
                                 net.minecraft.world.level.entity.EntityInLevelCallback callback,
                                 long residentSection) {}

    private record Position(Vec3 position, Vec3 motion, float yaw, float pitch, Pose pose) {
        static Position capture(Entity entity) {
            return new Position(entity.position(), entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(), entity.getPose());
        }
        void apply(Entity entity) {
            entity.setPose(pose);
            entity.setPos(position.x, position.y, position.z);
            entity.setDeltaMovement(motion);
            entity.setYRot(yaw);
            entity.setXRot(pitch);
        }
    }

    private static final class Scope {
        final Transfer transfer;
        final Claims claims;
        Scope(Transfer transfer, Claims claims) { this.transfer = transfer; this.claims = claims; }
        boolean references(Entity entity) {
            if (transfer.portalPoses.containsKey(entity) || transfer.arrivals.containsKey(entity)) return true;
            for (Entity source : transfer.players.sourceReferences()) if (source == entity) return true;
            return false;
        }
    }

    private static final class Claims implements AutoCloseable {
        final List<NetherPortalPreparation.ChunkRef> footprint;
        final ArrayList<IslandRegistry.PIsland> owners = new ArrayList<>();
        final ArrayList<IslandRegistry.PIsland> acquired = new ArrayList<>();
        final boolean quiescent;
        private Claims(List<NetherPortalPreparation.ChunkRef> footprint, boolean quiescent) {
            this.footprint = List.copyOf(footprint);
            this.quiescent = quiescent;
        }
        static Claims tryAcquire(List<NetherPortalPreparation.ChunkRef> footprint, boolean quiescent) {
            Claims claims = new Claims(footprint, quiescent);
            if (quiescent) return claims;
            IdentityHashMap<IslandRegistry.PIsland, Boolean> unique = new IdentityHashMap<>();
            for (NetherPortalPreparation.ChunkRef chunk : footprint) {
                RegionizedServer runtime = RegionScheduler.forLevel(chunk.level());
                if (runtime == null || !RegionScheduler.ownsRuntimeGeneration(runtime)) return null;
                IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(chunk.chunkKey());
                if (owner == null || owner.retired) return null;
                if (!allowsEntry(runtime, owner)) return null;
                unique.put(owner, Boolean.TRUE);
            }
            ArrayList<IslandRegistry.PIsland> ordered = new ArrayList<>(unique.keySet());
            ordered.sort(Comparator.comparing((IslandRegistry.PIsland owner) -> owner.domainLevel().dimension().identifier().toString())
                    .thenComparingLong(owner -> owner.id).thenComparingLong(owner -> owner.gen));
            claims.owners.ensureCapacity(ordered.size());
            claims.acquired.ensureCapacity(ordered.size());
            Boolean previousClaiming = CLAIMING.get();
            CLAIMING.set(true);
            try {
                for (IslandRegistry.PIsland owner : ordered) {
                    RegionizedServer runtime = RegionScheduler.forLevel(owner.domainLevel());
                    if (mainOwns(runtime, owner)) {
                        claims.owners.add(owner);
                        continue;
                    }
                    synchronized (owner.unitAdmissionLock) {
                        if (owner.retired || owner.globalSprintFenced
                                || !owner.tryClaimAutonomousWork()) {
                            claims.close();
                            return null;
                        }
                        claims.owners.add(owner);
                        claims.acquired.add(owner);
                        owner.noteClaim("nether-transfer");
                    }
                }
                for (NetherPortalPreparation.ChunkRef chunk : footprint) {
                    if (!claims.owns(chunk.level(), BlockPos.containing(
                            ChunkPos.unpack(chunk.chunkKey()).getMinBlockX(), 0,
                            ChunkPos.unpack(chunk.chunkKey()).getMinBlockZ()))) {
                        claims.close();
                        return null;
                    }
                }
                if (!claims.allowsEntry()) {
                    claims.close();
                    return null;
                }
                return claims;
            } catch (Throwable thrown) {
                Throwable failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
                try { claims.close(); } catch (Throwable cleanup) { failure = merge(failure, cleanup); }
                NetherPortalTransfers.<RuntimeException>rethrow(failure);
                return null;
            }
            finally {
                if (previousClaiming == null) CLAIMING.remove();
                else CLAIMING.set(previousClaiming);
            }
        }
        boolean allowsEntry() {
            if (quiescent) return true;
            for (IslandRegistry.PIsland owner : owners) {
                if (!allowsEntry(RegionScheduler.forLevel(owner.domainLevel()), owner)) return false;
            }
            return true;
        }
        private static boolean allowsEntry(RegionizedServer runtime, IslandRegistry.PIsland owner) {
            return mainOwns(runtime, owner) || runtime != null && runtime.asyncActive
                    && !runtime.mainClaimsFenceActive() && !runtime.globalSprintAdmissionFenced
                    && !owner.globalSprintFenced;
        }
        boolean owns(ServerLevel level, BlockPos pos) {
            if (quiescent) return RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer());
            RegionizedServer runtime = RegionScheduler.forLevel(level);
            if (runtime == null || !RegionScheduler.ownsRuntimeGeneration(runtime)) return false;
            IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(ChunkPos.pack(pos));
            if (owner == null || owner.retired || !owners.contains(owner)
                    || !owner.unitClaim.get() || owner.claimHolder != Thread.currentThread()
                    || owner.domain.entityPending.get() != 0) return false;
            if (owner.domain.entityBarrier != null && (!owner.domain.entityBarrier.isDone()
                    || owner.domain.entityBarrier.isCompletedExceptionally())) return false;
            Region region = runtime.regionizer().peekRegionForChunk(ChunkPos.pack(pos));
            return region == null || !region.hasClaimOrDoneSeal()
                    && !region.claimRebaseClosed() && region.activeClaimTransactions() == 0;
        }
        private static boolean mainOwns(RegionizedServer runtime, IslandRegistry.PIsland owner) {
            return runtime != null && owner != null
                    && RegionalPlayerEntityTicksV2.isPhysicalServerThread(runtime.level.getServer())
                    && (runtime.currentThreadHoldsAllClaims() || IslandCommands.mainHolds(owner));
        }
        @Override public void close() {
            Throwable failure = null;
            for (int i = acquired.size() - 1; i >= 0; i--) {
                IslandRegistry.PIsland owner = acquired.get(i);
                try { owner.clearClaim(); }
                catch (Throwable cleanup) { failure = RegionScheduler.publishFatalAndSelectWinner(merge(failure, cleanup)); }
                try { owner.unitClaim.set(false); }
                catch (Throwable cleanup) { failure = RegionScheduler.publishFatalAndSelectWinner(merge(failure, cleanup)); }
            }
            owners.clear();
            acquired.clear();
            if (failure != null) NetherPortalTransfers.<RuntimeException>rethrow(failure);
        }
    }

    public static Object currentTransfer() { Scope scope = ACTIVE.get(); return scope == null ? null : scope.transfer; }
    public static long gameTimeFor(ServerLevel level) {
        Scope scope = ACTIVE.get();
        if (scope == null || !(Thread.currentThread() instanceof RegionWorkerThread)) return Long.MIN_VALUE;
        Transfer transfer = scope.transfer;
        BlockPos position;
        if (level == transfer.sourceLevel) {
            position = BlockPos.containing(transfer.portalPoses.get(transfer.root).position);
        } else {
            TeleportTransition transition = transfer.preparation.transition();
            if (transition == null || transition.newLevel() != level) return Long.MIN_VALUE;
            position = BlockPos.containing(transition.position());
        }
        if (!scope.claims.owns(level, position)) return Long.MIN_VALUE;
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        IslandRegistry.PIsland owner = runtime == null ? null
                : runtime.islandOwnerOfChunk(ChunkPos.pack(position));
        return owner == null ? Long.MIN_VALUE : owner.logicalTick;
    }
    public static boolean owns(ServerLevel level, BlockPos pos) {
        Scope scope = ACTIVE.get();
        return scope != null && scope.claims.owns(level, pos);
    }
    public static boolean owns(Entity entity) {
        Scope scope = ACTIVE.get();
        return scope != null && entity != null && scope.references(entity)
                && entity.level() instanceof ServerLevel level && scope.claims.owns(level, entity.blockPosition());
    }
    public static boolean isResolvingSource(Object entity) {
        return entity instanceof Entity source && owns(source);
    }
    public static boolean isSourceMutationBlocked(Object entity) {
        boolean pending = entity instanceof SyncItemPickup.PortalReservation reservation
                && reservation.fabrolia$isPortalPending();
        if (entity instanceof Entity source) {
            // Preparatory player custody cannot revoke the native consumer
            // that already owns the consumption guard. Other lifecycle callers
            // retain the full captured-custody protection.
            if (!SyncItemPickup.holdsUnreservedConsumption(source)) {
                Object captured = NetherPortalPlayerAccess.requestForLifecycle(source);
                pending |= captured instanceof Transfer transfer && transfer.state.get() != TERMINAL;
            }
            return pending && !owns(source) && !AsyncIslandPlayerTicks.ownsPendingNetherTurn(source);
        }
        return pending && !((SyncItemPickup.PortalReservation) entity).fabrolia$isPortalResolutionOwner();
    }

    /** Called at the actual destination add seam, not by arbitrary unpublished entities. */
    public static boolean admitArrival(ServerLevel level, Entity entity) {
        Scope scope = ACTIVE.get();
        if (scope == null) return false;
        if (!scope.claims.owns(level, entity.blockPosition()))
            throw new IllegalStateException("Nether arrival escaped its prepared ownership footprint");
        boolean expected = false;
        for (Entity source : scope.transfer.entities) {
            if (source.getUUID().equals(entity.getUUID())) { expected = true; break; }
        }
        if (!expected) for (ServerPlayer player : scope.transfer.players.references()) {
            if (player == entity && scope.transfer.players.isTeleportCandidate(player)) { expected = true; break; }
        }
        if (!expected) throw new IllegalStateException("Nether add introduced an uncaptured entity identity");
        scope.transfer.arrivals.put(entity, Boolean.TRUE);
        NetherPortalPlayerAccess.captureArrivalMetadata(entity);
        return true;
    }

    public static void cancelForLifecycle(Entity entity) {
        if (entity == null) return;
        Transfer transfer;
        if (NetherPortalPlayerAccess.requestForLifecycle(entity) instanceof Transfer captured) {
            transfer = captured;
        } else {
            transfer = SOURCES.get(entity.getUUID());
            // The destination clone keeps the UUID, not the source incarnation's
            // lifecycle lease. Only the root can precede identity capture.
            if (transfer != null && transfer.root != entity) return;
        }
        if (transfer == null || currentTransfer() == transfer) return;
        if (transfer.state.get() == TERMINAL || transfer.state.get() == CANCELLED) return;
        // A consumer which won the source guard may finish its native
        // discard, including cancellation of preparatory player custody.
        // MAIN_SYNC consumers need no spatial claim for that exact identity.
        if (!SyncItemPickup.holdsUnreservedConsumption(entity)
                && (!(entity.level() instanceof ServerLevel level)
                || !RegionScheduler.ownsCurrentContext(level, entity.blockPosition())
                && !AsyncIslandPlayerTicks.ownsPendingNetherTurn(entity)
                && !(RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)
                && !RegionScheduler.isActive())))
            throw new IllegalStateException("Nether lifecycle cancellation lacks source ownership");
        if (transfer.state.get() == ENTERED || transfer.state.get() == BODY_DONE) {
            throw new IllegalStateException("player lifecycle crossed an entered Nether transfer");
        }
        transfer.cancel(null);
    }

    public static boolean tryRunPlayerMetadata(Object identity, Runnable body) {
        if (!(identity instanceof Transfer transfer)
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server))
            throw new IllegalStateException("Nether metadata lost its exact Main request");
        if (transfer.state.get() != BODY_DONE) throw new IllegalStateException("Nether metadata preceded body completion");
        ArrayList<NetherPortalPreparation.ChunkRef> footprint = new ArrayList<>();
        boolean quiescent = !RegionScheduler.isActive() || RegionScheduler.ownsExactExecutionTurn();
        if (!transfer.addFootprint(footprint, quiescent)) return false;
        Claims claims = Claims.tryAcquire(footprint, quiescent);
        if (claims == null) return false;
        try {
            if (!transfer.validateIndexedSources(claims)) return false;
            if (!claims.allowsEntry()) return false;
            ACTIVE.set(new Scope(transfer, claims));
            RegionScheduler.runSuccessBodyIfHealthy(body);
            return true;
        } finally {
            ACTIVE.remove();
            claims.close();
        }
    }

    public static void serviceMain(MinecraftServer current) {
        if (server != current) return;
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(current)
                || RegionWorkerPool.currentThreadCausalOwnerOrNull() != null
                || !RegionalPlayerEntityTicksV2.schedulerLifecycleTransitionAllowed(current))
            throw new IllegalStateException("Nether Main maintenance lost its completed tick boundary");
        // Vanilla Main player ticks have also unwound; their fake-removal view
        // must not leak into compatibility validation of the still-live source.
        DEPARTURES.get().clear();
        NetherPortalPlayerAccess.drainMetadata();
        if (RegionScheduler.isActive()) NetherPortalPreparation.serviceMain(current);
        else NetherPortalPreparation.serviceQuiescent(current);
        boolean compatibility = !FLAT_DOMAINS.isEmpty();
        for (Source source : DOMAINS.values()) {
            RegionizedServer runtime = RegionScheduler.forLevel(source.level);
            if (runtime == null || !runtime.asyncActive) { compatibility = true; break; }
        }
        if (!compatibility) return;
        boolean paused = RegionScheduler.isActive();
        // Queued next-unit work must stay parked, not wait on the pacer this pause excludes.
        if (paused && !RegionScheduler.pauseMainPreparationTurn()) return;
        try {
            for (Source source : FLAT_DOMAINS.values()) {
                NetherSourceCohorts.Prepared<?> seal = source.queue.prepare();
                RegionScheduler.commitSuccessIfHealthy(seal::commit);
                serviceCompatibility(source);
            }
            for (Source source : DOMAINS.values()) {
                RegionizedServer runtime = RegionScheduler.forLevel(source.level);
                if (runtime == null || !runtime.asyncActive) serviceCompatibility(source);
            }
            NetherPortalPlayerAccess.drainMetadata();
        } finally {
            if (paused) RegionScheduler.resumeExactPlayerTurn();
        }
    }

    private static void serviceCompatibility(Source source) {
        NetherSourceCohorts.Cohort<Transfer> cohort;
        while ((cohort = source.queue.tryAcquireHead()) != null) {
            try {
                if (!tryCohort(cohort, true)) return;
                cohort.complete();
                clearPrelude(cohort.items().getFirst());
            } finally { cohort.close(); }
        }
    }

    public static void flushQuiescent() {
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)
                || RegionScheduler.isActive())
            throw new IllegalStateException("Nether compatibility flush requires retired worker execution");
        for (Source source : DOMAINS.values()) seal(source.domain);
        for (Source source : FLAT_DOMAINS.values()) {
            NetherSourceCohorts.Prepared<?> seal = source.queue.prepare();
            RegionScheduler.commitSuccessIfHealthy(seal::commit);
        }
        ArrayList<Source> sources = new ArrayList<>(DOMAINS.values());
        sources.addAll(FLAT_DOMAINS.values());
        for (Source source : sources) {
            NetherSourceCohorts.Cohort<Transfer> cohort;
            while ((cohort = source.queue.tryAcquireHead()) != null) {
                try {
                    while (!tryCohort(cohort, true)) {
                        NetherSourceCohorts.Cohort<Transfer> waiting = cohort;
                        server.managedBlock(() -> playersReady(waiting));
                        NetherPortalPreparation.serviceClaimFree();
                        NetherPortalPreparation.serviceQuiescent(server);
                        NetherPortalPlayerAccess.drainMetadata();
                        RegionScheduler.throwWorkerFailureIfAny();
                    }
                    cohort.complete();
                    clearPrelude(cohort.items().getFirst());
                } finally { cohort.close(); }
            }
        }
        NetherPortalPlayerAccess.drainMetadata();
        NetherPortalPreparation.serviceQuiescent(server);
        if (!SOURCES.isEmpty()) throw new IllegalStateException("Nether metadata survived runtime retirement");
    }

    private static boolean playersReady(NetherSourceCohorts.Cohort<Transfer> cohort) {
        RegionScheduler.throwWorkerFailureIfAny();
        for (Transfer transfer : cohort.items())
            if (transfer.state.get() == WAITING && !transfer.players.ready()) return false;
        return true;
    }

    public static void onLevelUnload(ServerLevel level) {
        for (Transfer transfer : Set.copyOf(SOURCES.values())) {
            if (transfer.sourceLevel == level) {
                transfer.cancel(transfer.root instanceof ServerPlayer ? null
                        : new IllegalStateException("accepted Nether source level unloaded"));
            } else if (transfer.preparation.footprint().stream().anyMatch(ref -> ref.level() == level)) {
                transfer.cancel(null);
            }
        }
        ACTIVE_CHUNKS.entrySet().removeIf(entry -> entry.getValue().level == level);
        synchronized (PRELUDES) {
            DOMAINS.entrySet().removeIf(entry -> entry.getValue().level == level);
            FLAT_DOMAINS.remove(level);
            Set<Transfer> notices = ConcurrentHashMap.newKeySet();
            for (Set<Transfer> pending : PRELUDES.values()) notices.addAll(pending);
            for (Transfer token : notices) {
                boolean affected = token.sourceLevel == level;
                for (IslandRegistry.PIsland owner : token.preludeOwners)
                    affected |= owner.domainLevel() == level;
                if (affected) clearPrelude(token);
            }
        }
        NetherPortalPreparation.onLevelUnload(level);
        RegionScheduler.throwWorkerFailureIfAny();
    }
    public static int pendingCount() { return Set.copyOf(SOURCES.values()).size(); }
    public static boolean hasPending() { return !SOURCES.isEmpty(); }
    public static void shutdown() {
        if (!SOURCES.isEmpty()) throw new IllegalStateException("Nether shutdown retained accepted requests");
        DOMAINS.clear();
        FLAT_DOMAINS.clear();
        PRELUDES.clear();
        NetherPortalPreparation.resetAfterShutdown();
        ACTIVE_CHUNKS.clear();
        server = null;
    }

    /** Registered-generation cancellation after worker steps/root publication settle;
     * an entered request retains custody through attempting until its body closes. */
    public static Throwable cancelFailedGeneration(Throwable failure) {
        failure = RegionScheduler.publishFatalAndSelectWinner(failure);
        for (Transfer transfer : Set.copyOf(SOURCES.values())) {
            try { transfer.cancel(failure); }
            catch (Throwable cleanup) { failure = merge(failure, cleanup); }
        }
        DOMAINS.clear();
        FLAT_DOMAINS.clear();
        PRELUDES.clear();
        return failure;
    }
    public static void forEachPendingSourceChunk(ServerLevel level, LongConsumer sink) {
        for (Transfer transfer : Set.copyOf(SOURCES.values())) if (transfer.sourceLevel == level) {
            for (long chunk : transfer.sourceChunks) sink.accept(chunk);
        }
    }
    private record ActiveChunk(ServerLevel level, long chunkKey, long atNanos) {}
    public static void notePortalActive(Entity entity, ServerLevel level) {
        ACTIVE_CHUNKS.put(entity.getUUID(), new ActiveChunk(level, entity.chunkPosition().pack(), System.nanoTime()));
    }
    public static void forEachPortalActiveChunk(ServerLevel level, LongConsumer sink) {
        long now = System.nanoTime();
        for (var entry : ACTIVE_CHUNKS.entrySet()) {
            ActiveChunk chunk = entry.getValue();
            if (now - chunk.atNanos > 3_000_000_000L) ACTIVE_CHUNKS.remove(entry.getKey(), chunk);
            else if (chunk.level == level && !SOURCES.containsKey(entry.getKey())) sink.accept(chunk.chunkKey);
        }
    }
    private static Throwable merge(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (primary != secondary) try { primary.addSuppressed(secondary); } catch (Throwable ignored) {}
        return primary;
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> void rethrow(Throwable failure) throws E { throw (E) failure; }
}
