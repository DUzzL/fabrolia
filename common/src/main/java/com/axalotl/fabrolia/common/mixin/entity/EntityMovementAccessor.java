package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Owner-only movement preflight; never grants authority to read a foreign entity. */
@Mixin(Entity.class)
public interface EntityMovementAccessor {
    @Accessor("stuckSpeedMultiplier")
    Vec3 fabrolia$stuckSpeedMultiplier();

    @Accessor("movementThisTick")
    java.util.ArrayDeque<?> fabrolia$movementThisTick();
}
