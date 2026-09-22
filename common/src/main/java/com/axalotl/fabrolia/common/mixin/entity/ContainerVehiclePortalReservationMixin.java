package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import org.spongepowered.asm.mixin.Mixin;

/** Container overrides have inventory effects after super.destroy returns. */
@Mixin({AbstractChestBoat.class, AbstractMinecartContainer.class})
public abstract class ContainerVehiclePortalReservationMixin {
    @WrapMethod(method = "destroy(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V")
    private void fabrolia$guardPortalInventoryDrops(ServerLevel level, DamageSource source,
                                                   Operation<Void> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return;
        original.call(level, source);
    }

    @WrapMethod(method = "remove")
    private void fabrolia$guardPortalRemovalDrops(net.minecraft.world.entity.Entity.RemovalReason reason,
                                                  Operation<Void> original) {
        if (reason.shouldDestroy() && NetherPortalTransfers.isSourceMutationBlocked(this)) return;
        original.call(reason);
    }
}
