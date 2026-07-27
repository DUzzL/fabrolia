package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;

@Mixin(MinecartHopper.class)
public class MinecartHopperMixin {
    @Unique
    private static final Object lock = new Object();

    @WrapMethod(method = "suckInItems")
    private boolean suckInItems(Operation<Boolean> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            return original.call();
        }
    }
}