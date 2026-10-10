package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.world.NetherPortalBlockInvoker;
import com.axalotl.fabrolia.common.utils.FabroliaPoiLoadGate;
import com.axalotl.fabrolia.common.utils.PoiSectionReadSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.EntityDimensions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

/** Readiness is not authority: every retry acquires the entire current footprint anew. */
public final class NetherPortalPreparation {
    public record ChunkRef(ServerLevel level, long chunkKey) {}

    public interface PoiAccess {
        PoiPublicationFence fabrolia$portalPublications();
    }

    public interface ChunkAccessBridge {
        CompletableFuture<ChunkResult<ChunkAccess>> fabrolia$portalChunkFuture(
                int x, int z, ChunkStatus status, boolean load);
        void fabrolia$removePortalTicket(Ticket ticket, ChunkPos chunk);
        ChunkAccess fabrolia$portalChunkNow(int x, int z, ChunkStatus status);
    }

    private static final ThreadLocal<Request> READ = new ThreadLocal<>();
    private static final Map<Object, Request> TRANSFER_BORDERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<ServerLevel, PortalBorderSnapshot> BORDERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> QUIESCENT = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Request> GENERATING = new ThreadLocal<>();
    private static final ConcurrentLinkedQueue<Request> STAGED = new ConcurrentLinkedQueue<>();
    private static final Set<Request> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Request> RELEASE = new ConcurrentLinkedQueue<>();
    /** READ/GENERATING scopes open on any thread. Raised before a scope's
     *  ThreadLocal is set and lowered after it is cleared, so a thread inside
     *  a scope always observes it non-zero: the per-call ServerChunkCache.getChunk
     *  hook reads this one shared word and touches no ThreadLocal while zero. */
    private static final java.util.concurrent.atomic.AtomicInteger OPEN_CHUNK_SCOPES =
            new java.util.concurrent.atomic.AtomicInteger();
    // Unique, nonpersistent, loading-only type per request prevents shared-ticket removal.
    private NetherPortalPreparation() {}

    public static Request capture(Entity root, BlockPos entry, ServerLevel source) {
        Request request = new Request(root, entry.immutable(), source);
        STAGED.add(request);
        return request;
    }

    /** Lock-free: whether {@link #serviceClaimFree} has anything to move. */
    static boolean hasStaged() {
        return !STAGED.isEmpty();
    }

    /** Called only after all transfer/source claims have been released. */
    public static void serviceClaimFree() {
        Request request;
        while ((request = STAGED.poll()) != null) {
            synchronized (request) {
                if (!request.closed) ACTIVE.add(request);
            }
        }
    }

    /**
     * Core has terminalized every request and joined its workers. On success it
     * drains tickets first; after W this only detaches references, never world work.
     */
    public static void resetAfterShutdown() {
        Set<Request> requests = retainedRequests();
        for (Request request : requests) {
            if (!request.closed) throw new IllegalStateException("live portal preparation at shutdown reset");
        }
        detachClosed(requests);
        BORDERS.clear();
        READ.remove();
        GENERATING.remove();
        QUIESCENT.remove();
    }

    /** Main, after core cancellation and the affected bodies' actual quiescence. */
    public static void onLevelUnload(ServerLevel level) {
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) {
            throw new IllegalStateException("portal level cleanup requires physical Main");
        }
        BORDERS.remove(level);
        Set<Request> requests = retainedRequests();
        requests.removeIf(request -> !request.closed || !request.referencesLevel(level));
        if (RegionScheduler.workerFailureOrNull() == null) {
            // These requests can also pin other, surviving dimensions. Release
            // every native ticket before discarding its only cleanup handle.
            for (Request request : requests) request.releaseTickets();
        }
        detachClosed(requests);
    }

    private static Set<Request> retainedRequests() {
        Set<Request> requests = new LinkedHashSet<>(STAGED);
        requests.addAll(ACTIVE);
        requests.addAll(RELEASE);
        requests.addAll(TRANSFER_BORDERS.values());
        return requests;
    }

    private static void detachClosed(Set<Request> requests) {
        STAGED.removeIf(requests::contains);
        ACTIVE.removeAll(requests);
        RELEASE.removeIf(requests::contains);
        TRANSFER_BORDERS.values().removeIf(requests::contains);
        for (Request request : requests) request.detachClosed();
    }

    private static void publishBorders(MinecraftServer server) {
        BORDERS.keySet().removeIf(level -> level.getServer() != server);
        for (ServerLevel level : server.getAllLevels()) {
            WorldBorder live = level.getWorldBorder();
            PortalBorderSnapshot previous = BORDERS.get(level);
            if (previous == null || !previous.matches(live)) {
                BORDERS.put(level, new PortalBorderSnapshot(live));
            }
        }
    }

    /** Clean post-tick Main only, never from a server executor task or external permit. */
    public static void serviceMain(MinecraftServer server) {
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("portal preparation requires physical Main");
        }
        RegionScheduler.throwWorkerFailureIfAny();
        if (!RegionScheduler.isActive()) {
            serviceQuiescent(server);
            return;
        }
        publishBorders(server);
        serviceClaimFree();
        Request released;
        while ((released = RELEASE.poll()) != null) released.releaseTickets();
        boolean cold = false;
        for (Request request : ACTIVE) {
            // The pool pause is the expensive part; prepareMain itself is cheap.
            // A request that only waits for loads Main already requested pauses
            // once one of them completes, not on every tick of the load.
            if (request.wantsMainTurn()) { cold = true; break; }
        }
        if (!cold) return;
        if (!RegionScheduler.pauseMainPreparationTurn()) return;
        Throwable failure = null;
        try {
            for (Request request : ACTIVE) {
                synchronized (request) {
                    if (request.closed || !request.needsMain) continue;
                    if (request.source.getServer() != server) {
                        throw new IllegalStateException("portal preparation crossed server lifetime");
                    }
                    RegionScheduler.runSuccessBodyIfHealthy(request::prepareMain);
                }
            }
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        } finally {
            try {
                RegionScheduler.resumeExactPlayerTurn();
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(cleanup);
            }
        }
        if (failure != null) IslandCommands.publishAndPropagate(failure);
    }

    /** Explicit post-join lifecycle or clean post-tick flat-worker compatibility only. */
    public static void serviceQuiescent(MinecraftServer server) {
        if (RegionScheduler.isActive() || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("portal quiescent preparation requires retired workers");
        }
        RegionScheduler.throwWorkerFailureIfAny();
        publishBorders(server);
        serviceClaimFree();
        QUIESCENT.set(true);
        try {
            Request released;
            while ((released = RELEASE.poll()) != null) released.releaseTickets();
            for (Request request : ACTIVE) {
                synchronized (request) {
                    if (request.closed) continue;
                    if (request.source.getServer() != server) {
                        throw new IllegalStateException("portal preparation crossed server lifetime");
                    }
                    if (request.destination != null) {
                        server.managedBlock(() -> published(request.source) && published(request.destination));
                    }
                    request.prepareMain();
                }
            }
        } catch (Throwable failure) {
            IslandCommands.publishAndPropagate(failure);
        } finally {
            QUIESCENT.remove();
        }
    }

    public static WorldBorder snapshotBorder(ServerLevel level) {
        Request request = READ.get();
        if (request == null) request = GENERATING.get();
        if (request == null) {
            // Main metadata tails need the real center/size/warnings for packets.
            // Only an actual worker body may borrow the pinned geometry view.
            if (!(Thread.currentThread() instanceof RegionWorkerThread)) return null;
            Object identity = NetherPortalTransfers.currentTransfer();
            if (identity == null) return null;
            request = TRANSFER_BORDERS.get(identity);
            if (request == null || request.transferIdentity != identity) return null;
        }
        return request.destination == level ? request.border : null;
    }

    public static boolean deferGenerationPublication(ServerLevel level) {
        Request request = GENERATING.get();
        return request != null && request.destination == level;
    }

    /** Whether any thread is inside a portal read or generation scope. */
    public static boolean chunkScopesOpen() { return OPEN_CHUNK_SCOPES.get() != 0; }

    public static boolean readScopeActive() { return READ.get() != null; }

    private static void openScope(ThreadLocal<Request> scope, Request request) {
        OPEN_CHUNK_SCOPES.incrementAndGet();
        try {
            scope.set(request);
        } catch (Throwable failure) {
            OPEN_CHUNK_SCOPES.decrementAndGet();
            throw failure;
        }
    }

    private static void closeScope(ThreadLocal<Request> scope) {
        try {
            scope.remove();
        } finally {
            OPEN_CHUNK_SCOPES.decrementAndGet();
        }
    }

    /** Only the two portal-calculation callsites use this accepted immutable input. */
    public static EntityDimensions portalCalculationDimensions(Entity entity) {
        Request request = READ.get();
        return request != null && request.root == entity ? request.sourceDimensions : null;
    }

    /** Actual stock generation reads retain only their own loading requirements. */
    public static void retainGenerationChunk(ServerLevel level, int x, int z,
                                              ChunkStatus status, boolean load) {
        Request request = GENERATING.get();
        if (request == null || !load) return;
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) {
            throw new IllegalStateException("generation read left physical Main");
        }
        ChunkRef ref = new ChunkRef(level, ChunkPos.pack(x, z));
        ChunkStatus previousStatus = request.required.get(ref);
        if (previousStatus == null || status.isAfter(previousStatus)) request.required.put(ref, status);
        int ticketLevel = ChunkLevel.byStatus(request.required.get(ref));
        Ticket previous = request.tickets.get(ref);
        if (previous != null && previous.getTicketLevel() <= ticketLevel) return;
        if (previous != null) ((ChunkAccessBridge) level.getChunkSource())
                .fabrolia$removePortalTicket(previous, ChunkPos.unpack(ref.chunkKey()));
        Ticket ticket = new Ticket(request.ticketType, ticketLevel);
        level.getChunkSource().addTicket(ticket, ChunkPos.unpack(ref.chunkKey()));
        request.tickets.put(ref, ticket);
    }

    /** Preserves nullable collision lookups while never entering the worker/Main bridge. */
    public static ChunkAccess readChunk(ServerLevel level, int x, int z,
                                        ChunkStatus status, boolean load) {
        Request request = READ.get();
        if (request == null) throw new IllegalStateException("missing portal read scope");
        ChunkRef ref = new ChunkRef(level, ChunkPos.pack(x, z));
        request.chunks.add(ref);
        if (!NetherPortalTransfers.owns(level, new BlockPos(x << 4, 0, z << 4))) {
            RegionizedServer runtime = RegionScheduler.forLevel(level);
            if (runtime != null && runtime.asyncActive
                    && runtime.islandOwnerOfChunk(ref.chunkKey()) == null) request.promote(ref);
            throw ReadinessMiss.INSTANCE;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
        if (chunk == null && load) {
            request.require(ref, ChunkStatus.FULL);
            throw ReadinessMiss.INSTANCE;
        }
        return chunk;
    }

    private static boolean published(ServerLevel level) {
        return ((PoiAccess) level).fabrolia$portalPublications().isPublished();
    }

    /** Vanilla double distance, lower-Y tie break; equal ties retain encounter order. */
    static BlockPos closest(Iterable<BlockPos> positions, BlockPos center, int radius,
                            Predicate<BlockPos> eligible) {
        BlockPos best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (BlockPos pos : positions) {
            if (Math.abs((long) pos.getX() - center.getX()) > radius
                    || Math.abs((long) pos.getZ() - center.getZ()) > radius || !eligible.test(pos)) continue;
            double candidate = pos.distSqr(center);
            if (candidate < distance || candidate == distance && best != null && pos.getY() < best.getY()) {
                best = pos;
                distance = candidate;
            }
        }
        return best;
    }

    static boolean generationDecision(PoiPublicationFence publications,
                                      java.util.function.BooleanSupplier freshSearch,
                                      Runnable create) {
        if (!publications.isPublished()) return false;
        if (freshSearch.getAsBoolean()) return true;
        if (!publications.isPublished()) return false;
        create.run();
        // Pending publications are tolerated (resolve waits for them); a failure
        // swallowed by the native executor must still surface here.
        publications.isPublished();
        return true;
    }

    private static final class ReadinessMiss extends RuntimeException {
        static final ReadinessMiss INSTANCE = new ReadinessMiss();
        private ReadinessMiss() { super(null, null, false, false); }
    }

    public static final class Request implements AutoCloseable {
        private final Entity root;
        private final ServerLevel source;
        private final ServerLevel destination;
        private final BlockPos entry;
        private final Vec3 sourcePosition;
        private final EntityDimensions sourceDimensions;
        private final LinkedHashSet<ChunkRef> chunks = new LinkedHashSet<>();
        private final Map<ChunkRef, ChunkStatus> required = new LinkedHashMap<>();
        private final Map<ChunkRef, CompletableFuture<ChunkResult<ChunkAccess>>> loading = new LinkedHashMap<>();
        private final Map<ChunkRef, Ticket> tickets = new LinkedHashMap<>();
        private final TicketType ticketType = new TicketType(0L, TicketType.FLAG_LOADING);
        private PortalBorderSnapshot border;
        private BlockPos center;
        private int radius;
        private volatile boolean needsMain;
        /** The last Main turn ended waiting only for {@link #awaitedLoads}. */
        private volatile boolean waitingOnlyForLoads;
        /** Loads Main already requested; Main reads these without the request lock. */
        private final Set<CompletableFuture<?>> awaitedLoads =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private boolean generate;
        private boolean denied;
        private volatile boolean closed;
        private Object transferIdentity;
        private TeleportTransition transition;
        private BlockUtil.FoundRectangle created;
        private Direction.Axis sourceAxis;

        private Request(Entity root, BlockPos entry, ServerLevel source) {
            this.root = root;
            this.entry = entry;
            this.source = source;
            this.sourcePosition = root.position();
            this.sourceDimensions = root.getDimensions(root.getPose());
            this.destination = source.getServer().getLevel(source.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER);
            refreshBorder();
            root.getSelfAndPassengers().forEach(entity -> {
                var box = entity.getBoundingBox();
                addBounds(source, (int) Math.floor(box.minX), (int) Math.floor(box.maxX),
                        (int) Math.floor(box.minZ), (int) Math.floor(box.maxZ));
            });
            chunks.add(new ChunkRef(source, ChunkPos.containing(entry).pack()));
        }

        public synchronized List<ChunkRef> footprint() {
            refreshBorder();
            // Only async cells can gain island ownership through normal topology rebuilds.
            // Ownership is per grid cell, and promote() of a second chunk in an already
            // promoted cell is a no-op, so resolve each cell once: the destination square
            // alone spans up to 17x17 chunks and workers repeat this every attempt.
            ServerLevel lastLevel = null;
            RegionizedServer runtime = null;
            it.unimi.dsi.fastutil.longs.LongOpenHashSet checkedCells = null;
            for (ChunkRef ref : List.copyOf(chunks)) {
                if (ref.level() != lastLevel) {
                    lastLevel = ref.level();
                    runtime = RegionScheduler.forLevel(lastLevel);
                    checkedCells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                }
                if (runtime == null || !runtime.asyncActive) continue;
                long cell = runtime.regionizer().grid.keyFor(ref.chunkKey());
                if (checkedCells.add(cell) && runtime.islandOwnerOfCell(cell) == null) promote(ref);
            }
            return List.copyOf(chunks);
        }

        /** Queue precedence hint only; resolve still validates the acquired attempt. */
        public synchronized boolean readyToAttempt() {
            return !closed && (destination == null || !needsMain && (denied || border != null));
        }

        /** Exactly {@link #resolve}'s refusal for a request Main has not serviced yet. */
        public synchronized boolean awaitingMain() {
            return !closed && destination != null && needsMain;
        }

        /** Adds an actual vanilla rider/follower arrival dependency before body entry. */
        public synchronized void requireArrival(ServerLevel level, long chunkKey) {
            if (closed) throw new IllegalStateException("closed portal preparation");
            ChunkRef ref = new ChunkRef(level, chunkKey);
            chunks.add(ref);
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)) == null) {
                require(ref, ChunkStatus.FULL);
            }
            RegionizedServer runtime = RegionScheduler.forLevel(level);
            if (runtime != null && runtime.asyncActive
                    && runtime.islandOwnerOfChunk(chunkKey) == null) promote(ref);
        }

        public synchronized boolean resolve() {
            if (closed) throw new IllegalStateException("closed portal preparation");
            transition = null;
            if (destination == null) return true;
            if (needsMain) return false;
            if (!published(source) || !published(destination)) {
                requestMain();
                return false;
            }
            if (denied) return true;
            if (border == null || BORDERS.get(destination) != border) return false;
            transferIdentity = NetherPortalTransfers.currentTransfer();
            openScope(READ, this);
            try {
                BlockState state = source.getBlockState(entry);
                Vec3 relative;
                sourceAxis = state.getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS).orElse(Direction.Axis.X);
                if (state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
                    BlockUtil.FoundRectangle sourceRectangle = BlockUtil.getLargestRectangleAround(entry,
                            sourceAxis, 21, Direction.Axis.Y, 21, pos -> source.getBlockState(pos) == state);
                    relative = root.getRelativePortalPosition(sourceAxis, sourceRectangle);
                } else {
                    relative = new Vec3(0.5, 0.0, 0.0);
                }
                List<BlockPos> positions = snapshotPositions(false);
                if (positions == null) throw ReadinessMiss.INSTANCE;
                BlockPos selected = closest(positions, center, radius, pos -> border.isWithinBounds(pos)
                        && destination.getBlockState(pos).hasProperty(BlockStateProperties.HORIZONTAL_AXIS));
                BlockUtil.FoundRectangle rectangle;
                TeleportTransition.PostTeleportTransition post;
                if (selected != null) {
                    BlockState portal = destination.getBlockState(selected);
                    rectangle = BlockUtil.getLargestRectangleAround(selected,
                            portal.getValue(BlockStateProperties.HORIZONTAL_AXIS), 21, Direction.Axis.Y, 21,
                            pos -> destination.getBlockState(pos) == portal);
                    post = created != null && rectangle.minCorner.equals(created.minCorner)
                            ? TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
                            : TeleportTransition.PLAY_PORTAL_SOUND.then(entity -> entity.placePortalTicket(selected));
                } else if (root.isSpectator()) {
                    // 26.3 NetherPortalBlock#getExitPortal: a spectator with no
                    // existing exit portal gets no destination, never a new portal.
                    return true;
                } else {
                    generate = true;
                    requestMain();
                    return false;
                }
                transition = NetherPortalBlockInvoker.fabrolia$createDimensionTransition(destination,
                        rectangle, sourceAxis, relative, root, post);
                if (transferIdentity != null) TRANSFER_BORDERS.put(transferIdentity, this);
                return true;
            } catch (ReadinessMiss miss) {
                return false;
            } finally {
                closeScope(READ);
            }
        }

        public synchronized TeleportTransition transition() { return transition; }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            ACTIVE.remove(this);
            if (transferIdentity != null) TRANSFER_BORDERS.remove(transferIdentity, this);
            RELEASE.add(this);
        }

        public void cancel() { close(); }

        private synchronized boolean referencesLevel(ServerLevel level) {
            if (source == level || destination == level) return true;
            for (ChunkRef ref : chunks) if (ref.level() == level) return true;
            for (ChunkRef ref : tickets.keySet()) if (ref.level() == level) return true;
            return false;
        }

        private synchronized void detachClosed() {
            if (!closed) throw new IllegalStateException("detaching live portal preparation");
            chunks.clear();
            required.clear();
            loading.clear();
            tickets.clear();
            transition = null;
            created = null;
            border = null;
            transferIdentity = null;
        }

        private void addBounds(ServerLevel level, int minX, int maxX, int minZ, int maxZ) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                for (int x = minX >> 4; x <= maxX >> 4; x++) chunks.add(new ChunkRef(level, ChunkPos.pack(x, z)));
            }
        }

        private void promote(ChunkRef ref) {
            int shift = RegionScheduler.grid().shift;
            BlockPos origin = ref.level() == destination && center != null
                    ? center : BlockPos.containing(sourcePosition);
            long originChunk = ChunkPos.containing(origin).pack();
            int x = ChunkPos.getX(ref.chunkKey()) >> shift;
            int z = ChunkPos.getZ(ref.chunkKey()) >> shift;
            int originX = ChunkPos.getX(originChunk) >> shift;
            int originZ = ChunkPos.getZ(originChunk) >> shift;
            // An actual FULL center promotes its cell to CORE on ordinary rebuild;
            // the real configured grid's one-cell halo covers these dependencies.
            if (Math.abs((long) x - originX) <= 1 && Math.abs((long) z - originZ) <= 1) {
                require(new ChunkRef(ref.level(), originChunk), ChunkStatus.FULL);
                return;
            }
            for (Map.Entry<ChunkRef, ChunkStatus> anchor : required.entrySet()) {
                if (anchor.getKey().level() == ref.level() && anchor.getValue() == ChunkStatus.FULL
                        && (ChunkPos.getX(anchor.getKey().chunkKey()) >> shift) == x
                        && (ChunkPos.getZ(anchor.getKey().chunkKey()) >> shift) == z) return;
            }
            require(ref, ChunkStatus.FULL);
        }

        private void require(ChunkRef ref, ChunkStatus status) {
            ChunkStatus old = required.get(ref);
            if (old == null || status.isAfter(old)) {
                required.put(ref, status);
                loading.remove(ref);
                requestMain();
                return;
            }
            // An unchanged demand whose load is still running is no new Main work:
            // its ticket and future already exist. Worker footprint checks repeat
            // this for every attempt, and re-arming a Main turn for it paused the
            // whole worker pool each tick until the chunk had loaded.
            CompletableFuture<ChunkResult<ChunkAccess>> pending = loading.get(ref);
            if (pending != null && !pending.isDone()) awaitLoad(pending);
            else requestMain();
        }

        /** New Main work: a demand, ticket, publication, border or generation step. */
        private void requestMain() {
            waitingOnlyForLoads = false;
            needsMain = true;
        }

        /** Main already requested this load; only its completion enables progress. */
        private void awaitLoad(CompletableFuture<?> load) {
            awaitedLoads.add(load);
            needsMain = true;
        }

        /**
         * Whether a Main turn can progress this request now. A request that waits
         * only for loads Main already requested would repeat the same prepareMain
         * result, so it does not pause the worker pool until one of them completes.
         */
        boolean wantsMainTurn() {
            if (closed || !needsMain) return false;
            if (!waitingOnlyForLoads) return true;
            if (destination != null && BORDERS.get(destination) != border) return true;
            for (CompletableFuture<?> load : awaitedLoads) {
                if (load.isDone()) return true;
            }
            return false;
        }

        private void refreshBorder() {
            if (destination == null) return;
            PortalBorderSnapshot current = BORDERS.get(destination);
            if (current == null) { requestMain(); return; }
            if (current == border) return;
            // A changed border moves the search square; a pending wait no longer
            // describes the whole remaining Main work.
            if (border != null) waitingOnlyForLoads = false;
            border = current;
            double scale = DimensionType.getTeleportationScale(source.dimensionType(), destination.dimensionType());
            center = border.clampToBounds(sourcePosition.x * scale, sourcePosition.y, sourcePosition.z * scale);
            radius = destination.dimension() == Level.NETHER ? 16 : 128;
            addBounds(destination, center.getX() - radius, center.getX() + radius,
                    center.getZ() - radius, center.getZ() + radius);
        }


        private void prepareMain() {
            awaitedLoads.clear();
            waitingOnlyForLoads = true;
            try {
                prepareMainTurn();
            } finally {
                // Every other reason went through requestMain(), which already cleared it.
                if (!needsMain || awaitedLoads.isEmpty()) waitingOnlyForLoads = false;
            }
        }

        private void prepareMainTurn() {
            if (destination == null) { needsMain = false; return; }
            refreshBorder();
            if (border == null) return;
            needsMain = false;
            if (!published(source) || !published(destination)) { requestMain(); return; }
            List<BlockPos> positions = snapshotPositions(true);
            for (Map.Entry<ChunkRef, ChunkStatus> demand : required.entrySet()) {
                ChunkRef ref = demand.getKey();
                Ticket ticket = new Ticket(ticketType, ChunkLevel.byStatus(demand.getValue()));
                Ticket previous = tickets.get(ref);
                if (previous == null || previous.getTicketLevel() != ticket.getTicketLevel()) {
                    if (previous != null) ((ChunkAccessBridge) ref.level().getChunkSource())
                            .fabrolia$removePortalTicket(previous, ChunkPos.unpack(ref.chunkKey()));
                    ref.level().getChunkSource().addTicket(ticket, ChunkPos.unpack(ref.chunkKey()));
                    tickets.put(ref, ticket);
                }
                CompletableFuture<ChunkResult<ChunkAccess>> future = loading.computeIfAbsent(ref,
                        ignored -> ((ChunkAccessBridge) ref.level().getChunkSource()).fabrolia$portalChunkFuture(
                                ChunkPos.getX(ref.chunkKey()), ChunkPos.getZ(ref.chunkKey()), demand.getValue(), true));
                if (QUIESCENT.get()) ref.level().getChunkSource().mainThreadProcessor.managedBlock(future::isDone);
                if (!future.isDone()) { awaitLoad(future); continue; }
                if (future.join().orElse(null) == null) throw new IllegalStateException("portal chunk preparation returned unloaded chunk");
            }
            if (positions == null || needsMain || !generate || root.isSpectator()) return;
            positions = snapshotPositions(true);
            // A null snapshot has already recorded its own reason.
            if (positions == null || needsMain) { needsMain = true; return; }
            if (!published(destination)) { requestMain(); return; }
            List<BlockPos> freshPositions = positions;
            openScope(GENERATING, this);
            try {
                boolean decided = generationDecision(((PoiAccess) destination).fabrolia$portalPublications(),
                        () -> closest(freshPositions, center, radius, pos -> border.isWithinBounds(pos)
                                && destination.getBlockState(pos).hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) != null,
                        () -> {
                            Optional<BlockUtil.FoundRectangle> result = destination.getPortalForcer().createPortal(center, sourceAxis);
                            created = result.orElse(null);
                            denied = created == null;
                        });
                generate = !decided;
                if (!decided || !published(source) || !published(destination)) requestMain();
                else needsMain = false;
            } finally {
                closeScope(GENERATING);
            }
        }

        private List<BlockPos> snapshotPositions(boolean main) {
            FabroliaPoiLoadGate storage = (FabroliaPoiLoadGate) destination.getPoiManager();
            ArrayList<BlockPos> positions = new ArrayList<>();
            int minX = (center.getX() - radius) >> 4;
            int maxX = (center.getX() + radius) >> 4;
            int minZ = (center.getZ() - radius) >> 4;
            int maxZ = (center.getZ() + radius) >> 4;
            boolean ready = true;
            // Whether every miss is a load Main already requested (see awaitLoad).
            boolean loadsOnly = true;
            // Columns outside these bounds contribute no square-filtered record. Detached
            // snapshots never query that disposable outer ring or call live getOrLoad.
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    if (main) {
                        CompletableFuture<?> prefetched = destination.getPoiManager().prefetch(chunk);
                        if (QUIESCENT.get()) destination.getServer().managedBlock(prefetched::isDone);
                        if (!prefetched.isDone()) { awaitLoad(prefetched); ready = false; continue; }
                        prefetched.join();
                        storage.fabrolia$materializePortalColumn(chunk, destination.getMinSectionY());
                    }
                    boolean valid = true;
                    for (int y = destination.getMinSectionY(); y <= destination.getMaxSectionY(); y++) {
                        Optional<PoiSectionReadSnapshot> section = storage.fabrolia$portalSectionSnapshot(SectionPos.of(chunk, y).asLong());
                        if (section == null) { loadsOnly = false; ready = false; continue; }
                        if (section.isEmpty() || !section.get().isValid()) valid = false;
                        section.ifPresent(snapshot -> snapshot.groupedRecords().stream(type -> type.is(PoiTypes.NETHER_PORTAL), record -> true)
                                .forEach(record -> positions.add(record.getPos().immutable())));
                    }
                    int ensureRadius = Math.floorDiv(radius, 16);
                    ChunkPos centerChunk = ChunkPos.containing(center);
                    if (!valid && Math.abs(x - centerChunk.x()) <= ensureRadius && Math.abs(z - centerChunk.z()) <= ensureRadius
                            && ((ChunkAccessBridge) destination.getChunkSource()).fabrolia$portalChunkNow(x, z, ChunkStatus.EMPTY) == null) {
                        ChunkRef ref = new ChunkRef(destination, chunk.pack());
                        if (!required.containsKey(ref)) require(ref, ChunkStatus.EMPTY);
                        CompletableFuture<ChunkResult<ChunkAccess>> loaded = loading.get(ref);
                        if (loaded == null) { loadsOnly = false; ready = false; }
                        else if (!loaded.isDone()) { awaitLoad(loaded); ready = false; }
                    }
                }
            }
            if (!ready) {
                if (!loadsOnly || !needsMain) requestMain();
                return null;
            }
            for (BlockPos pos : positions) {
                if (Math.abs((long) pos.getX() - center.getX()) <= radius && Math.abs((long) pos.getZ() - center.getZ()) <= radius
                        && border.isWithinBounds(pos) && destination.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                    require(new ChunkRef(destination, ChunkPos.containing(pos).pack()), ChunkStatus.FULL);
                    ready = false;
                }
            }
            return ready ? positions : null;
        }

        private synchronized void releaseTickets() {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                for (Map.Entry<ChunkRef, Ticket> held : tickets.entrySet()) {
                    ((ChunkAccessBridge) held.getKey().level().getChunkSource())
                            .fabrolia$removePortalTicket(held.getValue(), ChunkPos.unpack(held.getKey().chunkKey()));
                }
            });
            tickets.clear();
            loading.clear();
        }
    }
}
