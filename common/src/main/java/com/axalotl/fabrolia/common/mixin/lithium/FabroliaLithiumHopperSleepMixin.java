package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.regions.BlockEntitySleepDomains;
import com.axalotl.fabrolia.common.regions.HopperClockHeal;
import net.caffeinemc.mods.lithium.common.block.entity.SleepUntilTimeBlockEntityTickInvoker;
import net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clock-domain coherence for Lithium's hopper sleep/search stamps — the
 * STAMP-side half of the never-waking-hopper fix (2026-08-04; the wake-side
 * residue heal is {@link FabroliaLithiumSleepInvokerMixin}, the defect
 * writeup is {@link BlockEntitySleepDomains}).
 *
 * <p>Two seams, both merged onto vanilla {@code HopperBlockEntity} at
 * priority 1500 (after Lithium's 1000):
 * <ul>
 *   <li>{@link #sleepOnlyCurrentTick()} supersedes the
 *       {@code SleepingBlockEntity} interface DEFAULT (adding the method to
 *       the class overrides it — the {@code LithiumServerLevel} pattern):
 *       identical body to Lithium's except the wake deadline is stamped from
 *       the hopper's OWNING venue clock resolved by position
 *       ({@link BlockEntitySleepDomains#ownerNow}), not from whichever
 *       thread happens to run the stamp. The only caller is Lithium's
 *       {@code setCooldown} hook ({@code wakeUpOnCooldownSet}), which can
 *       execute on a foreign venue — a main-lane insert into an
 *       island-owned sleeping hopper stamped W+1 against an island clock
 *       trailing W by an unbounded gap. Same-venue stamps produce exactly
 *       Lithium's value.</li>
 *   <li>{@link #fabrolia$onPushItemsTick}: at the head of every real hopper
 *       tick, clamp the three failed-search stamps
 *       ({@code collectItemEntityAttemptTime},
 *       {@code extract/insertInventoryEntityFailedSearchTime}) that a clock
 *       drop (island merge onto an older clock, async toggle) left in the
 *       hopper's FUTURE. A future stamp makes the shared movement tracker's
 *       "unchanged since stamp" shortcut swallow the single movement event a
 *       landing item ever emits; one tick later the refreshed stamp is past
 *       that event and the resting pile is invisible forever — the second
 *       forever-hole behind the farm report. Clamping to Lithium's own
 *       force-search sentinel costs one vanilla search, then the stamp
 *       re-anchors to the hopper's current timeline.</li>
 * </ul>
 *
 * <p>Gated in MixinCanceller ({@code !LITHIUM}): shadows Lithium-merged
 * members and references Lithium types. The {@code @Shadow}s fail LOUDLY if
 * Lithium's {@code block.hopper} / {@code block_entity_ticking.sleeping}
 * modules are absent — deliberate, per the Task-#38 gating convention.
 */
@Mixin(value = HopperBlockEntity.class, priority = 1500)
public abstract class FabroliaLithiumHopperSleepMixin extends BlockEntity implements HopperClockHeal {

    protected FabroliaLithiumHopperSleepMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // --- Lithium block_entity_ticking.sleeping members (merged onto the vanilla class)

    @Shadow(remap = false)
    public abstract WrappedBlockEntityTickInvokerAccessor lithium$getTickWrapper();

    @Shadow(remap = false)
    public abstract TickingBlockEntity lithium$getSleepingTicker();

    @Shadow(remap = false)
    public abstract void lithium$setSleepingTicker(TickingBlockEntity ticker);

    // --- Lithium block.hopper failed-search stamps (merged onto the vanilla class)

    @Shadow(remap = false)
    private long collectItemEntityAttemptTime;

    @Shadow(remap = false)
    private long extractInventoryEntityFailedSearchTime;

    @Shadow(remap = false)
    private long insertInventoryEntityFailedSearchTime;

    /**
     * Supersedes {@code SleepingBlockEntity.sleepOnlyCurrentTick()}'s default:
     * Lithium's body verbatim, with the deadline stamped from the hopper's
     * owning venue instead of the executing thread's context clock. Client
     * side (never reached in practice — hoppers have no client ticker) keeps
     * the vanilla-Lithium stamp.
     */
    public void sleepOnlyCurrentTick() {
        TickingBlockEntity sleepingTicker = this.lithium$getSleepingTicker();
        WrappedBlockEntityTickInvokerAccessor tickWrapper = this.lithium$getTickWrapper();
        if (sleepingTicker == null) {
            sleepingTicker = tickWrapper.getWrapped();
        }
        Level world = this.getLevel();
        long deadline = world instanceof ServerLevel sl
                ? BlockEntitySleepDomains.ownerNow(sl, this.getBlockPos()) + 1L
                : world.getGameTime() + 1L;
        tickWrapper.callSetWrapped(new SleepUntilTimeBlockEntityTickInvoker(
                (BlockEntity) (Object) this, deadline, sleepingTicker));
        this.lithium$setSleepingTicker(null);
    }

    @Override
    public void fabrolia$healSearchStamps(long evaluatorNow) {
        this.collectItemEntityAttemptTime =
                BlockEntitySleepDomains.healSearchStamp(this.collectItemEntityAttemptTime, evaluatorNow);
        this.extractInventoryEntityFailedSearchTime =
                BlockEntitySleepDomains.healSearchStamp(this.extractInventoryEntityFailedSearchTime, evaluatorNow);
        this.insertInventoryEntityFailedSearchTime =
                BlockEntitySleepDomains.healSearchStamp(this.insertInventoryEntityFailedSearchTime, evaluatorNow);
    }

    /**
     * Head of the real hopper tick, before vanilla stamps
     * {@code tickedGameTime} and before any Lithium tracker shortcut runs:
     * re-anchor future-residue search stamps against the same clock value
     * this tick is about to use.
     */
    @Inject(method = "pushItemsTick", at = @At("HEAD"))
    private static void fabrolia$onPushItemsTick(Level level, BlockPos pos, BlockState state,
                                                HopperBlockEntity blockEntity, CallbackInfo ci) {
        ((HopperClockHeal) blockEntity).fabrolia$healSearchStamps(level.getGameTime());
    }
}
