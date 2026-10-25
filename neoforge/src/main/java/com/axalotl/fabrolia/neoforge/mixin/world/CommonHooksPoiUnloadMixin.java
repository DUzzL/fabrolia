package com.axalotl.fabrolia.neoforge.mixin.world;

import com.axalotl.fabrolia.common.utils.FabroliaPoiLoadGate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;

/**
 * NeoForge unloads a chunk's POI column ({@code ChunkMap#scheduleUnload} →
 * {@code CommonHooks#onChunkUnload}: {@code flush}, then {@code SectionStorage#remove})
 * outside Fabrolia's POI column fence. A worker unpacking or mutating the same
 * column could then publish sections without their {@code loadedChunks} entry,
 * and the next load overwrote the live state from disk (lost tickets, a bed
 * handed out twice). Route the whole unload through the fence, as the C2ME
 * unload ({@code PoiUnloadGuardMixin}) already is; a conflicting unload stays
 * pending and its body, flush included, runs again later.
 */
@Mixin(CommonHooks.class)
public abstract class CommonHooksPoiUnloadMixin {

    @WrapMethod(method = "onChunkUnload(Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;)V")
    private static void fabrolia$fencePoiUnload(PoiManager poiManager, ChunkAccess chunk,
                                                Operation<Void> original) {
        ((FabroliaPoiLoadGate) poiManager).fabrolia$unloadPoiColumn(
                chunk.getPos().pack(), () -> original.call(poiManager, chunk));
    }
}
