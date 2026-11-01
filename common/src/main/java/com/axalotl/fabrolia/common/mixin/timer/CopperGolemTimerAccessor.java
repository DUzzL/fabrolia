package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the next weathering time ({@code TimerClocks}). */
@Mixin(CopperGolem.class)
public interface CopperGolemTimerAccessor {
    @Accessor("nextWeatheringTick")
    long fabrolia$nextWeatheringTick();

    @Accessor("nextWeatheringTick")
    void fabrolia$setNextWeatheringTick(long value);
}
