package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.CrossRegionGuard;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cross-region containment for the neighbour-update cascade. {@code executeUpdate} is the
 * per-target dispatch the {@link net.minecraft.world.level.redstone.CollectingNeighborUpdater}
 * runs for every block a cascade touches; it reads the target's neighbours (which, when the
 * target is in a region two cells away, includes a concurrently-ticking same-colour region)
 * and may write the target. If the current region worker's claimed region does NOT contain
 * the target position, we DEFER the whole dispatch to the main thread (replayed after the
 * phase barrier, fresh-read) and cancel the inline run — so the worker's cascade can never
 * propagate into, nor read across into, a concurrent region. See {@link CrossRegionGuard}.
 *
 * <p>The {@code state} argument is dropped: the replay re-reads the live state on main, so a
 * deferred update never acts on a value that drifted while it sat in the queue.
 */
@Mixin(NeighborUpdater.class)
public interface NeighborUpdaterMixin {

    @Inject(method = "executeUpdate", at = @At("HEAD"), cancellable = true)
    private static void fabrolia$deferCrossRegion(Level level, BlockState state, BlockPos pos,
                                               Block neighborBlock, Orientation orientation,
                                               boolean movedByPiston, CallbackInfo ci) {
        RegionizedServer rs = CrossRegionGuard.crossRegionTargetOrNull(level, pos);
        if (rs == null) {
            return;     // in-region (or not a claimed worker) — run inline as vanilla
        }
        // Rare cross-region path: snapshot the (reused) pos, queue a fresh-read replay.
        BlockPos target = pos.immutable();
        rs.deferCrossRegion(() -> NeighborUpdater.executeUpdate(
                level, level.getBlockState(target), target, neighborBlock, orientation, movedByPiston), target);
        ci.cancel();
    }
}
