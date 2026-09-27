package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.RegionMainBlockingWaitGuard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guards the declaration-level server queue wait without affecting other loops. */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMainWaitGuardMixin {

    @Inject(
            method = "executeBlocking(Ljava/lang/Runnable;)V",
            at = @At("HEAD"),
            require = 1,
            allow = 1)
    private void fabrolia$failBeforeCausalServerMainWait(
            Runnable task, CallbackInfo ci) {
        Object receiver = this;
        if (receiver instanceof MinecraftServer server
                && !server.isSameThread()) {
            RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates(
                    "MinecraftServer.executeBlocking(Runnable)");
        }
    }
}
