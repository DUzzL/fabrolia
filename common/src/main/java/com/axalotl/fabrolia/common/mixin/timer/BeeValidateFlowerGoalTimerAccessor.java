package com.axalotl.fabrolia.common.mixin.timer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the bee's flower revalidation throttle ({@code TimerClocks}). */
@Mixin(targets = "net.minecraft.world.entity.animal.bee.Bee$ValidateFlowerGoal")
public interface BeeValidateFlowerGoalTimerAccessor {
    @Accessor("lastValidateTick")
    long fabrolia$lastValidateTick();

    @Accessor("lastValidateTick")
    void fabrolia$setLastValidateTick(long value);
}
