package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.TimerClocks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.ai.behavior.StrollToPoi;
import org.apache.commons.lang3.mutable.MutableLong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Timer clock fix ({@link TimerClocks}): the stroll-to-POI throttle lives in the
 * behaviour lambda, out of reach of a clock move. A stamp further ahead than
 * any legal one ({@link TimerClocks#STROLL_TO_POI_MAX_LEAD}) is residue of a
 * clock that was ahead: the stroll is due now, so a villager that entered an
 * island behind still walks to its meeting point and job site.
 */
@Mixin(StrollToPoi.class)
public abstract class StrollToPoiTimerClockMixin {

    @WrapOperation(method = "lambda$create$2",
            at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/mutable/MutableLong;longValue()J",
                    remap = false),
            require = 1, allow = 1)
    private static long fabrolia$healFutureStroll(MutableLong nextOkStartTime, Operation<Long> original,
                                                  @Local(argsOnly = true) long timestamp) {
        return TimerClocks.healFutureStart(nextOkStartTime, original.call(nextOkStartTime), timestamp,
                TimerClocks.STROLL_TO_POI_MAX_LEAD);
    }
}
