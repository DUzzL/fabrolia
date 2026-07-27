package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.common.parallelised.utils.SpawnCensus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Census persistence flip (task #21): a name-tag/pickup marks the mob
 * persistent — vanilla's scan would stop counting it the next tick; the
 * incremental census re-evaluates its ledgered contribution at the flip.
 * Dynamic custom-persistence overrides with no vanilla seam converge at the
 * 256-gt audit.
 */
@Mixin(Mob.class)
public abstract class MobPersistenceCensusMixin {

    @Inject(method = "setPersistenceRequired()V", at = @At("RETURN"))
    private void fabrolia$censusPersistenceFlip(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (self.level() instanceof ServerLevel level) {
            SpawnCensus.update(level, self, null);
        }
    }
}
