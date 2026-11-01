package com.axalotl.fabrolia.common.mixin.timer;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.world.entity.ai.sensing.NearestBedSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the baby bed search throttle and its cache ({@code TimerClocks}). */
@Mixin(NearestBedSensor.class)
public interface NearestBedSensorTimerAccessor {
    @Accessor("lastUpdate")
    long fabrolia$lastUpdate();

    @Accessor("lastUpdate")
    void fabrolia$setLastUpdate(long value);

    @Accessor("batchCache")
    Long2LongMap fabrolia$batchCache();
}
