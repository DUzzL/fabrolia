package com.axalotl.fabrolia.common.mixin.c2me;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replacement for C2ME 0.4.2-alpha.0.41's
 * {@code fixes.worldgen.threading_issues...MixinServerWorld}.
 *
 * <p>The upstream mixin has one hook: its wrapper calls
 * {@link ServerChunkCache#hasEntityWithId(int)} only on the physical
 * {@code Level.thread}, returning {@code false} for every other caller.
 * {@code MixinCanceller} removes exactly that one upstream mixin; this wrapper
 * reproduces its physical-thread branch.  C2ME-absent runs cancel this paired
 * accessor and wrapper, so vanilla-only behaviour remains untouched.</p>
 */
@Mixin(ServerLevel.class)
public abstract class FabroliaC2meServerLevelActorThreadMixin {

    @WrapOperation(
            method = "getNextEntityId()I",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;hasEntityWithId(I)Z"),
            require = 1,
            allow = 1)
    private boolean fabrolia$c2meActorAwareEntityIdCollisionCheck(
            ServerChunkCache chunkSource, int entityId, Operation<Boolean> original) {
        Thread physical = ((FabroliaC2meLevelThreadAccessor) (Object) this)
                .fabrolia$getPhysicalLevelThread();
        if (Thread.currentThread() != physical) {
            return false;
        }
        return original.call(chunkSource, entityId);
    }
}
