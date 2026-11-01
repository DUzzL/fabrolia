package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.TimerClocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Timer clock fix (2026-11-01, {@link TimerClocks}): the per-POI retry cache of
 * AcquirePoi lives in a captured lambda map. An attempt stamped in the future
 * cannot come from this clock: the entry is stale (dropped) and due.
 */
@Mixin(targets = "net.minecraft.world.entity.ai.behavior.AcquirePoi$JitteredLinearRetry")
public abstract class AcquirePoiRetryTimerClockMixin {

    @Shadow private long previousAttemptTimestamp;
    @Shadow private long nextScheduledAttemptTimestamp;

    @Inject(method = "isStillValid", at = @At("HEAD"), cancellable = true)
    private void fabrolia$futureAttemptIsStale(long timestamp, CallbackInfoReturnable<Boolean> cir) {
        if (this.previousAttemptTimestamp > timestamp) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldRetry", at = @At("HEAD"), cancellable = true)
    private void fabrolia$futureRetryIsDue(long timestamp, CallbackInfoReturnable<Boolean> cir) {
        if (TimerClocks.isFutureResidue(this.nextScheduledAttemptTimestamp, timestamp,
                TimerClocks.ACQUIRE_POI_RETRY_MAX_LEAD)) {
            cir.setReturnValue(true);
        }
    }
}
