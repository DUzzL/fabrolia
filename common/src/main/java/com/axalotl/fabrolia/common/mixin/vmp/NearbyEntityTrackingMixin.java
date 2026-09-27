package com.axalotl.fabrolia.common.mixin.vmp;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaTrackingDirtyFlag;
import com.axalotl.fabrolia.common.regions.IslandEntityTracking;
import com.axalotl.fabrolia.common.regions.IslandTrackedEntityAccess;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionTaskFanIn;
import com.axalotl.fabrolia.common.regions.VmpTrackingFanoutGate;
import com.ishland.vmp.common.maps.AreaMap;
import com.ishland.vmp.common.playerwatching.EntityTrackerExtension;
import com.ishland.vmp.common.playerwatching.ServerPlayerEntityExtension;
import com.ishland.vmp.mixins.access.IThreadedAnvilChunkStorageEntityTracker;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;


/**
 * Keeps VMP's native tracker and packet order on the caller's gameplay owner.
 * Idle ticks may be skipped; island-owned bulk pairing still uses its owner tail.
 */
@Pseudo
@Mixin(targets = "com.ishland.vmp.common.playerwatching.NearbyEntityTracking", remap = false)
public abstract class NearbyEntityTrackingMixin {

    @Shadow @Final
    private AreaMap<ChunkMap.TrackedEntity> areaMap;

    @Shadow @Final
    private Reference2ReferenceLinkedOpenHashMap<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> playerTrackers;

    @Shadow @Final
    private Reference2LongOpenHashMap<ChunkMap.TrackedEntity> tracker2ChunkPos;

    @Shadow @Final
    private ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> trackerTickList;

    @Shadow
    private ChunkMap.DistanceManager ticketManager;


    @Unique
    private final Object fabrolia$lock = new Object();

    /** Last value of dirty counter seen at tick0 entry (used for skip predicate). */
    @Unique
    private long fabrolia$lastSeenDirty = -1;

    /** Per-tracker tick/update requests captured from handleTracker, drained after tick(). */
    @Unique
    private IdentityHashMap<ChunkMap.TrackedEntity, TrackerOps> fabrolia$pendingOps = new IdentityHashMap<>(256);

    /** {tracker, players} pairs captured from tickStaging while {@link #fabrolia$deferStagedUpdates} is set. */
    @Unique
    private ArrayList<Object[]> fabrolia$stagedUpdates = new ArrayList<>(64);

    @Unique
    private boolean fabrolia$deferStagedUpdates;

    @Shadow
    protected abstract void tickStaging();

    @Shadow
    private static ChunkPos getEntityChunkPos(Entity entity) {
        throw new AssertionError("shadow");
    }

    @Shadow
    private int getChunkViewDistance(ChunkMap.TrackedEntity tracker) {
        throw new AssertionError("shadow");
    }

    @Shadow
    private static void tryTickTracker(ChunkMap.TrackedEntity tracker) {
        throw new AssertionError("shadow");
    }

    @Shadow
    private static void tryUpdateTracker(ChunkMap.TrackedEntity tracker, ServerPlayer player) {
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
        if (!this.fabrolia$deferStagedUpdates) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(tracker, players));
        } else {
            this.fabrolia$stagedUpdates.add(new Object[]{tracker, players});
        }
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


    @WrapMethod(method = "addEntityTracker")
    private void fabrolia$lockAdd(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            synchronized (fabrolia$lock) { original.call(tracker); }
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    @WrapMethod(method = "removeEntityTracker")
    private void fabrolia$lockRemove(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            synchronized (fabrolia$lock) { original.call(tracker); }
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    /**
     * Reflective handles for VMP's private {@code vmpTracking$prev{X,Y,Z}} fields on
     * {@code ServerPlayer}. We don't have a public reset API in VMP and we don't want
     * to fork VMP for one method, so we set the fields directly. Resolved once at
     * class init; null if VMP isn't loaded or the field names changed upstream.
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

    /**
     * Forces {@code vmpTracking$isPositionUpdated()} to return true on the next tick0 by
     * resetting the cached prev coords to NaN (NaN != anything, including itself). Called
     * on every {@code NET.addPlayer} so a player who lands in a new dim — including at the
     * same XYZ they had in the old dim — gets picked up by every stationary tracker in
     * range on their first post-teleport tick instead of being blind to non-moving
     * entities until they nudge sub-section.
     */
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
            synchronized (fabrolia$lock) { original.call(player); }
            fabrolia$resetVmpPositionCache(player);
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    @WrapMethod(method = "removePlayer")
    private void fabrolia$lockRemovePlayer(ServerPlayer player, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            synchronized (fabrolia$lock) { original.call(player); }
            FabroliaTrackingDirtyFlag.markDirty();
        });
    }

    /**
     * Skip-when-idle check at tick0 HEAD. Cancels tick0 entirely if nothing has changed since
     * the last tick. Cheap: one atomic read + O(players) field compares.
     */
    @Inject(method = "tick0", at = @At("HEAD"), cancellable = true)
    private void fabrolia$maybeSkipTick0(CallbackInfo ci) {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaVmpTracking) return;

        long counter = FabroliaTrackingDirtyFlag.current();
        if (counter != this.fabrolia$lastSeenDirty) {
            this.fabrolia$lastSeenDirty = counter;
            return;
        }
        // Counter unchanged — no entity moved, no structural change. Confirm no player moved
        // (even sub-section): vmpTracking$isPositionUpdated is a 3-double compare against
        // cached prev coords, trivially fast.
        for (ServerPlayer p : this.playerTrackers.keySet()) {
            try {
                if (((ServerPlayerEntityExtension) p).vmpTracking$isPositionUpdated()) return;
            } catch (ClassCastException ignored) {
                return;
            }
        }
        ci.cancel();
    }

    /** The lock protects native tracker iteration; it does not confer gameplay authority. */
    @WrapMethod(method = "tick")
    private void fabrolia$lockTick(ChunkMap.DistanceManager ticketManager, Operation<Void> original) {
        IdentityHashMap<ChunkMap.TrackedEntity, TrackerOps> snapshot = null;
        synchronized (fabrolia$lock) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(ticketManager));
            if (!this.fabrolia$pendingOps.isEmpty()) {
                snapshot = this.fabrolia$pendingOps;
                this.fabrolia$pendingOps = new IdentityHashMap<>(Math.max(16, snapshot.size()));
            }
        }
        if (snapshot != null) {
            this.fabrolia$drainOps(snapshot);
        }
    }

    @WrapMethod(method = "tick0")
    private void fabrolia$parallelTick0(Operation<Void> original) {
        ExecutorService exec = ParallelProcessor.executor;
        boolean canDispatch = fabrolia$canDispatch() && exec != null && !exec.isShutdown();
        int playerCount = this.playerTrackers.size();
        if (!canDispatch || playerCount < 4) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call());
            return;
        }

        this.fabrolia$deferStagedUpdates = true;
        try {
            RegionScheduler.runSuccessBodyIfHealthy(this::tickStaging);
        } finally {
            this.fabrolia$deferStagedUpdates = false;
        }

        if (!this.fabrolia$stagedUpdates.isEmpty()) {
            ArrayList<Object[]> staged = this.fabrolia$stagedUpdates;
            this.fabrolia$stagedUpdates = new ArrayList<>(Math.max(16, staged.size()));
            int stagedCount = staged.size();
            int stagedSlots = Math.max(1, ParallelProcessor.getPoolSize()) << 1;
            int stagedBatch = Math.max(16, (stagedCount + stagedSlots - 1) / stagedSlots);
            int stagedBatches = (stagedCount + stagedBatch - 1) / stagedBatch;
            fabrolia$runFanout(exec, stagedBatches, (gate, batchIndex) -> {
                int from = batchIndex * stagedBatch;
                int to = Math.min(from + stagedBatch, stagedCount);
                for (int k = from; k < to; k++) {
                    Object[] pair = staged.get(k);
                    ChunkMap.TrackedEntity tracker = (ChunkMap.TrackedEntity) pair[0];
                    @SuppressWarnings("unchecked")
                    List<ServerPlayer> players = (List<ServerPlayer>) pair[1];
                    gate.runNext(() -> tracker.updatePlayers(players));
                }
            });
        }

        int pool = Math.max(1, ParallelProcessor.getPoolSize());
        int trackerCount = this.tracker2ChunkPos.size();
        if (trackerCount >= 4096) {
            ChunkMap.TrackedEntity[] keys = new ChunkMap.TrackedEntity[trackerCount];
            long[] cachedPos = new long[trackerCount];
            int ti = 0;
            for (Reference2LongMap.Entry<ChunkMap.TrackedEntity> e : this.tracker2ChunkPos.reference2LongEntrySet()) {
                keys[ti] = e.getKey();
                cachedPos[ti] = e.getLongValue();
                ti++;
            }

            long[] currentPos = new long[trackerCount];
            int scanBatch = Math.max(512, (trackerCount + (pool << 1) - 1) / (pool << 1));
            int scanBatches = (trackerCount + scanBatch - 1) / scanBatch;
            fabrolia$runFanout(exec, scanBatches, (gate, batchIndex) -> {
                int from = batchIndex * scanBatch;
                int to = Math.min(from + scanBatch, trackerCount);
                for (int k = from; k < to; k++) {
                    currentPos[k] = getEntityChunkPos(keys[k].entity).pack();
                }
            });

            for (int k = 0; k < trackerCount; k++) {
                long cur = currentPos[k];
                if (cur != cachedPos[k]) {
                    int index = k;
                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        ChunkMap.TrackedEntity t = keys[index];
                        this.areaMap.update(t, (int) cur, (int) (cur >>> 32), this.getChunkViewDistance(t));
                        this.tracker2ChunkPos.put(t, cur);
                    });
                }
            }
        } else {
            Iterator<Reference2LongMap.Entry<ChunkMap.TrackedEntity>> entryIt =
                    this.tracker2ChunkPos.reference2LongEntrySet().iterator();
            while (entryIt.hasNext()) {
                Reference2LongMap.Entry<ChunkMap.TrackedEntity> e = entryIt.next();
                ChunkMap.TrackedEntity tracker = e.getKey();
                ChunkPos pos = getEntityChunkPos(tracker.entity);
                long packed = pos.pack();
                if (packed != e.getLongValue()) {
                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        this.areaMap.update(tracker, pos.x(), pos.z(), this.getChunkViewDistance(tracker));
                        e.setValue(packed);
                    });
                }
            }
        }

        Set<ChunkMap.TrackedEntity> phase2TickList = ConcurrentHashMap.newKeySet();
        RegionScheduler.runSuccessBodyIfHealthy(this.trackerTickList::clear);
        @SuppressWarnings("unchecked")
        Map.Entry<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>>[] entries = new Map.Entry[playerCount];
        int idx = 0;
        for (Map.Entry<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> e : this.playerTrackers.entrySet()) {
            entries[idx++] = e;
        }

        int batchSize = Math.max(1, (playerCount + pool - 1) / pool);
        int batchCount = (playerCount + batchSize - 1) / batchSize;
        fabrolia$runFanout(exec, batchCount, (gate, batchIndex) -> {
            int from = batchIndex * batchSize;
            int to = Math.min(from + batchSize, playerCount);
            for (int k = from; k < to; k++) {
                int playerIndex = k;
                gate.runNext(() -> this.fabrolia$processPlayer(entries[playerIndex], phase2TickList, gate));
            }
        });

        for (ChunkMap.TrackedEntity tracker : phase2TickList) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> ((EntityTrackerExtension) tracker).updatePosition());
        }
    }

    @Unique
    private void fabrolia$processPlayer(
            Map.Entry<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> entry,
            Set<ChunkMap.TrackedEntity> tickList, VmpTrackingFanoutGate gate) {
        ServerPlayer player = entry.getKey();
        Set<ChunkMap.TrackedEntity> inRange = this.areaMap.getObjectsInRange(getEntityChunkPos(player).pack());
        ServerPlayerEntityExtension ext = (ServerPlayerEntityExtension) player;
        boolean posUpdated = ext.vmpTracking$isPositionUpdated();
        gate.runNext(ext::vmpTracking$updatePosition);
        ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> tracked = entry.getValue();
        Iterator<ChunkMap.TrackedEntity> it = tracked.iterator();
        while (it.hasNext()) {
            ChunkMap.TrackedEntity t = it.next();
            if (inRange.contains(t)) {
                this.fabrolia$handleTrackerInline(player, posUpdated, t, tickList, gate);
            } else {
                gate.runNext(() -> {
                    t.removePlayer(player);
                    it.remove();
                });
            }
        }
        for (ChunkMap.TrackedEntity t : inRange) {
            if (!tracked.contains(t)) {
                this.fabrolia$handleTrackerInline(player, posUpdated, t, tickList, gate);
                gate.callNext(() -> tracked.add(t));
            }
        }
    }

    @Unique
    private void fabrolia$handleTrackerInline(ServerPlayer player, boolean playerPosUpdated,
                                              ChunkMap.TrackedEntity tracker,
                                              Set<ChunkMap.TrackedEntity> tickList,
                                              VmpTrackingFanoutGate gate) {
        gate.runNext(() -> {
            if (tickList.add(tracker)) {
                SectionPos section = ((IThreadedAnvilChunkStorageEntityTracker) tracker).getTrackedSection();
                if (this.ticketManager.inEntityTickingRange(ChunkPos.pack(section.x(), section.z()))) {
                    tryTickTracker(tracker);
                }
            }
        });
        gate.runNext(() -> {
            if (playerPosUpdated || ((EntityTrackerExtension) tracker).isPositionUpdated()) {
                tryUpdateTracker(tracker, player);
            }
        });
    }

    @WrapOperation(method = "handleTracker", at = @At(value = "INVOKE", target = "Lcom/ishland/vmp/common/playerwatching/NearbyEntityTracking;tryTickTracker(Lnet/minecraft/server/level/ChunkMap$TrackedEntity;)V"))
    private void fabrolia$queueTryTick(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        if (!fabrolia$canDispatch()) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(tracker));
            return;
        }
        TrackerOps ops = this.fabrolia$pendingOps.get(tracker);
        if (ops == null) {
            ops = new TrackerOps();
            this.fabrolia$pendingOps.put(tracker, ops);
        }
        ops.doTick = true;
    }

    @WrapOperation(method = "handleTracker", at = @At(value = "INVOKE", target = "Lcom/ishland/vmp/common/playerwatching/NearbyEntityTracking;tryUpdateTracker(Lnet/minecraft/server/level/ChunkMap$TrackedEntity;Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void fabrolia$queueTryUpdate(ChunkMap.TrackedEntity tracker, ServerPlayer player, Operation<Void> original) {
        if (!fabrolia$canDispatch()) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(tracker, player));
            return;
        }
        TrackerOps ops = this.fabrolia$pendingOps.get(tracker);
        if (ops == null) {
            ops = new TrackerOps();
            this.fabrolia$pendingOps.put(tracker, ops);
        }
        ops.addUpdate(player);
    }

    @Unique
    private void fabrolia$drainOps(IdentityHashMap<ChunkMap.TrackedEntity, TrackerOps> ops) {
        ExecutorService exec = ParallelProcessor.executor;
        boolean canDispatch = fabrolia$canDispatch() && exec != null && !exec.isShutdown();
        int n = ops.size();
        if (!canDispatch || n < 4) {
            for (Map.Entry<ChunkMap.TrackedEntity, TrackerOps> e : ops.entrySet()) {
                fabrolia$applyOps(e.getKey(), e.getValue());
            }
            return;
        }

        ChunkMap.TrackedEntity[] keys = new ChunkMap.TrackedEntity[n];
        TrackerOps[] vals = new TrackerOps[n];
        int i = 0;
        for (Map.Entry<ChunkMap.TrackedEntity, TrackerOps> e : ops.entrySet()) {
            keys[i] = e.getKey();
            vals[i] = e.getValue();
            i++;
        }

        int pool = Math.max(1, ParallelProcessor.getPoolSize());
        int chunkSize = Math.max(8, (n + pool - 1) / pool);
        int batchCount = (n + chunkSize - 1) / chunkSize;
        fabrolia$runFanout(exec, batchCount, (gate, batchIndex) -> {
            int from = batchIndex * chunkSize;
            int to = Math.min(from + chunkSize, n);
            for (int k = from; k < to; k++) {
                int trackerIndex = k;
                gate.runNext(() -> fabrolia$applyOps(keys[trackerIndex], vals[trackerIndex], gate));
            }
        });
    }

    @Unique
    private static void fabrolia$applyOps(ChunkMap.TrackedEntity tracker, TrackerOps ops) {
        fabrolia$applyOps(tracker, ops, null);
    }

    @Unique
    private static void fabrolia$applyOps(ChunkMap.TrackedEntity tracker, TrackerOps ops, VmpTrackingFanoutGate gate) {
        if (ops.doTick) {
            fabrolia$runTrackerBodyIfHealthy(gate, () -> tryTickTracker(tracker));
        }
        if (ops.u0 != null) {
            fabrolia$runTrackerBodyIfHealthy(gate, () -> tryUpdateTracker(tracker, ops.u0));
        }
        if (ops.u1 != null) {
            fabrolia$runTrackerBodyIfHealthy(gate, () -> tryUpdateTracker(tracker, ops.u1));
        }
        if (ops.uMore != null) {
            ArrayList<ServerPlayer> rest = ops.uMore;
            for (int i = 0, sz = rest.size(); i < sz; i++) {
                ServerPlayer p = rest.get(i);
                fabrolia$runTrackerBodyIfHealthy(gate, () -> tryUpdateTracker(tracker, p));
            }
        }
    }

    @Unique
    private static void fabrolia$runTrackerBodyIfHealthy(VmpTrackingFanoutGate gate, Runnable body) {
        if (gate != null) {
            gate.runNext(body);
        } else {
            RegionScheduler.runSuccessBodyIfHealthy(body);
        }
    }

    @Unique
    private static void fabrolia$runFanout(ExecutorService executor, int batchCount, GatedBatch body) {
        if (batchCount <= 0) return;
        VmpTrackingFanoutGate gate = new VmpTrackingFanoutGate();
        FanoutTask[] tasks = new FanoutTask[batchCount];
        int submitted = 0;
        try {
            for (int batchIndex = 0; batchIndex < batchCount; batchIndex++) {
                FanoutTask task = new FanoutTask(gate, body, batchIndex);
                tasks[submitted++] = task;
                gate.runNext(() -> {
                    RegionScheduler.executeTrackedExternal(accepted -> {
                        task.acceptedBody = accepted;
                        executor.execute(task);
                    }, () -> gate.runNext(() -> task.body.run(gate, task.batchIndex)));
                    task.acceptPublication();
                });
            }
        } catch (Throwable submissionFailure) {
            gate.publishFailure(submissionFailure);
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
    private static boolean fabrolia$canDispatch() {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaVmpTracking) return false;
        ExecutorService exec = ParallelProcessor.executor;
        return exec != null && !exec.isShutdown();
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
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final AtomicInteger publication = new AtomicInteger(OFFERING);
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

    @Unique
    private static final class TrackerOps {
        boolean doTick;
        ServerPlayer u0;
        ServerPlayer u1;
        ArrayList<ServerPlayer> uMore;

        void addUpdate(ServerPlayer p) {
            if (this.u0 == null) {
                this.u0 = p;
            } else if (this.u1 == null) {
                this.u1 = p;
            } else {
                if (this.uMore == null) {
                    this.uMore = new ArrayList<>(4);
                }
                this.uMore.add(p);
            }
        }
    }

}
