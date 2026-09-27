package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TargetingConditions.class)
public interface TargetingConditionsAccessor {
   @Accessor("range")
   double fabrolia$range();
}
