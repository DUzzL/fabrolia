package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Stable owner reference access across projectile/player dimension changes. */
@Mixin(Projectile.class)
public interface ProjectileOwnerAccessor {

    @Accessor("owner")
    EntityReference<Entity> fabrolia$getOwnerReference();

    @Accessor("owner")
    void fabrolia$setOwnerReference(EntityReference<Entity> reference);
}
