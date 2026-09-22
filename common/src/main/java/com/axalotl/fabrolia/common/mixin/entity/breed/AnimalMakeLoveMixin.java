package com.axalotl.fabrolia.common.mixin.entity.breed;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AnimalMakeLove.class)
public abstract class AnimalMakeLoveMixin {
    @Shadow @Final private EntityType<? extends Animal> partnerType;

    @WrapOperation(method = "canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;J)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/AnimalMakeLove;hasBreedTargetOfRightType(Lnet/minecraft/world/entity/animal/Animal;)Z"))
    private boolean fabrolia$captureBreedTarget(AnimalMakeLove behavior, Animal body, Operation<Boolean> original,
            @Share("fabrolia$breedTarget") LocalRef<Animal> captured) {
        Animal target = body.getBrain().getMemory(MemoryModuleType.BREED_TARGET).orElse(null)
                instanceof Animal animal ? animal : null;
        captured.set(target);
        return target != null && target.is(partnerType);
    }

    @WrapOperation(method = "canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;J)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/AnimalMakeLove;getBreedTarget(Lnet/minecraft/world/entity/animal/Animal;)Lnet/minecraft/world/entity/animal/Animal;"))
    private Animal fabrolia$useCapturedBreedTarget(AnimalMakeLove behavior, Animal body, Operation<Animal> original,
            @Share("fabrolia$breedTarget") LocalRef<Animal> captured) {
        return captured.get();
    }

    @Inject(method = "getBreedTarget", at = @At("HEAD"), cancellable = true)
    private void fabrolia$readBreedTarget(Animal body, CallbackInfoReturnable<Animal> cir) {
        cir.setReturnValue(body.getBrain().getMemory(MemoryModuleType.BREED_TARGET).orElse(null)
                instanceof Animal animal ? animal : null);
    }

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;J)V",
            at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/world/entity/ai/behavior/AnimalMakeLove;getBreedTarget(Lnet/minecraft/world/entity/animal/Animal;)Lnet/minecraft/world/entity/animal/Animal;"),
            cancellable = true)
    private void fabrolia$guardCapturedTickTarget(ServerLevel level, Animal body, long timestamp,
            CallbackInfo ci, @Local(ordinal = 1) Animal target) {
        if (target == null) ci.cancel();
    }
}
