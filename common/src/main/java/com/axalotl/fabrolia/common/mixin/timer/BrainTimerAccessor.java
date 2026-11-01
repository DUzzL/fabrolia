package com.axalotl.fabrolia.common.mixin.timer;

import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: memories, behaviours and sensors that hold a game time ({@code TimerClocks}). */
@Mixin(Brain.class)
public interface BrainTimerAccessor {
    @Accessor("memories")
    Map<MemoryModuleType<?>, MemorySlot<?>> fabrolia$memories();

    @Accessor("sensors")
    Map<SensorType<?>, Sensor<?>> fabrolia$sensors();

    @Accessor("lastScheduleUpdate")
    long fabrolia$lastScheduleUpdate();

    @Accessor("lastScheduleUpdate")
    void fabrolia$setLastScheduleUpdate(long value);

    @Accessor("availableBehaviorsByPriority")
    Map<Integer, Map<Activity, Set<BehaviorControl<?>>>> fabrolia$availableBehaviorsByPriority();
}
