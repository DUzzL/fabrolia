package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.utils.FabroliaPoiLoadGate;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * A new C2ME disk residency supersedes an older deferred POI unload. This is
 * deliberately the installed C2ME lifecycle callsite, not SectionStorage.prefetch:
 * portal searches also prefetch, but cannot discharge a one-shot unload request.
 */
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDisk", remap = false)
public abstract class FabroliaC2mePoiResidencyMixin {
    @WrapOperation(
            method = "lambda$invokeInitialChunkRead$3",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;prefetch(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"),
            require = 1)
    private static CompletableFuture<?> fabrolia$beginPoiResidency(
            PoiManager manager, ChunkPos chunk, Operation<CompletableFuture<?>> original) {
        ((FabroliaPoiLoadGate) manager).fabrolia$beginPoiResidency(chunk.pack());
        return original.call(manager, chunk);
    }
}
