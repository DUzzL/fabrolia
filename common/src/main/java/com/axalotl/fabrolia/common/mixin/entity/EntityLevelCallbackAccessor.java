package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the entity's authoritative PESM lifecycle callback. */
@Mixin(Entity.class)
public interface EntityLevelCallbackAccessor {

    @Accessor("levelCallback")
    EntityInLevelCallback fabrolia$getLevelCallback();
}
