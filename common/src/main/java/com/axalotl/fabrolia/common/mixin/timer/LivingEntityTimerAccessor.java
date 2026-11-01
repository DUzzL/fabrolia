package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the last-damage stamp ({@code TimerClocks}). */
@Mixin(LivingEntity.class)
public interface LivingEntityTimerAccessor {
    @Accessor("lastDamageStamp")
    long fabrolia$lastDamageStamp();

    @Accessor("lastDamageStamp")
    void fabrolia$setLastDamageStamp(long value);

    @Accessor("lastDamageSource")
    DamageSource fabrolia$lastDamageSource();
}
