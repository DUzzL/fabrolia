package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Creeper.class)
public interface CreeperExplosionAccessor {
    @Accessor("explosionRadius")
    int fabrolia$explosionRadius();
}
