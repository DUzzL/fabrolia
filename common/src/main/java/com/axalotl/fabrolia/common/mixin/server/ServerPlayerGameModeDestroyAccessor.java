package com.axalotl.fabrolia.common.mixin.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Block-break progress that {@code ServerPlayerGameMode.tick} resolves every tick;
 *  read by {@link com.axalotl.fabrolia.common.regions.PlayerRemoteState}. */
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeDestroyAccessor {
    @Accessor("isDestroyingBlock")
    boolean fabrolia$isDestroyingBlock();

    @Accessor("destroyPos")
    BlockPos fabrolia$destroyPos();

    @Accessor("hasDelayedDestroy")
    boolean fabrolia$hasDelayedDestroy();

    @Accessor("delayedDestroyPos")
    BlockPos fabrolia$delayedDestroyPos();
}
