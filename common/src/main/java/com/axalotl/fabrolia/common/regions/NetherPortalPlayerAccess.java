package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Exact listener FIFO exclusion and ordered metadata for a core-owned Nether transfer.
 *
 * <p>A reservation runs in one of two modes, fixed in {@link #reserve}:
 * <ul>
 *   <li><b>tail</b> - the cohort carries a player (root, passenger, spectating
 *       follower) or a transmitting waypoint. Main-only metadata (camera,
 *       disconnects, waypoints, viewer pairing) is deferred to the ordered
 *       Main tail; arrivals stay {@code arrivalPending} until it drains.</li>
 *   <li><b>inline</b> - no player and no waypoint anywhere in custody (mobs,
 *       items, carts). Nothing is deferred: the worker holding both island
 *       claims finishes the reservation right after the vanilla teleport, so
 *       the arrival ticks on the destination island's next unit without Main
 *       (the farm case: Main lag must not delay a mob's portal crossing).</li>
 * </ul>
 */
public final class NetherPortalPlayerAccess {
    private static final ThreadLocal<Reservation> CURRENT = new ThreadLocal<>();
    public static boolean capturesMetadata() {
        Reservation current = CURRENT.get();
        return current != null && !current.metadata && !current.inline;
    }
    private static final ConcurrentLinkedQueue<Reservation> METADATA = new ConcurrentLinkedQueue<>();
    /** Identity and level are published by the lifecycle owner; position requires indexed-owner claims. */
    public record SourceReference(Entity entity, ServerLevel level) {}
    private record CameraLink(ServerLevel level, Entity target, List<SourceReference> rideComponent) {}
    private record DeferredDisconnect(ServerGamePacketListenerImpl listener, Runnable body) {}
    private static final IdentityHashMap<ServerPlayer, CameraLink> CAMERAS = new IdentityHashMap<>();
    private static final IdentityHashMap<ServerPlayer, CameraMutation> CAMERA_MUTATIONS = new IdentityHashMap<>();
    private static final IdentityHashMap<Entity, Reservation> CAPTURED = new IdentityHashMap<>();
    /** Arrival publication/readiness only: never grants source or island ownership. */
    private static final IdentityHashMap<Entity, Reservation> ARRIVAL_METADATA = new IdentityHashMap<>();
    /** Optional serializers exclude later source/follower capture by exact identity. */
    private static final IdentityHashMap<Entity, EntitySaveScope> ENTITY_SAVES = new IdentityHashMap<>();
    /** Sender admission and transfer capture share one cut; no transport runs under CAMERAS. */
    private static final IdentityHashMap<ServerPlayer, ChunkSendScope> CHUNK_SENDS = new IdentityHashMap<>();

    /** Cached per sender, with depth so a nested Main call cannot release its outer admission. */
    public static final class ChunkSendScope implements AutoCloseable {
        private ServerPlayer player;
        private int depth;

        public boolean tryEnter(ServerPlayer current) {
            synchronized (CAMERAS) {
                if (CAPTURED.containsKey(current) || ARRIVAL_METADATA.containsKey(current)) return false;
                ChunkSendScope prior = CHUNK_SENDS.get(current);
                if (prior != null && prior != this) return false;
                if (depth != 0 && player != current) {
                    throw new IllegalStateException("chunk sender reentered with a different player");
                }
                if (depth == 0) {
                    CHUNK_SENDS.put(current, this);
                    player = current;
                }
                depth++;
                return true;
            }
        }

        @Override public void close() {
            synchronized (CAMERAS) {
                if (depth == 0) throw new IllegalStateException("chunk sender admission closed twice");
                if (--depth == 0) {
                    CHUNK_SENDS.remove(player, this);
                    player = null;
                }
            }
        }
    }

    private static final class CameraMutation {
        int depth;
        boolean trackingEnded;
        final ArrayList<CameraLink> relations = new ArrayList<>();
        void remember(CameraLink link) {
            if (link == null) return;
            for (CameraLink prior : relations) {
                if (prior.level == link.level && prior.target == link.target) return;
            }
            relations.add(link);
        }
    }

    private static Reservation captured(Entity entity) {
        synchronized (CAMERAS) { return CAPTURED.get(entity); }
    }

    static boolean needsMetadataSettlement(Entity entity) {
        synchronized (CAMERAS) {
            // Preparatory custody cannot revoke an admitted native consumer.
            // Its discard must finish; captureCustody will decline the busy
            // identity instead of waiting while retaining source ownership.
            return ARRIVAL_METADATA.containsKey(entity)
                    || CAPTURED.containsKey(entity) && !SyncItemPickup.holdsUnreservedConsumption(entity);
        }
    }

    /** Custody of an existing exact-token tracking task, not permission to run it inline. */
    interface ArrivalPublication {
        void publish();
        void cancel();
    }

    static boolean deferArrivalPublication(Entity entity, ArrivalPublication publication) {
        var lease = ((ParallelProcessor.TickGuard) entity).fabrolia$tickLease();
        while (lease.isArrivalPending()) {
            Reservation reservation;
            // A producer may hold its tracker: never nest CAMERAS around the reservation monitor.
            synchronized (CAMERAS) { reservation = ARRIVAL_METADATA.get(entity); }
            if (reservation == null) return false;
            synchronized (reservation) {
                // A new arrival may have replaced the observed, now-settled reservation.
                if (reservation.publicationClosed) continue;
                RegionScheduler.throwWorkerFailureIfAny();
                if (reservation.failure != null)
                    return NetherPortalPlayerAccess.<Boolean, RuntimeException>rethrow(reservation.failure);
                if (reservation.canceled)
                    throw new IllegalStateException("Canceled Nether arrival cannot accept publication");
                Objects.requireNonNull(publication);
                if (reservation.arrivalPublications == null) reservation.arrivalPublications = new ArrayList<>();
                reservation.arrivalPublications.add(publication);
                return true;
            }
        }
        return false;
    }

    /**
     * Nonblocking admission under the serializer's existing owner claim.
     * Metadata must settle at the clean Main drain, never inside an unload iterator.
     * Full all-claims saves use their existing exclusive path instead.
     */
    public static EntitySaveScope tryBeginEntitySave(Object manager, long chunkPos) {
        if (!RegionScheduler.isActive() || IslandCommands.fenceWindowHeld()
                || IslandUnitAdmission.isFullEntitySave(manager)) return EntitySaveScope.EMPTY;
        var storage = ((com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess) manager)
                .fabrolia$sectionStorage();
        IdentityHashMap<Entity, Boolean> entities = null;
        try (var sections = storage.getExistingSectionsInChunk(chunkPos)) {
            var iterator = sections.iterator();
            while (iterator.hasNext()) {
                try (var residents = iterator.next().getEntities()) {
                    var members = residents.iterator();
                    while (members.hasNext()) {
                        var member = members.next();
                        if (member instanceof Entity entity && entity.shouldBeSaved()) {
                            if (entities == null) entities = new IdentityHashMap<>();
                            collectRideComponent(entity, entities);
                        }
                    }
                }
            }
        }
        if (entities == null) return EntitySaveScope.EMPTY;
        // A removable multi-player vehicle can require the ordinary A5 Main
        // lifecycle fence even without a Nether reservation. Optional unload
        // must not acquire that fence under its serializer claim either.
        if (IslandUnitAdmission.isEntityUnloadAttempt(manager, chunkPos)
                && AsyncIslandPlayerTicks.enabledForAsync()) {
            for (Entity entity : entities.keySet()) {
                if (entity instanceof ServerPlayer player
                        && AsyncIslandPlayerLifecycle.participatesInLifecycle(player)) return null;
            }
        }
        EntitySaveScope scope = new EntitySaveScope(entities);
        synchronized (CAMERAS) {
            for (Entity entity : entities.keySet()) {
                if (CAPTURED.containsKey(entity) || ARRIVAL_METADATA.containsKey(entity)
                        || ENTITY_SAVES.containsKey(entity)) return null;
            }
            try {
                for (Entity entity : entities.keySet()) ENTITY_SAVES.put(entity, scope);
            } catch (Throwable failure) {
                scope.close();
                throw failure;
            }
        }
        return scope;
    }

    /** Admission only; this scope grants no entity or island ownership. */
    public static final class EntitySaveScope implements AutoCloseable {
        private static final EntitySaveScope EMPTY = new EntitySaveScope(null);
        private final IdentityHashMap<Entity, Boolean> entities;

        private EntitySaveScope(IdentityHashMap<Entity, Boolean> entities) {
            this.entities = entities;
        }

        @Override public void close() {
            if (entities == null) return;
            synchronized (CAMERAS) {
                for (Entity entity : entities.keySet()) ENTITY_SAVES.remove(entity, this);
            }
        }
    }

    /** Resolve before allocating a builder; null leaves ordinary waypoint calls unchanged. */
    public static Reservation metadataOwner(Object identity) {
        Reservation current = CURRENT.get();
        if (current != null && !current.inline) return current.metadata ? null : current;
        synchronized (CAMERAS) { return ARRIVAL_METADATA.get(identity); }
    }

    static void captureArrivalMetadata(Entity entity) {
        Reservation current = Objects.requireNonNull(CURRENT.get(), "Nether arrival metadata owner");
        current.requireWorker();
        if (current.inline) return;
        synchronized (current) {
            synchronized (CAMERAS) {
                Reservation previous = ARRIVAL_METADATA.get(entity);
                if (previous != null && previous != current)
                    throw new IllegalStateException("Nether arrival crossed metadata ownership");
                if (previous == null) {
                    current.metadataArrivals.add(entity);
                    ARRIVAL_METADATA.put(entity, current);
                    ((ParallelProcessor.TickGuard) entity).fabrolia$tickLease().setArrivalPending(true);
                }
            }
        }
    }

    /** Publish prospective camera identity before cancellation, closing admission against new followers. */
    public static void changeCamera(ServerPlayer player, Entity target, Runnable body) {
        boolean tookFence = false;
        Throwable failure = null;
        try {
            // Foreign spectate/admin changes are rare Main lifecycle operations.
            // Vanilla's in-transfer spectator reset keeps the existing exact claims.
            if (CURRENT.get() == null && IslandCommands.requiresMainFence()
                    && RegionalPlayerEntityTicksV2.isPhysicalServerThread(player.level().getServer())) {
                tookFence = IslandCommands.quiesceAllExcept(null);
                if (!tookFence && !IslandCommands.fenceWindowHeld()) {
                    throw new IllegalStateException("Pending Nether camera mutation lacks its Main lifecycle fence");
                }
                drainMetadata();
            }
            Reservation owner = captured(player);
            if (owner == null) {
                changeCameraBody(player, target, body);
            } else {
                synchronized (owner) {
                    if (owner.executionReserved && NetherPortalTransfers.currentTransfer() != owner.request) {
                        throw new IllegalStateException("Camera mutation crossed reserved Nether body custody");
                    }
                    changeCameraBody(player, target, body);
                }
            }
        } catch (Throwable problem) {
            failure = RegionScheduler.publishFatalAndSelectWinner(problem);
        }
        if (tookFence) failure = IslandCommands.releaseAllExcept(failure);
        if (failure != null) NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(failure);
    }

    private static void changeCameraBody(ServerPlayer player, Entity target, Runnable body) {
        var token = AsyncIslandPlayerTicks.prepareLifecycleMutationBody(player);
        var tokens = List.of(token);
        Entity previousTarget = player.getCamera();
        AsyncIslandPlayerTicks.beginLifecycleMutationBody(token);
        Throwable failure = null;
        try {
            withRidePublication(new Entity[]{player}, () -> {
                CameraLink prospective = new CameraLink(player.level(), target == null ? player : target,
                        rideComponent(player));
                synchronized (CAMERAS) {
                    CAMERA_MUTATIONS.get(player).remember(prospective);
                    CAMERAS.put(player, prospective);
                }
                NetherPortalTransfers.cancelForLifecycle(player);
                if (target != null) NetherPortalTransfers.cancelForLifecycle(target);
                if (previousTarget != player) NetherPortalTransfers.cancelForLifecycle(previousTarget);
                RegionScheduler.runSuccessBodyIfHealthy(body);
                return null;
            });
        } catch (Throwable problem) {
            failure = RegionScheduler.publishFatalAndSelectWinner(problem);
        }
        try {
            failure = AsyncIslandPlayerTicks.finishLifecycleMutationBody(token, failure);
        } catch (Throwable problem) {
            failure = RegionScheduler.publishFatalAndSelectWinner(problem);
        }
        try {
            failure = AsyncIslandPlayerTicks.finishLifecycleHandoffPublications(tokens, 1, failure);
        } catch (Throwable problem) {
            failure = RegionScheduler.publishFatalAndSelectWinner(problem);
        }
        if (failure != null) NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(
                RegionScheduler.publishFatalAndSelectWinner(failure));
    }


    static boolean deferDisconnect(ServerGamePacketListenerImpl listener, Runnable disconnect) {
        if (!(listener instanceof AsyncIslandListenerAccess access)) return false;
        var state = access.fabrolia$asyncIslandPlayerState();
        if (state == null) return false;
        PlayerReservation token;
        synchronized (state) { token = state.netherReservation; }
        if (token == null) return false;
        synchronized (token.reservation) {
            Reservation reservation = token.reservation;
            if (token.listener != listener || token.player != listener.player) {
                throw new IllegalStateException("Nether disconnect crossed listener identity");
            }
            if (reservation.terminal || !reservation.entered && !reservation.executionReserved
                    || reservation.deliveringDisconnect == listener) return false;
            DeferredDisconnect deferred = new DeferredDisconnect(listener, disconnect);
            if (reservation.disconnects.putIfAbsent(listener, deferred) != null) {
                throw new IllegalStateException("Nether disconnect was accepted twice");
            }
            reservation.disconnectOrder.add(deferred);
            return true;
        }
    }

    public static void forgetCamera(ServerPlayer player) {
        synchronized (CAMERAS) {
            CAMERAS.remove(player);
            CameraMutation mutation = CAMERA_MUTATIONS.get(player);
            if (mutation != null) mutation.trackingEnded = true;
        }
    }

    public static void publishTrackedCamera(ServerPlayer player) {
        synchronized (CAMERAS) {
            CameraMutation mutation = CAMERA_MUTATIONS.get(player);
            if (mutation != null) mutation.trackingEnded = false;
        }
        publishCamera(player);
    }

    public static Object requestForLifecycle(Entity entity) {
        Reservation reservation = captured(entity);
        return reservation == null ? null : reservation.request;
    }

    public static boolean hasPublishedCamera(ServerPlayer player) {
        synchronized (CAMERAS) { return CAMERAS.containsKey(player); }
    }

    public static void publishCamera(ServerPlayer player) {
        Entity target = player.getCamera();
        CameraLink link = target == player || player.hasDisconnected() || player.getRemovalReason() != null
                ? null : new CameraLink(player.level(), target, rideComponent(player));
        synchronized (CAMERAS) {
            CameraMutation mutation = CAMERA_MUTATIONS.get(player);
            if (link == null || mutation != null && mutation.trackingEnded) CAMERAS.remove(player);
            else CAMERAS.put(player, link);
            if (mutation != null) mutation.remember(link);
        }
    }

    private static List<SourceReference> rideComponent(Entity root) {
        IdentityHashMap<Entity, Boolean> component = new IdentityHashMap<>();
        collectRideComponent(root, component);
        ArrayList<SourceReference> references = new ArrayList<>(component.size());
        for (Entity entity : component.keySet()) {
            references.add(new SourceReference(entity, (ServerLevel) entity.level()));
        }
        return List.copyOf(references);
    }

    private static void collectRideComponent(Entity entity, IdentityHashMap<Entity, Boolean> component) {
        if (entity == null || component.put(entity, Boolean.TRUE) != null) return;
        collectRideComponent(entity.getVehicle(), component);
        for (Entity passenger : entity.getPassengers()) collectRideComponent(passenger, component);
    }

    /**
     * Runs inside the existing exact lifecycle lease, never around an ordinary tick.
     * Keep admission closed across nested dismount/tracking/camera publications, then
     * publish each saved player even when vanilla detached it from the named roots.
     */
    public static <T> T withRidePublication(Entity[] roots, java.util.function.Supplier<T> body) {
        IdentityHashMap<Entity, Boolean> component = new IdentityHashMap<>();
        for (Entity root : roots) collectRideComponent(root, component);
        ArrayList<ServerPlayer> players = new ArrayList<>();
        for (Entity entity : component.keySet()) {
            if (entity instanceof ServerPlayer player) players.add(player);
        }
        int marked = 0;
        T result = null;
        Throwable failure = null;
        try {
            synchronized (CAMERAS) {
                for (ServerPlayer player : players) {
                    CameraMutation mutation = CAMERA_MUTATIONS.computeIfAbsent(player, key -> new CameraMutation());
                    mutation.remember(CAMERAS.get(player));
                    mutation.depth = Math.incrementExact(mutation.depth);
                    marked++;
                }
            }
            for (Entity entity : component.keySet()) NetherPortalTransfers.cancelForLifecycle(entity);
            result = body.get();
        } catch (Throwable problem) {
            failure = RegionScheduler.publishFatalAndSelectWinner(problem);
        }
        for (int index = 0; index < marked; index++) {
            ServerPlayer player = players.get(index);
            try {
                publishCamera(player);
            } catch (Throwable problem) {
                failure = RegionScheduler.publishFatalAndSelectWinner(problem);
            } finally {
                synchronized (CAMERAS) {
                    CameraMutation mutation = CAMERA_MUTATIONS.get(player);
                    if (--mutation.depth == 0) CAMERA_MUTATIONS.remove(player);
                }
            }
        }
        if (failure != null) return NetherPortalPlayerAccess.<T, RuntimeException>rethrow(failure);
        return result;
    }

    /** Called by the core's clean Main drain and already-exclusive healthy-stop settlement. */
    public static void drainMetadata() {
        int remaining = METADATA.size();
        while (remaining-- > 0) {
            Reservation reservation = METADATA.peek();
            if (reservation == null || !reservation.runMetadata()) return;
            METADATA.remove(reservation);
        }
    }

    private NetherPortalPlayerAccess() {}

    /** Null means a concurrent lifecycle publication or overlapping request must finish first. */
    public static Reservation reserve(Entity root, Object requestIdentity) {
        Reservation reservation = new Reservation(root, requestIdentity);
        try {
            synchronized (reservation) {
                synchronized (CAMERAS) {
                    if (!reservation.captureFollowers()) return null;
                    for (Entity entity : reservation.custody.keySet()) {
                        if (CAPTURED.containsKey(entity) || ENTITY_SAVES.containsKey(entity)
                                || entity instanceof ServerPlayer player && CHUNK_SENDS.containsKey(player)) return null;
                    }
                    for (Entity entity : reservation.custody.keySet()) CAPTURED.put(entity, reservation);
                }
                for (Entity entity : reservation.custody.keySet()) {
                    if (entity instanceof ServerPlayer player) {
                        PlayerReservation token = new PlayerReservation(reservation, player);
                        synchronized (token.state) {
                            token.validateIdentity();
                            reservation.players.add(token);
                            AsyncIslandPlayerTicks.enqueueNetherMarkerLocked(token);
                        }
                    }
                }
                reservation.inline = reservation.players.isEmpty() && !reservation.transmitsWaypoint();
                return reservation;
            }
        } catch (Throwable failure) {
            // No arrival can exist before worker entry; cancellation still runs outside the monitor.
            Throwable winner = RegionScheduler.publishFatalAndSelectWinner(failure);
            reservation.cancel(winner);
            return NetherPortalPlayerAccess.<Reservation, RuntimeException>rethrow(winner);
        }
    }

    /** A capability for this captured player only, never for a neighboring owner. */
    public static boolean owns(ServerPlayer player) {
        Reservation current = CURRENT.get();
        return current != null && !current.metadata
                && NetherPortalTransfers.currentTransfer() == current.request
                && current.custody.containsKey(player) && NetherPortalTransfers.owns(player);
    }

    /** Capture a Main-only builder at its vanilla position, before evaluating it. */
    public static boolean deferMetadata(Runnable operation) {
        Reservation current = CURRENT.get();
        if (current == null || current.metadata || current.inline) return false;
        return current.deferMetadata(operation);
    }

    /** The source list is a structural snapshot; only captured identities may be queried by vanilla. */
    public static List<ServerPlayer> spectatorCandidates(List<ServerPlayer> sourceSnapshot) {
        Reservation current = CURRENT.get();
        if (current == null || current.metadata) return sourceSnapshot;
        current.requireWorker();
        if (current.players.isEmpty() || sourceSnapshot.isEmpty()) return List.of();
        ArrayList<ServerPlayer> captured = new ArrayList<>(Math.min(sourceSnapshot.size(), current.players.size()));
        for (ServerPlayer player : sourceSnapshot) {
            if (!current.tree.containsKey(player)) continue;
            if (!NetherPortalTransfers.owns(player)) {
                throw new IllegalStateException("Nether spectator candidate lost its exact player claim");
            }
            captured.add(player);
        }
        return List.copyOf(captured);
    }


    static final class PlayerReservation {
        final Reservation reservation;
        final ServerPlayer player;
        final ServerGamePacketListenerImpl listener;
        final AsyncIslandPlayerTicks.PlayerState state;
        final AsyncIslandPlayerTicks.InboundPacket marker;
        final long lifecycle;
        boolean installed;

        PlayerReservation(Reservation reservation, ServerPlayer player) {
            this.reservation = reservation;
            this.player = player;
            listener = Objects.requireNonNull(player.connection, "Nether player listener");
            state = AsyncIslandPlayerTicks.reserveNetherListener(player);
            lifecycle = ((PlayerTickPhaseStamp) player).fabrolia$playerLifecycleEpoch();
            marker = AsyncIslandPlayerTicks.InboundPacket.listenerTick(listener, () -> {
                throw new IllegalStateException("Nether exclusion marker executed as a listener body");
            });
        }

        void validateIdentity() {
            if (player.connection != listener || listener.player != player
                    || ((AsyncIslandListenerAccess) listener).fabrolia$asyncIslandPlayerState() != state
                    || state.listenerGate != null && state.listenerGate != listener) {
                throw new IllegalStateException("Nether reservation crossed player/listener identity");
            }
        }

        boolean ready() {
            synchronized (state) {
                validateIdentity();
                return state.netherReservation == this
                        && (state.session.view().phase() == IslandPlayerTurnSession.Phase.MAIN
                        || AsyncIslandPlayerTicks.netherSourceReadyLocked(this))
                        && state.mainIngress.peekFirst() == marker
                        && !state.mainIngressHandlerActive && state.mainExecutionLeases == 0
                        && !state.mainEntityFallbackPending && state.activeMainEntityFallback == null
                        && state.lifecycleMutationBodyDepth == 0 && !state.mainDisconnectPending
                        && state.trackingTopologyGap == null;
            }
        }
    }

    public static final class Reservation implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        private final Entity root;
        private final Object request;
        private final IdentityHashMap<Entity, Boolean> tree = new IdentityHashMap<>();
        private final IdentityHashMap<Entity, SourceReference> custody = new IdentityHashMap<>();
        private List<Entity> sourceReferences;
        private List<SourceReference> sourceLocations;
        private final ArrayList<PlayerReservation> players = new ArrayList<>();
        private final ArrayList<Runnable> outbox = new ArrayList<>();
        private final ArrayList<Entity> metadataArrivals = new ArrayList<>();
        private boolean metadataClosed;
        /** Closed with arrival readiness, independently of metadata drain and terminal cleanup. */
        private boolean publicationClosed;
        private ArrayList<ArrivalPublication> arrivalPublications;
        private final IdentityHashMap<ServerGamePacketListenerImpl, DeferredDisconnect> disconnects = new IdentityHashMap<>();
        private final ArrayList<DeferredDisconnect> disconnectOrder = new ArrayList<>();
        private ServerGamePacketListenerImpl deliveringDisconnect;
        private List<ServerPlayer> references;
        private boolean entered;
        private boolean executionReserved;
        private boolean active;
        private boolean metadata;
        /** Fixed by {@link #reserve}: no player and no transmitting waypoint in custody. */
        private boolean inline;
        private boolean queued;
        private boolean cancellationQueued;
        private boolean canceled;
        private boolean metadataCompleted;
        private boolean terminal;
        private Throwable failure;
        private Runnable releaseSource;

        private Reservation(Entity root, Object request) {
            this.root = Objects.requireNonNull(root);
            this.request = Objects.requireNonNull(request);
            collect(root);
        }

        /** Called under CAMERAS: foreign ride topology is consumed only from immutable publication. */
        private boolean captureFollowers() {
            boolean added;
            do {
                added = false;
                for (var entry : CAMERAS.entrySet()) {
                    CameraLink link = entry.getValue();
                    if (link.level == root.level() && tree.containsKey(link.target)) {
                        if (CAMERA_MUTATIONS.containsKey(entry.getKey())) return false;
                        if (tree.put(entry.getKey(), Boolean.TRUE) == null) added = true;
                        for (SourceReference reference : link.rideComponent) {
                            custody.put(reference.entity, reference);
                        }
                    }
                }
            } while (added);
            for (CameraMutation mutation : CAMERA_MUTATIONS.values()) {
                for (CameraLink relation : mutation.relations) {
                    if (relation.level == root.level() && tree.containsKey(relation.target)) return false;
                }
            }
            for (Entity entity : custody.keySet()) {
                if (entity instanceof ServerPlayer player && CAMERA_MUTATIONS.containsKey(player)) return false;
            }
            sourceReferences = List.copyOf(custody.keySet());
            sourceLocations = List.copyOf(custody.values());
            return true;
        }

        /**
         * Waypoint untracking happens in the source removal, before any arrival
         * exists, so the mode is decided on the source tree. The copy inherits
         * the attribute, so this equals the arrival-side answer.
         */
        private boolean transmitsWaypoint() {
            for (Entity entity : custody.keySet()) {
                if (entity instanceof net.minecraft.world.waypoints.WaypointTransmitter transmitter
                        && transmitter.isTransmittingWaypoint()) return true;
            }
            return false;
        }

        /** Exact source custody, including vehicles/co-passengers which vanilla does not teleport. */
        public List<Entity> sourceReferences() {
            if (sourceReferences == null) throw new IllegalStateException("Nether source references precede capture");
            return sourceReferences;
        }

        public List<SourceReference> sourceLocations() {
            if (sourceLocations == null) throw new IllegalStateException("Nether source locations precede capture");
            return sourceLocations;
        }

        public boolean isTeleportCandidate(Entity entity) {
            return tree.containsKey(entity);
        }

        private void collect(Entity entity) {
            if (tree.put(entity, Boolean.TRUE) != null) {
                throw new IllegalStateException("Nether passenger tree contains a cycle");
            }
            custody.put(entity, new SourceReference(entity, (ServerLevel) entity.level()));
            for (Entity passenger : entity.getPassengers()) collect(passenger);
        }

        public synchronized boolean ready() {
            RegionScheduler.throwWorkerFailureIfAny();
            if (terminal || active || queued) return false;
            for (PlayerReservation player : players) if (!player.ready()) return false;
            if (references == null) references = players.stream().map(token -> token.player).toList();
            return true;
        }

        public synchronized List<ServerPlayer> references() {
            if (references == null) throw new IllegalStateException("Nether player references precede readiness");
            return references;
        }

        public synchronized boolean reserveExecution() {
            if (executionReserved || entered) {
                throw new IllegalStateException("Nether player execution custody reserved twice");
            }
            if (!ready()) return false;
            if (CURRENT.get() != null
                    || NetherPortalTransfers.currentTransfer() != request
                    || !NetherPortalTransfers.owns(root)) {
                throw new IllegalStateException("Nether player entry lacks exact transfer authority");
            }
            for (Entity entity : custody.keySet()) {
                if (!NetherPortalTransfers.owns(entity)) {
                    throw new IllegalStateException("Nether passenger is outside core claims");
                }
            }
            for (PlayerReservation token : players) {
                synchronized (token.state) {
                    token.validateIdentity();
                    if (((PlayerTickPhaseStamp) token.player).fabrolia$playerLifecycleEpoch() != token.lifecycle
                            || token.player.hasDisconnected() || token.player.isRemoved()) {
                        return false;
                    }
                    if (!AsyncIslandPlayerTicks.closeNetherSourceOwnerLocked(token)) return false;
                }
            }
            executionReserved = true;
            return true;
        }

        public void releaseExecution() {
            boolean cancelNow;
            Throwable problem;
            synchronized (this) {
                if (active || entered) return;
                executionReserved = false;
                cancelNow = canceled;
                problem = failure;
            }
            if (cancelNow) cancel(problem);
        }

        public Scope enter() {
            Throwable winner;
            synchronized (this) {
                if (!executionReserved || entered || canceled || terminal
                        || CURRENT.get() != null
                        || NetherPortalTransfers.currentTransfer() != request) {
                    throw new IllegalStateException("Nether body lost its reserved player execution custody");
                }
                Scope scope = new Scope(this);
                try {
                    CURRENT.set(this);
                    entered = true;
                    active = true;
                    return scope;
                } catch (Throwable problem) {
                    winner = RegionScheduler.publishFatalAndSelectWinner(problem);
                    CURRENT.remove();
                }
            }
            // Entry has not published an arrival; keep cleanup outside the monitor regardless.
            cancel(winner);
            return NetherPortalPlayerAccess.<Scope, RuntimeException>rethrow(winner);
        }

        private void requireWorker() {
            if (!active || metadata || NetherPortalTransfers.currentTransfer() != request) {
                throw new IllegalStateException("Nether metadata escaped its exact worker body");
            }
            RegionScheduler.throwWorkerFailureIfAny();
        }

        /** Append a later arrival callback without granting any source or island authority. */
        public synchronized boolean deferMetadata(Runnable operation) {
            RegionScheduler.throwWorkerFailureIfAny();
            if (failure != null) return NetherPortalPlayerAccess.<Boolean, RuntimeException>rethrow(failure);
            if (canceled) throw new IllegalStateException("Canceled Nether metadata cannot accept a success body");
            if (metadataClosed || terminal) return false;
            if (CURRENT.get() == this) requireWorker();
            outbox.add(Objects.requireNonNull(operation));
            return true;
        }

        private void drainOutbox() {
            for (int index = 0;; index++) {
                Runnable operation;
                synchronized (this) {
                    RegionScheduler.throwWorkerFailureIfAny();
                    if (index == outbox.size()) {
                        metadataClosed = true;
                        // Readiness stays closed through source UUID retirement in finish().
                        return;
                    }
                    operation = outbox.get(index);
                }
                // Never hold the reservation monitor across a manager callback.
                RegionScheduler.runSuccessBodyIfHealthy(operation);
            }
        }

        public void complete(Runnable releaseSourceReservations) {
            Throwable winner;
            synchronized (this) {
                if (!entered || active || queued || terminal) {
                    throw new IllegalStateException("Nether player completion outside closed worker body");
                }
                releaseSource = Objects.requireNonNull(releaseSourceReservations);
                try {
                    // Tail reservations always queue: even without a captured
                    // builder their arrivals must settle on Main. Inline
                    // reservations defer nothing and finish here, still under
                    // the cohort claims (SOURCES cleared before either island
                    // is released).
                    if (players.isEmpty() && outbox.isEmpty() && metadataArrivals.isEmpty()) {
                        RegionScheduler.runSuccessBodyIfHealthy(() -> {
                            finish();
                            terminal = true;
                        });
                        return;
                    }
                    queued = true;
                    METADATA.add(this);
                    return;
                } catch (Throwable problem) {
                    winner = RegionScheduler.publishFatalAndSelectWinner(problem);
                }
            }
            cancel(winner);
            NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(winner);
        }

        private boolean runMetadata() {
            synchronized (this) {
                if (terminal) return true;
                if (active || !queued || CURRENT.get() != null) {
                    throw new IllegalStateException("Nether metadata tail entered twice");
                }
                active = true;
                metadata = true;
            }
            Throwable thrown = null;
            metadataCompleted = false;
            try {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    metadataCompleted = NetherPortalTransfers.tryRunPlayerMetadata(request, () -> {
                    try {
                        CURRENT.set(this);
                        for (PlayerReservation token : players) {
                            synchronized (token.state) {
                                token.validateIdentity();
                                if (AsyncIslandPlayerTicks.netherTerminalLocked(token)) {
                                    throw new IllegalStateException("Nether entered player terminalized before its metadata tail");
                                }
                                if (token.state.netherReservation != token || !token.ready()) {
                                    throw new IllegalStateException("Nether metadata lost listener exclusion");
                                }
                            }
                        }
                        drainOutbox();
                    } finally {
                        CURRENT.remove();
                    }
                    });
                    if (metadataCompleted) completeMetadata();
                });
            } catch (Throwable problem) {
                thrown = RegionScheduler.publishFatalAndSelectWinner(problem);
            }
            boolean cancelNow;
            Throwable cancellationFailure;
            synchronized (this) {
                active = false;
                metadata = false;
                if (thrown != null) failure = thrown;
                cancelNow = failure != null || canceled;
                cancellationFailure = failure;
            }
            if (cancelNow) cancel(cancellationFailure);
            if (thrown != null) NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(thrown);
            return metadataCompleted || terminal;
        }

        /** Called only after tryRunPlayerMetadata returned and released every cohort claim. */
        private void completeMetadata() {
            synchronized (this) {
                if (canceled || failure != null) return;
                finish();
            }
            deliverDisconnects();
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                synchronized (this) {
                    if (canceled || failure != null) return;
                    releaseMarkers(true);
                    disconnects.clear();
                    disconnectOrder.clear();
                    terminal = true;
                }
            });
            Throwable problem = settleArrivalPublications(true, null);
            if (problem != null) NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(problem);
        }

        private Throwable settleArrivalPublications(boolean publish, Throwable problem) {
            ArrayList<ArrivalPublication> publications;
            synchronized (this) {
                publicationClosed = true;
                publications = arrivalPublications;
                arrivalPublications = null;
                if (failure != null) problem = failure;
                if (canceled) publish = false;
            }
            if (problem != null) problem = RegionScheduler.publishFatalAndSelectWinner(problem);
            if (publications == null) return problem;
            if (publish && problem == null) {
                try {
                    for (ArrivalPublication publication : publications)
                        RegionScheduler.runSuccessBodyIfHealthy(publication::publish);
                    return null;
                } catch (Throwable publicationFailure) {
                    // Select W before releasing even the offer-then-throw task's token.
                    problem = RegionScheduler.publishFatalAndSelectWinner(publicationFailure);
                }
            }
            // Also cancel already-offered members: a failing offer can retain its TickTask.
            for (ArrivalPublication publication : publications) {
                try {
                    publication.cancel();
                } catch (Throwable cleanupFailure) {
                    problem = RegionScheduler.publishFatalAndSelectWinner(cleanupFailure);
                }
            }
            return problem;
        }

        private void finish() {
            RegionScheduler.commitSuccessIfHealthy(() -> {});
            Runnable release = releaseSource;
            releaseSource = null;
            release.run();
            unregisterCaptured();
            outbox.clear();
        }

        public void cancel(Throwable problem) {
            boolean publishDisconnect = false;
            Throwable cleanupFailure = null;
            synchronized (this) {
                if (terminal && problem == null && failure == null) return;
                if (!terminal && problem == null && entered) {
                    NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(
                            RegionScheduler.publishFatalAndSelectWinner(new IllegalStateException(
                                    "An entered Nether player must settle metadata before healthy cancellation")));
                }
                if (problem != null) failure = RegionScheduler.publishFatalAndSelectWinner(problem);
                if (!terminal) {
                    canceled = true;
                    if (active || executionReserved && !entered) return;
                    try {
                        METADATA.remove(this);
                        if (failure == null && !disconnects.isEmpty()) {
                            unregisterCaptured();
                            outbox.clear();
                            if (!cancellationQueued) {
                                cancellationQueued = true;
                                publishDisconnect = true;
                            }
                        } else {
                            try {
                                unregisterCaptured();
                                releaseMarkers(failure == null);
                            } finally {
                                outbox.clear();
                                disconnects.clear();
                                disconnectOrder.clear();
                                terminal = true;
                            }
                        }
                    } catch (Throwable cleanup) {
                        cleanupFailure = RegionScheduler.publishFatalAndSelectWinner(cleanup);
                    }
                } else {
                    METADATA.remove(this);
                }
            }
            // Even a failed terminal tail must release the list it has not dispatched.
            Throwable publicationFailure = settleArrivalPublications(false, cleanupFailure);
            if (publishDisconnect && publicationFailure == null) {
                try {
                    RegionScheduler.enqueueGlobalMainOnly(this);
                } catch (Throwable rejected) {
                    Throwable winner = RegionScheduler.publishFatalAndSelectWinner(rejected);
                    cancel(winner);
                    NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(winner);
                }
            }
            if (cleanupFailure != null || publicationFailure != null && publicationFailure != failure)
                NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(publicationFailure);
        }


        /** The already-accepted disconnect keeps its listener marker until its real Main body closes. */
        @Override
        public void run() {
            synchronized (this) {
                if (terminal) return;
                if (!cancellationQueued || active || entered) {
                    throw new IllegalStateException("Nether canceled disconnect lost its exact custody");
                }
                active = true;
            }
            Throwable problem = null;
            try {
                deliverDisconnects();
            } catch (Throwable thrown) {
                problem = RegionScheduler.publishFatalAndSelectWinner(thrown);
            }
            synchronized (this) {
                active = false;
                if (problem != null) failure = problem;
                try {
                    releaseMarkers(failure == null);
                } finally {
                    disconnects.clear();
                    disconnectOrder.clear();
                    terminal = true;
                }
            }
            if (problem != null) NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(problem);
        }

        private void deliverDisconnects() {
            for (int index = 0;; index++) {
                DeferredDisconnect disconnect;
                synchronized (this) {
                    if (index == disconnectOrder.size()) return;
                    disconnect = disconnectOrder.get(index);
                    deliveringDisconnect = disconnect.listener;
                }
                try {
                    RegionScheduler.runSuccessBodyIfHealthy(disconnect.body);
                } finally {
                    synchronized (this) { deliveringDisconnect = null; }
                }
            }
        }

        @Override
        public void cancelQueuedBeforeStart() {
            Throwable winner = RegionScheduler.workerFailureOrNull();
            if (winner == null) winner = new IllegalStateException("Healthy disconnect continuation cannot be discarded");
            cancel(winner);
        }

        @Override
        public void cancelQueuedBeforeStartWithFailure(Throwable primary) {
            cancel(Objects.requireNonNull(primary));
        }

        private void unregisterCaptured() {
            synchronized (CAMERAS) {
                for (Entity entity : custody.keySet()) CAPTURED.remove(entity, this);
                metadataClosed = true;
                unregisterArrivalMetadata();
            }
        }

        private void unregisterArrivalMetadata() {
            synchronized (CAMERAS) {
                publicationClosed = true;
                for (Entity entity : metadataArrivals) {
                    if (ARRIVAL_METADATA.remove(entity, this))
                        ((ParallelProcessor.TickGuard) entity).fabrolia$tickLease().setArrivalPending(false);
                }
                metadataArrivals.clear();
            }
        }

        private void releaseMarkers(boolean resume) {
            Throwable cleanupFailure = null;
            for (PlayerReservation token : players) {
                try {
                    synchronized (token.state) {
                        if (!token.installed) continue;
                        AsyncIslandPlayerTicks.releaseNetherMarkerLocked(token, resume && cleanupFailure == null);
                    }
                } catch (Throwable problem) {
                    cleanupFailure = RegionScheduler.publishFatalAndSelectWinner(problem);
                }
            }
            if (cleanupFailure != null) NetherPortalPlayerAccess.<Void, RuntimeException>rethrow(cleanupFailure);
        }
    }

    public static final class Scope implements AutoCloseable {
        private Reservation reservation;
        private Scope(Reservation reservation) { this.reservation = reservation; }

        @Override
        public void close() {
            Reservation current = reservation;
            if (current == null || CURRENT.get() != current) {
                throw new IllegalStateException("Nether player scope closed outside its owner");
            }
            boolean cancelNow;
            Throwable failure;
            synchronized (current) {
                CURRENT.remove();
                current.active = false;
                reservation = null;
                cancelNow = current.canceled;
                failure = current.failure;
            }
            if (cancelNow) current.cancel(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <R, T extends Throwable> R rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
