package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.TimerClocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Timer clock fix (2026-11-01, {@link TimerClocks}): {@code lastRingTimestamp}
 * (not saved) follows the clock of {@code updateEntities}, its only reader and
 * writer, so the 60-tick nearby-entity refresh is measured in one clock.
 */
@Mixin(BellBlockEntity.class)
public abstract class BellBlockEntityTimerClockMixin implements TimerClocks.Stamped {

    @Shadow private long lastRingTimestamp;

    @Unique
    private IslandRegistry.PIsland fabrolia$timerBasis = TimerClocks.currentBasis();

    @Override
    public IslandRegistry.PIsland fabrolia$timerBasis() {
        return this.fabrolia$timerBasis;
    }

    @Override
    public void fabrolia$setTimerBasis(IslandRegistry.PIsland basis) {
        this.fabrolia$timerBasis = basis;
    }

    @Override
    public void fabrolia$shiftTimers(long delta, boolean clamp) {
        this.lastRingTimestamp = TimerClocks.shift(this.lastRingTimestamp, delta, 0L, clamp);
    }

    @Inject(method = "updateEntities", at = @At("HEAD"))
    private void fabrolia$timersOnRingClock(CallbackInfo ci) {
        if (((BellBlockEntity) (Object) this).getLevel() instanceof ServerLevel level) {
            TimerClocks.ensureCurrent(this, level);
        }
    }
}
