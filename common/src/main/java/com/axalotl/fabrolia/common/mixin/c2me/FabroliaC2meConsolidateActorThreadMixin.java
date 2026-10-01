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
 * C2ME 0.4.2-alpha.0.88 (mc26.3) added a direct
 * {@code Thread.currentThread() != mainThread} check that throws
 * {@code ConcurrentModificationException("Async update chunks")} at the head of
 * its {@code runDistanceManagerUpdates → DistanceManager#runAllUpdates}
 * consolidation wrapper. Fabrolia's own wrapper
 * ({@code ServerChunkCacheMixin#fabrolia$serializeDistanceUpdatesWithPlayerMove})
 * admits exactly two callers: physical Main and the exact player actor, which runs
 * while physical Main is parked and is logical Main for every vanilla
 * {@code mainThread} comparison. Make C2ME's comparison agree for that actor;
 * every other off-Main caller is refused before C2ME is reached.
 */
@Mixin(value = ServerChunkCache.class, priority = 2000)
public abstract class FabroliaC2meConsolidateActorThreadMixin {

    @Shadow
    @Final
    private Thread mainThread;

    @Dynamic("C2ME consolidation handler selected through MixinSquared")
    @TargetHandler(
            mixin = "com.ishland.c2me.rewrites.chunksystem.mixin.MixinServerChunkManager",
            name = "consolidateSchedules",
            prefix = "wrapOperation")
    @ModifyExpressionValue(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;",
                    ordinal = 0),
            require = 1,
            allow = 1,
            remap = false)
    private Thread fabrolia$logicalC2meConsolidateThread(Thread original) {
        return RegionalPlayerEntityTicksV2.inExactPlayerActor()
                ? this.mainThread
                : original;
    }
}
