package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: when the brain animal's child is due ({@code TimerClocks}). */
@Mixin(AnimalMakeLove.class)
public interface AnimalMakeLoveTimerAccessor {
    @Accessor("spawnChildAtTime")
    long fabrolia$spawnChildAtTime();

    @Accessor("spawnChildAtTime")
    void fabrolia$setSpawnChildAtTime(long value);
}
