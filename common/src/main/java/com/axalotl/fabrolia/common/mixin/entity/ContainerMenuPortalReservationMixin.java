package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** A click can mutate non-clicked slots and live stacks, so reserve the whole menu ingress. */
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuPortalReservationMixin {
    @Shadow @Final public NonNullList<Slot> slots;
    @Shadow protected abstract void resetQuickCraft();

    @Unique
    private boolean fabrolia$hasReservedSlot() {
        for (int i = 0; i < this.slots.size(); i++) {
            if (NetherPortalTransfers.isSourceMutationBlocked(this.slots.get(i).container)) return true;
        }
        return false;
    }

    @WrapMethod(method = "clicked")
    private void fabrolia$guardReservedClick(int slotIndex, int button, ContainerInput input,
                                           Player player, Operation<Void> original) {
        if (this.fabrolia$hasReservedSlot()) {
            // A drag begun before reservation must not resume after cancellation with stale targets.
            this.resetQuickCraft();
            return;
        }
        original.call(slotIndex, button, input, player);
    }

    @WrapMethod(method = "setSelectedBundleItemIndex")
    private void fabrolia$guardReservedBundleSelection(int slotIndex, int selectedItemIndex, Operation<Void> original) {
        if (this.fabrolia$hasReservedSlot()) return;
        original.call(slotIndex, selectedItemIndex);
    }

    @WrapMethod(method = "moveItemStackTo")
    private boolean fabrolia$guardReservedQuickMove(ItemStack stack, int start, int end,
                                                  boolean backwards, Operation<Boolean> original) {
        if (this.fabrolia$hasReservedSlot()) return false;
        return original.call(stack, start, end, backwards);
    }
}
