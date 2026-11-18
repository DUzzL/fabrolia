package com.axalotl.fabrolia.common.mixin.timer;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: flowers the bee could not reach, each until "now + 600"
 *  ({@code TimerClocks}). */
@Mixin(targets = "net.minecraft.world.entity.animal.bee.Bee$BeePollinateGoal")
public interface BeePollinateGoalTimerAccessor {
    @Accessor("unreachableFlowerCache")
    Long2LongOpenHashMap fabrolia$unreachableFlowerCache();
}
