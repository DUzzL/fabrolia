package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaNavigationBatch;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.axalotl.fabrolia.api.utils.SnapshotList;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
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
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.storage.WritableLevelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;
import com.axalotl.fabrolia.common.utils.SpawnFutureTaker;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;

@SuppressWarnings("all")
@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class ServerLevelMixin extends Level implements WorldGenLevel,
        com.axalotl.fabrolia.common.regions.BlockEventQueueAccess,
        com.axalotl.fabrolia.common.regions.BlockTickingPassAccess {

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
    @Shadow @Mutable @Final private Int2ObjectMap<EnderDragonPart> dragonParts;

    @Shadow public abstract @NotNull ServerLevel getLevel();

    /** {@code shouldTickBlocksAt(long)} = {@code inBlockTickingRange} = the completed
     *  simulation level (DistanceManagerMixin), read from one captured snapshot. */
    @Override
    public java.util.function.LongPredicate fabrolia$blockTickingPass() {
        var tracker = ((com.axalotl.fabrolia.common.mixin.server.DistanceManagerAccessor)
                this.chunkSource.chunkMap.getDistanceManager()).fabrolia$simulationChunkTracker();
        com.axalotl.fabrolia.common.regions.CompletedChunkLevels.Reader levels =
                ((com.axalotl.fabrolia.common.regions.CompletedSimulationView) tracker)
                        .fabrolia$completedLevels().reader();
        return chunkKey -> net.minecraft.server.level.ChunkLevel.isBlockTicking(levels.get(chunkKey));
    }

    @Unique
    private final Object fabrolia$explosionLock = new Object();

    /** Guards the vanilla {@code blockEvents} ObjectLinkedOpenHashSet so region workers can queue
     *  block events concurrently with each other and the main drain. Vanilla's SET semantics
     *  (dedup — a piston re-triggered several times in a tick queues ONE event — AND insertion
     *  order) MUST be preserved: the old ConcurrentLinkedQueue replacement lost the dedup, so a
     *  duplicate block event fired the piston twice → flying machines broke on themselves. */
    @Unique
    final Object fabrolia$blockEventLock = new Object();
    @Unique
    private int fabrolia$lastBatchSize = 256;
    @Unique
    private int fabrolia$lastLiveSize = 256;
    @Unique
    private ArrayList<Entity> fabrolia$syncBuf;
    @Unique
    private ArrayList<Entity> fabrolia$liveBuf;

    protected ServerLevelMixin(WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess, Holder<DimensionType> dimensionTypeRegistration, boolean isClientSide, boolean isDebug, long biomeZoomSeed, int maxChainedNeighborUpdates) {
        super(levelData, dimension, registryAccess, dimensionTypeRegistration, isClientSide, isDebug, biomeZoomSeed, maxChainedNeighborUpdates);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(CallbackInfo ci) {
        navigatingMobs = ConcurrentCollections.newHashSet();
        // SnapshotList: login/logout = rare write rebuilding snapshot; broadcast =
        // very hot lock-free iteration over the volatile snapshot array.
        players = new SnapshotList<>();
        // Entity-target packets may resolve an EnderDragonPart on physical Main
        // while an owning island publishes dragon teardown. Keep the exact
        // vanilla id index linearizable; the default synchronized wrapper uses
        // itself as the mutex for get/put/remove and its collection views.
        dragonParts = Int2ObjectMaps.synchronize(dragonParts);
    }

    @WrapMethod(method = "dragonParts")
    private Collection<EnderDragonPart> fabrolia$snapshotDragonParts(
            Operation<Collection<EnderDragonPart>> original) {
        synchronized (dragonParts) {
            return List.copyOf(original.call());
        }
    }

    /**
     * Autonomous islands own their scheduled lanes; Main services only the
     * residual native complement. Regions-off retains the original scheduler
     * under global authority. Both block and fluid call sites use this boundary.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/ticks/LevelTicks;"
                            + "tick(JILjava/util/function/BiConsumer;)V"),
            require = 2,
            allow = 2)
    private <T> void fabrolia$islandScheduledTicks(net.minecraft.world.ticks.LevelTicks<T> instance,
                                                long gameTime, int maxTicks,
                                                java.util.function.BiConsumer<net.minecraft.core.BlockPos, T> ticker,
                                                Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (com.axalotl.fabrolia.common.regions.IslandScheduledTicks
                    .slotPartitionedAsync(
                            instance, this.getLevel(), gameTime, maxTicks, ticker)) {
                return;
            }
            if (com.axalotl.fabrolia.common.regions.IslandScheduledTicks
                    .tickPartitioned(
                            instance, this.getLevel(), gameTime, maxTicks, ticker)) {
                return;
            }
            original.call(instance, gameTime, maxTicks, ticker);
        });
    }

    @WrapMethod(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V")
    private void fabrolia$guardTickNonPassenger(Entity entity, Operation<Void> original) {
        ParallelProcessor.TickGuard guard = (ParallelProcessor.TickGuard) entity;
        if (guard.fabrolia$tickLease().isArrivalPending()) return;
        boolean inheritedReservation = com.axalotl.fabrolia.common.regions
                .RegionalPlayerEntityTicksV2.ownsReservedEntityTick(entity);
        boolean acquired = false;
        if (!inheritedReservation && !guard.fabrolia$tryBeginTick()) {
            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                    .failIfClaimedTickGuardContention(entity, "tickNonPassenger");
            return;
        }
        if (!inheritedReservation) acquired = true;
        Throwable failure = null;
        try {
            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                    .rejectReservedExternalEntityConsumer(entity);
            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                    .markVanillaEntityConsumerEntered(entity);
            original.call(entity);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        } finally {
            try {
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .finishPlayerEntityBody(entity);
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        fabrolia$mergePlayerTickFailure(failure, cleanup));
            }
            if (acquired) {
                try {
                    guard.fabrolia$endTick();
                } catch (Throwable cleanup) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            fabrolia$mergePlayerTickFailure(failure, cleanup));
                }
            }
        }
        if (failure != null) ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(failure);
    }

    @Unique
    private static Throwable fabrolia$mergePlayerTickFailure(Throwable primary,
                                                              Throwable cleanup) {
        if (primary == null) return cleanup;
        if (primary != cleanup) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
                // Cleanup diagnostics cannot replace the exact tick failure.
            }
        }
        return primary;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$throwPlayerTickFailure(
            Throwable failure) throws T {
        throw (T) failure;
    }

    @Unique
    private static CompletableFuture<Void> fabrolia$beginFlatFanout(
            ExecutorService executor, int batchCount,
            FabroliaFlatFailureGate gate, FabroliaFlatBatch body) {
        if (batchCount <= 0) return CompletableFuture.completedFuture(null);
        FabroliaFlatFanout fanout;
        try {
            fanout = new FabroliaFlatFanout(batchCount, gate, body);
        } catch (Throwable constructionFailure) {
            Throwable winner = gate.publish(constructionFailure);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(winner);
            return failed;
        }
        try {
            for (FabroliaFlatTask task : fanout.tasks) {
                gate.runNext(() -> executor.execute(task));
            }
        } catch (Throwable submissionFailure) {
            gate.publish(submissionFailure);
        } finally {
            fanout.finishSubmission();
        }
        return fanout.completion;
    }

    @Unique
    private static void fabrolia$recordFlatFailure(FabroliaFlatFailureGate failure, Throwable thrown) {
        failure.publish(thrown);
    }

    @Unique
    private static void fabrolia$recordFlatCleanupFailure(FabroliaFlatFailureGate failure, Throwable cleanup) {
        failure.recordCleanup(cleanup);
    }

    @Unique
    private static CompletableFuture<Void> fabrolia$observeFlatFuture(CompletableFuture<Void> future,
                                                                      FabroliaFlatFailureGate failure) {
        if (future == null) return null;
        return future.handle((unused, thrown) -> {
            if (thrown != null) fabrolia$recordFlatFutureFailure(failure, thrown);
            return null;
        });
    }

    @Unique
    private void fabrolia$awaitAcceptedFlatWork(CompletableFuture<?> first, CompletableFuture<?> second,
                                                FabroliaFlatFailureGate failure) {
        fabrolia$awaitFlatTerminal(first, failure);
        if (second != null && second != first) {
            fabrolia$awaitFlatTerminal(second, failure);
        }
        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable globalWinner) {
            failure.publish(globalWinner);
        }
    }

    @Unique
    private void fabrolia$awaitFlatTerminal(CompletableFuture<?> future, FabroliaFlatFailureGate failure) {
        if (future == null) return;
        MinecraftServer server = this.getServer();
        if (server != null && server.isSameThread()) {
            server.blockingCount++;
            try {
                while (!future.isDone()) {
                    boolean progressed = false;
                    if (failure.failureOrNull() == null) {
                        try {
                            for (ServerLevel level : server.getAllLevels()) {
                                if (failure.failureOrNull() != null) break;
                                if (RegionScheduler.callSuccessBooleanIfHealthy(
                                        () -> level.getChunkSource().mainThreadProcessor.pollTask())) {
                                    progressed = true;
                                }
                            }
                        } catch (Throwable progressFailure) {
                            failure.publish(progressFailure);
                        }
                    }
                    if (!progressed) {
                        LockSupport.parkNanos("Fabrolia ServerLevel flat fail-stop await", 100_000L);
                    }
                }
            } finally {
                server.blockingCount--;
            }
        } else {
            while (!future.isDone()) {
                LockSupport.parkNanos("Fabrolia ServerLevel flat fail-stop await off-main", 100_000L);
            }
        }
        try {
            future.join();
        } catch (Throwable wrapped) {
            fabrolia$recordFlatFutureFailure(failure, wrapped);
        }
    }

    @Unique
    private static void fabrolia$recordFlatFutureFailure(FabroliaFlatFailureGate failure, Throwable transported) {
        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable globalWinner) {
            failure.publish(globalWinner);
            return;
        }
        failure.publish(fabrolia$unwrapFlatFailure(transported));
    }

    @Unique
    private static Throwable fabrolia$unwrapFlatFailure(Throwable failure) {
        Throwable exact = failure;
        while ((exact instanceof CompletionException || exact instanceof ExecutionException)
                && exact.getCause() != null) {
            exact = exact.getCause();
        }
        return exact;
    }

    @Unique
    private static void fabrolia$throwFlatFailure(FabroliaFlatFailureGate failure) {
        Throwable thrown = failure.failureOrNull();
        if (thrown != null) ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(thrown);
        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable globalWinner) {
            ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(failure.publish(globalWinner));
        }
    }


    @WrapMethod(method = "tickPassenger(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)V")
    private void fabrolia$guardTickPassenger(Entity vehicle, Entity entity, Operation<Void> original) {
        if (((ParallelProcessor.TickGuard) vehicle).fabrolia$tickLease().isArrivalPending()
                || ((ParallelProcessor.TickGuard) entity).fabrolia$tickLease().isArrivalPending()) return;
        // The source ride is retained for the between-unit transfer, not dead.
        // Vanilla's pre-body isRemoved gate would otherwise dismount a rider
        // hidden by this same source frame's temporary departure visibility.
        if (vehicle.getRemovalReason() == null && entity.getRemovalReason() == null
                && (com.axalotl.fabrolia.common.regions.NetherPortalTransfers.isSourceMutationBlocked(vehicle)
                || com.axalotl.fabrolia.common.regions.NetherPortalTransfers.isSourceMutationBlocked(entity))) return;
        ParallelProcessor.TickGuard guard = (ParallelProcessor.TickGuard) entity;
        boolean inheritedReservation = com.axalotl.fabrolia.common.regions
                .RegionalPlayerEntityTicksV2.ownsReservedEntityTick(entity);
        boolean acquired = false;
        if (!inheritedReservation && !guard.fabrolia$tryBeginTick()) {
            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                    .failIfClaimedTickGuardContention(entity, "tickPassenger");
            return;
        }
        if (!inheritedReservation) acquired = true;
        Throwable failure = null;
        try {
            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                    .rejectReservedExternalEntityConsumer(entity);
            original.call(vehicle, entity);
        } catch (Throwable thrown) {
            // Close the generation before either ownership witness becomes
            // visible again. An outer entity/tick boundary is too late: both
            // cleanup calls below release admission for another consumer.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        } finally {
            try {
                com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                        .finishPlayerEntityBody(entity);
            } catch (Throwable cleanup) {
                Throwable candidate = fabrolia$mergePlayerTickFailure(failure, cleanup);
                failure = RegionScheduler.publishFatalAndSelectWinner(candidate);
            }
            if (acquired) {
                try {
                    guard.fabrolia$endTick();
                } catch (Throwable cleanup) {
                    Throwable candidate = fabrolia$mergePlayerTickFailure(failure, cleanup);
                    failure = RegionScheduler.publishFatalAndSelectWinner(candidate);
                }
            }
        }
        if (failure != null) ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(failure);
    }

    /**
     * tickPassenger has removal/vehicle gates before its actual body. Stamp only at the
     * exact commonTick invoke (26.3's tick head: invulnerability, old pos/rot, tickCount),
     * not at wrapper HEAD, or an early return would suppress a later legitimate
     * standalone consumer in the same global phase.
     */
    @WrapOperation(
            method = "tickPassenger(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;commonTick()V"),
            require = 1)
    private void fabrolia$stampPassengerBody(Entity entity, Operation<Void> original) {
        com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .markPlayerEntityBodyPhase(entity);
        // The passenger's real tick: re-open its per-tick function pass here,
        // after vanilla's removed/vehicle/tick-list gates.
        com.axalotl.fabrolia.common.regions.IslandFunctionHead.entityTicked(
                (com.axalotl.fabrolia.common.regions.FunctionPassMark) entity);
        original.call(entity);
    }

    /**
     * Exact vanilla ordinal seam. Every lambda gate, checkDespawn call, profiler
     * section and vehicle decision has already run on physical main. Only the one
     * guardEntityTick invocation for an eligible standalone real player migrates.
     */
    @WrapOperation(
            method = "lambda$tick$0(Lnet/minecraft/world/TickRateManager;"
                    + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                    + "Lnet/minecraft/world/entity/Entity;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;guardEntityTick("
                            + "Ljava/util/function/Consumer;"
                            + "Lnet/minecraft/world/entity/Entity;)V",
                    ordinal = 0),
            require = 1,
            allow = 1)
    private void fabrolia$exactOrdinalPlayerBody(
            ServerLevel level, Consumer<Entity> ticker, Entity entity,
            Operation<Void> original) {
        if (com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                .executeExactOrdinal(level, entity,
                        () -> original.call(level, ticker, entity))) {
            return;
        }
        // An ineligible/non-player entity still executes the exact vanilla
        // guard body at this canonical ordinal.  The exact actor owns the
        // paused pool here, so a fresh body permit is legal and prevents a
        // peer W from being followed by this fallback mutation.
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(level, ticker, entity));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;"
                            + "invalidateTickCache()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitEnvironmentTickCacheInvalidation(
            net.minecraft.world.attribute.EnvironmentAttributeSystem instance,
            Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/border/WorldBorder;tick()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitWorldBorderTick(
            net.minecraft.world.level.border.WorldBorder instance,
            Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;advanceWeatherCycle()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitWeatherTick(
            ServerLevel instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    // The sleep clock advance (vanilla moveToTimeMarker; NeoForge's
    // ClockAdjustment.apply) is admitted by the platform modules'
    // ServerLevelSleepClockMixin: NeoForge patches that call out of tick.

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;wakeUpAllPlayers()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitWakePlayers(
            ServerLevel instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;resetWeatherCycle()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitWeatherReset(
            ServerLevel instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;updateSkyBrightness()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitSkyBrightnessUpdate(
            ServerLevel instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;tickTime()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitLevelTimeTick(
            ServerLevel instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/raid/Raids;"
                            + "tick(Lnet/minecraft/server/level/ServerLevel;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitRaidCollectionTick(
            net.minecraft.world.entity.raid.Raids instance, ServerLevel level,
            Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(instance, level));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;"
                            + "tick(Ljava/util/function/BooleanSupplier;Z)V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitChunkSourceTick(
            ServerChunkCache instance, java.util.function.BooleanSupplier haveTime,
            boolean tickChunks, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(instance, haveTime, tickChunks));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;resetEmptyTime()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitEmptyTimeReset(
            ServerLevel instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/server/level/ServerLevel;emptyTime:I",
                    opcode = Opcodes.PUTFIELD),
            require = 1,
            allow = 1)
    private void fabrolia$admitEmptyTimeIncrement(
            ServerLevel instance, int value, Operation<Void> original) {
        // Vanilla has already calculated old emptyTime + 1, but no state was
        // changed yet. Linearize the sole PUTFIELD against W instead of
        // retaining a permit across the level's later exact-pause boundary.
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(instance, value));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/dimension/end/"
                            + "EnderDragonFight;tick()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitEnderDragonFightTick(
            EnderDragonFight instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitBlockEntityTick(
            ServerLevel instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/"
                            + "PersistentEntitySectionManager;tick()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitEntityManagerTick(
            net.minecraft.world.level.entity.PersistentEntitySectionManager<?> instance,
            Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/redstone/"
                            + "CollectingNeighborUpdater;"
                            + "setDebugListener(Ljava/util/function/Consumer;)V"),
            require = 2,
            allow = 2)
    private void fabrolia$admitNeighborDebugListener(
            net.minecraft.world.level.redstone.CollectingNeighborUpdater instance,
            java.util.function.Consumer<?> listener, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(instance, listener));
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/debug/LevelDebugSynchronizers;"
                            + "tick(Lnet/minecraft/util/debug/ServerDebugSubscribers;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitLevelDebugSynchronizersTick(
            net.minecraft.util.debug.LevelDebugSynchronizers instance,
            net.minecraft.util.debug.ServerDebugSubscribers subscribers,
            Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(instance, subscribers));
    }

    /** Retain the worker exclusion fence through vanilla's block-entity tail. */
    @WrapMethod(method = "tick(Ljava/util/function/BooleanSupplier;)V")
    private void fabrolia$exactPlayerLevelTurn(java.util.function.BooleanSupplier hasTimeLeft,
                                               Operation<Void> original) {
        Throwable failure = null;
        try {
            // Logical entry is selected under the global W lock, but no external
            // permit survives this cut: beginExactLevelIteration later waits for
            // externalObligations == 0 and must never wait on its own level body.
            RegionScheduler.commitSuccessIfHealthy(() -> {});
            original.call(hasTimeLeft);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            failure = com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                    .finishLevelTurn((ServerLevel) (Object) this, failure);
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    fabrolia$mergePlayerTickFailure(failure, cleanup));
        }
        if (failure == null) {
            try {
                // finishLevelTurn may release the exact-level pause. Refuse a
                // normal return if peer W won during that mandatory cleanup.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(failure);
    }


    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V",
                    ordinal = 0),
            require = 1,
            allow = 1)
    private void overwriteEntityTicking(EntityTickList entityTickList, Consumer<Entity> consumer,
                                        Operation<Void> originalEntityList) {
        ProfilerFiller profiler = Profiler.get();
        ServerLevel self = this.getLevel();
        long batchStart = System.nanoTime();


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
            boolean exactPlayerOrder = com.axalotl.fabrolia.common.regions
                    .RegionalPlayerEntityTicksV2.exactOrdinalActive(rsLease);
            if (rsLease != null) {
                com.axalotl.fabrolia.common.regions.CrossDimArrivals.rearmPendingLeases(rsLease);
            }
            if (((Object) this.chunkSource) instanceof SpawnFutureTaker armTaker) {
                RegionScheduler.runSuccessBodyIfHealthy(
                        armTaker::fabrolia$armDeferredChunkWork);
            }
            java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>> regionAllRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            RegionScheduler.runSuccessBodyIfHealthy(() -> regionAllRef.set(
                    exactPlayerOrder
                            ? RegionScheduler.dispatchPreEntityWork(self)
                            : RegionScheduler.dispatchOwnedEntityTicks(self)));
            CompletableFuture<Void> regionAll = java.util.Objects.requireNonNull(
                    regionAllRef.get(), "region dispatch did not publish its barrier");
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
            // Ordinary region producers are now quiescent. Drain the established
            // main-owned entity lane before acquiring the exact-turn pool fence.
            // In async mode islands chain ahead of Main and keep publishing:
            // Main services the islands that are idle now and leaves the rest
            // queued for its next barrier pump instead of parking behind a
            // unit that ends anyway; the exact-turn pool fence needs the
            // complete lane.
            if (exactPlayerOrder) RegionScheduler.drainMainSyncTicks();
            else RegionScheduler.drainReadyMainSyncTicks();
            if (exactPlayerOrder) {
                Throwable exactFailure = null;
                boolean opened = false;
                try {
                    com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                            .beginExactLevelIteration(rsLease);
                    opened = true;
                    // Invoke the actual transformed call operation and its actual
                    // synthetic vanilla Consumer. Gates/profiler/vehicle handling and
                    // EntityTickList ordinal therefore remain canonical on main. The
                    // exact pause is already held before this permit is acquired, and
                    // the permit terminates before endExactLevelIteration/release.
                    RegionScheduler.runSuccessBodyIfHealthy(
                            () -> originalEntityList.call(entityTickList, consumer));
                } catch (Throwable failure) {
                    // The transformed EntityTickList body may already have changed
                    // vanilla/Fabric state.  Its exact W must become generation-fatal
                    // before the exact-level ThreadLocal/fence cleanup is observable.
                    exactFailure = RegionScheduler.publishFatalAndSelectWinner(failure);
                } finally {
                    if (opened) {
                        try {
                            com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                                    .endExactLevelIteration(rsLease);
                        } catch (Throwable cleanup) {
                            exactFailure = RegionScheduler.publishFatalAndSelectWinner(
                                    fabrolia$mergePlayerTickFailure(exactFailure, cleanup));
                        }
                    }
                }
                if (exactFailure == null) {
                    try {
                        // ThreadLocal teardown is outside the entity-list permit.
                        // Do not publish finishLevel or any following stage after
                        // a peer W which won during that mandatory cleanup.
                        RegionScheduler.commitSuccessIfHealthy(() -> {});
                    } catch (Throwable peerFailure) {
                        exactFailure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
                    }
                }
                if (exactFailure != null) {
                    ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(exactFailure);
                }
            }
            // Every exact actor consumer (or pre-entry main fallback) has completed
            // synchronously at its canonical list ordinal before this seal.
            RegionScheduler.runSuccessBodyIfHealthy(() ->
                    com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2
                            .finishLevel(rsLease));
            // Cross-region effects the workers deferred (writes / neighbour updates that
            // left their claimed region) — replay on main now that every region is
            // quiescent, so boundary-crossing redstone completes within this tick,
            // single-threaded and safe (CrossRegionGuard).
            var rsDefer = RegionScheduler.forLevel(self);
            if (rsDefer != null) {
                // A2 async islands: island-domain deferrals were already replayed
                // inside each unit (coordinator, post-barrier); this drains only
                // the GLOBAL timeline's queue — identical to pre-A2 in sync mode.
                RegionScheduler.runSuccessBodyIfHealthy(rsDefer::drainDeferredCrossRegion);
                // A8: same-tick completion window for routed commands — units
                // that reached their command quickly get their result THIS
                // tick (bounded fence attempt; head batch next tick is the
                // fallback), so a routed repeating block costs ~1ms of unit
                // time instead of a whole tick.
                if (rsDefer.asyncActive
                        && com.axalotl.fabrolia.common.regions.IslandCommands.hasRoutedPending()) {
                    RegionScheduler.runSuccessBodyIfHealthy(
                            () -> com.axalotl.fabrolia.common.regions.IslandCommands
                                    .drainRoutedBatch());
                }
            }
            // Tail entries queued just before the last region task completed.
            // Main holds no claim and the global lane is done. MAIN_SYNC work of
            // busy islands does not wait here: it raises a service request and is
            // served at the next safe point (post-tick boundary, level head).
            // Only overdue tracker publications may wait, bounded by the island's
            // running unit; the rest waits for the next pump.
            if (exactPlayerOrder) {
                RegionScheduler.drainMainSyncTicks();
            } else {
                RegionScheduler.drainOverdueMainSyncTicks();
                RegionScheduler.drainOverdueMainTracking();
            }
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> ParallelProcessor.onEntityTickBatchEnd(batchStart));
            return;
        }

        // Regions off: fail-stop flat fan-out over the vanilla entity list.
        FabroliaFlatFailureGate flatFailure = new FabroliaFlatFailureGate();
        CompletableFuture<Void> spawnFuture = ((Object) this.chunkSource) instanceof SpawnFutureTaker taker
                ? taker.fabrolia$takeSpawnFuture() : null;
        CompletableFuture<Void> observedSpawn = fabrolia$observeFlatFuture(spawnFuture, flatFailure);
        DistanceManager distanceManager = this.chunkSource.chunkMap.getDistanceManager();
        TickRateManager tickRateManager = this.tickRateManager();
        ArrayList<Entity> live = fabrolia$liveBuf;
        if (live == null) {
            live = new ArrayList<>(fabrolia$lastLiveSize);
            fabrolia$liveBuf = live;
        } else {
            live.clear();
        }
        ArrayList<Entity> liveList = live;
        try {
            flatFailure.runNext(() -> this.entityTickList.forEach(e -> flatFailure.runNext(() -> {
                if (e != null && !e.isRemoved() && !tickRateManager.isEntityFrozen(e)) {
                    liveList.add(e);
                }
            })));
        } catch (Throwable thrown) {
            fabrolia$recordFlatFailure(flatFailure, thrown);
        }

        int liveCount = live.size();
        CompletableFuture<Void> despawnWork = null;
        if (flatFailure.failureOrNull() == null && FabroliaConfig.enableFabroliaDespawn
                && !FabroliaConfig.disabled && liveCount > 0) {
            ExecutorService despawnExecutor = ParallelProcessor.executor;
            int despawnPool = Math.max(1, ParallelProcessor.getPoolSize());
            int despawnChunkSize = Math.max(16, (liveCount + despawnPool - 1) / despawnPool);
            int despawnChunks = (liveCount + despawnChunkSize - 1) / despawnChunkSize;
            despawnWork = fabrolia$beginFlatFanout(despawnExecutor, despawnChunks, flatFailure,
                    (failureGate, batchIndex) -> {
                        int from = batchIndex * despawnChunkSize;
                        int to = Math.min(from + despawnChunkSize, liveCount);
                        for (int j = from; j < to; j++) {
                            int entityIndex = j;
                            failureGate.runNext(() -> {
                                Entity ent = liveList.get(entityIndex);
                                if (!ent.isRemoved()
                                        && !((ParallelProcessor.TickGuard) ent).fabrolia$tickLease().isArrivalPending()) {
                                    ent.checkDespawn();
                                }
                            });
                        }
                    });
            fabrolia$awaitAcceptedFlatWork(despawnWork, null, flatFailure);
        } else if (flatFailure.failureOrNull() == null) {
            boolean pushed = false;
            try {
                flatFailure.runNext(() -> profiler.push("checkDespawn"));
                pushed = true;
            } catch (Throwable thrown) {
                fabrolia$recordFlatFailure(flatFailure, thrown);
            }
            if (pushed) {
                try {
                    for (int i = 0; i < liveCount; i++) {
                        int entityIndex = i;
                        try {
                            flatFailure.runNext(() -> {
                                Entity ent = liveList.get(entityIndex);
                                if (!ent.isRemoved()
                                        && !((ParallelProcessor.TickGuard) ent).fabrolia$tickLease().isArrivalPending()) {
                                    ent.checkDespawn();
                                }
                            });
                        } catch (Throwable thrown) {
                            fabrolia$recordFlatFailure(flatFailure, thrown);
                            break;
                        }
                    }
                } finally {
                    try {
                        profiler.pop();
                    } catch (Throwable cleanup) {
                        fabrolia$recordFlatCleanupFailure(flatFailure, cleanup);
                    }
                }
            }
        }

        fabrolia$awaitAcceptedFlatWork(null, null, flatFailure);
        if (flatFailure.failureOrNull() != null) {
            fabrolia$awaitAcceptedFlatWork(observedSpawn, null, flatFailure);
            fabrolia$throwFlatFailure(flatFailure);
        }

        ArrayList<Entity> pendingFabrolia = new ArrayList<>(fabrolia$lastBatchSize);
        ArrayList<Entity> syncList = fabrolia$syncBuf;
        if (syncList == null) {
            syncList = new ArrayList<>(64);
            fabrolia$syncBuf = syncList;
        } else {
            syncList.clear();
        }
        ArrayList<Entity> syncDeferred = syncList;
        for (int i = 0; i < liveCount; i++) {
            int entityIndex = i;
            try {
                flatFailure.runNext(() -> {
                    Entity entity = liveList.get(entityIndex);
                    if (entity.isRemoved() || tickRateManager.isEntityFrozen(entity)) return;
                    if (!(entity instanceof ServerPlayer)
                            && !distanceManager.inEntityTickingRange(entity.chunkPosition().pack())) {
                        return;
                    }
                    Entity vehicle = entity.getVehicle();
                    if (vehicle != null) {
                        if (vehicle instanceof ParallelProcessor.TickGuard vg && vg.fabrolia$isPortalPending()) {
                            return;
                        }
                        if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                            return;
                        }
                        entity.stopRiding();
                    }
                    if (!FabroliaConfig.disabled && !ParallelProcessor.shouldTickSynchronously(entity)) {
                        pendingFabrolia.add(entity);
                    } else {
                        syncDeferred.add(entity);
                    }
                });
            } catch (Throwable thrown) {
                fabrolia$recordFlatFailure(flatFailure, thrown);
                break;
            }
        }
        if (flatFailure.failureOrNull() != null) {
            fabrolia$awaitAcceptedFlatWork(observedSpawn, null, flatFailure);
            fabrolia$throwFlatFailure(flatFailure);
        }

        int pendingCount = pendingFabrolia.size();
        CompletableFuture<Void> entityWork = null;
        if (pendingCount > 0) {
            ExecutorService entityExecutor = ParallelProcessor.executor;
            int pool = Math.max(1, ParallelProcessor.getPoolSize());
            int chunkSize = Math.max(8, (pendingCount + pool - 1) / pool);
            int chunks = (pendingCount + chunkSize - 1) / chunkSize;
            ArrayList<Entity> pending = pendingFabrolia;
            entityWork = fabrolia$beginFlatFanout(entityExecutor, chunks, flatFailure,
                    (failureGate, batchIndex) -> {
                        int from = batchIndex * chunkSize;
                        int to = Math.min(from + chunkSize, pendingCount);
                        for (int j = from; j < to; j++) {
                            int entityIndex = j;
                            failureGate.runNext(
                                    () -> ParallelProcessor.tickEntity(self, pending.get(entityIndex), true));
                        }
                    });
        }

        int syncCount = syncDeferred.size();
        if (syncCount > 0) {
            boolean pushed = false;
            try {
                flatFailure.runNext(() -> profiler.push("tickSync"));
                pushed = true;
            } catch (Throwable thrown) {
                fabrolia$recordFlatFailure(flatFailure, thrown);
            }
            if (pushed) {
                try {
                    for (int i = 0; i < syncCount; i++) {
                        int entityIndex = i;
                        try {
                            flatFailure.runNext(
                                    () -> ParallelProcessor.tickEntity(self, syncDeferred.get(entityIndex), false));
                        } catch (Throwable thrown) {
                            fabrolia$recordFlatFailure(flatFailure, thrown);
                            break;
                        }
                    }
                } finally {
                    try {
                        profiler.pop();
                    } catch (Throwable cleanup) {
                        fabrolia$recordFlatCleanupFailure(flatFailure, cleanup);
                    }
                }
            }
        }

        fabrolia$awaitAcceptedFlatWork(entityWork, observedSpawn, flatFailure);
        fabrolia$throwFlatFailure(flatFailure);
        RegionScheduler.commitSuccessIfHealthy(() -> {
            fabrolia$lastLiveSize = Math.max(64, liveCount + (liveCount >> 2));
            fabrolia$lastBatchSize = Math.max(64, pendingCount + (pendingCount >> 2));
        });
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> ParallelProcessor.onEntityTickBatchEnd(batchStart));
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
        if (!com.axalotl.fabrolia.common.regions.IslandBlockEvents
                .runPartitioned(this.getLevel())) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call());
        }
    }

    /**
     * SavedData encode quiesce (2026-08-13, island raid ticks): vanilla's
     * save path runs {@code saveLevelData} (SavedDataStorage encode of Raids
     * & co) BEFORE {@code chunkSource.save} — i.e. before the
     * ServerChunkCacheMixin quiesce. With island-dispatched raid ticks a
     * worker may be mutating {@code Raid} state exactly while the encode
     * reads it. Quiesce this level's islands around the WHOLE save; the
     * nested chunk-save quiesce then no-ops through mainHoldsAllClaims.
     * No-op when async is off (claims always free).
     */
    @WrapMethod(method = "save")
    private void fabrolia$quiesceIslandsForLevelSave(net.minecraft.util.ProgressListener listener,
                                                     boolean flush, boolean skipSave,
                                                     Operation<Void> original) {
        com.axalotl.fabrolia.common.regions.RegionizedServer rsq =
                com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(this.getLevel());
        if (rsq == null || !rsq.asyncActive
                || rsq.currentThreadHoldsAllClaims()) {
            // This is still the real vanilla save body. Another level may own
            // the live worker generation even when this level has no claims to
            // take, so bypassing the quiesce must not bypass W-vs-body entry.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                com.axalotl.fabrolia.common.regions.RegionTickingAccess chunkMap =
                        (com.axalotl.fabrolia.common.regions.RegionTickingAccess)
                                (Object) this.chunkSource.chunkMap;
                chunkMap.fabrolia$flushDeferredOwnerUnloads();
                original.call(listener, flush, skipSave);
                chunkMap.fabrolia$flushDeferredOwnerUnloads();
            });
            return;
        }
        boolean took = rsq.quiesceAllIslands();
        Throwable failure = null;
        try {
            // Claims cover both accepted unload continuations and the vanilla
            // level save. Flush before the save, then close work whose save
            // futures became ready inside the successful vanilla body.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                com.axalotl.fabrolia.common.regions.RegionTickingAccess chunkMap =
                        (com.axalotl.fabrolia.common.regions.RegionTickingAccess)
                                (Object) this.chunkSource.chunkMap;
                chunkMap.fabrolia$flushDeferredOwnerUnloads();
                original.call(listener, flush, skipSave);
                chunkMap.fabrolia$flushDeferredOwnerUnloads();
            });
        } catch (Throwable primary) {
            // Select the exact generation W before mandatory claim release.
            // Cleanup may add diagnostics, but can never replace this winner.
            failure = RegionScheduler.publishFatalAndSelectWinner(primary);
        }
        if (took) {
            failure = rsq.releaseAllIslands(failure);
        }
        if (failure == null) {
            try {
                // Claim release is cleanup, not permission to report success.
                // A peer W selected during it forbids the normal return.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
        }
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
            if (set.isEmpty()) return null;
            return set.removeFirst();
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

    /**
     * Task #32 — the vanilla navigatingMobs scan calls the synchronized
     * {@code shouldRecomputePath}: a BLOCKING monitor entry on a nav whose
     * owner worker may be parked in the getGroundY→getChunk main bridge while
     * holding it (with Lithium this loop iterates Lithium's internal set —
     * empty under our LithiumServerLevel overrides — but without Lithium it
     * is the live scan). Route through the monitor-free snapshot check
     * instead; identical decision logic, stale-tolerant by design.
     */
    @Redirect(method = "sendBlockUpdated", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;shouldRecomputePath(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean fabrolia$monitorFreeScan(net.minecraft.world.entity.ai.navigation.PathNavigation nav,
                                             net.minecraft.core.BlockPos pos) {
        if (nav instanceof com.axalotl.fabrolia.common.parallelised.utils.FabroliaSafeNavigation safe) {
            Mob m = safe.fabrolia$navMob();
            if (m == null || m.isRemoved()) return false; // leaked entry (task #24)
            return safe.fabrolia$shouldRecomputePathSafe(pos);
        }
        return nav.shouldRecomputePath(pos);
    }

    /**
     * Task #32 (the 2026-07-28 production watchdog deadlock) — vanilla runs
     * {@code recomputePath()} (full pathfinding, synchronized) on whatever
     * thread changed the block. Cross-owner that was BOTH the deadlock edge
     * (main parked on a nav monitor held by a worker that was itself parked
     * on main's chunk service) and an ownership violation. NavRecompute runs
     * it inline only in the mob's own execution context and defers to the
     * owner island's lane otherwise.
     */
    @Redirect(method = "sendBlockUpdated", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;recomputePath()V"))
    private void fabrolia$ownerRoutedRecompute(net.minecraft.world.entity.ai.navigation.PathNavigation nav) {
        com.axalotl.fabrolia.common.regions.NavRecompute.recomputeOwnerSafe(this.getLevel(), nav);
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
            FabroliaNavigationBatch navigationState = (Object) this instanceof FabroliaNavigationBatch batch
                    ? batch : null;
            final boolean[] restoreRequired = {false};
            Throwable failure = null;
            try {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    // Only an admitted, successfully entered optional batch owns cleanup.
                    if (navigationState != null) {
                        navigationState.fabrolia$beginNavigationBatch();
                        restoreRequired[0] = true;
                    }
                    original.call(
                            source, damageSource, damageCalculator,
                            x, y, z, radius, fire,
                            explosionInteraction,
                            smallExplosionParticles,
                            largeExplosionParticles,
                            particleInfo,
                            explosionSound
                    );
                });
            } catch (Throwable bodyFailure) {
                // Select the exact vanilla/Fabric W before making the suppression
                // cleanup visible. A cleanup Throwable must never replace it.
                failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure);
            }

            // Balance only this entered batch; nested/overlapping explosions
            // retain their own suppression. Cleanup never dispatches navigation.
            if (restoreRequired[0]) {
                try {
                    navigationState.fabrolia$endNavigationBatch();
                } catch (Throwable cleanupFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            fabrolia$mergePlayerTickFailure(failure, cleanupFailure));
                }
            }

            if (failure == null && restoreRequired[0]) {
                try {
                    // Recomputing paths mutates simulation state; it is a success
                    // tail, not resource cleanup. Give it a fresh global W cut.
                    RegionScheduler.runSuccessBodyIfHealthy(
                            navigationState::fabrolia$drainNavigationBatch);
                } catch (Throwable tailFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(tailFailure);
                }
            }
            if (failure != null) {
                ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(failure);
            }
        }
    }

    @Unique
    @FunctionalInterface
    interface FabroliaFlatBatch {
        void run(FabroliaFlatFailureGate gate, int batchIndex);
    }

    @Unique
    static final class FabroliaFlatFailureGate {
        private static final int MAX_SUPPRESSED = 8;
        private final ArrayList<FabroliaFlatTask> handles = new ArrayList<>();
        private Throwable failure;
        private int suppressedCleanup;

        void runNext(Runnable body) {
            if (body == null) throw new NullPointerException("body");
            try {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    synchronized (this) {
                        if (failure != null) {
                            ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(failure);
                        }
                    }
                    body.run();
                });
            } catch (Throwable thrown) {
                ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(publish(thrown));
            }
        }

        synchronized void register(FabroliaFlatTask[] tasks) {
            for (FabroliaFlatTask task : tasks) handles.add(task);
            if (failure != null) cancelQueuedLocked();
        }

        synchronized Throwable publish(Throwable candidate) {
            return publishLocked(candidate);
        }

        private Throwable publishLocked(Throwable candidate) {
            if (candidate == null) candidate = new NullPointerException("flat entity tick failure");
            if (failure == null) {
                failure = RegionScheduler.publishFatalAndSelectWinner(candidate);
                cancelQueuedLocked();
            }
            return failure;
        }

        private void cancelQueuedLocked() {
            for (FabroliaFlatTask task : handles) {
                try {
                    task.cancelQueuedBeforeStart();
                } catch (Throwable cleanup) {
                    mergeCleanupLocked(cleanup);
                }
            }
        }

        synchronized void recordCleanup(Throwable cleanup) {
            if (failure == null) {
                publishLocked(cleanup);
            } else {
                mergeCleanupLocked(cleanup);
            }
        }

        private void mergeCleanupLocked(Throwable cleanup) {
            if (cleanup != null && cleanup != failure && suppressedCleanup < MAX_SUPPRESSED) {
                try {
                    failure.addSuppressed(cleanup);
                    suppressedCleanup++;
                } catch (Throwable ignored) {
                }
            }
        }

        synchronized Throwable failureOrNull() {
            return failure;
        }
    }

    @Unique
    static final class FabroliaFlatFanout {
        private final FabroliaFlatFailureGate gate;
        private final FabroliaFlatTask[] tasks;
        private final AtomicInteger pending;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private FabroliaFlatFanout(int batchCount, FabroliaFlatFailureGate gate, FabroliaFlatBatch body) {
            this.gate = gate;
            this.tasks = new FabroliaFlatTask[batchCount];
            this.pending = new AtomicInteger(batchCount + 1);
            for (int i = 0; i < batchCount; i++) {
                tasks[i] = new FabroliaFlatTask(this, gate, body, i);
            }
            gate.register(tasks);
        }

        private void finishSubmission() {
            finishObligation();
        }

        private void finishTask() {
            finishObligation();
        }

        private void finishObligation() {
            int remaining = pending.decrementAndGet();
            if (remaining < 0) {
                Throwable duplicate = gate.publish(
                        new IllegalStateException("flat entity fan-out obligation completed more than once"));
                ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(duplicate);
            }
            if (remaining == 0) {
                Throwable terminalFailure = gate.failureOrNull();
                if (terminalFailure == null) {
                    try {
                        RegionScheduler.commitSuccessIfHealthy(() -> {
                            if (!completion.complete(null)) {
                                throw new IllegalStateException("flat entity fan-out completed more than once");
                            }
                        });
                        return;
                    } catch (Throwable completionFailure) {
                        terminalFailure = gate.publish(completionFailure);
                    }
                }
                completion.completeExceptionally(terminalFailure);
            }
        }
    }

    @Unique
    static final class FabroliaFlatTask implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;
        private final FabroliaFlatFanout owner;
        private final FabroliaFlatFailureGate gate;
        private final FabroliaFlatBatch body;
        private final int batchIndex;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private FabroliaFlatTask(FabroliaFlatFanout owner, FabroliaFlatFailureGate gate,
                                 FabroliaFlatBatch body, int batchIndex) {
            this.owner = owner;
            this.gate = gate;
            this.body = body;
            this.batchIndex = batchIndex;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                if (state.get() != CANCELLED) {
                    Throwable duplicate = gate.publish(
                            new IllegalStateException("flat entity fan-out task executed more than once"));
                    ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(duplicate);
                }
                return;
            }
            Throwable failure = null;
            try {
                gate.runNext(() -> body.run(gate, batchIndex));
            } catch (Throwable thrown) {
                failure = gate.publish(thrown);
            } finally {
                state.set(TERMINAL);
                owner.finishTask();
            }
            if (failure != null) {
                ServerLevelMixin.<RuntimeException>fabrolia$throwPlayerTickFailure(failure);
            }
        }

        private void cancelQueuedBeforeStart() {
            if (state.compareAndSet(QUEUED, CANCELLED)) {
                owner.finishTask();
            }
        }
    }
}
