package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex;
import com.axalotl.fabrolia.common.regions.CopperSectionAccess;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
 * overwhelmingly-common already-present path). Removal stays event-driven (stopTracking +
 * census purge) — this hook only guarantees the add side can never be missed.
 */
@Mixin(value = EntitySection.class, priority = 900)
public abstract class EntitySectionAddIndexMixin<T extends EntityAccess> implements CopperSectionAccess {

    @Shadow
    private Visibility chunkStatus;

    @Shadow @Final private ClassInstanceMultiMap<T> storage;
    @Unique private long fabrolia$copperChunk;
    @Unique private boolean fabrolia$attached;
    // Null in every section that has never contained a server copper golem. Do not walk or
    // populate a native class bucket on a visibility change in those sections.
    @Unique private ServerLevel fabrolia$copperLevel;

    @Override public void fabrolia$bindCopperSection(long chunkKey) {
        fabrolia$copperChunk = chunkKey;
        fabrolia$attached = true;
    }

    @Override public void fabrolia$seedTrackedCopper(RegionizedServer runtime) {
        if (fabrolia$copperLevel == runtime.level && fabrolia$attached
                && chunkStatus != null && chunkStatus.isAccessible()) {
            fabrolia$publishCopper(runtime, true);
        }
    }

    @Override public void fabrolia$detachCopperSection() {
        if (fabrolia$attached && fabrolia$copperLevel != null && chunkStatus != null && chunkStatus.isAccessible()) {
            fabrolia$publishCopper(RegionizedServer.copperSectionRuntime(fabrolia$copperLevel, false), false);
        }
        fabrolia$attached = false;
    }

    @Unique private void fabrolia$publishCopper(RegionizedServer runtime, boolean accessible) {
        if (runtime == null) return;
        for (CopperGolem golem : storage.find(CopperGolem.class)) {
            runtime.updateCopperSection(fabrolia$copperChunk, golem, accessible);
        }
    }

    @ModifyReturnValue(method = "updateChunkStatus", at = @At("RETURN"), require = 1)
    private Visibility fabrolia$copperVisibility(Visibility previous, Visibility status) {
        if (fabrolia$attached && fabrolia$copperLevel != null) {
            boolean accessible = status != null && status.isAccessible();
            if (accessible != (previous != null && previous.isAccessible())) {
                fabrolia$publishCopper(RegionizedServer.copperSectionRuntime(fabrolia$copperLevel, accessible), accessible);
            }
        }
        return previous;
    }

    @ModifyReturnValue(method = "remove", at = @At("RETURN"), require = 1)
    private boolean fabrolia$copperRemoved(boolean removed, T entity) {
        if (removed && entity instanceof CopperGolem golem && fabrolia$attached
                && fabrolia$copperLevel != null && chunkStatus != null && chunkStatus.isAccessible()) {
            var runtime = RegionizedServer.copperSectionRuntime(fabrolia$copperLevel, false);
            if (runtime != null) runtime.updateCopperSection(fabrolia$copperChunk, golem, false);
        }
        return removed;
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void fabrolia$indexOnAdd(T entityAccess, CallbackInfo ci) {
        if (!(entityAccess instanceof Entity e)) {
            return;
        }
        if (!(e.level() instanceof ServerLevel sl)) {
            return;
        }
        Visibility vis = this.chunkStatus;
        if (e instanceof CopperGolem golem && fabrolia$attached) {
            fabrolia$copperLevel = sl;
            if (vis != null && vis.isAccessible()) {
                var runtime = RegionizedServer.copperSectionRuntime(sl, true);
                if (runtime != null) runtime.updateCopperSection(fabrolia$copperChunk, golem, true);
            }
        }
        if ((vis == null || !vis.isAccessible()) && !entityAccess.isAlwaysTicking()) {
            return;
        }
        SpawnIndex.ensurePresent(sl, e);
    }
}
