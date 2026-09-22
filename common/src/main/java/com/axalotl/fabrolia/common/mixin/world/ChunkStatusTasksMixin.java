package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.WorldGenEntityConstruction;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    @WrapMethod(method = {"generateFeatures", "generateSpawn"})
    private static CompletableFuture<ChunkAccess> fabrolia$constructGenerationEntities(
            WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
        WorldGenEntityConstruction.enter(context.level());
        try {
            // Vanilla performs decoration/spawning synchronously before returning its
            // completed future. Never propagate construction authority to continuations.
            return original.call(context, step, cache, chunk);
        } finally {
            WorldGenEntityConstruction.exit();
        }
    }
}
