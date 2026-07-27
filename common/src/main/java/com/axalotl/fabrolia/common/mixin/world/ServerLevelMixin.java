package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.mixin.lithium.LithiumServerLevel;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.axalotl.fabrolia.api.utils.SnapshotList;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.storage.WritableLevelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import com.axalotl.fabrolia.common.utils.SpawnFutureTaker;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.TickRateManager;
import net.minecraft.server.level.DistanceManager;

@SuppressWarnings("all")
@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class ServerLevelMixin extends Level implements WorldGenLevel,
        com.axalotl.fabrolia.common.regions.BlockEventQueueAccess {

    @org.spongepowered.asm.mixin.injection.Inject(method = "isHandlingTick", at = @At("HEAD"), cancellable = true)
    private void fabrolia$contextHandlingTick(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        Boolean ctx = com.axalotl.fabrolia.common.regions.IslandClock.workerHandlingTick(
                (net.minecraft.server.level.ServerLevel) (Object) this);
        if (ctx != null) {
            cir.setReturnValue(ctx);
        }
    }


    @Shadow @Final public EntityTickList entityTickList;
    @Shadow @Final private ServerChunkCache chunkSource;
    @Shadow @Final @Mutable Set<Mob> navigatingMobs;
    @Shadow @Mutable @Final private List<ServerPlayer> players;

    @Shadow public abstract @NotNull ServerLevel getLevel();

    @Unique
    private final Object fabrolia$explosionLock = new Object();

    /** Guards the vanilla {@code blockEvents} ObjectLinkedOpenHashSet so region workers can queue
     *  block events concurrently with each other and the main drain. Vanilla's SET semantics
     *  (dedup — a piston re-triggered several times in a tick queues ONE event — AND insertion
     *  order) MUST be preserved: the old ConcurrentLinkedQueue replacement lost the dedup, so a
     *  duplicate block event fired the piston twice → flying machines broke on themselves. */
    @Unique
    final Object fabrolia$blockEventLock = new Object();

    protected ServerLevelMixin(WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess, Holder<DimensionType> dimensionTypeRegistration, boolean isClientSide, boolean isDebug, long biomeZoomSeed, int maxChainedNeighborUpdates) {
        super(levelData, dimension, registryAccess, dimensionTypeRegistration, isClientSide, isDebug, biomeZoomSeed, maxChainedNeighborUpdates);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(CallbackInfo ci) {
        navigatingMobs = ConcurrentCollections.newHashSet();
        // SnapshotList: login/logout = rare write rebuilding snapshot; broadcast =
        // very hot lock-free iteration over the volatile snapshot array.
        players = new SnapshotList<>();
    }

    /**
     * F1 — island-partitioned scheduled block/fluid ticks (the user's
     * Folia-batch strategy). One handler covers both call sites (block +
     * fluid schedulers share the target signature). Falls through to vanilla
     * whenever the partition declines (<2 islands, regions off, flag off).
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"))
    private <T> void fabrolia$islandScheduledTicks(net.minecraft.world.ticks.LevelTicks<T> instance,
                                                long gameTime, int maxTicks,
                                                java.util.function.BiConsumer<net.minecraft.core.BlockPos, T> ticker,
                                                Operation<Void> original) {
        if (com.axalotl.fabrolia.common.regions.IslandScheduledTicks.slotPartitionedAsync(
                instance, this.getLevel(), gameTime, maxTicks, ticker)) {
            return;
        }
        if (com.axalotl.fabrolia.common.regions.IslandScheduledTicks.tickPartitioned(
                instance, this.getLevel(), gameTime, maxTicks, ticker)) {
            return;
        }
        original.call(instance, gameTime, maxTicks, ticker);
    }

    @WrapMethod(method = "tickNonPassenger")
    private void fabrolia$guardTickNonPassenger(Entity entity, Operation<Void> original) {
        ParallelProcessor.TickGuard guard = (ParallelProcessor.TickGuard) entity;
        if (!guard.fabrolia$tryBeginTick()) {
            return;
        }
        try {
            original.call(entity);
        } finally {
            guard.fabrolia$endTick();
        }
    }

    @WrapMethod(method = "tickPassenger")
    private void fabrolia$guardTickPassenger(Entity vehicle, Entity entity, Operation<Void> original) {
        ParallelProcessor.TickGuard guard = (ParallelProcessor.TickGuard) entity;
        if (!guard.fabrolia$tryBeginTick()) {
            return;
        }
        try {
            original.call(vehicle, entity);
        } finally {
            guard.fabrolia$endTick();
        }
    }

    @Unique
    private static final Logger FABROLIA_LOGGER = LoggerFactory.getLogger("Fabrolia-EntityTick");

    @Unique
    private int fabrolia$lastBatchSize = 256;

    @Unique
    private int fabrolia$lastLiveSize = 256;

    @Unique
    private ArrayList<Entity> fabrolia$syncBuf;

    @Unique
    private ArrayList<Entity> fabrolia$liveBuf;

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void overwriteEntityTicking(EntityTickList entityTickList, Consumer<Entity> consumer) {
        ProfilerFiller profilerfiller = Profiler.get();
        ServerLevel self = this.getLevel();
        long batchStart = System.nanoTime();

        // Tracker barrier: the VMP tracker tick (vanilla: before entities) was moved
        // off-main overlapping the chunk phase — it MUST complete before any entity
        // mutates a position (tracking reads pre-tick positions, exactly vanilla).
        if (((Object) this.chunkSource) instanceof SpawnFutureTaker taker0) {
            CompletableFuture<Void> trackerFut = taker0.fabrolia$takeTrackerFuture();
            if (trackerFut != null && !trackerFut.isDone()) {
                ParallelProcessor.pumpUntilDone(trackerFut);
            }
        }

        // Region-owned path: regions iterate their OWN entity sets and run the full
        // vanilla per-entity sequence (frozen/range/holder/vehicle gates, per-entity
        // checkDespawn-then-tick — vanilla interleaving) on region workers. Main does
        // NO list walk, NO classify loop, NO sync-entity loop here — it dispatches,
        // adopts the in-flight spawn future, and pumps one barrier (during which it
        // ticks main-required entities from the region tasks' sync queue and drains
        // chunk-system tasks). enableFabroliaDespawn is irrelevant on this path — despawn
        // runs per-entity inside the region task, which is what vanilla does anyway.
        if (!FabroliaConfig.disabled && FabroliaConfig.enableRegionThreading && RegionScheduler.isActive()) {
            // Entity tasks land in each region's queue AFTER its spawn and random-tick
            // tasks (chunk phase submitted them first) — per-region FIFO gives every
            // area vanilla's spawn → random ticks → entities order; this single barrier
            // covers all three phases at once.
            // Deferred chunk-work arm: the chunk phase only COLLECTED spawn/random-tick
            // work; submit it to the region queues NOW — after main's runBlockEvents
            // (piston/block-event writes are complete, so region workers can no longer
            // race them inside player-area chunks) and before entity dispatch (ColorGate
            // submit-before-dispatch invariant, per-region FIFO preserved).
            var rsLease = RegionScheduler.forLevel(self);
            if (rsLease != null) {
                com.axalotl.fabrolia.common.regions.CrossDimArrivals.rearmPendingLeases(rsLease);
            }
            if (((Object) this.chunkSource) instanceof SpawnFutureTaker armTaker) {
                armTaker.fabrolia$armDeferredChunkWork();
            }
            CompletableFuture<Void> regionAll = RegionScheduler.dispatchOwnedEntityTicks(self);
            if (((Object) this.chunkSource) instanceof SpawnFutureTaker t) {
                CompletableFuture<Void> regionSpawnFut = t.fabrolia$takeSpawnFuture();
                if (regionSpawnFut != null) {
                    regionAll = CompletableFuture.allOf(regionAll, regionSpawnFut);
                }
                CompletableFuture<Void> regionBlockTicks = t.fabrolia$takeBlockTicksFuture();
                if (regionBlockTicks != null) {
                    regionAll = CompletableFuture.allOf(regionAll, regionBlockTicks);
                }
            }
            ParallelProcessor.pumpUntilDone(regionAll);
            // Cross-region effects the workers deferred (writes / neighbour updates that
            // left their claimed region) — replay on main now that every region is
            // quiescent, so boundary-crossing redstone completes within this tick,
            // single-threaded and safe (CrossRegionGuard).
            var rsDefer = RegionScheduler.forLevel(self);
            if (rsDefer != null) {
                // A2 async islands: island-domain deferrals were already replayed
                // inside each unit (coordinator, post-barrier); this drains only
                // the GLOBAL timeline's queue — identical to pre-A2 in sync mode.
                rsDefer.drainDeferredCrossRegion();
                // A8: same-tick completion window for routed commands — units
                // that reached their command quickly get their result THIS
                // tick (bounded fence attempt; head batch next tick is the
                // fallback), so a routed repeating block costs ~1ms of unit
                // time instead of a whole tick.
                if (rsDefer.asyncActive
                        && com.axalotl.fabrolia.common.regions.IslandCommands.hasRoutedPending()) {
                    com.axalotl.fabrolia.common.regions.IslandCommands.drainRoutedBatch(2_000_000L);
                }
            }
            // Tail entries queued just before the last region task completed.
            RegionScheduler.drainMainSyncTicks();
            ParallelProcessor.onEntityTickBatchEnd(batchStart);
            return;
        }

        DistanceManager distanceManager = this.chunkSource.chunkMap.getDistanceManager();
        TickRateManager tickRateManager = this.tickRateManager();

        ArrayList<Entity> live = fabrolia$liveBuf;
        if (live == null) {
            live = new ArrayList<>(fabrolia$lastLiveSize);
            fabrolia$liveBuf = live;
        } else {
            live.clear();
        }
        final ArrayList<Entity> liveList = live;
        this.entityTickList.forEach(e -> {
            if (e == null || e.isRemoved()) return;
            if (tickRateManager.isEntityFrozen(e)) return;
            liveList.add(e);
        });
        final int liveCount = live.size();
        fabrolia$lastLiveSize = Math.max(64, liveCount + (liveCount >> 2));
        if (FabroliaConfig.enableFabroliaDespawn && !FabroliaConfig.disabled && liveCount > 0) {
            ExecutorService dexec = ParallelProcessor.executor;
            int dpool = Math.max(1, ParallelProcessor.getPoolSize());
            // poolSize fan-out, min 16 entities/batch — checkDespawn is dirt cheap so a
            // higher per-batch floor avoids worker startup dominating the work.
            int dchunkSize = Math.max(16, (liveCount + dpool - 1) / dpool);
            int dchunks = (liveCount + dchunkSize - 1) / dchunkSize;
            CompletableFuture<?>[] dfutures = new CompletableFuture<?>[dchunks];
            for (int c = 0, i = 0; c < dchunks; c++, i += dchunkSize) {
                final int from = i;
                final int to = Math.min(i + dchunkSize, liveCount);
                dfutures[c] = CompletableFuture.runAsync(() -> {
                    for (int j = from; j < to; j++) {
                        Entity ent = liveList.get(j);
                        if (ent.isRemoved()) continue;
                        try {
                            ent.checkDespawn();
                        } catch (Exception ex) {
                            FABROLIA_LOGGER.error("Fabrolia checkDespawn failed for {} ({})",
                                    ent.getType(), ent.getUUID(), ex);
                        }
                    }
                }, dexec);
            }
            ParallelProcessor.pumpUntilDone(CompletableFuture.allOf(dfutures));
        } else {
            profilerfiller.push("checkDespawn");
            for (int i = 0; i < liveCount; i++) {
                Entity ent = live.get(i);
                if (!ent.isRemoved()) ent.checkDespawn();
            }
            profilerfiller.pop();
        }

        ArrayList<Entity> pendingFabrolia = new ArrayList<>(fabrolia$lastBatchSize);
        ArrayList<Entity> syncList = fabrolia$syncBuf;
        if (syncList == null) {
            syncList = new ArrayList<>(64);
            fabrolia$syncBuf = syncList;
        } else {
            syncList.clear();
        }

        final ArrayList<Entity> syncDeferred = syncList;
        for (int i = 0; i < liveCount; i++) {
            Entity entity = live.get(i);
            if (entity.isRemoved()) continue;
            if (tickRateManager.isEntityFrozen(entity)) continue;

            if (!(entity instanceof ServerPlayer) && !distanceManager.inEntityTickingRange(entity.chunkPosition().pack())) continue;
            if (!(entity instanceof ServerPlayer)) {
                long entityChunkPos = entity.chunkPosition().pack();
                ChunkHolder holder = this.chunkSource.getVisibleChunkIfPresent(entityChunkPos);
                if (holder == null || holder.getTickingChunk() == null) continue;
            }

            Entity vehicle = entity.getVehicle();
            if (vehicle != null) {
                if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) continue;
                entity.stopRiding();
            }

            if (FabroliaConfig.disabled || ParallelProcessor.shouldTickSynchronously(entity)) {
                syncDeferred.add(entity);
            } else {
                pendingFabrolia.add(entity);
            }
        }

        int n = pendingFabrolia.size();
        fabrolia$lastBatchSize = Math.max(64, n + (n >> 2));
        CompletableFuture<Void> all = null;
        if (n > 0) {
            // Chunked fabrolia-pool dispatch (region mode returns early above with the
            // region-owned path — this is the proven FJ fallback).
            ExecutorService exec = ParallelProcessor.executor;
            int pool = Math.max(1, ParallelProcessor.getPoolSize());
            // poolSize fan-out (memory: never per-entity CF; gather + fan-out poolSize chunks).
            // Min 8 entities/batch so tiny entity counts don't pay scheduling overhead.
            int chunkSize = Math.max(8, (n + pool - 1) / pool);
            int chunks = (n + chunkSize - 1) / chunkSize;

            final ArrayList<Entity> pending = pendingFabrolia;
            CompletableFuture<?>[] futures = new CompletableFuture<?>[chunks];
            for (int c = 0, i = 0; c < chunks; c++, i += chunkSize) {
                final int from = i;
                final int to = Math.min(i + chunkSize, n);
                futures[c] = CompletableFuture.runAsync(() -> {
                    for (int j = from; j < to; j++) {
                        ParallelProcessor.tickEntity(self, pending.get(j), true);
                    }
                }, exec);
            }
            all = CompletableFuture.allOf(futures);
        }
        // Spawn-barrier overlap: adopt this level's in-flight parallel-spawn future
        // (launched in tickChunks, deliberately not awaited there — see
        // ServerChunkCacheMixin.fabrolia$waitSpawnThenBlockTick) so the single barrier
        // below covers max(entityBatch, spawn) instead of two sequential parks.
        CompletableFuture<Void> spawnFut =
                ((Object) this.chunkSource) instanceof SpawnFutureTaker taker
                        ? taker.fabrolia$takeSpawnFuture() : null;
        if (spawnFut != null) {
            all = all == null ? spawnFut : CompletableFuture.allOf(all, spawnFut);
        }

        int sz = syncDeferred.size();
        if (sz > 0) {
            profilerfiller.push("tickSync");
            for (int i = 0; i < sz; i++) {
                ParallelProcessor.tickEntity(self, syncDeferred.get(i), false);
            }
            profilerfiller.pop();
        }
        if (all != null) ParallelProcessor.pumpUntilDone(all);
        ParallelProcessor.onEntityTickBatchEnd(batchStart);
    }

    @Shadow @Final private ObjectLinkedOpenHashSet<BlockEventData> blockEvents;

    @Override
    public Object fabrolia$blockEventLock() {
        return fabrolia$blockEventLock;
    }

    @Override
    public ObjectLinkedOpenHashSet<BlockEventData> fabrolia$blockEvents() {
        return blockEvents;
    }

    /**
     * A2 increment 3 — with async islands live, the block-event slot processes
     * only GLOBAL-domain events here; island-owned events are stashed (in
     * queue order, under the same lock) and executed by their island's unit
     * coordinator before its entity phase — vanilla's island-relative order.
     * Async off: vanilla body (with the lock redirects below) runs unchanged.
     */
    @WrapMethod(method = "runBlockEvents")
    private void fabrolia$islandBlockEvents(Operation<Void> original) {
        if (com.axalotl.fabrolia.common.regions.IslandBlockEvents.runPartitioned(this.getLevel())) {
            return;
        }
        original.call();
    }

    // All five redirects now operate on the REAL vanilla blockEvents set (insertion-ordered SET
    // → dedup + FIFO exactly as vanilla), just serialized by fabrolia$blockEventLock so region
    // workers may queue events concurrently with the main drain. Restores the dedup the
    // ConcurrentLinkedQueue lost (double-fired pistons → flying-machine self-break).
    @Redirect(method = "blockEvent", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;add(Ljava/lang/Object;)Z", remap = false))
    private boolean overwriteQueueAdd(ObjectLinkedOpenHashSet<BlockEventData> set, Object object) {
        // Unit-local capture: the coordinator's own posts during its
        // scheduled+event segments run in THIS unit's drain (vanilla
        // same-tick recursion); everything else takes the shared set.
        if (com.axalotl.fabrolia.common.regions.IslandBlockEvents.captureLocal((ServerLevel) (Object) this, (BlockEventData) object)) {
            return true;
        }
        synchronized (fabrolia$blockEventLock) {
            return set.add((BlockEventData) object);
        }
    }

    @Redirect(method = "clearBlockEvents", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;removeIf(Ljava/util/function/Predicate;)Z", remap = false))
    private boolean overwriteQueueRemoveIf(ObjectLinkedOpenHashSet<BlockEventData> set, Predicate<BlockEventData> filter) {
        synchronized (fabrolia$blockEventLock) {
            return set.removeIf(filter);
        }
    }

    @Redirect(method = "runBlockEvents", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;isEmpty()Z", remap = false))
    private boolean overwriteEmptyCheck(ObjectLinkedOpenHashSet<BlockEventData> set) {
        synchronized (fabrolia$blockEventLock) {
            return set.isEmpty();
        }
    }

    @Redirect(method = "runBlockEvents", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;removeFirst()Ljava/lang/Object;", remap = false))
    private Object overwriteQueueRemoveFirst(ObjectLinkedOpenHashSet<BlockEventData> set) {
        synchronized (fabrolia$blockEventLock) {
            // The runBlockEvents loop only calls this after its own isEmpty() check; main is the
            // sole drainer (workers only add), so the set is non-empty here. Guard defensively.
            return set.isEmpty() ? null : set.removeFirst();
        }
    }

    @Redirect(method = "runBlockEvents", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;addAll(Ljava/util/Collection;)Z", remap = false))
    private boolean overwriteQueueAddAll(ObjectLinkedOpenHashSet<BlockEventData> set, Collection<? extends BlockEventData> c) {
        synchronized (fabrolia$blockEventLock) {
            return set.addAll(c);
        }
    }

    @Redirect(method = "sendBlockUpdated", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;isUpdatingNavigations:Z", opcode = Opcodes.PUTFIELD))
    private void skipSendBlockUpdatedCheck(ServerLevel instance, boolean value) {
    }

    @WrapMethod(method = "explode")
    private void createExplosion(
            @Nullable Entity source,
            @Nullable DamageSource damageSource,
            @Nullable ExplosionDamageCalculator damageCalculator,
            double x, double y, double z,
            float radius,
            boolean fire,
            Level.ExplosionInteraction explosionInteraction,
            ParticleOptions smallExplosionParticles,
            ParticleOptions largeExplosionParticles,
            WeightedList<ExplosionParticleInfo> particleInfo,
            Holder<SoundEvent> explosionSound,
            Operation<Void> original
    ) {
        synchronized (FabroliaLocks.tickLockOr(this.fabrolia$explosionLock)) {
            // A throw out of the explosion used to leave suppress=true forever:
            // every later sendBlockUpdated then parked navigations in
            // fabrolia$pendingRecompute with nothing ever draining it — mobs stop
            // repathing on block changes for the rest of the session and the set
            // grows without bound. Audit 2026-07-26.
            ((LithiumServerLevel) (Object) this).fabrolia$setSuppress(true);
            try {
                original.call(
                        source, damageSource, damageCalculator,
                        x, y, z, radius, fire,
                        explosionInteraction,
                        smallExplosionParticles,
                        largeExplosionParticles,
                        particleInfo,
                        explosionSound
                );
            } finally {
                ((LithiumServerLevel) (Object) this).fabrolia$setSuppress(false);
                ((LithiumServerLevel) (Object) this).fabrolia$recomputeAllNavigations();
            }
        }
    }
}