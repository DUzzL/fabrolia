package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import org.spongepowered.asm.mixin.Mixin;

/** TNT effects can precede super.hurtServer and need their own entry gates. */
@Mixin(MinecartTNT.class)
public abstract class TntVehiclePortalReservationMixin {
    @WrapMethod(method = "hurtServer")
    private boolean fabrolia$guardPortalDamage(ServerLevel level, DamageSource source,
                                               float damage, Operation<Boolean> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return true;
        return original.call(level, source, damage);
    }

    @WrapMethod(method = "destroy(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V")
    private void fabrolia$guardPortalDestroy(ServerLevel level, DamageSource source,
                                            Operation<Void> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return;
        original.call(level, source);
    }

    @WrapMethod(method = "explode")
    private void fabrolia$guardPortalExplosion(DamageSource source, double speedSquared,
                                              Operation<Void> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return;
        original.call(source, speedSquared);
    }
}
