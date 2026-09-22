package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Repairs C2ME rewrites-chunk-system's off-thread shortcut at the handler that
 * executes before vanilla {@code ServerChunkCache#getChunk}.
 *
 * <p>The exact player actor is already made logical main for the vanilla
 * getChunk/getChunkFuture/getChunkNow comparisons, but C2ME's cancellable HEAD
 * injector runs first and compares {@link Thread#currentThread()} directly with
 * {@code mainThread}.  On a ready visible holder it can therefore return through
 * C2ME's worker shortcut before the actor-aware vanilla seam is reached.  A
 * MixinSquared handler selector is required here because an {@code @Inject}
 * handler is merged under a generated {@code handler$...} name.</p>
 */
@Mixin(value = ServerChunkCache.class, priority = 2000)
public abstract class FabroliaC2meChunkShortcutActorThreadMixin {

    @Shadow
    @Final
    private Thread mainThread;

    @Dynamic("C2ME injection handler selected through MixinSquared")
    @TargetHandler(
            mixin = "com.ishland.c2me.rewrites.chunksystem.mixin.MixinServerChunkManager",
            name = "shortcutGetChunk",
            prefix = "handler")
    @ModifyExpressionValue(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;",
                    ordinal = 0),
            require = 1,
            allow = 1,
            remap = false)
    private Thread fabrolia$logicalC2meChunkShortcutThread(Thread original) {
        return RegionalPlayerEntityTicksV2.inExactPlayerActor()
                ? this.mainThread
                : original;
    }
}
