package com.axalotl.fabrolia.common.mixin.entity.spawn;

import com.axalotl.fabrolia.common.parallelised.utils.SpawnCensus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Census death flip (task #21): a dying mob moves to the corpse bucket
 * (counted only while its chunk entity-ticks — the refill-parity corpse
 * rule). Reverse flips (health restored on a dead mob) have no vanilla seam
 * and converge at the 256-gt audit.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDeathCensusMixin {

    @Inject(method = "die", at = @At("RETURN"))
    private void fabrolia$censusDeathFlip(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level() instanceof ServerLevel level) {
            SpawnCensus.update(level, self, null);
        }
    }
}
