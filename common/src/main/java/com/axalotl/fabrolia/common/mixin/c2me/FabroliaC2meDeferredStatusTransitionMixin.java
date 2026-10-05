package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.regions.WorkerChunkCache;
import net.minecraft.server.level.ChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets C2ME's {@code NewChunkHolderVanillaInterface.updateDeferredStatus},
 * the only method that lowers {@code loadedDeferredStatus} or raises
 * {@code deferredStatus}, as a {@link WorkerChunkCache} transition: after it, the
 * {@code triggerDeferredLoad} a cache hit skips may no longer be a no-op.
 */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface",
        remap = false)
public abstract class FabroliaC2meDeferredStatusTransitionMixin {

    @Inject(method = "updateDeferredStatus", at = @At("HEAD"), require = 1, allow = 1, remap = false)
    private void fabrolia$beginDeferredStatusTransition(CallbackInfo ci) {
        WorkerChunkCache.beginTransition(((ChunkHolder) (Object) this).getPos().pack());
    }

    @Inject(method = "updateDeferredStatus", at = @At("RETURN"), require = 1, remap = false)
    private void fabrolia$endDeferredStatusTransition(CallbackInfo ci) {
        WorkerChunkCache.endTransition(((ChunkHolder) (Object) this).getPos().pack());
    }
}
