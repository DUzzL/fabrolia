package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The fake player's early tick must not change support before its combined physics. */
@Mixin(ServerPlayerGameMode.class)
public interface PlayerMotionGameModeAccessor {
    @Accessor("isDestroyingBlock")
    boolean fabrolia$motionDestroyingBlock();

    @Accessor("hasDelayedDestroy")
    boolean fabrolia$motionDelayedDestroy();
}
