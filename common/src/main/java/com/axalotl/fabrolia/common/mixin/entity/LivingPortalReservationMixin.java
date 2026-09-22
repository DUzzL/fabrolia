package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Reject damage before health, equipment, shoulder entities or death loot change. */
@Mixin({LivingEntity.class, Player.class})
public abstract class LivingPortalReservationMixin {
    @WrapMethod(method = "hurtServer")
    private boolean fabrolia$guardReservedDamage(ServerLevel level, DamageSource source,
                                                float damage, Operation<Boolean> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return false;
        return original.call(level, source, damage);
    }

    @WrapMethod(method = "die")
    private void fabrolia$guardReservedDeath(DamageSource source, Operation<Void> original) {
        // ServerPlayer death is a lifecycle cancellation ingress owned by the player adapter.
        if (!((Object) this instanceof ServerPlayer)
                && NetherPortalTransfers.isSourceMutationBlocked(this)) return;
        original.call(source);
    }
}
