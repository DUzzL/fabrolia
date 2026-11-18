package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.TimerClocks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.ai.behavior.StrollAroundPoi;
import org.apache.commons.lang3.mutable.MutableLong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Timer clock fix ({@link TimerClocks}): the throttle of a villager's stroll
 * around its POI lives in the behaviour lambda. A stamp further ahead than any
 * legal one ({@link TimerClocks#STROLL_AROUND_POI_MAX_LEAD}) is residue of a
 * clock that was ahead: the stroll is due now.
 */
@Mixin(StrollAroundPoi.class)
public abstract class StrollAroundPoiTimerClockMixin {

    @WrapOperation(method = "lambda$create$2",
            at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/mutable/MutableLong;longValue()J",
                    remap = false),
            require = 1, allow = 1)
    private static long fabrolia$healFutureStroll(MutableLong nextOkStartTime, Operation<Long> original,
                                                  @Local(argsOnly = true) long timestamp) {
        return TimerClocks.healFutureStart(nextOkStartTime, original.call(nextOkStartTime), timestamp,
                TimerClocks.STROLL_AROUND_POI_MAX_LEAD);
    }
}
