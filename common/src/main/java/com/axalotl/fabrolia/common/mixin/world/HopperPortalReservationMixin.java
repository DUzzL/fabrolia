package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Guard before removeItem or a live getItem reference can participate in a transfer. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperPortalReservationMixin {
    @WrapMethod(method = "tryTakeInItemFromSlot")
    private static boolean fabrolia$guardReservedExtraction(Hopper hopper, Container source, int slot,
                                                           Direction direction, Operation<Boolean> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(hopper)
                || NetherPortalTransfers.isSourceMutationBlocked(source)) return false;
        return original.call(hopper, source, slot, direction);
    }

    @WrapMethod(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;")
    private static ItemStack fabrolia$guardReservedInsertion(Container from, Container into, ItemStack stack,
                                                           Direction direction, Operation<ItemStack> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(from)
                || NetherPortalTransfers.isSourceMutationBlocked(into)) return stack;
        return original.call(from, into, stack, direction);
    }

    @WrapMethod(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z")
    private static boolean fabrolia$guardReservedItemIntake(Container into, ItemEntity item, Operation<Boolean> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(into)) return false;
        return original.call(into, item);
    }

    @WrapMethod(method = "suckInItems")
    private static boolean fabrolia$guardReservedHopper(Level level, Hopper hopper, Operation<Boolean> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(hopper)) return false;
        return original.call(level, hopper);
    }

    @WrapMethod(method = "isFullContainer")
    private static boolean fabrolia$guardReservedDestinationProbe(Container container, Direction direction,
                                                                Operation<Boolean> original) {
        // ejectItems must not even unpack a queued vehicle's loot while probing its slots.
        if (NetherPortalTransfers.isSourceMutationBlocked(container)) return true;
        return original.call(container, direction);
    }
}
