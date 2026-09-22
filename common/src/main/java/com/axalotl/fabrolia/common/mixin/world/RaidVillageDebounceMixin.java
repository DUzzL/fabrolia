package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.utils.RaidVillageDebounce;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stacked-raid-farm fix (2026-08-14): tolerate transient "not a village"
 * answers while the farm's workstations are mid-flight.
 *
 * <p><b>Root cause established by prod traces.</b> The farm bobs its
 * workstations (fletching tables) on pistons, remove+add every move, and the
 * claim dies WITH the record — zero {@code PoiManager.release} calls in the
 * whole log, while {@code poiOccupied48} flapped 0→1→0 and record identity
 * hashes proved the occupied record was replaced by a fresh unclaimed one.
 * All workstations share one clock, so each cycle opens a global window with
 * NO claimed POI anywhere near the center. Vanilla never samples that window:
 * {@code Raid.tick} runs on main at a fixed point in the tick order, after
 * the block-event and entity phases that re-place and re-claim. Under async
 * regions the raid ticks on a worker at an arbitrary phase against main-side
 * block events, samples mid-window, and dies to the vanilla double strike
 * (NOT-A-VILLAGE → relocate → NOT-A-VILLAGE → stop/LOSS) within 3-4 ticks.
 *
 * <p><b>Fix.</b> Debounce ONLY the kill switch (the second, unconditional
 * {@code isVillage} read): when relocation found no village, the raid gets
 * {@code GAP_TOLERANCE} of its own ticks to ride out the transient global
 * claim gap instead of dying to the double strike within 3-4 ticks. The
 * relocation trigger (first read) stays RAW — it is the load-bearing
 * stacking mechanic: the bottom village exists ~1s per cycle, the raid
 * starts there, then relocation transfers the center up to the permanently
 * claimed top villager, and only then does the next raid omen create a NEW
 * raid instead of extending this one. The first version of this fix
 * debounced both sites, pinning the center at the bottom — the farm degraded
 * to "one raid, no stack". The state machine lives in
 * {@link RaidVillageDebounce} and is unit-tested.
 */
@Mixin(Raid.class)
public abstract class RaidVillageDebounceMixin {

    @Shadow
    private long ticksActive;

    /** Raid ticks of sustained village absence before vanilla sees false. */
    @Unique
    private static final int fabrolia$GAP_TOLERANCE =
            Integer.getInteger("fabrolia.raid.villageGapTolerance", 100);

    @Unique
    private final RaidVillageDebounce.State fabrolia$debounce = new RaidVillageDebounce.State();

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;isVillage(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean fabrolia$debounceVillageCheck(ServerLevel level, BlockPos pos,
                                                  Operation<Boolean> original) {
        boolean raw = original.call(level, pos);
        // Gate on region THREADING being live (any mode), not on islands:
        // the phase decoupling this debounce rides out exists whenever POI
        // mutations can run on workers while the raid samples elsewhere —
        // including regions-on/islands-off (raid ticks on main in the vanilla
        // slot while villager AI claims on region workers). Regions fully off
        // (rs == null) is pure single-threaded vanilla: nothing to ride out.
        boolean regionsOn = RegionScheduler.forLevel(level) != null;
        return RaidVillageDebounce.answer(raw, regionsOn, this.ticksActive,
                fabrolia$GAP_TOLERANCE, this.fabrolia$debounce);
    }
}
