package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.regions.BlockEntitySleepDomains;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.lithium.common.block.entity.SleepUntilTimeBlockEntityTickInvoker;
import net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Cross-clock residue heal on the WAKE side of Lithium's one-tick sleep
 * (never-waking-hopper fix, 2026-08-04 — see
 * {@link BlockEntitySleepDomains} for the full defect writeup).
 *
 * <p>Lithium's {@code tick()} wakes on {@code getGameTime() >= deadline}
 * with both sides assumed to share one clock. Under async islands the
 * deadline can be left strictly ahead of the evaluating timeline by more
 * than any legal stamp (foreign-thread stamp, island merge/rebuild/toggle
 * between stamp and evaluation — the deadline lives in an immutable Lithium
 * record the clock-rebase machinery cannot translate). Such residue is
 * unreachable — the hopper's wrapper keeps ticking but the delegate never
 * runs. Wake it immediately: rebind the delegate and tick it, exactly
 * Lithium's own wake body. Waking early degrades to a vanilla tick;
 * sleeping late is the production bug.
 *
 * <p>The legal case ({@code now < deadline <= now + MAX_RELATIVE_SLEEP})
 * falls through to Lithium's original comparison untouched — same-domain
 * behaviour is bit-for-bit Lithium's.
 *
 * <p>Gated in MixinCanceller ({@code !LITHIUM}): targets a Lithium class
 * and casts to Lithium types.
 */
@Mixin(value = SleepUntilTimeBlockEntityTickInvoker.class, remap = false)
public abstract class FabroliaLithiumSleepInvokerMixin {

    @Shadow
    @Final
    private BlockEntity sleepingBlockEntity;

    @Shadow
    @Final
    private long sleepUntilTickExclusive;

    @Shadow
    @Final
    private TickingBlockEntity delegate;

    @WrapMethod(method = "tick")
    private void fabrolia$wakeOnCrossClockResidue(Operation<Void> original) {
        long now = this.sleepingBlockEntity.getLevel().getGameTime();
        if (BlockEntitySleepDomains.deadlineUnreachable(now, this.sleepUntilTickExclusive)) {
            // Lithium's wake body, verbatim: rebind the delegate, tick it.
            ((SleepingBlockEntity) this.sleepingBlockEntity).setTicker(this.delegate);
            this.delegate.tick();
            return;
        }
        original.call();
    }
}
