package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes Lithium's two chunk-status thread assertions recognise the exact
 * authenticated player actor.
 *
 * <p>Do not inject into Lithium's {@code LevelAccessor#getThread()} accessor:
 * Mixin generates accessor bodies after it prepares ordinary injectors, so
 * that method is not a legal cross-mixin injection target. The two audited
 * consumers below are real methods in {@code ChunkStatusTracker}; each pin is
 * deliberately exact and fail-loud for a changed supported Lithium version.
 */
@Mixin(
        targets = "net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker",
        priority = 900,
        remap = false)
public abstract class FabroliaLithiumChunkStatusTrackerActorThreadMixin {

    private static final String LEVEL_THREAD_ACCESSOR =
            "Lnet/caffeinemc/mods/lithium/mixin/util/accessors/LevelAccessor;"
                    + "getThread()Ljava/lang/Thread;";

    @ModifyExpressionValue(
            method = "onChunkAccessible(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At(value = "INVOKE", target = LEVEL_THREAD_ACCESSOR),
            require = 1,
            allow = 1,
            remap = false)
    private static Thread fabrolia$logicalAccessibleThread(Thread original) {
        return RegionalPlayerEntityTicksV2.inExactPlayerActor()
                ? Thread.currentThread()
                : original;
    }

    @ModifyExpressionValue(
            method = "onChunkInaccessible(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/level/ChunkPos;)V",
            at = @At(value = "INVOKE", target = LEVEL_THREAD_ACCESSOR),
            require = 1,
            allow = 1,
            remap = false)
    private static Thread fabrolia$logicalInaccessibleThread(Thread original) {
        return RegionalPlayerEntityTicksV2.inExactPlayerActor()
                ? Thread.currentThread()
                : original;
    }
}
