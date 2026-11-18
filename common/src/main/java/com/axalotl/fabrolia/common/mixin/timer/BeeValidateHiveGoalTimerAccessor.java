package com.axalotl.fabrolia.common.mixin.timer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the bee's hive revalidation throttle ({@code TimerClocks}). */
@Mixin(targets = "net.minecraft.world.entity.animal.bee.Bee$ValidateHiveGoal")
public interface BeeValidateHiveGoalTimerAccessor {
    @Accessor("lastValidateTick")
    long fabrolia$lastValidateTick();

    @Accessor("lastValidateTick")
    void fabrolia$setLastValidateTick(long value);
}
