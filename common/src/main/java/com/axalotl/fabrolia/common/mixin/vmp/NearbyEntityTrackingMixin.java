package com.axalotl.fabrolia.common.mixin.vmp;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaTrackingDirtyFlag;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.LockSupport;

/**
 * Three-layer optimisation of VMP's {@code NearbyEntityTracking.tick0}:
 *
 * <ol>
 *   <li><b>Skip when idle</b> — at HEAD of tick0, if the global {@link FabroliaTrackingDirtyFlag}
 *       counter hasn't advanced (no entity moved, no structural change) AND no player has
 *       {@code vmpTracking$isPositionUpdated}, cancel tick0 entirely. For stationary bots /
 *       AFK worlds this drives VMP tracking to ~0% CPU.</li>
 *   <li><b>Parallel phase 2</b> — when tick0 IS running, replace VMP's serial per-player loop
 *       (the heavy {@code O(players × in-range)} block) with a fork/join fan-out via
 *       {@link ParallelProcessor#executor}. Phase 1 (areaMap mutations) and phase 3
 *       ({@code updatePosition} fan-out) stay serial.</li>
 *   <li><b>Fallback queue-then-drain</b> — when fabrolia dispatch is unavailable / config-disabled,
 *       fall through to vanilla tick0; the {@code @WrapOperation} hooks below queue tryTick /
 *       tryUpdate calls and drain them in a parallel batch after the iteration ends.</li>
 * </ol>
 *
 * <p><b>Concurrency for parallel phase 2:</b>
 * <ul>
 *   <li>{@code areaMap} — read-only during phase 2 (phase 1 wrote, phase 2 just reads via
 *       {@code getObjectsInRange}). All worker reads on the underlying fastutil maps are
 *       safe as long as nothing writes concurrently — and nothing does, because
 *       {@code fabrolia$lock} blocks {@code addEntityTracker} / {@code removeEntityTracker} until
 *       the tick completes.</li>
 *   <li>{@code playerTrackers} — snapshotted to an array before parallel fan-out so the map
 *       isn't iterated by multiple threads.</li>
 *   <li>Per-player {@code tracked} set ({@code ReferenceLinkedOpenHashSet}) — mutated only by
 *       the worker assigned to that player. Workers don't share these.</li>
 *   <li>{@code phase2TickList} — a {@code ConcurrentHashMap.newKeySet()} replaces vanilla's
 *       non-concurrent {@code trackerTickList} for the duration of phase 2; we merge / iterate
 *       it serially in phase 3.</li>
 *   <li>{@code tracker.removePlayer(p)} — {@code seenBy} is concurrent
 *       ({@code ConcurrentCollections.newHashSet} via {@code ChunkMapTrackedEntityMixin});
 *       sending the remove packet is netty-thread-safe.</li>
 *   <li>{@code tryTickTracker(tracker)} — guarded by {@code phase2TickList.add(tracker)}
 *       returning true (CAS-atomic) — only one worker per tracker per tick.</li>
 *   <li>{@code tryUpdateTracker(tracker, p)} → {@code tracker.updatePlayer(p)} —
 *       {@code updatePlayer} is {@code synchronized} per-instance via
 *       {@code ChunkMapTrackedEntityMixin}, so concurrent calls for the same tracker serialise.</li>
 * </ul>
 *
 * <p><b>Why we can't {@code pumpUntilDone} the worker barrier:</b>
 * VMP's tick is called from inside {@code ChunkMap.tick → ServerChunkCache.tick → ServerLevel.tick},
 * which itself runs inside {@code MinecraftServer.tickChildren}'s {@code getAllLevels} loop.
 * That outer loop iterates a vanilla ArrayList. Pumping {@code MinecraftServer.tasks} here
 * would let queued tasks (e.g. carpet bot {@code placeNewPlayer}) mutate ArrayLists mid-iteration
 * → CME on {@code tickChildren:1164}. We use a bare park-loop for the barrier; the lost
 * packet responsiveness is bounded (a few ms per VMP tick) and other pump sites still drain.
 */
@Pseudo
@Mixin(targets = "com.ishland.vmp.common.playerwatching.NearbyEntityTracking", remap = false)
public abstract class NearbyEntityTrackingMixin {

    @Unique
    private static final Logger FABROLIA_LOGGER = LoggerFactory.getLogger("Fabrolia-VmpTracking");

    @Shadow @Final
    private AreaMap<ChunkMap.TrackedEntity> areaMap;

    @Shadow @Final
    private Reference2ReferenceLinkedOpenHashMap<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> playerTrackers;

    @Shadow @Final
    private Reference2LongOpenHashMap<ChunkMap.TrackedEntity> tracker2ChunkPos;

    @Shadow @Final
    private ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> trackerTickList;

    @Shadow private ChunkMap.DistanceManager ticketManager;

    @Shadow protected abstract void tickStaging();
    @Shadow private static ChunkPos getEntityChunkPos(Entity entity) { throw new AssertionError("shadow"); }
    @Shadow private int getChunkViewDistance(ChunkMap.TrackedEntity tracker) { throw new AssertionError("shadow"); }
    @Shadow private static void tryTickTracker(ChunkMap.TrackedEntity tracker) { throw new AssertionError("shadow"); }
    @Shadow private static void tryUpdateTracker(ChunkMap.TrackedEntity tracker, ServerPlayer player) { throw new AssertionError("shadow"); }

    @Unique
    private final Object fabrolia$lock = new Object();

    /** Last value of dirty counter seen at tick0 entry (used for skip predicate). */
    @Unique
    private long fabrolia$lastSeenDirty = -1;

    /** Fallback queue used only when fabrolia dispatch is unavailable. */
    @Unique
    private IdentityHashMap<ChunkMap.TrackedEntity, TrackerOps> fabrolia$pendingOps = new IdentityHashMap<>(256);

    /** Staged-tracker updatePlayers calls deferred out of tickStaging (parallel burst). */
    @Unique
    private ArrayList<Object[]> fabrolia$stagedUpdates = new ArrayList<>(64);
    @Unique
    private boolean fabrolia$deferStagedUpdates;

    @WrapOperation(method = "tickStaging",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"))
    private void fabrolia$captureStagedUpdate(ChunkMap.TrackedEntity tracker,
                                           java.util.List<ServerPlayer> players,
                                           Operation<Void> original) {
        if (!fabrolia$deferStagedUpdates) {
            original.call(tracker, players);
            return;
        }
        fabrolia$stagedUpdates.add(new Object[]{tracker, players});
    }

    @Unique
    private static final class TrackerOps {
        boolean doTick;
        ServerPlayer u0, u1;
        ArrayList<ServerPlayer> uMore;

        void addUpdate(ServerPlayer p) {
            if (u0 == null) { u0 = p; return; }
            if (u1 == null) { u1 = p; return; }
            if (uMore == null) uMore = new ArrayList<>(4);
            uMore.add(p);
        }
    }

    @WrapMethod(method = "addEntityTracker")
    private void fabrolia$lockAdd(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        synchronized (fabrolia$lock) { original.call(tracker); }
        FabroliaTrackingDirtyFlag.markDirty();
    }

    @WrapMethod(method = "removeEntityTracker")
    private void fabrolia$lockRemove(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        synchronized (fabrolia$lock) { original.call(tracker); }
        FabroliaTrackingDirtyFlag.markDirty();
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
        } catch (IllegalAccessException ignored) {
            // setAccessible succeeded at class-init; this branch is dead in practice.
        }
    }

    @WrapMethod(method = "addPlayer")
    private void fabrolia$lockAddPlayer(ServerPlayer player, Operation<Void> original) {
        synchronized (fabrolia$lock) { original.call(player); }
        fabrolia$resetVmpPositionCache(player);
        FabroliaTrackingDirtyFlag.markDirty();
    }

    @WrapMethod(method = "removePlayer")
    private void fabrolia$lockRemovePlayer(ServerPlayer player, Operation<Void> original) {
        synchronized (fabrolia$lock) { original.call(player); }
        FabroliaTrackingDirtyFlag.markDirty();
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

    /**
     * Outer {@code tick} wrap. Holds {@code fabrolia$lock} so fabrolia-spawn worker calls to
     * {@code addEntityTracker} etc. are serialised against the iteration. After tick returns,
     * we drain {@code fabrolia$pendingOps} (populated only in fallback mode by the {@code @WrapOperation}
     * hooks below; empty when parallel tick0 ran).
     */
    @WrapMethod(method = "tick")
    private void fabrolia$lockTick(ChunkMap.DistanceManager ticketManager, Operation<Void> original) {
        IdentityHashMap<ChunkMap.TrackedEntity, TrackerOps> snapshot = null;
        synchronized (fabrolia$lock) {
            original.call(ticketManager);
            if (!fabrolia$pendingOps.isEmpty()) {
                snapshot = fabrolia$pendingOps;
                fabrolia$pendingOps = new IdentityHashMap<>(Math.max(16, snapshot.size()));
            }
        }
        if (snapshot != null) fabrolia$drainOps(snapshot);
    }

    /**
     * Parallel reimplementation of tick0. Used when fabrolia dispatch is available; otherwise
     * falls through to vanilla via {@code original.call()}.
     */
    @WrapMethod(method = "tick0")
    private void fabrolia$parallelTick0(Operation<Void> original) {
        ExecutorService exec = ParallelProcessor.executor;
        boolean canDispatch = fabrolia$canDispatch() && exec != null && !exec.isShutdown();
        int playerCount = this.playerTrackers.size();
        if (!canDispatch || playerCount < 4) {
            // Fallback: vanilla tick0 — handleTracker's tryTick/tryUpdate calls get queued
            // via the @WrapOperation hooks below, drained after by fabrolia$lockTick.
            original.call();
            return;
        }

        // === Phase 1 (split): parallel read-only position scan + serial areaMap apply ===
        // The old serial walk called getEntityChunkPos for EVERY tracked entity on main
        // (12k entities ≈ 2.8% of the server thread, 50-bot load 2026-06-12). The scan
        // is pure reads (entity position + transformer chain) — fan it out; only the
        // chunk-crossers (few hundred) need the serial areaMap mutation. Same pattern
        // as the MFC read-parallel + buffered-serial-apply lane.
        //
        // tickStaging's updatePlayers calls (first spawn-packet burst for newly staged
        // trackers — 3.5% of main under spawn waves) are deferred via the WrapOperation
        // below and fanned out right after: areaMap inserts stay serial inside staging,
        // packet bursts go parallel. Per-tracker updatePlayer is synchronized
        // (ChunkMapTrackedEntityMixin) and packet sends are netty-safe — the same
        // concurrency contract the fallback drain path already relies on.
        fabrolia$deferStagedUpdates = true;
        try {
            this.tickStaging();
        } finally {
            fabrolia$deferStagedUpdates = false;
        }
        if (!fabrolia$stagedUpdates.isEmpty()) {
            final ArrayList<Object[]> staged = fabrolia$stagedUpdates;
            fabrolia$stagedUpdates = new ArrayList<>(Math.max(16, staged.size()));
            int sn = staged.size();
            int sBatch = Math.max(16, (sn + (Math.max(1, ParallelProcessor.getPoolSize()) << 1) - 1)
                    / (Math.max(1, ParallelProcessor.getPoolSize()) << 1));
            int sBatches = (sn + sBatch - 1) / sBatch;
            CompletableFuture<?>[] sFutures = new CompletableFuture<?>[sBatches];
            for (int b = 0, s = 0; s < sn; s += sBatch, b++) {
                final int from = s, to = Math.min(s + sBatch, sn);
                sFutures[b] = CompletableFuture.runAsync(() -> {
                    for (int k = from; k < to; k++) {
                        Object[] pair = staged.get(k);
                        try {
                            @SuppressWarnings("unchecked")
                            java.util.List<ServerPlayer> players = (java.util.List<ServerPlayer>) pair[1];
                            ((ChunkMap.TrackedEntity) pair[0]).updatePlayers(players);
                        } catch (Throwable t) {
                            FABROLIA_LOGGER.error("Staged updatePlayers failed", t);
                        }
                    }
                }, exec);
            }
            CompletableFuture<Void> sAll = CompletableFuture.allOf(sFutures);
            while (!sAll.isDone()) LockSupport.parkNanos(20_000L);
        }

        int pool = Math.max(1, ParallelProcessor.getPoolSize());
        int trackerCount = this.tracker2ChunkPos.size();
        if (trackerCount >= 4096) {
            ChunkMap.TrackedEntity[] keys = new ChunkMap.TrackedEntity[trackerCount];
            long[] cachedPos = new long[trackerCount];
            int ti = 0;
            for (Reference2LongMap.Entry<ChunkMap.TrackedEntity> e
                    : this.tracker2ChunkPos.reference2LongEntrySet()) {
                keys[ti] = e.getKey();
                cachedPos[ti] = e.getLongValue();
                ti++;
            }
            final long[] currentPos = new long[trackerCount];
            int scanBatch = Math.max(512, (trackerCount + (pool << 1) - 1) / (pool << 1));
            int scanBatches = (trackerCount + scanBatch - 1) / scanBatch;
            CompletableFuture<?>[] scans = new CompletableFuture<?>[scanBatches];
            for (int b = 0, s = 0; s < trackerCount; s += scanBatch, b++) {
                final int from = s, to = Math.min(s + scanBatch, trackerCount);
                scans[b] = CompletableFuture.runAsync(() -> {
                    for (int k = from; k < to; k++) {
                        try {
                            currentPos[k] = getEntityChunkPos(keys[k].entity).pack();
                        } catch (Throwable t) {
                            currentPos[k] = cachedPos[k];   // scan failure → treat as not-moved
                        }
                    }
                }, exec);
            }
            CompletableFuture<Void> scanAll = CompletableFuture.allOf(scans);
            while (!scanAll.isDone()) LockSupport.parkNanos(20_000L);
            if (scanAll.isCompletedExceptionally()) {
                try { scanAll.join(); } catch (Throwable t) {
                    FABROLIA_LOGGER.error("VMP tracking phase 1 scan failed", t);
                }
            }
            for (int k = 0; k < trackerCount; k++) {
                long cur = currentPos[k];
                if (cur != cachedPos[k]) {
                    ChunkMap.TrackedEntity t = keys[k];
                    this.areaMap.update(t, (int) cur, (int) (cur >>> 32), this.getChunkViewDistance(t));
                    this.tracker2ChunkPos.put(t, cur);
                }
            }
        } else {
            Iterator<Reference2LongMap.Entry<ChunkMap.TrackedEntity>> p1it =
                    this.tracker2ChunkPos.reference2LongEntrySet().iterator();
            while (p1it.hasNext()) {
                Reference2LongMap.Entry<ChunkMap.TrackedEntity> e = p1it.next();
                ChunkMap.TrackedEntity tracker = e.getKey();
                ChunkPos pos = getEntityChunkPos(tracker.entity);
                long packed = pos.pack();
                if (packed != e.getLongValue()) {
                    this.areaMap.update(tracker, pos.x(), pos.z(), this.getChunkViewDistance(tracker));
                    e.setValue(packed);
                }
            }
        }

        // Concurrent tick list — replaces vanilla trackerTickList for parallel writes.
        Set<ChunkMap.TrackedEntity> phase2TickList = ConcurrentHashMap.newKeySet();
        this.trackerTickList.clear();

        // Snapshot playerTrackers entries — workers iterate the snapshot, not the map.
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map.Entry<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>>[] entries =
                new Map.Entry[playerCount];
        int idx = 0;
        for (Map.Entry<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> e
                : this.playerTrackers.entrySet()) {
            entries[idx++] = e;
        }

        // === Phase 2 (parallel): per-player diff + handleTracker fan-out ===
        int batchSize = Math.max(1, (playerCount + pool - 1) / pool);
        int batchCount = (playerCount + batchSize - 1) / batchSize;
        CompletableFuture<?>[] futures = new CompletableFuture<?>[batchCount];

        final Map.Entry<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>>[] entriesRef = entries;
        for (int b = 0, s = 0; s < playerCount; s += batchSize, b++) {
            final int from = s;
            final int to = Math.min(s + batchSize, playerCount);
            futures[b] = CompletableFuture.runAsync(() -> {
                for (int k = from; k < to; k++) {
                    try {
                        fabrolia$processPlayer(entriesRef[k], phase2TickList);
                    } catch (Throwable t) {
                        FABROLIA_LOGGER.error("VMP tracking phase 2 failed for player {}", entriesRef[k].getKey(), t);
                    }
                }
            }, exec);
        }
        CompletableFuture<?>[] slice = (batchCount == futures.length) ? futures : Arrays.copyOf(futures, batchCount);
        CompletableFuture<Void> all = CompletableFuture.allOf(slice);
        while (!all.isDone()) LockSupport.parkNanos(50_000L);
        if (all.isCompletedExceptionally()) {
            try { all.join(); } catch (Throwable t) {
                FABROLIA_LOGGER.error("VMP tracking phase 2 batch failed", t);
            }
        }

        // === Phase 3 (serial): updatePosition fan-out for trackers added during phase 2 ===
        for (ChunkMap.TrackedEntity tracker : phase2TickList) {
            try {
                ((EntityTrackerExtension) tracker).updatePosition();
            } catch (Throwable t) {
                FABROLIA_LOGGER.error("VMP tracking phase 3 updatePosition failed for tracker {}", tracker, t);
            }
        }
    }

    /**
     * Per-player phase-2 worker. Mirrors VMP's inner loop body:
     * iterates the player's cached tracker set, drops out-of-range, adds newly-in-range,
     * and calls {@link #fabrolia$handleTrackerInline} for each visible (player, tracker) pair.
     */
    @Unique
    private void fabrolia$processPlayer(Map.Entry<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> entry,
                                     Set<ChunkMap.TrackedEntity> tickList) {
        ServerPlayer player = entry.getKey();
        Set<ChunkMap.TrackedEntity> inRange = this.areaMap.getObjectsInRange(getEntityChunkPos(player).pack());
        ServerPlayerEntityExtension ext = (ServerPlayerEntityExtension) player;
        boolean posUpdated = ext.vmpTracking$isPositionUpdated();
        ext.vmpTracking$updatePosition();
        ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> tracked = entry.getValue();

        Iterator<ChunkMap.TrackedEntity> it = tracked.iterator();
        while (it.hasNext()) {
            ChunkMap.TrackedEntity t = it.next();
            if (inRange.contains(t)) {
                fabrolia$handleTrackerInline(player, posUpdated, t, tickList);
            } else {
                t.removePlayer(player);
                it.remove();
            }
        }
        for (ChunkMap.TrackedEntity t : inRange) {
            if (!tracked.contains(t)) {
                fabrolia$handleTrackerInline(player, posUpdated, t, tickList);
                tracked.add(t);
            }
        }
    }

    /**
     * Inlined VMP {@code handleTracker} logic with concurrent {@code phase2TickList.add} as the
     * "first to see this tracker" guard — same semantics as vanilla, just thread-safe.
     */
    @Unique
    private void fabrolia$handleTrackerInline(ServerPlayer player, boolean playerPosUpdated,
                                           ChunkMap.TrackedEntity tracker,
                                           Set<ChunkMap.TrackedEntity> tickList) {
        SectionPos section = ((IThreadedAnvilChunkStorageEntityTracker) tracker).getTrackedSection();
        boolean newlyAdded = tickList.add(tracker);
        if (newlyAdded && this.ticketManager.inEntityTickingRange(ChunkPos.pack(section.x(), section.z()))) {
            tryTickTracker(tracker);
        }
        if (playerPosUpdated || ((EntityTrackerExtension) tracker).isPositionUpdated()) {
            tryUpdateTracker(tracker, player);
        }
    }

    // ─── Fallback path (vanilla tick0) — queue tryTick/tryUpdate, drain after tick ────────

    @WrapOperation(method = "handleTracker", at = @At(value = "INVOKE", target = "Lcom/ishland/vmp/common/playerwatching/NearbyEntityTracking;tryTickTracker(Lnet/minecraft/server/level/ChunkMap$TrackedEntity;)V"))
    private void fabrolia$queueTryTick(ChunkMap.TrackedEntity tracker, Operation<Void> original) {
        if (!fabrolia$canDispatch()) { original.call(tracker); return; }
        TrackerOps ops = fabrolia$pendingOps.get(tracker);
        if (ops == null) {
            ops = new TrackerOps();
            fabrolia$pendingOps.put(tracker, ops);
        }
        ops.doTick = true;
    }

    @WrapOperation(method = "handleTracker", at = @At(value = "INVOKE", target = "Lcom/ishland/vmp/common/playerwatching/NearbyEntityTracking;tryUpdateTracker(Lnet/minecraft/server/level/ChunkMap$TrackedEntity;Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void fabrolia$queueTryUpdate(ChunkMap.TrackedEntity tracker, ServerPlayer player, Operation<Void> original) {
        if (!fabrolia$canDispatch()) { original.call(tracker, player); return; }
        TrackerOps ops = fabrolia$pendingOps.get(tracker);
        if (ops == null) {
            ops = new TrackerOps();
            fabrolia$pendingOps.put(tracker, ops);
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

        @SuppressWarnings({"unchecked"})
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
        CompletableFuture<?>[] futures = new CompletableFuture[batchCount];

        for (int b = 0, s = 0; s < n; s += chunkSize, b++) {
            final int from = s;
            final int to = Math.min(s + chunkSize, n);
            try {
                futures[b] = CompletableFuture.runAsync(() -> {
                    for (int k = from; k < to; k++) fabrolia$applyOps(keys[k], vals[k]);
                }, exec);
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                for (int j = 0; j < b; j++) { try { futures[j].join(); } catch (Throwable ignored) {} futures[j] = null; }
                for (int k = from; k < n; k++) fabrolia$applyOps(keys[k], vals[k]);
                return;
            }
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(futures);
        while (!all.isDone()) LockSupport.parkNanos(50_000L);
        if (all.isCompletedExceptionally()) {
            try { all.join(); } catch (Throwable t) {
                FABROLIA_LOGGER.error("VMP tracking drain batch failed", t);
            }
        }
    }

    @Unique
    private static void fabrolia$applyOps(ChunkMap.TrackedEntity tracker, TrackerOps ops) {
        if (ops.doTick) {
            try { tryTickTracker(tracker); } catch (Throwable t) {
                FABROLIA_LOGGER.error("tryTickTracker failed for tracker {}", tracker, t);
            }
        }
        if (ops.u0 != null) {
            try { tryUpdateTracker(tracker, ops.u0); } catch (Throwable t) {
                FABROLIA_LOGGER.error("tryUpdateTracker failed for tracker {} / player {}", tracker, ops.u0, t);
            }
        }
        if (ops.u1 != null) {
            try { tryUpdateTracker(tracker, ops.u1); } catch (Throwable t) {
                FABROLIA_LOGGER.error("tryUpdateTracker failed for tracker {} / player {}", tracker, ops.u1, t);
            }
        }
        if (ops.uMore != null) {
            ArrayList<ServerPlayer> rest = ops.uMore;
            for (int i = 0, sz = rest.size(); i < sz; i++) {
                ServerPlayer p = rest.get(i);
                try { tryUpdateTracker(tracker, p); } catch (Throwable t) {
                    FABROLIA_LOGGER.error("tryUpdateTracker failed for tracker {} / player {}", tracker, p, t);
                }
            }
        }
    }

    @Unique
    private static boolean fabrolia$canDispatch() {
        if (FabroliaConfig.disabled || !FabroliaConfig.enableFabroliaVmpTracking) return false;
        ExecutorService exec = ParallelProcessor.executor;
        return exec != null && !exec.isShutdown();
    }
}
