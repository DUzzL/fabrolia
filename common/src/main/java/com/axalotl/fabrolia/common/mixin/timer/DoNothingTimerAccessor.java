package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.DoNothing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the idle behaviour's end (a {@code BehaviorControl}, not a
 *  {@code Behavior}; {@code TimerClocks}). */
@Mixin(DoNothing.class)
public interface DoNothingTimerAccessor {
    @Accessor("endTimestamp")
    long fabrolia$endTimestamp();

    @Accessor("endTimestamp")
    void fabrolia$setEndTimestamp(long value);
}
