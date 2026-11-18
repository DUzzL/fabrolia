package com.axalotl.fabrolia.common.mixin.timer;

import java.util.Optional;
import net.minecraft.world.entity.ai.behavior.PrepareRamNearestTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: when the goat reached its ram position ({@code TimerClocks}). */
@Mixin(PrepareRamNearestTarget.class)
public interface PrepareRamNearestTargetTimerAccessor {
    @Accessor("reachedRamPositionTimestamp")
    Optional<Long> fabrolia$reachedRamPositionTimestamp();

    @Accessor("reachedRamPositionTimestamp")
    void fabrolia$setReachedRamPositionTimestamp(Optional<Long> value);
}
