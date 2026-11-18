package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the farmer's next harvest step ({@code TimerClocks}). */
@Mixin(HarvestFarmland.class)
public interface HarvestFarmlandTimerAccessor {
    @Accessor("nextOkStartTime")
    long fabrolia$nextOkStartTime();

    @Accessor("nextOkStartTime")
    void fabrolia$setNextOkStartTime(long value);
}
