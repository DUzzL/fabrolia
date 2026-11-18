package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.ai.behavior.LongJumpToRandomPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: when the long-jump crouch started ({@code TimerClocks}). */
@Mixin(LongJumpToRandomPos.class)
public interface LongJumpToRandomPosTimerAccessor {
    @Accessor("prepareJumpStart")
    long fabrolia$prepareJumpStart();

    @Accessor("prepareJumpStart")
    void fabrolia$setPrepareJumpStart(long value);
}
