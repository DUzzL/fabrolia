package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.FabroliaLocks;
import com.axalotl.fabrolia.common.utils.ContainerQueryOwners;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(TransportItemsBetweenContainers.class)
public class TransportItemsBetweenContainersMixin {

    @Unique
    private static final Object lock = new Object();

    @WrapMethod(method = "pickUpItems")
    private void pickUpItems(PathfinderMob body, Container container, Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            original.call(body, container);
        }
    }

    @WrapMethod(method = "putDownItem")
    private void putDownItem(PathfinderMob body, Container container, Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            original.call(body, container);
        }
    }

    @WrapMethod(method = "isAnotherMobInteractingWithTarget")
    private boolean isAnotherMobInteractingWithTarget(
            TransportItemsBetweenContainers.TransportItemTarget target,
            Level level,
            Operation<Boolean> original
    ) {
        return ((ContainerQueryOwners.Access) level).fabrolia$containerQueryOwners().read(target, level, original);
    }
}