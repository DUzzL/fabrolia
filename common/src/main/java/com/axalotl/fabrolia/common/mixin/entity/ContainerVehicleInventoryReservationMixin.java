package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** Mutation ingress only: getItem/getItemStacks and serialization retain the real inventory. */
@Mixin({AbstractChestBoat.class, AbstractMinecartContainer.class})
public abstract class ContainerVehicleInventoryReservationMixin {
    @WrapMethod(method = "removeItem")
    private ItemStack fabrolia$guardReservedExtraction(int slot, int count, Operation<ItemStack> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return ItemStack.EMPTY;
        return original.call(slot, count);
    }

    @WrapMethod(method = "removeItemNoUpdate")
    private ItemStack fabrolia$guardReservedExtractionNoUpdate(int slot, Operation<ItemStack> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return ItemStack.EMPTY;
        return original.call(slot);
    }

    @WrapMethod(method = "setItem")
    private void fabrolia$guardReservedInsertion(int slot, ItemStack stack, Operation<Void> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return;
        original.call(slot, stack);
    }

    @WrapMethod(method = "clearContent")
    private void fabrolia$guardReservedClear(Operation<Void> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return;
        original.call();
    }

    @WrapMethod(method = "interact")
    private InteractionResult fabrolia$guardReservedInteraction(Player player, InteractionHand hand,
                                                               Vec3 location, Operation<InteractionResult> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return InteractionResult.FAIL;
        return original.call(player, hand, location);
    }

    @WrapMethod(method = "createMenu(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/inventory/AbstractContainerMenu;")
    private AbstractContainerMenu fabrolia$guardReservedMenu(int containerId, Inventory inventory,
                                                            Player player, Operation<AbstractContainerMenu> original) {
        if (NetherPortalTransfers.isSourceMutationBlocked(this)) return null;
        return original.call(containerId, inventory, player);
    }
}
