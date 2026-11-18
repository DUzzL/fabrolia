package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.monster.PatrollingMonster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the patrol's 200-tick path cooldown ({@code TimerClocks}). */
@Mixin(PatrollingMonster.LongDistancePatrolGoal.class)
public interface PatrolGoalTimerAccessor {
    @Accessor("cooldownUntil")
    long fabrolia$cooldownUntil();

    @Accessor("cooldownUntil")
    void fabrolia$setCooldownUntil(long value);
}
