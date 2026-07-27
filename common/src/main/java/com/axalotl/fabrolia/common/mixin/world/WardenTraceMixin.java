package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.SpawnUtil;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Warden creation-path tracer (task #16 rework, 2026-07-22): production shows
 * MOST shrieker activations create no Warden at all, in every mode including
 * {@code /fabrolia regions off} — an always-installed hook is suspected. This
 * logs every {@link SpawnUtil#trySpawnMob} call and its OUTCOME so each failed
 * activation names its rejection instead of silently producing nothing.
 *
 * <p>Gated on {@code -Dfabrolia.debug.wardenTrace=true}; default off.
 */
@Mixin(SpawnUtil.class)
public abstract class WardenTraceMixin {

    private static final boolean fabrolia$WT = Boolean.getBoolean("fabrolia.debug.wardenTrace");

    @Inject(method = "trySpawnMob", at = @At("HEAD"))
    private static <T extends Mob> void fabrolia$traceAttempt(EntityType<T> type, EntitySpawnReason reason,
            ServerLevel level, BlockPos pos, int attempts, int range, int yOffset,
            SpawnUtil.Strategy strategy, boolean checkCollision,
            CallbackInfoReturnable<Optional<T>> cir) {
        if (fabrolia$WT) {
            org.slf4j.LoggerFactory.getLogger("Fabrolia-WardenTrace").info(
                    "[wtrace] ATTEMPT {} W={} @{} attempts={} range={} thread={}",
                    EntityType.getKey(type), com.axalotl.fabrolia.common.regions.IslandClock.wallTime(level),
                    pos.toShortString(), attempts, range, Thread.currentThread().getName());
        }
    }

    @Inject(method = "trySpawnMob", at = @At("RETURN"))
    private static <T extends Mob> void fabrolia$traceResult(EntityType<T> type, EntitySpawnReason reason,
            ServerLevel level, BlockPos pos, int attempts, int range, int yOffset,
            SpawnUtil.Strategy strategy, boolean checkCollision,
            CallbackInfoReturnable<Optional<T>> cir) {
        if (fabrolia$WT) {
            Optional<T> r = cir.getReturnValue();
            org.slf4j.LoggerFactory.getLogger("Fabrolia-WardenTrace").info(
                    "[wtrace] RESULT {} W={} {} thread={}",
                    EntityType.getKey(type), com.axalotl.fabrolia.common.regions.IslandClock.wallTime(level),
                    r != null && r.isPresent()
                            ? "SPAWNED@" + r.get().blockPosition().toShortString()
                            : "NO-CANDIDATE (all " + attempts + " tries rejected)",
                    Thread.currentThread().getName());
        }
    }
}
