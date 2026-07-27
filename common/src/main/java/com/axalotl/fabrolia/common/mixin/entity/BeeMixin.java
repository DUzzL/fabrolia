package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;

@Mixin(Bee.class)
public class BeeMixin {
    @Unique
    private static final Object lock = new Object();

    @WrapMethod(method = "wantsToEnterHive")
    private boolean wantsToEnterHive(Operation<Boolean> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            return original.call();
        }
    }
}