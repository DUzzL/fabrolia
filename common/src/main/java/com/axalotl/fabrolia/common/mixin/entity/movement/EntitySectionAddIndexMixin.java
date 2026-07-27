package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SpawnIndex membership choke point (task #32 cod runaway, root cause 1):
 * every path that makes an entity gameplay-visible ends in
 * {@code EntitySection.add} — vanilla {@code PESM.addEntity}, section
 * transfers in {@code Callback.onMove}, AND the c2me chunk system's
 * load/restore path. c2me attaches entities WITHOUT firing the vanilla
 * {@code startTracking} seam the index mirrors (proven for its unload side in
 * the 2026-07-18 mob-switch work; symmetric on load), so before this hook a
 * chunk unload+reload cycle left every surviving mob permanently invisible to
 * the spawn census: caps under-filled forever, natural spawning refilled the
 * "missing" population each cycle — the production 142-cod staircase.
 *
 * <p>Accessibility rule mirrors vanilla {@code getEffectiveStatus}: the
 * section's status, with {@code isAlwaysTicking} forcing accessible.
 * {@code ensurePresent} is contains-first idempotent (one set lookup on the
 * overwhelmingly-common already-present path) and bumps the precompute
 * version only on an actual heal. Removal stays event-driven (stopTracking +
 * census purge) — this hook only guarantees the add side can never be missed.
 */
@Mixin(value = EntitySection.class, priority = 900)
public abstract class EntitySectionAddIndexMixin<T extends EntityAccess> {

    @Shadow
    private Visibility chunkStatus;

    @Inject(method = "add", at = @At("RETURN"))
    private void fabrolia$indexOnAdd(T entityAccess, CallbackInfo ci) {
        if (!(entityAccess instanceof Entity e)) {
            return;
        }
        if (!(e.level() instanceof ServerLevel sl)) {
            return;
        }
        Visibility vis = this.chunkStatus;
        if ((vis == null || !vis.isAccessible()) && !entityAccess.isAlwaysTicking()) {
            return;
        }
        SpawnIndex.ensurePresent(sl, e);
    }
}
