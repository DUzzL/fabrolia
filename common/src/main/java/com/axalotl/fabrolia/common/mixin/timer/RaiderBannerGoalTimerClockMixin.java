package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.TimerClocks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Timer clock fix (2026-11-01, {@link TimerClocks}): a raider remembers banners
 * it could not reach until "now + 600" in a goal field. An entry further ahead
 * than that is residue of a clock that was ahead and no longer blocks the
 * banner, so raid captains are still replaced.
 */
@Mixin(targets = "net.minecraft.world.entity.raid.Raider$ObtainRaidLeaderBannerGoal")
public abstract class RaiderBannerGoalTimerClockMixin {

    @Shadow @Final private Raider mob;

    @WrapOperation(method = "canUse",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2LongOpenHashMap;getOrDefault(IJ)J",
                    remap = false),
            require = 1, allow = 1)
    private long fabrolia$healFutureUnreachable(Int2LongOpenHashMap cache, int id, long fallback,
                                                Operation<Long> original) {
        long until = original.call(cache, id, fallback);
        if (until != Long.MIN_VALUE && TimerClocks.isFutureResidue(until, this.mob.level().getGameTime(),
                TimerClocks.BANNER_RETRY_MAX_LEAD)) {
            return fallback;
        }
        return until;
    }
}
