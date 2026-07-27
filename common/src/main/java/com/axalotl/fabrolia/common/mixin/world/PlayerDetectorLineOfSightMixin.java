package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.TrialSpawnerLineOfSightCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.trialspawner.PlayerDetector;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@link PlayerDetector#inLineOfSight} (a private static method on the
 * interface — vanilla calls it from each detector lambda for every candidate player) and
 * routes through {@link TrialSpawnerLineOfSightCache}.
 *
 * <p>HEAD-cancellable: cache hit returns the saved result. Miss falls through to vanilla's
 * {@code level.clip(...)} raymarch, then TAIL inject stores the result for the next 5
 * ticks.
 */
@Mixin(PlayerDetector.class)
public interface PlayerDetectorLineOfSightMixin {

    @Inject(
            method = "inLineOfSight",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void fabrolia$cacheLookup(Level level, Vec3 from, Vec3 to, CallbackInfoReturnable<Boolean> cir) {
        Boolean cached = TrialSpawnerLineOfSightCache.lookup(level, from, to);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(
            method = "inLineOfSight",
            at = @At("RETURN"),
            remap = false
    )
    private static void fabrolia$cacheStore(Level level, Vec3 from, Vec3 to, CallbackInfoReturnable<Boolean> cir) {
        TrialSpawnerLineOfSightCache.store(level, from, to, cir.getReturnValue());
    }
}
