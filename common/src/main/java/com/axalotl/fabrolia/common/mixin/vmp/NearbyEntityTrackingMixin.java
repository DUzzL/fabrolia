package com.axalotl.fabrolia.common.mixin.vmp;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaTrackingDirtyFlag;
import com.axalotl.fabrolia.common.regions.IslandEntityTracking;
import com.axalotl.fabrolia.common.regions.IslandTrackedEntityAccess;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionTaskFanIn;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.TrackedEntityPassAccess;
import com.axalotl.fabrolia.common.regions.TrackerIndexChanges;
import com.axalotl.fabrolia.common.regions.TrackingViewerPass;
import com.axalotl.fabrolia.common.regions.VmpTrackingFanoutGate;
import com.ishland.vmp.common.maps.AreaMap;
import com.ishland.vmp.common.playerwatching.NearbyEntityTracking;
import com.ishland.vmp.mixins.access.IThreadedAnvilChunkStorage;
import com.ishland.vmp.mixins.access.IThreadedAnvilChunkStorageLevelManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;


/**
 * Fabrolia's tick for VMP's nearby-entity tracker.
 *
 * <p>The tick is a discovery pass: it decides which (viewer, tracker) pairs must
 * be re-evaluated and hands every pairing mutation to the tracker's owner (the
 * staged {@code updatePlayer/removePlayer} protocol; the island tail commits).
 * One consistent position snapshot per tracker per tick feeds both the decisions
 * and the next tick's movement test; pairs whose exact vanilla outcome equals
 * the current pairing are not re-staged. The viewer passes are spread over the
 * tick pool and the calling thread. Non-player tracker add/remove never waits for
 * the tick: the structural change is queued and applied at the next tick head
 * (the immediate all-player pairing still happens at add, as the owner-tail bulk
 * bit), so island workers no longer convoy behind the tracker lock.</p>
 */
@Pseudo
@Mixin(targets = "com.ishland.vmp.common.playerwatching.NearbyEntityTracking", remap = false)
public abstract class NearbyEntityTrackingMixin
        implements TrackerIndexChanges.Index<ChunkMap.TrackedEntity, ServerPlayer> {

    @Shadow @Final
    private AreaMap<ChunkMap.TrackedEntity> areaMap;

    @Shadow @Final
    private Reference2ReferenceLinkedOpenHashMap<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> playerTrackers;

    @Shadow @Final
    private Reference2LongOpenHashMap<ChunkMap.TrackedEntity> tracker2ChunkPos;

    @Shadow
    private ChunkMap.DistanceManager ticketManager;

    /** Serializes VMP's structures: the tick, player membership, locked add/remove. */
    @Unique
    private final Object fabrolia$lock = new Object();

    /**
     * Non-player tracker add/remove published without the lock and applied at
     * the next lock holder, plus the fresh trackers whose bulk pairing has not
     * yet reached the viewers' tracked sets (see {@link TrackerIndexChanges}).
     */
    @Unique
    private final TrackerIndexChanges<ChunkMap.TrackedEntity, ServerPlayer> fabrolia$indexChanges =
            new TrackerIndexChanges<>();

    /** Set only by the lock holder while it applies a queued removal. */
    @Unique
    private boolean fabrolia$applyingStructuralChange;

    @Unique
    private long fabrolia$tickStamp;

    /** Last value of the dirty counter seen by a running tick (idle-skip predicate). */
    @Unique
    private long fabrolia$lastSeenDirty = -1;

    /** Position each viewer's previous pass decided with; guarded by the lock (map), per viewer (value). */
    @Unique
    private final Reference2ObjectOpenHashMap<ServerPlayer, ViewerCursor> fabrolia$viewerCursors =
            new Reference2ObjectOpenHashMap<>();

    @Unique
    private ChunkMap.TrackedEntity[] fabrolia$snapshotTrackers = new ChunkMap.TrackedEntity[0];

    @Unique
    private long[] fabrolia$snapshotPreviousChunks = new long[0];

    @Unique
    private long[] fabrolia$snapshotChunks = new long[0];

    @Shadow
    protected abstract void tickStaging();

    @Shadow
    public abstract void removeEntityTracker(ChunkMap.TrackedEntity tracker);

    @Shadow
    private void addEntityTrackerAreaMap(ChunkMap.TrackedEntity tracker) {
        throw new AssertionError("shadow");
    }

    @Shadow
    private static ChunkPos getEntityChunkPos(Entity entity) {
        throw new AssertionError("shadow");
    }

    @Shadow
    private int getChunkViewDistance(ChunkMap.TrackedEntity tracker) {
        throw new AssertionError("shadow");
    }

    @WrapOperation(method = "tickStaging",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"))
    private void fabrolia$captureStagedUpdate(ChunkMap.TrackedEntity tracker,
                                           java.util.List<ServerPlayer> players,
                                           Operation<Void> original) {
        if (tracker instanceof IslandTrackedEntityAccess access
                && IslandEntityTracking.stageBulkPairingRefreshIfOwned(access)) {
            return;
        }
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(tracker, players));
    }

    /** VMP's addEntityTracker loops over every player one-by-one. Collapse the
     *  whole initial burst into the same one-bit owner-tail bulk refresh. */
    @WrapOperation(method = "addEntityTracker",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayer("
                            + "Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void fabrolia$captureInitialTrackerPlayers(
            ChunkMap.TrackedEntity tracker, ServerPlayer player,
            Operation<Void> original) {
        if (tracker instanceof IslandTrackedEntityAccess access
                && IslandEntityTracking.stageBulkPairingRefreshIfOwned(access)) {
            return;
        }
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(tracker, player));
    }

    /**
     * A non-player tracker joins without the tracker lock: its immediate pairing
     * with every player is the owner-tail bulk bit (exactly what VMP's staging
     * loop collapses to), and its area-index insertion is queued for the next
     * tick head. Players, and trackers whose bulk pairing cannot be staged,
     * keep VMP's synchronous body under the lock.
     */
    @WrapMethod(method = "addEntityTracker")
    private void fabrolia$lockAdd(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            // A removal that already entered for this generation wins: never index it.
            if (!((TrackedEntityPassAccess) tracker).fabrolia$vmpRemoved()
                    && !this.fabrolia$queueAdd(tracker)) {
                synchronized (fabrolia$lock) { original.call(tracker); }
            }
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    /**
     * A non-player tracker leaves without the tracker lock. ChunkMap.removeEntity
     * calls {@code broadcastRemoved} right after this method, which retires the
     * tracker generation and removes every viewer; the area index forgets it at
     * the next tick head, where a retired tracker can no longer pair. The
     * tombstone is set first, so an add racing ahead of or queued behind this
     * removal can never index the generation.
     */
    @WrapMethod(method = "removeEntityTracker")
    private void fabrolia$lockRemove(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (this.fabrolia$applyingStructuralChange && Thread.holdsLock(fabrolia$lock)) {
                original.call(tracker);
                return;
            }
            ((TrackedEntityPassAccess) tracker).fabrolia$markVmpRemoved();
            if (fabrolia$enabled() && !(tracker.entity instanceof ServerPlayer)) {
                this.fabrolia$indexChanges.queueRemove(tracker);
            } else {
                synchronized (fabrolia$lock) {
                    original.call(tracker);
                    this.fabrolia$indexChanges.forget(tracker);
                }
            }
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    /** Every insertion (queued add, VMP staging expiry, VMP direct add) honours the tombstone. */
    @WrapMethod(method = "addEntityTrackerAreaMap")
    private void fabrolia$skipRemovedIndexInsertion(ChunkMap.TrackedEntity tracker,
                                                    Operation<Void> original) {
        TrackedEntityPassAccess pass = (TrackedEntityPassAccess) tracker;
        if (pass.fabrolia$vmpRemoved() || pass.fabrolia$islandTrackingStateRelaxed()
                == IslandTrackedEntityAccess.TRACKING_RETIRED) return;
        original.call(tracker);
    }

    /**
     * VMP refreshes the whole tracker after a player tracker leaves. Fabrolia's
     * tick needs no such refresh (the leaving player's tracker is retired by
     * broadcastRemoved, and viewer passes drop retired trackers silently), and
     * running a full fanned-out tick on the removing thread - an island worker
     * during an A5 respawn - could wait on monitors that thread holds.
     */
    @WrapOperation(method = "removeEntityTracker",
            at = @At(value = "INVOKE",
                    target = "Lcom/ishland/vmp/common/playerwatching/NearbyEntityTracking;tick0()V"))
    private void fabrolia$skipPlayerRemovalRefresh(
            NearbyEntityTracking self, Operation<Void> original) {
        if (fabrolia$enabled()) return;
        original.call(self);
    }

    @Unique
    private boolean fabrolia$queueAdd(ChunkMap.TrackedEntity tracker) {
        if (!fabrolia$enabled() || tracker.entity instanceof ServerPlayer
                || !(tracker instanceof IslandTrackedEntityAccess access)
                || !IslandEntityTracking.stageBulkPairingRefreshIfOwned(access)) {
            return false;
        }
        this.fabrolia$indexChanges.queueAdd(tracker);
        return true;
    }

    @Override
    public boolean fabrolia$indexed(ChunkMap.TrackedEntity tracker) {
        return this.tracker2ChunkPos.containsKey(tracker);
    }

    @Override
    public void fabrolia$insert(ChunkMap.TrackedEntity tracker) {
        this.addEntityTrackerAreaMap(tracker);
    }

    @Override
    public void fabrolia$remove(ChunkMap.TrackedEntity tracker) {
        this.fabrolia$applyingStructuralChange = true;
        try {
            this.removeEntityTracker(tracker);
        } finally {
            this.fabrolia$applyingStructuralChange = false;
        }
    }

    @Override
    public boolean fabrolia$gone(ChunkMap.TrackedEntity tracker) {
        TrackedEntityPassAccess pass = (TrackedEntityPassAccess) tracker;
        return pass.fabrolia$vmpRemoved() || pass.fabrolia$islandTrackingStateRelaxed()
                == IslandTrackedEntityAccess.TRACKING_RETIRED;
    }

    @Override
    public boolean fabrolia$viewersSettled(ChunkMap.TrackedEntity tracker,
                                           java.util.function.Consumer<ServerPlayer> viewerSink) {
        return ((TrackedEntityPassAccess) tracker).fabrolia$passViewersSettled(viewerSink);
    }

    @Override
    public Set<ChunkMap.TrackedEntity> fabrolia$trackedBy(ServerPlayer viewer) {
        return this.playerTrackers.get(viewer);
    }

    @Override
    public boolean fabrolia$absentAndIdle(ChunkMap.TrackedEntity tracker, ServerPlayer viewer) {
        return ((TrackedEntityPassAccess) tracker).fabrolia$passAbsentAndIdle(viewer);
    }

    @Override
    public void fabrolia$removeViewer(ChunkMap.TrackedEntity tracker, ServerPlayer viewer) {
        tracker.removePlayer(viewer);
    }

    /**
     * Forces {@code vmpTracking$isPositionUpdated()} to return true on the next tick0 by
     * resetting the cached prev coords to NaN (NaN != anything, including itself). Called
     * on every {@code NET.addPlayer} so a player who lands in a new dim — including at the
     * same XYZ they had in the old dim — gets picked up by every stationary tracker in
     * range on their first post-teleport tick instead of being blind to non-moving
     * entities until they nudge sub-section. Fabrolia's own tick drops the viewer cursor
     * for the same reason.
     */
    @Unique
    private static final java.lang.reflect.Field fabrolia$VMP_PREV_X;
    @Unique
    private static final java.lang.reflect.Field fabrolia$VMP_PREV_Y;
    @Unique
    private static final java.lang.reflect.Field fabrolia$VMP_PREV_Z;
    static {
        java.lang.reflect.Field x = null, y = null, z = null;
        try {
            Class<?> cls = ServerPlayer.class;
            x = cls.getDeclaredField("vmpTracking$prevX");
            y = cls.getDeclaredField("vmpTracking$prevY");
            z = cls.getDeclaredField("vmpTracking$prevZ");
            x.setAccessible(true);
            y.setAccessible(true);
            z.setAccessible(true);
        } catch (NoSuchFieldException | SecurityException ignored) {
            // VMP missing or field names changed — fabrolia$resetVmpPositionCache becomes a no-op.
            x = y = z = null;
        }
        fabrolia$VMP_PREV_X = x;
        fabrolia$VMP_PREV_Y = y;
        fabrolia$VMP_PREV_Z = z;
    }

    @Unique
    private static void fabrolia$resetVmpPositionCache(ServerPlayer player) {
        if (fabrolia$VMP_PREV_X == null) return;
        try {
            fabrolia$VMP_PREV_X.setDouble(player, Double.NaN);
            fabrolia$VMP_PREV_Y.setDouble(player, Double.NaN);
            fabrolia$VMP_PREV_Z.setDouble(player, Double.NaN);
        } catch (IllegalAccessException failure) {
            NearbyEntityTrackingMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    @WrapMethod(method = "addPlayer")
    private void fabrolia$lockAddPlayer(ServerPlayer player, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            synchronized (fabrolia$lock) {
                original.call(player);
                this.fabrolia$viewerCursors.remove(player);
            }
            fabrolia$resetVmpPositionCache(player);
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    /**
     * Lifecycle removal (disconnect, respawn, dimension change). VMP visits the
     * staged trackers and the player's tracked set; a tracker paired by its
     * immediate bulk refresh may be in neither yet, so queued adds are indexed
     * first and the fresh set is visited too. A respawned player keeps its
     * connection: a missed removal would leave that connection in seenBy and the
     * successor would never receive the spawn.
     */
    @WrapMethod(method = "removePlayer")
    private void fabrolia$lockRemovePlayer(ServerPlayer player, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            synchronized (fabrolia$lock) {
                this.fabrolia$indexChanges.apply(this);
                original.call(player);
                this.fabrolia$indexChanges.removeViewer(player, this);
                this.fabrolia$viewerCursors.remove(player);
            }
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    /** The lock protects native tracker iteration; it does not confer gameplay authority. */
    @WrapMethod(method = "tick")
    private void fabrolia$lockTick(ChunkMap.DistanceManager ticketManager, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(ticketManager));
        }
    }

    /**
     * Every tick0 entry (the tick, and VMP's player-removal refresh) holds the
     * lock. Queued structural changes are applied first in every mode.
     */
    @WrapMethod(method = "tick0")
    private void fabrolia$tick0(Operation<Void> original) {
        if (!Thread.holdsLock(fabrolia$lock)) {
            throw new IllegalStateException("VMP tracker tick entered without its structural lock");
        }
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            this.fabrolia$indexChanges.apply(this);
            if (fabrolia$enabled()) {
                this.fabrolia$tickTrackers();
            } else {
                original.call();
            }
            this.fabrolia$indexChanges.graduate(this);
        });
    }

    @Unique
    private void fabrolia$tickTrackers() {
        long stamp = ++this.fabrolia$tickStamp;
        if (this.fabrolia$idle()) return;
        // Staged trackers (players, and trackers added while Fabrolia tracking
        // was off) keep VMP's per-tick all-player refresh until they enter the index.
        this.tickStaging();

        ExecutorService executor = fabrolia$dispatchExecutor();
        this.fabrolia$snapshotTrackers(executor);

        int viewers = this.playerTrackers.size();
        if (viewers == 0) return;
        ChunkMap chunkMap = ((IThreadedAnvilChunkStorageLevelManager) this.ticketManager).getField_17443();
        ServerLevel level = ((IThreadedAnvilChunkStorage) chunkMap).getWorld();
        TickContext context = new TickContext(stamp, chunkMap, this.ticketManager,
                RegionScheduler.forLevel(level), this.areaMap);
        ViewerJob[] jobs = new ViewerJob[viewers];
        int index = 0;
        for (Map.Entry<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> entry
                : this.playerTrackers.entrySet()) {
            ServerPlayer viewer = entry.getKey();
            ViewerCursor cursor = this.fabrolia$viewerCursors.get(viewer);
            if (cursor == null) {
                cursor = new ViewerCursor();
                this.fabrolia$viewerCursors.put(viewer, cursor);
            }
            jobs[index++] = new ViewerJob(viewer, entry.getValue(), cursor);
        }
        if (this.fabrolia$viewerCursors.size() > viewers) {
            this.fabrolia$viewerCursors.keySet().removeIf(
                    viewer -> !this.playerTrackers.containsKey(viewer));
        }
        if (executor == null || viewers < 2) {
            for (ViewerJob job : jobs) {
                RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$passViewer(job, context));
            }
            return;
        }
        fabrolia$runFanout(executor, viewers,
                (gate, batch) -> gate.runNext(() -> fabrolia$passViewer(jobs[batch], context)));
    }

    /**
     * Idle skip: nothing moved anywhere (dirty counter) and no viewer moved since
     * its previous pass. Queued structural changes bump the counter.
     */
    @Unique
    private boolean fabrolia$idle() {
        long counter = FabroliaTrackingDirtyFlag.current();
        if (counter != this.fabrolia$lastSeenDirty) {
            this.fabrolia$lastSeenDirty = counter;
            return false;
        }
        for (ServerPlayer viewer : this.playerTrackers.keySet()) {
            ViewerCursor cursor = this.fabrolia$viewerCursors.get(viewer);
            if (cursor == null || fabrolia$moved(viewer.position(), cursor.previous)) return false;
        }
        return true;
    }

    /**
     * One position snapshot per indexed tracker (parallel when large), then the
     * serial area-index update for trackers whose chunk changed.
     */
    @Unique
    private void fabrolia$snapshotTrackers(ExecutorService executor) {
        int count = this.tracker2ChunkPos.size();
        if (count == 0) return;
        if (this.fabrolia$snapshotTrackers.length < count) {
            int capacity = Math.max(count, this.fabrolia$snapshotTrackers.length * 3 / 2);
            this.fabrolia$snapshotTrackers = new ChunkMap.TrackedEntity[capacity];
            this.fabrolia$snapshotPreviousChunks = new long[capacity];
            this.fabrolia$snapshotChunks = new long[capacity];
        }
        ChunkMap.TrackedEntity[] trackers = this.fabrolia$snapshotTrackers;
        long[] previousChunks = this.fabrolia$snapshotPreviousChunks;
        long[] chunks = this.fabrolia$snapshotChunks;
        int index = 0;
        for (Reference2LongMap.Entry<ChunkMap.TrackedEntity> entry
                : this.tracker2ChunkPos.reference2LongEntrySet()) {
            trackers[index] = entry.getKey();
            previousChunks[index] = entry.getLongValue();
            index++;
        }
        try {
            if (executor == null || count < 4096) {
                RegionScheduler.runSuccessBodyIfHealthy(() -> fabrolia$snapshotRange(
                        trackers, previousChunks, chunks, 0, count));
            } else {
                int slots = Math.max(1, ParallelProcessor.getPoolSize()) << 1;
                int batch = Math.max(512, (count + slots - 1) / slots);
                int batches = (count + batch - 1) / batch;
                fabrolia$runFanout(executor, batches, (gate, batchIndex) -> {
                    int from = batchIndex * batch;
                    int to = Math.min(from + batch, count);
                    gate.runNext(() -> fabrolia$snapshotRange(
                            trackers, previousChunks, chunks, from, to));
                });
            }
            for (int k = 0; k < count; k++) {
                long chunk = chunks[k];
                if (chunk == previousChunks[k]) continue;
                ChunkMap.TrackedEntity tracker = trackers[k];
                this.areaMap.update(tracker, (int) chunk, (int) (chunk >>> 32),
                        this.getChunkViewDistance(tracker));
                this.tracker2ChunkPos.put(tracker, chunk);
            }
        } finally {
            // The scratch array must not retain removed trackers (and their entities).
            Arrays.fill(trackers, 0, count, null);
        }
    }

    @Unique
    private static void fabrolia$snapshotRange(ChunkMap.TrackedEntity[] trackers,
                                               long[] previousChunks, long[] chunks,
                                               int from, int to) {
        for (int k = from; k < to; k++) {
            ChunkMap.TrackedEntity tracker = trackers[k];
            long chunk = getEntityChunkPos(tracker.entity).pack();
            chunks[k] = chunk;
            ((TrackedEntityPassAccess) tracker).fabrolia$passSnapshot(chunk != previousChunks[k]);
        }
    }

    @Unique
    private static void fabrolia$passViewer(ViewerJob job, TickContext context) {
        ServerPlayer viewer = job.viewer;
        Vec3 position = viewer.position();
        boolean moved = fabrolia$moved(position, job.cursor.previous);
        job.cursor.previous = position;
        Set<ChunkMap.TrackedEntity> inRange = context.areaMap
                .getObjectsInRange(getEntityChunkPos(viewer).pack());
        int viewDistance = ((IThreadedAnvilChunkStorage) context.chunkMap)
                .invokeGetViewDistance(viewer);
        TrackingViewerPass.run(moved, inRange, job.tracked,
                new ViewerTrackers(viewer, position, viewDistance, context));
    }

    @Unique
    private static boolean fabrolia$moved(Vec3 position, Vec3 previous) {
        return previous == null || position.x != previous.x
                || position.y != previous.y || position.z != previous.z;
    }

    /** Effects of one viewer pass; see {@link TrackingViewerPass}. */
    @Unique
    private static final class ViewerTrackers
            implements TrackingViewerPass.Trackers<ChunkMap.TrackedEntity> {
        private final ServerPlayer viewer;
        private final Vec3 position;
        private final int viewDistance;
        private final TickContext context;

        private ViewerTrackers(ServerPlayer viewer, Vec3 position, int viewDistance,
                               TickContext context) {
            this.viewer = viewer;
            this.position = position;
            this.viewDistance = viewDistance;
            this.context = context;
        }

        @Override
        public boolean retired(ChunkMap.TrackedEntity tracker) {
            return ((TrackedEntityPassAccess) tracker).fabrolia$islandTrackingStateRelaxed()
                    == IslandTrackedEntityAccess.TRACKING_RETIRED;
        }

        @Override
        public boolean moved(ChunkMap.TrackedEntity tracker) {
            return ((TrackedEntityPassAccess) tracker).fabrolia$passMoved();
        }

        @Override
        public boolean claimTick(ChunkMap.TrackedEntity tracker) {
            return ((TrackedEntityPassAccess) tracker).fabrolia$passClaimTick(this.context.stamp);
        }

        @Override
        public void tick(ChunkMap.TrackedEntity tracker) {
            TrackedEntityPassAccess pass = (TrackedEntityPassAccess) tracker;
            long sectionChunk = pass.fabrolia$passSectionChunk();
            if (!this.context.ticketManager.inEntityTickingRange(sectionChunk)) return;
            // An attached island cadence publishes this tracker at every unit
            // tail; routing it again would only re-attach it to the same owner.
            if (IslandEntityTracking.islandCadenceCovers(
                    pass, this.context.runtime, sectionChunk)) {
                return;
            }
            // Per-pair fail-stop cut, as before the viewer-granular admission.
            RegionScheduler.throwWorkerFailureIfAny();
            pass.fabrolia$passSendChanges();
        }

        @Override
        public boolean pairingCurrent(ChunkMap.TrackedEntity tracker) {
            return ((TrackedEntityPassAccess) tracker)
                    .fabrolia$passPairingCurrent(this.viewer, this.position, this.viewDistance);
        }

        @Override
        public boolean absentAndIdle(ChunkMap.TrackedEntity tracker) {
            return ((TrackedEntityPassAccess) tracker).fabrolia$passAbsentAndIdle(this.viewer);
        }

        @Override
        public void updatePlayer(ChunkMap.TrackedEntity tracker) {
            RegionScheduler.throwWorkerFailureIfAny();
            tracker.updatePlayer(this.viewer);
        }

        @Override
        public void removePlayer(ChunkMap.TrackedEntity tracker) {
            RegionScheduler.throwWorkerFailureIfAny();
            tracker.removePlayer(this.viewer);
        }
    }

    @Unique
    private static final class TickContext {
        private final long stamp;
        private final ChunkMap chunkMap;
        private final ChunkMap.DistanceManager ticketManager;
        private final RegionizedServer runtime;
        private final AreaMap<ChunkMap.TrackedEntity> areaMap;

        private TickContext(long stamp, ChunkMap chunkMap,
                            ChunkMap.DistanceManager ticketManager,
                            RegionizedServer runtime,
                            AreaMap<ChunkMap.TrackedEntity> areaMap) {
            this.stamp = stamp;
            this.chunkMap = chunkMap;
            this.ticketManager = ticketManager;
            this.runtime = runtime;
            this.areaMap = areaMap;
        }
    }

    @Unique
    private static final class ViewerJob {
        private final ServerPlayer viewer;
        private final ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> tracked;
        private final ViewerCursor cursor;

        private ViewerJob(ServerPlayer viewer,
                          ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> tracked,
                          ViewerCursor cursor) {
            this.viewer = viewer;
            this.tracked = tracked;
            this.cursor = cursor;
        }
    }

    /** Position the viewer's previous pass decided with. */
    @Unique
    private static final class ViewerCursor {
        private Vec3 previous;
    }

    /**
     * Runs {@code batchCount} batches on the tick pool and on the calling thread:
     * helpers and the caller claim batch indices from one counter, so the caller
     * works instead of parking, and helpers that never started are cancelled once
     * every batch is claimed.
     */
    @Unique
    private static void fabrolia$runFanout(ExecutorService executor, int batchCount, GatedBatch body) {
        if (batchCount <= 0) return;
        VmpTrackingFanoutGate gate = new VmpTrackingFanoutGate();
        AtomicInteger nextBatch = new AtomicInteger();
        GatedBatch claiming = (claimGate, ignored) -> {
            int batch;
            while (claimGate.failureOrNull() == null
                    && (batch = nextBatch.getAndIncrement()) < batchCount) {
                body.run(claimGate, batch);
            }
        };
        int helpers = Math.min(batchCount - 1, Math.max(1, ParallelProcessor.getPoolSize()));
        FanoutTask[] tasks = new FanoutTask[Math.max(0, helpers)];
        int submitted = 0;
        try {
            for (int helper = 0; helper < helpers; helper++) {
                FanoutTask task = new FanoutTask(gate, claiming, helper);
                tasks[submitted++] = task;
                gate.runNext(() -> {
                    RegionScheduler.executeTrackedExternal(accepted -> {
                        task.acceptedBody = accepted;
                        executor.execute(task);
                    }, () -> gate.runNext(() -> task.body.run(gate, task.batchIndex)));
                    task.acceptPublication();
                });
            }
            claiming.run(gate, -1);
        } catch (Throwable failure) {
            gate.publishFailure(failure);
        }
        for (int i = 0; i < submitted; i++) {
            tasks[i].cancelUnstarted();
        }
        fabrolia$awaitFanout(tasks, submitted, gate);
    }

    @Unique
    private static void fabrolia$awaitFanout(FanoutTask[] tasks, int count, VmpTrackingFanoutGate gate) {
        boolean interrupted = Thread.interrupted();
        boolean complete;
        do {
            Throwable failure = gate.failureOrNull();
            if (failure != null) {
                for (int i = 0; i < count; i++) {
                    tasks[i].cancelBeforeRun(failure);
                }
            }
            complete = true;
            for (int i = 0; i < count; i++) {
                if (!tasks[i].done.isDone()) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                if (Thread.interrupted()) interrupted = true;
                LockSupport.parkNanos(20_000L);
            }
        } while (!complete);
        if (Thread.interrupted()) interrupted = true;

        for (int i = 0; i < count; i++) {
            try {
                tasks[i].done.join();
            } catch (Throwable encoded) {
                Throwable original = encoded instanceof CompletionException && encoded.getCause() != null
                        ? encoded.getCause() : encoded;
                gate.publishFailure(original);
            }
        }
        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable fatal) {
            gate.publishFailure(fatal);
        }
        if (interrupted) Thread.currentThread().interrupt();

        Throwable failure = gate.failureOrNull();
        if (failure != null) {
            NearbyEntityTrackingMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Unique
    private static boolean fabrolia$enabled() {
        return !FabroliaConfig.disabled && FabroliaConfig.enableFabroliaVmpTracking;
    }

    /** The tick pool when fan-out is allowed, else null (serial on the caller). */
    @Unique
    private static ExecutorService fabrolia$dispatchExecutor() {
        ExecutorService executor = ParallelProcessor.executor;
        return executor != null && !executor.isShutdown() ? executor : null;
    }

    @Unique
    private static final class FanoutTask implements Runnable {
        private static final int OFFERING = 0;
        private static final int ACCEPTED = 1;
        private static final int PUBLICATION_CANCELLED = 2;

        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final VmpTrackingFanoutGate gate;
        private final GatedBatch body;
        private final int batchIndex;
        private final java.util.concurrent.atomic.AtomicInteger state =
                new java.util.concurrent.atomic.AtomicInteger(QUEUED);
        private final java.util.concurrent.atomic.AtomicInteger publication =
                new java.util.concurrent.atomic.AtomicInteger(OFFERING);
        private final Thread publisher = Thread.currentThread();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private Runnable acceptedBody;
        private volatile boolean inlineAttempt;

        private FanoutTask(VmpTrackingFanoutGate gate, GatedBatch body, int batchIndex) {
            this.gate = gate;
            this.body = body;
            this.batchIndex = batchIndex;
        }

        @Override
        public void run() {
            if (this.publication.get() == OFFERING && Thread.currentThread() == this.publisher) {
                this.inlineAttempt = true;
                return;
            }
            if (!this.awaitAcceptedPublication()) return;
            if (!this.state.compareAndSet(QUEUED, RUNNING)) return;
            Throwable failure = null;
            try {
                this.acceptedBody.run();
            } catch (Throwable thrown) {
                failure = this.gate.publishFailure(thrown);
            } finally {
                this.state.set(TERMINAL);
                this.gate.completeTerminal(this.done);
            }
            if (failure != null) {
                NearbyEntityTrackingMixin.<RuntimeException>fabrolia$rethrow(failure);
            }
        }

        void acceptPublication() {
            if (this.publication.compareAndSet(OFFERING, ACCEPTED) && this.inlineAttempt) {
                this.run();
            }
        }

        private boolean awaitAcceptedPublication() {
            int spins = 0;
            while (true) {
                int observed = this.publication.get();
                if (observed != OFFERING) {
                    return observed == ACCEPTED;
                }
                if (++spins < 256) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(20_000L);
                }
            }
        }

        /**
         * Every batch is already claimed: a helper that has not started has no
         * work left. Close its accepted permit without a failure so the caller
         * does not wait for a pool thread merely to observe an empty counter.
         */
        void cancelUnstarted() {
            if (this.publication.get() != ACCEPTED
                    || !this.state.compareAndSet(QUEUED, CANCELLED)) return;
            try {
                if (this.acceptedBody instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                    cancellable.cancelQueuedBeforeStart();
                }
            } catch (Throwable cancellationFailure) {
                this.gate.publishFailure(cancellationFailure);
            } finally {
                this.gate.completeTerminal(this.done);
            }
        }

        void cancelBeforeRun(Throwable failure) {
            if (!this.state.compareAndSet(QUEUED, CANCELLED)) return;
            this.publication.compareAndSet(OFFERING, PUBLICATION_CANCELLED);
            try {
                if (this.acceptedBody instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                    cancellable.cancelQueuedBeforeStartWithFailure(failure);
                }
            } catch (Throwable cancellationFailure) {
                this.gate.publishFailure(cancellationFailure);
            } finally {
                this.gate.completeTerminal(this.done);
            }
        }
    }

    @Unique
    @FunctionalInterface
    private interface GatedBatch {
        void run(VmpTrackingFanoutGate gate, int batchIndex);
    }
}
