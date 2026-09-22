package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Raw source-owned state: these accessors never authorize a foreign read. */
@Mixin(LivingEntity.class)
public interface PlayerMotionLivingAccessor {
    @Accessor("jumping")
    boolean fabrolia$motionJumping();

    @Accessor("lastEquipmentItems")
    Map<EquipmentSlot, ItemStack> fabrolia$motionLastEquipment();

    @Accessor("activeLocationDependentEnchantments")
    java.util.EnumMap<EquipmentSlot, ?> fabrolia$motionLocationEnchantments();
}
