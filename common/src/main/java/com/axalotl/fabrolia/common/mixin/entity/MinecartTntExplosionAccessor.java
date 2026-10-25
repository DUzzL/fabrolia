package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecartTNT.class)
public interface MinecartTntExplosionAccessor {
    @Accessor("explosionPowerBase")
    float fabrolia$explosionPowerBase();

    @Accessor("explosionSpeedFactor")
    float fabrolia$explosionSpeedFactor();
}
