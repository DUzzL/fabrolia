package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    /**
     * Hopper item-entity intake under the item's stable identity monitor. Hopper BLOCKS tick in their
     * island's block-entity phase on a region worker and hopper MINECARTS suck via
     * the same static — both consume an ItemEntity that, with region-owned ticking, may
     * be mid-tick (moving, merging) on its region worker. Without this gate that is the
     * exact dupe vector that kept items sync-blacklisted in the pool model: hopper reads
     * the stack, the item's own tick merges/shrinks it concurrently, hopper inserts a
     * stale copy. Same-item monitor + isRemoved guard: a consumed item returns false
     * ("not absorbed"), matching vanilla's serial outcome when another consumer got it first.
     */
    @WrapMethod(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z")
    private static boolean fabrolia$syncItemIntake(Container container, ItemEntity item, Operation<Boolean> original) {
        if (item.level() instanceof ServerLevel serverLevel) {
            boolean[] absorbed = new boolean[1];
            SyncItemPickup.wrap(serverLevel, item,
                    () -> absorbed[0] = original.call(container, item));
            return absorbed[0];
        }
        return original.call(container, item);
    }
}
