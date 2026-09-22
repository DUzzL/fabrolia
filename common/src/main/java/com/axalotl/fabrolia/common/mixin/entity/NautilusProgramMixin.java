package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NativeNautilusProgram;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.nautilus.Nautilus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Nautilus.class)
public abstract class NautilusProgramMixin {
    @Shadow @Final private static Brain.Provider<Nautilus> BRAIN_PROVIDER;

    @WrapOperation(method = "makeBrain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/Brain$Provider;makeBrain(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/ai/Brain$Packed;)Lnet/minecraft/world/entity/ai/Brain;"))
    private Brain<Nautilus> fabrolia$witnessNativeInstallation(Brain.Provider<Nautilus> provider,
            LivingEntity body, Brain.Packed packed, Operation<Brain<Nautilus>> original) {
        if (provider != BRAIN_PROVIDER || body != (Object) this || body.getClass() != Nautilus.class) {
            return original.call(provider, body, packed);
        }
        var installation = NativeNautilusProgram.begin((Nautilus) body);
        try {
            Brain<Nautilus> brain = original.call(provider, body, packed);
            installation.bindReturnedBrain(brain);
            return brain;
        } finally {
            installation.close();
        }
    }
}
