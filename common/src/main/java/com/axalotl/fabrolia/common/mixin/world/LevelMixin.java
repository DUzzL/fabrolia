package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.FabroliaLocks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Level.class, priority = 1500)
public abstract class LevelMixin implements LevelAccessor, AutoCloseable {

    @Unique
    private static final Object fabrolia$lock = new Object();

    @Shadow
    @Final
    private Thread thread;

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void fabrolia$regionAccessProbe(net.minecraft.core.BlockPos pos,
                                         net.minecraft.world.level.block.state.BlockState state,
                                         int flags, int recursionLeft,
                                         org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        com.axalotl.fabrolia.common.regions.RegionAccessDetector.onBlockWrite(pos);
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
     * <p>{@code pendingBlockEntityTickers} doesn't need any protection — it
     * receives writes only during {@code tickBlockEntities} (when
     * {@code tickingBlockEntities=true}), which runs only on the server
     * thread after the scheduled-tick barrier.
     */
    @WrapMethod(method = "addBlockEntityTicker")
    private void fabrolia$syncAddBlockEntityTicker(
            net.minecraft.world.level.block.entity.TickingBlockEntity ticker,
            Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(ticker);
            // F2b: registration-time cell stamp for the persistent BE index —
            // inside the same lock so the stash order matches the list order
            // (per-cell relative order must equal vanilla's).
            com.axalotl.fabrolia.common.regions.RegionScheduler
                    .onBlockEntityTickerAdded((Level) (Object) this, ticker);
        }
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
            // Keep the persistent cell index fresh even while the vanilla body runs
            // (islands<2, paused tick rate, feature off): registrations were stamped
            // with their cell at add time, so draining here is pure bookkeeping. This
            // keeps the stash bounded and means the bulk rebuild only ever runs on a
            // true cold start — when no ticker can be Lithium-sleeping yet.
            com.axalotl.fabrolia.common.regions.IslandBlockEntityTicks
                    .maintainIndexOnly((Level) (Object) this);
            original.call();
        }
    }
}
