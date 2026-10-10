package com.axalotl.fabrolia.common.mixin.carpet;

import com.axalotl.fabrolia.common.regions.CarpetSpawnTrackingGuard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppress every Carpet tracking hook, including sessions started before a mode change.
 * Never reset Carpet's non-thread-safe maps on a worker: clearing only the session marker
 * permanently ends that session; the next permitted start resets its counters normally.
 */
@Pseudo
@Mixin(targets = "carpet.utils.SpawnReporter", remap = false)
public abstract class CarpetSpawnReporterTrackingMixin {
    @Shadow private static int spawnTrackingStartTime;
    @Unique private static final Logger fabrolia$trackingLogger = LogManager.getLogger("Fabrolia");

    @Inject(method = "trackingSpawns()Z", at = @At("HEAD"), cancellable = true,
            require = 1, allow = 1)
    private static void fabrolia$disableUnsafeTracking(CallbackInfoReturnable<Boolean> cir) {
        if (CarpetSpawnTrackingGuard.blocked()) {
            if (spawnTrackingStartTime != 0) {
                spawnTrackingStartTime = 0;
                fabrolia$trackingLogger.warn(CarpetSpawnTrackingGuard.MESSAGE + " Existing tracking stopped.");
            }
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "startTracking(Lnet/minecraft/server/MinecraftServer;"
            + "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)V",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void fabrolia$rejectDirectTrackingStart(MinecraftServer server, BoundingBox filter,
                                                         CallbackInfo ci) {
        if (CarpetSpawnTrackingGuard.blocked()) {
            spawnTrackingStartTime = 0;
            fabrolia$trackingLogger.warn(CarpetSpawnTrackingGuard.MESSAGE);
            ci.cancel();
        }
    }
}
