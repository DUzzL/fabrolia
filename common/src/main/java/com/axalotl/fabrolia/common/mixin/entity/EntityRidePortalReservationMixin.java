package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Both ends must be mutable before vanilla changes either side of a ride edge. */
@Mixin(Entity.class)
public abstract class EntityRidePortalReservationMixin {
    @Shadow public abstract Entity getVehicle();
    @Shadow public abstract List<Entity> getPassengers();

    @WrapMethod(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z")
    private boolean fabrolia$guardReservedMount(Entity vehicle, boolean force,
                                               boolean sendEventAndTriggers, Operation<Boolean> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)
                || NetherPortalTransfers.isSourceMutationBlocked(vehicle)
                || NetherPortalTransfers.isSourceMutationBlocked(this.getVehicle())) return false;
        return original.call(vehicle, force, sendEventAndTriggers);
    }

    @WrapMethod(method = "removeVehicle")
    private void fabrolia$guardReservedDismount(Operation<Void> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)
                || NetherPortalTransfers.isSourceMutationBlocked(this.getVehicle())) return;
        original.call();
    }

    @WrapMethod(method = "ejectPassengers")
    private void fabrolia$guardReservedEjection(Operation<Void> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return;
        for (Entity passenger : this.getPassengers()) {
            if (NetherPortalTransfers.isSourceMutationBlocked(passenger)) return;
        }
        original.call();
    }

    @WrapMethod(method = {"addPassenger", "removePassenger"})
    private void fabrolia$guardReservedPassenger(Entity passenger, Operation<Void> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)
                || NetherPortalTransfers.isSourceMutationBlocked(passenger)) return;
        original.call(passenger);
    }
}
