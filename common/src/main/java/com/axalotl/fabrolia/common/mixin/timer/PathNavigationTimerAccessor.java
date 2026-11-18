package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: path recompute throttle and stuck-timeout clock ({@code TimerClocks}). */
@Mixin(PathNavigation.class)
public interface PathNavigationTimerAccessor {
    @Accessor("timeLastRecompute")
    long fabrolia$timeLastRecompute();

    @Accessor("timeLastRecompute")
    void fabrolia$setTimeLastRecompute(long value);

    @Accessor("lastTimeoutCheck")
    long fabrolia$lastTimeoutCheck();

    @Accessor("lastTimeoutCheck")
    void fabrolia$setLastTimeoutCheck(long value);
}
