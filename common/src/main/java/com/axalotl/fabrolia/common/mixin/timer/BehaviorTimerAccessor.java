package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.Behavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: a running behaviour's end time ({@code TimerClocks}). */
@Mixin(Behavior.class)
public interface BehaviorTimerAccessor {
    @Accessor("endTimestamp")
    long fabrolia$endTimestamp();

    @Accessor("endTimestamp")
    void fabrolia$setEndTimestamp(long value);
}
