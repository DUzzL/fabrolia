package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the mob's own navigation ({@code getNavigation()} answers the
 *  controlled vehicle's), whose throttles {@code TimerClocks} moves. */
@Mixin(Mob.class)
public interface MobTimerAccessor {
    @Accessor("navigation")
    PathNavigation fabrolia$ownNavigation();

    @Accessor("goalSelector")
    GoalSelector fabrolia$goalSelector();

    @Accessor("targetSelector")
    GoalSelector fabrolia$targetSelector();
}
