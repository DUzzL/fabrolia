package com.axalotl.fabrolia.common.mixin.utils;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Deferred-landing verification for gravity blocks (2026-07-15 source-loss
 * fix). A falling block's landing {@code setBlock} can be optimistically
 * deferred by the cross-region guard; vanilla's failure fallback (the item
 * drop) is then skipped on trust. Verify after the owner-timeline replay and,
 * if the write was lost, pop the block item so the contraption's matter is
 * conserved.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockTransferMixin {

    /** Source-side landing write inside tick(): verify deferred placements. */
    @WrapOperation(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean fabrolia$sourcePlacement(Level level, BlockPos pos, BlockState state, int flags,
                                             Operation<Boolean> original) {
        boolean ok = original.call(level, pos, state, flags);
        if (ok && com.axalotl.fabrolia.common.regions.CrossRegionGuard.LAST_SETBLOCK_DEFERRED.get()
                && level instanceof net.minecraft.server.level.ServerLevel sl) {
            // The landing write was optimistic-true DEFERRED: vanilla's
            // failure fallback (item drop) was skipped on trust. Verify after
            // the owner-timeline replay; a lost write pops the block item so
            // the contraption's matter is conserved (2026-07-15 source loss).
            var rs = com.axalotl.fabrolia.common.regions.RegionScheduler.forLevel(sl);
            if (rs != null) {
                final BlockPos vpos = pos.immutable();
                final BlockState vstate = state;
                rs.deferCrossRegion(() -> {
                    if (sl.getBlockState(vpos) != vstate) {
                        net.minecraft.world.level.block.Block.popResource(sl, vpos,
                                new net.minecraft.world.item.ItemStack(vstate.getBlock()));
                    }
                }, vpos);
            }
        }
        return ok;
    }
}
