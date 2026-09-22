package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaRandomStriping;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Level.class, priority = 1500)
public abstract class LevelMixin implements LevelAccessor, AutoCloseable,
        com.axalotl.fabrolia.common.regions.BlockEntityTickerAccess {

    @Unique
    private static final Object fabrolia$lock = new Object();

    @Unique
    private final Object fabrolia$blockEntityTickerLock = new Object();

    @Override
    public Object fabrolia$blockEntityTickerLock() {
        return fabrolia$blockEntityTickerLock;
    }

    @Shadow
    @Final
    private Thread thread;

    @Shadow @Final protected RandomSource random;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void fabrolia$initializeSharedWorldRandom(CallbackInfo ci) {
        // ServerLevel fields are not initialized yet: inspect only its JVM type
        // and the already-constructed Level.random, never an overridable getter.
        if ((Object) this instanceof ServerLevel
                && random instanceof FabroliaRandomStriping.SharedWorldSource shared) {
            shared.fabrolia$markSharedWorldRandom();
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void fabrolia$regionAccessProbe(net.minecraft.core.BlockPos pos,
                                         net.minecraft.world.level.block.state.BlockState state,
                                         int flags, int recursionLeft,
                                         org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        com.axalotl.fabrolia.common.regions.RegionAccessDetector
                .onBlockWrite((Level) (Object) this, pos);
        // Cross-region containment: a worker writing a block state OUTSIDE its claimed
        // region (fire spread, plant growth, a contraption straddling the boundary) would
        // race a concurrent same-colour region two cells away — and even a distance-1
        // neighbour write races OTHER workers reading that section. Defer to main, replayed
        // after the barrier when every region is quiescent. In-region writes (the common
        // path) and the main thread itself return null here and run inline.
        com.axalotl.fabrolia.common.regions.RegionizedServer rs =
                com.axalotl.fabrolia.common.regions.CrossRegionGuard.crossRegionTargetOrNull(
                        (Level) (Object) this, pos);
        com.axalotl.fabrolia.common.regions.CrossRegionGuard.LAST_SETBLOCK_DEFERRED.set(rs != null);
        if (rs != null) {
            net.minecraft.core.BlockPos target = pos.immutable();
            rs.deferCrossRegion(() -> ((Level) (Object) this).setBlock(target, state, flags, recursionLeft), target);
            cir.setReturnValue(true);   // optimistic: the write applies on the owner-timeline drain
        }
    }

    @Redirect(method = "getBlockEntity", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
    private Thread overwriteCurrentThread() {
        return this.thread;
    }

    @Unique
    private final Object fabrolia$subTickLock = new Object();

    /**
     * subTickCount++ is racy once handlers schedule from parallel contexts
     * (worker random ticks today, island scheduled-tick slices now). A torn
     * increment hands two same-chunk ticks the same subTickOrder — comparator
     * tie, heap-order flip. Uncontended monitor ≈ 20ns per schedule.
     */
    @WrapMethod(method = "nextSubTickCount")
    private long fabrolia$atomicSubTick(Operation<Long> original) {
        synchronized (fabrolia$subTickLock) {
            return original.call();
        }
    }

    /**
     * Comparator-output notifications are loaded-only work on a region owner.
     * Vanilla guards the first horizontal chunk, but its conductor branch then
     * performs a second unconditional {@code getBlockState}; at a chunk edge
     * that becomes {@code getChunk(FULL, true)} and parks the worker on Main
     * while it retains the island claim. The first guard also has an unload
     * TOCTOU once chunk holders are concurrent.
     *
     * <p>Resolve both probes through the worker-safe ready-holder path. A
     * captured {@link LevelChunk} closes the unload race; AIR is only a
     * no-recipient sentinel for this method's comparator/conductor predicates.
     * Main and every non-region caller retain the composed original operation.</p>
     */
    @WrapOperation(
            method = "updateNeighbourForOutputSignal",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;"
                            + "getBlockState(Lnet/minecraft/core/BlockPos;)"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 2,
            allow = 2)
    private BlockState fabrolia$readLoadedComparatorNeighbor(
            Level instance, BlockPos pos, Operation<BlockState> original) {
        if (Thread.currentThread() instanceof RegionWorkerThread
                && instance instanceof ServerLevel serverLevel) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(
                    SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getZ()));
            return chunk == null
                    ? Blocks.AIR.defaultBlockState()
                    : chunk.getBlockState(pos);
        }
        return original.call(instance, pos);
    }

    @WrapMethod(method = "explode(Lnet/minecraft/world/entity/Entity;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)V")
    private void explode(Entity source, double x, double y, double z, float r, boolean fire, Level.ExplosionInteraction blockInteraction, Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(fabrolia$lock)) {
            original.call(source, x, y, z, r, fire, blockInteraction);
        }
    }

    @WrapMethod(method = "explode(Lnet/minecraft/world/entity/Entity;DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)V")
    private void explode(Entity source, double x, double y, double z, float r, Level.ExplosionInteraction blockInteraction, Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(fabrolia$lock)) {
            original.call(source, x, y, z, r, blockInteraction);
        }
    }

    @WrapMethod(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)V")
    private void explode(Entity source, DamageSource damageSource, ExplosionDamageCalculator damageCalculator, double x, double y, double z, float r, boolean fire, Level.ExplosionInteraction interactionType, Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(fabrolia$lock)) {
            original.call(source, damageSource, damageCalculator, x, y, z, r, fire, interactionType);
        }
    }

    /**
     * Targeted thread-safety on a single hot field. Vanilla
     * {@code Level.blockEntityTickers} is an {@code ArrayList} — non-thread-safe.
     * Worker scheduled-tick slices can call {@code setBlock} → {@code addBlockEntityTicker}
     * concurrently; without the gate two workers race {@code ArrayList.add}
     * on the same {@code elementData[size]} slot and one ticker silently
     * overwrites the other.
     *
     * <p>Targeted sync (not collection swap) is correct here because the
     * workload is "rare add, frequent iterate": ~30 adds/tick × ~30ns gate
     * vs ~1000s of iter steps/tick — a snapshot/lock-free collection would
     * either allocate per-iter (ConcurrentList, ~8KB/tick) or pay cache
     * misses (ConcurrentLinkedQueue, ~10µs/tick at N=2000). Vanilla ArrayList
     * iteration stays cache-friendly; the gate only fires on the rare path.
     *
     * <p>The same level-local gate covers the pending-list flush and derived
     * index publication. Autonomous units can register during Main maintenance.
     */
    @WrapMethod(method = "addBlockEntityTicker")
    private void fabrolia$syncAddBlockEntityTicker(
            net.minecraft.world.level.block.entity.TickingBlockEntity ticker,
            Operation<Void> original) {
        com.axalotl.fabrolia.common.regions.IslandBlockEntityTicks.registerTicker(
                (Level) (Object) this, ticker, () -> original.call(ticker));
    }

    /** Move the pending clear into the addAll transaction. Vanilla's later
     * clear must not erase a registration admitted after that transaction. */
    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE",
            target = "Ljava/util/List;addAll(Ljava/util/Collection;)Z"), require = 1, allow = 1)
    private boolean fabrolia$flushPendingTickers(java.util.List<?> tickers,
            java.util.Collection<?> pending, Operation<Boolean> original) {
        return com.axalotl.fabrolia.common.regions.IslandBlockEntityTicks
                .flushPendingTickers((Level) (Object) this);
    }

    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE",
            target = "Ljava/util/List;clear()V"), require = 1, allow = 1)
    private void fabrolia$pendingTickersAlreadyCleared(java.util.List<?> pending,
            Operation<Void> original) {
        // Cleared atomically with addAll above, not again after releasing the gate.
    }

    /**
     * Stage C — island-partitioned BE ticks. Hands off to
     * {@link com.axalotl.fabrolia.common.regions.IslandBlockEntityTicks} when
     * islands are present; if that returns false (≥2 islands not formed,
     * tickRateManager paused, regions off) the vanilla flat-list path
     * still runs.
     */
    @WrapMethod(method = "tickBlockEntities")
    private void fabrolia$regionizedTickBlockEntities(Operation<Void> original) {
        if (!com.axalotl.fabrolia.common.regions.IslandBlockEntityTicks
                .tickPartitioned((Level) (Object) this)) {
            Level level = (Level) (Object) this;
            Runnable vanilla = () -> {
                // Vanilla owns the authoritative list; retire its derived index
                // only after native inline removals complete.
                com.axalotl.fabrolia.common.regions.IslandBlockEntityTicks
                        .setTickingBlockEntities(level, true);
                try {
                    original.call();
                } finally {
                    com.axalotl.fabrolia.common.regions.IslandBlockEntityTicks
                            .setTickingBlockEntities(level, false);
                }
                com.axalotl.fabrolia.common.regions.IslandBlockEntityTicks
                        .maintainIndexOnly(level);
            };
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.axalotl.fabrolia.common.regions.RegionScheduler
                        .runGlobalMainBody(serverLevel.getServer(), vanilla);
            } else {
                vanilla.run();
            }
        }
    }
}
