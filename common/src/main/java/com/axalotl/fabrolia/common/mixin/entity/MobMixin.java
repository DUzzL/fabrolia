package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;
import com.axalotl.fabrolia.common.regions.NativePlayerObservations;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Mob.class)
public class MobMixin {

    @Unique
    private static final Object lock = new Object();

    /** The nearest-player result is an observer reference, not gameplay ownership. */
    @WrapOperation(
            method = "checkDespawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;distanceToSqr(Lnet/minecraft/world/entity/Entity;)D"),
            require = 1,
            allow = 1)
    private double fabrolia$observeDespawnDistance(Entity nearest, Entity source, Operation<Double> original) {
        if (nearest instanceof ServerPlayer player) {
            if (RegionScheduler.workerCount() != 0 && NativePlayerObservations.isSupportedPlayer(player)) {
                // Match the native Entity overload: source.position() precedes
                // the receiver's distance calculation. Read each immutable Vec3 once.
                Vec3 sourcePosition = source.position();
                return NativePlayerObservations.distanceToSqr(
                        player, sourcePosition.x, sourcePosition.y, sourcePosition.z);
            }
            // Unknown player implementations retain their actual Entity-overload
            // dispatch, but only a real gameplay owner may enter that callback.
            NativePlayerObservations.requireGameplayObservation(player);
        }
        return original.call(nearest, source);
    }

    @WrapMethod(method = "equipItemIfPossible")
    private ItemStack tryEquip(ServerLevel level, ItemStack itemStack, Operation<ItemStack> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            return original.call(level, itemStack);
        }
    }

    @WrapMethod(method = "pickUpItem")
    private void pickUpItem(ServerLevel level, ItemEntity entity, Operation<Void> original) {
        SyncItemPickup.wrap(level, entity, () -> original.call(level, entity));
    }

    @WrapMethod(method = "setItemSlotAndDropWhenKilled")
    private void equipLootStack(EquipmentSlot slot, ItemStack itemStack, Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            original.call(slot, itemStack);
        }
    }
    // setBodyArmorItem removed in MC 26.2 — equipment writes now flow through
    // equipItemIfPossible/setItemSlotAndDropWhenKilled (both wrapped above). No
    // standalone body-armor setter to wrap.
}