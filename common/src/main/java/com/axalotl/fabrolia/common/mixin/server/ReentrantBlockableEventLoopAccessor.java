package com.axalotl.fabrolia.common.mixin.server;

import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cold-path proof that a tagged RCON task is not nested under managedBlock. */
@Mixin(ReentrantBlockableEventLoop.class)
public interface ReentrantBlockableEventLoopAccessor {

    @Accessor("reentrantCount")
    int fabrolia$getReentrantCount();
}
