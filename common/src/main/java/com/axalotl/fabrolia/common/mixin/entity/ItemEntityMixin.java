package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;
import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin implements SyncItemPickup.Guarded {

    @Unique
    private final SyncItemPickup.Guard fabrolia$guard = new SyncItemPickup.Guard();

    @Override
    public SyncItemPickup.Guard fabrolia$consumptionGuard() {
        return fabrolia$guard;
    }

    /** Freeze the entire damage transaction, including container onDestroyed callbacks. */
    @WrapMethod(method = "hurtServer")
    private boolean fabrolia$guardReservedDamage(ServerLevel level, DamageSource source,
                                                float damage, Operation<Boolean> original) {
        fabrolia$guard.enterConsumption();
        try {
            if (NetherPortalTransfers.isSourceMutationBlocked(this)) return false;
            return original.call(level, source, damage);
        } finally {
            fabrolia$guard.exitConsumption();
        }
    }


    @WrapOperation(method = "mergeWithNeighbours", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<ItemEntity> fabrolia$classFilteredMergeLookup(Level level, Class<ItemEntity> cls, AABB box, Predicate<? super ItemEntity> pred, Operation<List<ItemEntity>> original) {
        return level.getEntities(EntityTypeTest.forClass(cls), box, pred);
    }

    /**
     * The outer merge wrapper owns the lane and self's consumption scope.
     * Guard the candidate as well: its stack and native discard are one
     * synchronous transaction, never a portal source halfway through a merge.
     */
    @WrapOperation(
            method = "mergeWithNeighbours",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/item/ItemEntity;"
                            + "tryToMerge(Lnet/minecraft/world/entity/item/ItemEntity;)V"))
    private void fabrolia$syncMergeCandidate(ItemEntity self, ItemEntity candidate,
                                              Operation<Void> original) {
        SyncItemPickup.Guard guard = SyncItemPickup.guard(candidate);
        guard.enterConsumption();
        try {
            if (SyncItemPickup.isAvailable(candidate)) {
                original.call(self, candidate);
            }
        } finally {
            guard.exitConsumption();
        }
    }

    /**
     * Player pickup shares the exact identity guard used by mob/hopper intake
     * and the item's tick, including a liveness recheck after waiting.
     */
    @WrapMethod(method = "playerTouch")
    private void fabrolia$syncPlayerPickup(Player player, Operation<Void> original) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel) {
            SyncItemPickup.wrap(serverLevel, self, () -> {
                if (self.level() != player.level()) return;
                Entity vehicle = player.getVehicle();
                boolean mounted = player.isPassenger()
                        && vehicle != null && !vehicle.isRemoved();
                AABB pickupBox = mounted
                        ? player.getBoundingBox().minmax(vehicle.getBoundingBox())
                                .inflate(1.0D, 0.0D, 1.0D)
                        : player.getBoundingBox().inflate(1.0D, 0.5D, 1.0D);
                if (self.getBoundingBox().intersects(pickupBox)) {
                    original.call(player);
                }
            });
        } else {
            original.call(player);
        }
    }

    /**
     * The item simulation itself shares the pickup guard. Locking only
     * playerTouch/merge is insufficient when a Main-fallback player executes
     * its pickup scan while the autonomous owner is moving, expiring, or
     * discarding this item. In particular, Inventory.add can empty the stack
     * while ItemEntity.tick concurrently observes that empty stack and removes
     * the entity before the collect packet is emitted.
     */
    @WrapMethod(method = "tick")
    private void fabrolia$syncWholeItemTick(Operation<Void> original) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel) {
            Object lane = FabroliaLocks.itemTickLaneOr(
                    serverLevel, self, SyncItemPickup.fallback());
            synchronized (lane) {
                SyncItemPickup.wrapTick(self, original::call);
            }
        } else {
            original.call();
        }
    }

    /**
     * Every merge entry acquires the island/fallback lane before either item
     * guard. Distinct islands are separated beyond native merge reach.
     */
    @WrapMethod(method = "mergeWithNeighbours")
    private void fabrolia$syncMerge(Operation<Void> original) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel) {
            // This method also has direct Main-side callers (notably the
            // destination continuation of ItemEntity.teleport), so the lane
            // cannot live only around tick().  Every merge entry follows the
            // same lane -> self -> candidate order; tick's nested acquisition
            // is reentrant.
            Object lane = FabroliaLocks.itemTickLaneOr(
                    serverLevel, self, SyncItemPickup.fallback());
            synchronized (lane) {
                SyncItemPickup.wrap(serverLevel, self, original::call);
            }
        } else {
            original.call();
        }
    }
}
