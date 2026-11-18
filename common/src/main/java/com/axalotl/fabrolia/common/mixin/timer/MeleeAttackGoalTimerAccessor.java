package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the 20-tick {@code canUse} throttle ({@code TimerClocks}). */
@Mixin(MeleeAttackGoal.class)
public interface MeleeAttackGoalTimerAccessor {
    @Accessor("lastCanUseCheck")
    long fabrolia$lastCanUseCheck();

    @Accessor("lastCanUseCheck")
    void fabrolia$setLastCanUseCheck(long value);
}
