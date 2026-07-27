package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;

@Mixin(CopperGolem.class)
public class CopperGolemMixin {
    @Unique
    private static final Object lock = new Object();

    @WrapMethod(method = "hasContainerOpen")
    private boolean hasContainerOpen(ContainerOpenersCounter container, BlockPos blockPos, Operation<Boolean> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            return original.call(container, blockPos);
        }
    }

    @WrapMethod(method = "setOpenedChestPos")
    private void setOpenedChestPos(BlockPos openedChestPos, Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            original.call(openedChestPos);
        }
    }

    @WrapMethod(method = "clearOpenedChestPos")
    private void clearOpenedChestPos(Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            original.call();
        }
    }
}