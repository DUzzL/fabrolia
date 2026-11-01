package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.WorkAtPoi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: the job-site work throttle ({@code TimerClocks}). */
@Mixin(WorkAtPoi.class)
public interface WorkAtPoiTimerAccessor {
    @Accessor("lastCheck")
    long fabrolia$lastCheck();

    @Accessor("lastCheck")
    void fabrolia$setLastCheck(long value);
}
