package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.RedstoneTorchHistory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Vanilla's per-level list cannot compare independent island clock stamps.
 * The history owns atomic purge/count and clock rebasing; block behavior stays vanilla. */
@Mixin(RedstoneTorchBlock.class)
public abstract class RedstoneTorchBlockMixin {
    @Shadow
    protected abstract boolean hasNeighborSignal(Level level, BlockPos pos, BlockState state);

    @Shadow
    private static boolean isToggledTooFrequently(Level level, BlockPos pos, boolean add) {
        throw new AssertionError();
    }

    @WrapMethod(method = "tick")
    private void fabrolia$atomicPurgeTick(BlockState state, ServerLevel level, BlockPos pos,
                                       RandomSource random, Operation<Void> original) {
        boolean neighborSignal = this.hasNeighborSignal(level, pos, state);
        RedstoneTorchHistory.purge(level);
        if (state.getValue(RedstoneTorchBlock.LIT)) {
            if (neighborSignal) {
                level.setBlock(pos, state.setValue(RedstoneTorchBlock.LIT, false), 3);
                if (isToggledTooFrequently(level, pos, true)) {
                    level.levelEvent(1502, pos, 0);
                    level.scheduleTick(pos, level.getBlockState(pos).getBlock(), 160);
                }
            }
        } else if (!neighborSignal && !isToggledTooFrequently(level, pos, false)) {
            level.setBlock(pos, state.setValue(RedstoneTorchBlock.LIT, true), 3);
        }
    }

    @WrapMethod(method = "isToggledTooFrequently")
    private static boolean fabrolia$ownerClockToggleCheck(Level level, BlockPos pos, boolean add,
                                                     Operation<Boolean> original) {
        return RedstoneTorchHistory.toggled(level, pos, add);
    }
}
