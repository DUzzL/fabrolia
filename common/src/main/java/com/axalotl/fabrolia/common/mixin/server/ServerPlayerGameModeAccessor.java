package com.axalotl.fabrolia.common.mixin.server;

import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only eligibility view for the deliberately narrow player-tick v2 milestone. */
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccessor {
    @Accessor("isDestroyingBlock")
    boolean fabrolia$isDestroyingBlock();

    @Accessor("hasDelayedDestroy")
    boolean fabrolia$hasDelayedDestroy();
}
