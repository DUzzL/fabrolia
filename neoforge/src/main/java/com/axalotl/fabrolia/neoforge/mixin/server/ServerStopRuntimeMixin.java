package com.axalotl.fabrolia.neoforge.mixin.server;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric's SERVER_STOPPING fires at the head of {@code stopServer} on the normal
 * and on the crash path. NeoForge's ServerStoppingEvent fires only after a normal
 * loop end, so after a tick crash the islands kept ticking through
 * {@code stopServer} (player removal, the fenced chunk save, level close) and the
 * advancement shutdown drain never ran (audit D-17). Stop the runtime at the same
 * point Fabric does.
 */
@Mixin(MinecraftServer.class)
public abstract class ServerStopRuntimeMixin {

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void fabrolia$stopRuntimeFirst(CallbackInfo ci) {
        ParallelProcessor.stopServerRuntime();
    }
}
