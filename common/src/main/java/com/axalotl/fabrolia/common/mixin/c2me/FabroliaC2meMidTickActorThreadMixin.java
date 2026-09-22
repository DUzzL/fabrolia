package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Preserves C2ME's mid-tick task opportunity at the exact player ordinal.
 *
 * <p>C2ME injects a call to {@code executeTasksMidTick} at the tail of
 * {@code Level#guardEntityTick}; that tail is inside the guarded operation moved
 * to the serial player actor.  Its direct {@code serverThread == currentThread}
 * check otherwise turns this one reachable call into a silent no-op.  Returning
 * the captured physical owner only for the authenticated actor restores the
 * original branch.  The existing MainThreadExecutor logical-owner mixin then
 * supplies the same executor ownership contract while physical main waits.</p>
 */
@Mixin(value = MinecraftServer.class, priority = 2000)
public abstract class FabroliaC2meMidTickActorThreadMixin {

    @Shadow
    @Final
    private Thread serverThread;

    @ModifyExpressionValue(
            method = "executeTasksMidTick(Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;",
                    ordinal = 0),
            require = 1,
            allow = 1,
            remap = false)
    private Thread fabrolia$logicalC2meMidTickThread(Thread original) {
        return RegionalPlayerEntityTicksV2.inExactPlayerActor()
                ? this.serverThread
                : original;
    }
}
