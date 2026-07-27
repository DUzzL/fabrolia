package com.axalotl.fabrolia.common.mixin.entity.movement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rig/production diagnostic (2026-07-22 duper footprint-progression divergence):
 * ordered log of every {@code Entity.moveTowardsClosestSpace} call — the
 * squeeze-escape impulse that ejects items overlapped by freshly-placed blocks
 * (NORTH/−Z favored on ties). Log-line ORDER within one game tick is direct
 * evidence of entity processing order (resident items vs the arriving
 * falling-block batch), which scarpet's between-tick sampling cannot observe.
 *
 * <p>Gated on {@code -Dfabrolia.debug.pushTrace=true}; default off, zero cost
 * beyond one static boolean read. Never enable permanently — one line per
 * squeezed entity per tick.
 */
@Mixin(Entity.class)
public abstract class EntityPushTraceMixin {

    private static final boolean fabrolia$PUSH_TRACE = Boolean.getBoolean("fabrolia.debug.pushTrace");

    @Inject(method = "moveTowardsClosestSpace", at = @At("TAIL"))
    private void fabrolia$tracePush(double x, double y, double z, CallbackInfo ci) {
        if (!fabrolia$PUSH_TRACE) {
            return;
        }
        Entity self = (Entity) (Object) this;
        if (!(self.level() instanceof ServerLevel sl)) {
            return;
        }
        org.slf4j.LoggerFactory.getLogger("Fabrolia-PushTrace").info(
                "[pushtrace] {} t={} id={} {} pos=({},{},{}) dm={} thread={}",
                sl.dimension().identifier(),
                com.axalotl.fabrolia.common.regions.IslandClock.wallTime(sl),
                self.getId(), self.getType().builtInRegistryHolder().key().identifier(),
                String.format("%.2f", self.getX()), String.format("%.2f", self.getY()),
                String.format("%.2f", self.getZ()),
                self.getDeltaMovement(), Thread.currentThread().getName());
    }
}
