package com.axalotl.fabrolia.common.mixin.entity.sensor;

import com.axalotl.fabrolia.common.regions.NativeTemptingSensorCalls;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.sensing.TemptingSensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = TemptingSensor.class, priority = 1500)
public abstract class TemptingSensorMixin {
    @WrapMethod(
            method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PathfinderMob;)V",
            require = 1, allow = 1)
    private void fabrolia$temptingInvocation(ServerLevel level, PathfinderMob source,
                                            Operation<Void> original) {
        NativeTemptingSensorCalls.tick((TemptingSensor) (Object) this, level, source, original);
    }

    @ModifyExpressionValue(
            method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PathfinderMob;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;range(D)Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;",
                    ordinal = 0),
            require = 1, allow = 1)
    private TargetingConditions fabrolia$bindNativeCopy(TargetingConditions actualNativeCopy) {
        return NativeTemptingSensorCalls.bind(actualNativeCopy);
    }

    @ModifyArg(
            method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PathfinderMob;)V",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;",
                    ordinal = 0),
            index = 0, require = 1, allow = 1)
    private Predicate<? super ServerPlayer> fabrolia$ownedFirstPredicate(
            Predicate<? super ServerPlayer> original) {
        return NativeTemptingSensorCalls.firstPredicate(original);
    }
}
