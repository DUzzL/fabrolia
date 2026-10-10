package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.PlayerDimensionTransition;
import com.axalotl.fabrolia.common.regions.CrossDimArrivals;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * End-platform regen staging (2026-07-15 excess-output fix): vanilla runs
 * createEndPlatform(dropResources=true) for EVERY entity entering the End —
 * inline on main that's idempotent per cohort (regen #1 clears, #2..#12
 * no-op). On a source-island worker the destroyBlock item pops ran inline
 * while the clearing writes were cross-region DEFERRED — teleports #2..#12
 * re-scanned the still-uncleared platform and re-popped the same block:
 * duplicate item drops per burst. Staged into the same cohort batch as the
 * entity insertions, the regens execute at the destination owner's phase
 * head in order: regen #1 really clears before #2 scans — vanilla-exact
 * output, and no destination block writes off-owner.
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {

    @WrapOperation(method = "entityInside",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;showEndCredits()V"))
    private void fabrolia$deferEndCredits(ServerPlayer player, Operation<Void> original) {
        if (!PlayerDimensionTransition.deferEndCredits(player, () -> original.call(player))) {
            original.call(player);
        }
    }

    /**
     * Platform regeneration shares the staged arrival's destination cohort,
     * including publication from physical Main.
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$shouldStageRegen(ServerLevel dest, BlockPos pos) {
        if (Thread.currentThread() instanceof RegionWorkerThread) {
            return true;
        }
        com.axalotl.fabrolia.common.regions.RegionizedServer destRs =
                RegionScheduler.forLevel(dest);
        if (destRs == null) {
            return false; // no regional runtime owns the destination
        }
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        return CrossDimArrivals.islandOwnedDestination(destRs, chunkKey);
    }

    @WrapOperation(method = "getPortalDestination",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/feature/EndPlatformFeature;createEndPlatform(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Z)V"))
    private void fabrolia$stageRegen(ServerLevelAccessor level, BlockPos pos, boolean dropResources,
                                     Operation<Void> original) {
        if (!CrossDimArrivals.PUBLISHING.get()
                && level instanceof ServerLevel sl
                && ((RegionScheduler.isActive() && fabrolia$shouldStageRegen(sl, pos))
                    || com.axalotl.fabrolia.common.regions.EndArrivalSavedData.hasFallback(sl))) {
            CrossDimArrivals.stagePlatform(sl, pos, dropResources);
            return;
        }
        original.call(level, pos, dropResources);
    }
}
