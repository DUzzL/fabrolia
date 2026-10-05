package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.MapItemUpdates;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;

/** Serialises every server-side map-pixel transaction by exact saved map. */
@Mixin(MapItem.class)
public abstract class MapItemMixin {

    @WrapMethod(method = "inventoryTick")
    private void fabrolia$lockInventoryMapUpdate(
            ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot,
            Operation<Void> original) {
        MapItemUpdates.inventoryTick(stack, level, entity, slot,
                () -> original.call(stack, level, entity, slot));
    }

    @WrapMethod(method = "update")
    private void fabrolia$lockDirectMapUpdate(
            Level level, Entity entity, MapItemSavedData data,
            Operation<Void> original) {
        MapItemUpdates.update(level, entity, data,
                () -> original.call(level, entity, data));
    }
}
