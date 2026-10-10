package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PrimedTnt.class)
public interface PrimedTntExplosionAccessor {
    @Accessor("explosionPower")
    float fabrolia$explosionPower();
}
