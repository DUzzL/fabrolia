package com.axalotl.fabrolia.common.mixin.fsit;

import com.axalotl.fabrolia.common.regions.NativePlayerObservations;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FSit bypasses Entity.startRiding through its generated vehicle accessor. */
@Pseudo
@Mixin(targets = "dev.rvbsm.fsit.world.entity.decoration.Ride", remap = false)
public abstract class FsitRideMixin {
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"))
    private void fabrolia$guardRideCallbackClosure(Entity vehicle, boolean force,
            boolean sendEventAndTriggers, CallbackInfoReturnable<Boolean> cir) {
        NativePlayerObservations.requireSupportedRideTransition(vehicle);
    }
}
