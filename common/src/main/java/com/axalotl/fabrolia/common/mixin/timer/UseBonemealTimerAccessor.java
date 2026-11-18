package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.UseBonemeal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the farmer's next bone meal cycle ({@code TimerClocks});
 *  {@code lastBonemealingSession} counts entity ticks and needs no move. */
@Mixin(UseBonemeal.class)
public interface UseBonemealTimerAccessor {
    @Accessor("nextWorkCycleTime")
    long fabrolia$nextWorkCycleTime();

    @Accessor("nextWorkCycleTime")
    void fabrolia$setNextWorkCycleTime(long value);
}
