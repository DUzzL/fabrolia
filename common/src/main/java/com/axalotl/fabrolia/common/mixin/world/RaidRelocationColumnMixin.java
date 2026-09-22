package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stacked-raid-farm fix, part 2 (2026-08-15): give the relocation a way to
 * reach the farm's STABLE anchor, not just whatever transient claim happens
 * to be within vanilla's ±2-section cube at sample time.
 *
 * <p><b>Game-server evidence.</b> With the village-check debounce the raid
 * survives and spawns waves, but on the loaded game server it stalled at the
 * first relocation hop (y 192→232) and never climbed to the top villager
 * (bell at y~304): the relocation cube from y=232 spans sections 12-16
 * (y 192-271), so the top village is physically out of reach from the stall
 * zone, and the intermediate ladder claims (the flying table claimed by the
 * middle villager ~1s per 30s lap) coincide with the raid's gap-tick samples
 * only by luck. On an idle test server the luck held (raids climbed and
 * stacked); on the loaded game server it didn't — one mega-raid, extended by
 * every new omen (it stays within 96 blocks of the player), no stacking.
 *
 * <p><b>Fix.</b> Vanilla-first: run the unmodified ±2-section relocation.
 * Only when it found NOTHING (center unchanged — the gap-window case where
 * the raid would sit still) retry over the same horizontal reach (±2
 * sections) but a tall vertical column (±8 sections = ±128 blocks). The
 * farm's top village holds a permanent claim (top villager + bell), so the
 * first gap tick relocates the center straight to the stable anchor,
 * deterministically, regardless of which tick phase the worker sampled.
 * Once the center is >96 blocks from the player, the next raid omen creates
 * a NEW raid instead of extending this one — stacking restored.
 *
 * <p>Scope: the fallback runs only when vanilla relocation found nothing,
 * only with region threading live (regions off = untouched vanilla), and
 * only widens the VERTICAL reach — a raid whose village vanished may migrate
 * to a vertically distant village instead of dying, which no normal village
 * layout ever triggers.
 */
@Mixin(Raid.class)
public abstract class RaidRelocationColumnMixin {

    /** Extra vertical reach of the fallback search, in sections (±128 blocks). */
    private static final int fabrolia$VERTICAL_REACH =
            Integer.getInteger("fabrolia.raid.relocationVerticalReach", 8);

    @Shadow
    private BlockPos center;

    @Shadow
    private void setCenter(BlockPos pos) {
        throw new AssertionError("shadow");
    }

    @WrapMethod(method = "moveRaidCenterToNearbyVillageSection")
    private void fabrolia$tallColumnFallback(ServerLevel level, Operation<Void> original) {
        BlockPos before = this.center;
        original.call(level);
        if (!this.center.equals(before) || RegionScheduler.forLevel(level) == null) {
            return; // vanilla found a target, or regions off → nothing to add
        }
        SectionPos base = SectionPos.of(before);
        List<SectionPos> column = new ArrayList<>();
        for (int dy = -fabrolia$VERTICAL_REACH; dy <= fabrolia$VERTICAL_REACH; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    column.add(SectionPos.of(base.x() + dx, base.y() + dy, base.z() + dz));
                }
            }
        }
        column.stream()
                .filter(level::isVillage)
                .map(SectionPos::center)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(before)))
                .ifPresent(this::setCenter);
    }
}
