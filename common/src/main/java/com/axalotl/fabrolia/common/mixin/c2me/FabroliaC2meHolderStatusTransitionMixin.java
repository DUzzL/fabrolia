package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.regions.WorkerChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Brackets flowsched's {@code ItemHolder.setStatus} — the only writer of a
 * chunk holder's status and status futures — as a {@link WorkerChunkCache}
 * transition of the holder's chunk position whenever it is the step down out
 * of the status C2ME's worker shortcut answers FULL from
 * ({@link WorkerChunkCache#demotesFullShortcut}). The decision reads only the
 * argument, so HEAD and RETURN agree; holder removal and every later reset of
 * that future follow this step.
 */
@Pseudo
@Mixin(targets = "com.ishland.flowsched.scheduler.ItemHolder", remap = false)
public abstract class FabroliaC2meHolderStatusTransitionMixin {

    @Shadow
    @Final
    private Object key;

    @Inject(method = "setStatus", at = @At("HEAD"), require = 1, allow = 1, remap = false)
    private void fabrolia$beginStatusTransition(@Coerce Object newStatus, boolean cancellation,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (this.key instanceof ChunkPos pos && WorkerChunkCache.demotesFullShortcut(newStatus)) {
            WorkerChunkCache.beginTransition(pos.pack());
        }
    }

    @Inject(method = "setStatus", at = @At("RETURN"), require = 1, remap = false)
    private void fabrolia$endStatusTransition(@Coerce Object newStatus, boolean cancellation,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (this.key instanceof ChunkPos pos && WorkerChunkCache.demotesFullShortcut(newStatus)) {
            WorkerChunkCache.endTransition(pos.pack());
        }
    }
}
