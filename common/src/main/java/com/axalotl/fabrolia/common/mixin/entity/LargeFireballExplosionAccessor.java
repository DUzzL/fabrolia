package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LargeFireball.class)
public interface LargeFireballExplosionAccessor {
    @Accessor("explosionPower")
    int fabrolia$explosionPower();
}
