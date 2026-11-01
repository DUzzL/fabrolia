package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.TimerClocks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.SetClosestHomeAsWalkTarget;
import org.apache.commons.lang3.mutable.MutableLong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Timer clock fix (2026-11-01, {@link TimerClocks}): the home-search throttle
 * and its bed cache live in the behaviour lambda, out of reach of a clock move.
 * A throttle stamp further ahead than any legal one
 * ({@link TimerClocks#HOME_SEARCH_MAX_LEAD}) is residue of a clock that was
 * ahead: the search is due now and cache entries beyond their legal lead are
 * dropped, so villagers still walk home at night.
 */
@Mixin(SetClosestHomeAsWalkTarget.class)
public abstract class SetClosestHomeAsWalkTargetTimerClockMixin {

    @WrapOperation(method = "lambda$create$2",
            at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/mutable/MutableLong;longValue()J",
                    remap = false),
            require = 1, allow = 1)
    private static long fabrolia$healFutureSearch(MutableLong lastUpdate, Operation<Long> original,
                                                  @Local(argsOnly = true) Long2LongMap batchCache,
                                                  @Local(argsOnly = true) ServerLevel level) {
        long stamp = original.call(lastUpdate);
        long now = level.getGameTime();
        if (TimerClocks.isFutureResidue(stamp, now, TimerClocks.HOME_SEARCH_MAX_LEAD)) {
            batchCache.long2LongEntrySet().removeIf(e ->
                    TimerClocks.isFutureResidue(e.getLongValue(), now, TimerClocks.HOME_SEARCH_CACHE_MAX_LEAD));
            long due = now - TimerClocks.HOME_SEARCH_MAX_LEAD;
            lastUpdate.setValue(due);
            return due;
        }
        return stamp;
    }
}
