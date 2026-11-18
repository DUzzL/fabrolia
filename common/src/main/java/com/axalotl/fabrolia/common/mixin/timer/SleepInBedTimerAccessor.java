package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.SleepInBed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the next bed attempt ({@code TimerClocks}). */
@Mixin(SleepInBed.class)
public interface SleepInBedTimerAccessor {
    @Accessor("nextOkStartTime")
    long fabrolia$nextOkStartTime();

    @Accessor("nextOkStartTime")
    void fabrolia$setNextOkStartTime(long value);
}
