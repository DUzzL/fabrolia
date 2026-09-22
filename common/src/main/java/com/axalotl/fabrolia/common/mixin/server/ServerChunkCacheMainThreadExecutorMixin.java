package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Completes the logical-server turn for the level chunk mailbox.
 *
 * <p>Vanilla and mods commonly use {@code mainThreadProcessor.submit/executeBlocking}
 * rather than calling {@link ServerChunkCache#getChunk} directly. During the exclusive
 * player actor turn those calls must execute inline just as they do on physical main;
 * otherwise the actor can enqueue work and synchronously wait while physical main is
 * waiting for the actor. The override is thread-local in effect and exists only while
 * the atomic logical-turn stamp names the actor.
 */
@Mixin(ServerChunkCache.MainThreadExecutor.class)
public abstract class ServerChunkCacheMainThreadExecutorMixin
        implements RegionScheduler.NativeChunkServiceExecutor {

    @Inject(method = "getRunningThread()Ljava/lang/Thread;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void fabrolia$logicalPlayerActorThread(CallbackInfoReturnable<Thread> cir) {
        if (RegionalPlayerEntityTicksV2.inExactPlayerActor()
                || RegionScheduler.exclusiveGameplayWorkerOrNull(ParallelProcessor.getServer())
                == Thread.currentThread()) {
            cir.setReturnValue(Thread.currentThread());
        }
    }
}
