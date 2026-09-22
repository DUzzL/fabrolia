package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.FabroliaWriteGen;
import com.axalotl.fabrolia.common.regions.ChunkWriteEnqueueFence;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact LevelChunk mutation side of the full-snapshot enqueue commit fence. */
@Mixin(LevelChunk.class)
public abstract class FabroliaLevelChunkWriteGenMixin implements FabroliaWriteGen {

    @Unique private ChunkWriteEnqueueFence fabrolia$writeFence;

    /**
     * Initialize in the one canonical constructor which every 26.2 overload
     * delegates to.  In particular, the ServerLevel/ProtoChunk overload calls
     * {@code markUnsaved} in its own body after this constructor returns, so a
     * generic/bare constructor selector is too late unless it also reaches this
     * exact root.  Never replace this with a merged field initializer or lazy
     * accessor allocation: the final full-modpack export verifies constructor
     * dominance directly.
     */
    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/world/level/ChunkPos;"
            + "Lnet/minecraft/world/level/chunk/UpgradeData;"
            + "Lnet/minecraft/world/ticks/LevelChunkTicks;"
            + "Lnet/minecraft/world/ticks/LevelChunkTicks;J"
            + "[Lnet/minecraft/world/level/chunk/LevelChunkSection;"
            + "Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;"
            + "Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V",
            at = @At("RETURN"), require = 1, allow = 1)
    private void fabrolia$initializeWriteFence(CallbackInfo ci) {
        if (fabrolia$writeFence == null) {
            fabrolia$writeFence = new ChunkWriteEnqueueFence();
        }
    }

    @Unique
    private ChunkWriteEnqueueFence fabrolia$writeFence() {
        ChunkWriteEnqueueFence fence = fabrolia$writeFence;
        if (fence == null) {
            throw new IllegalStateException("LevelChunk write fence is not initialized");
        }
        return fence;
    }

    @Override
    public long fabrolia$writeGen() {
        return fabrolia$writeFence().generation();
    }

    @Override
    public void fabrolia$bumpWriteGen() {
        fabrolia$writeFence().bump();
    }

    @Override
    public boolean fabrolia$tryReserveFullChunkEnqueue(long expectedGeneration) {
        return fabrolia$writeFence().tryReserveEnqueue(expectedGeneration);
    }

    @Override
    public void fabrolia$releaseFullChunkEnqueue(long expectedGeneration) {
        fabrolia$writeFence().releaseEnqueue(expectedGeneration);
    }

    @WrapMethod(method = "setBlockState(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)"
            + "Lnet/minecraft/world/level/block/state/BlockState;")
    private BlockState fabrolia$fenceSetBlockState(BlockPos pos, BlockState state,
                                                    int flags,
                                                    Operation<BlockState> original) {
        ChunkWriteEnqueueFence fence = fabrolia$writeFence();
        fence.markWriteStart();
        return original.call(pos, state, flags);
    }

    @WrapMethod(method = "setBlockEntity("
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;)V")
    private void fabrolia$fenceSetBlockEntity(BlockEntity blockEntity,
                                               Operation<Void> original) {
        ChunkWriteEnqueueFence fence = fabrolia$writeFence();
        fence.markWriteStart();
        original.call(blockEntity);
    }

    @WrapMethod(method = "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V")
    private void fabrolia$fenceRemoveBlockEntity(BlockPos pos,
                                                  Operation<Void> original) {
        ChunkWriteEnqueueFence fence = fabrolia$writeFence();
        fence.markWriteStart();
        original.call(pos);
    }

    /**
     * Standard block-entity data changes reach LevelChunk through
     * BlockEntity.setChanged -> Level.blockEntityChanged -> markUnsaved before
     * their update packet is published. Cover that non-structural NBT writer in
     * addition to set/removeBlockEntity.
     */
    @WrapMethod(method = "markUnsaved()V")
    private void fabrolia$fenceMarkUnsaved(Operation<Void> original) {
        ChunkWriteEnqueueFence fence = fabrolia$writeFence();
        fence.markWriteStart();
        original.call();
    }
}
