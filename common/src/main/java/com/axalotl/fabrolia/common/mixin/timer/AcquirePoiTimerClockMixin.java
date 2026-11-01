package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.TimerClocks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import org.apache.commons.lang3.mutable.MutableLong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Timer clock fix (2026-11-01, {@link TimerClocks}): AcquirePoi keeps its start
 * throttle in a {@code MutableLong} captured by the behaviour lambda, out of
 * reach of a clock move. A stamp further ahead than any legal one
 * ({@link TimerClocks#ACQUIRE_POI_MAX_LEAD}) is residue of a clock that was
 * ahead; it is due now, so villagers still claim beds and job sites.
 */
@Mixin(AcquirePoi.class)
public abstract class AcquirePoiTimerClockMixin {

    @WrapOperation(method = "lambda$create$3",
            at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/mutable/MutableLong;longValue()J",
                    remap = false),
            require = 2, allow = 2)
    private static long fabrolia$healFutureStart(MutableLong start, Operation<Long> original,
                                                 @Local(argsOnly = true) ServerLevel level) {
        long stamp = original.call(start);
        long now = level.getGameTime();
        if (stamp != 0L && TimerClocks.isFutureResidue(stamp, now, TimerClocks.ACQUIRE_POI_MAX_LEAD)) {
            start.setValue(now);
            return now;
        }
        return stamp;
    }
}
