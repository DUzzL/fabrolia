package com.axalotl.fabrolia.fabric.mixin.server;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ServerClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Admits ServerLevel.tick's sleep clock advance like the other tick stages
 *  (common ServerLevelMixin). NeoForge replaces this call; see its counterpart. */
@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class ServerLevelSleepClockMixin {

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/clock/ServerClockManager;"
                            + "moveToTimeMarker(Lnet/minecraft/core/Holder;"
                            + "Lnet/minecraft/resources/ResourceKey;)"
                            + "Lnet/minecraft/world/clock/ServerClockManager$MoveResult;"),
            require = 1,
            allow = 1)
    private ServerClockManager.MoveResult fabrolia$admitSleepTimeAdvance(
            ServerClockManager instance, Holder<?> clock, ResourceKey<?> marker,
            Operation<ServerClockManager.MoveResult> original) {
        ServerClockManager.MoveResult[] result = new ServerClockManager.MoveResult[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> result[0] = original.call(instance, clock, marker));
        return result[0];
    }
}
