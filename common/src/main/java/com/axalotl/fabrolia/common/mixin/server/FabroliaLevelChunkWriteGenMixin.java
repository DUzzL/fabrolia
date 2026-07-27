package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.FabroliaWriteGen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Write witness for the delivery fence: one volatile bump per block/BE
 *  mutation, on the object already in hand — no lookups, no allocation. */
@Mixin(LevelChunk.class)
public abstract class FabroliaLevelChunkWriteGenMixin implements FabroliaWriteGen {

    @Unique private volatile long fabrolia$writeGen;

    @Override
    public long fabrolia$writeGen() {
        return fabrolia$writeGen;
    }

    @Override
    public void fabrolia$bumpWriteGen() {
        fabrolia$writeGen++;
    }

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"))
    private void fabrolia$onSetBlock(BlockPos pos, BlockState state, int flags,
                                     CallbackInfoReturnable<BlockState> cir) {
        fabrolia$writeGen++;
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"))
    private void fabrolia$onSetBe(BlockEntity be, CallbackInfo ci) {
        fabrolia$writeGen++;
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void fabrolia$onRemoveBe(BlockPos pos, CallbackInfo ci) {
        fabrolia$writeGen++;
    }
}
