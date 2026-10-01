package com.axalotl.fabrolia.neoforge.mixin.server;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.neoforged.neoforge.common.util.ClockAdjustment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Admits ServerLevel.tick's sleep clock advance like the other tick stages
 *  (common ServerLevelMixin). NeoForge replaces vanilla's moveToTimeMarker
 *  with the SleepFinished event's ClockAdjustment, applied here. */
@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class ServerLevelSleepClockMixin {

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/common/util/ClockAdjustment;"
                            + "apply(Lnet/minecraft/world/clock/ServerClockManager;"
                            + "Lnet/minecraft/core/Holder;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitSleepTimeAdvance(
            ClockAdjustment adjustment, ServerClockManager clocks, Holder<WorldClock> clock,
            Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(adjustment, clocks, clock));
    }
}
