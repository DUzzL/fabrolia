package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Guards the three mutator paths on a {@link ChunkHolder} that can race when random ticks
 * fire on the fabrolia pool: a block update may originate from one chunk's tick and land in a
 * neighbour chunk's holder (fire spread, water flow, falling block landing, …), so two
 * workers can hit {@code blockChanged} on the SAME holder concurrently. A per-instance
 * monitor narrows the contention to that holder — previous code used a single static
 * lock that serialised every block change across the entire server.
 *
 * <p>All three vanilla methods mutate only this-instance fields ({@code changedBlocksPerSection},
 * {@code hasChangedSections}, the two light filter {@link java.util.BitSet}s, and the
 * broadcast bookkeeping), so a per-instance lock is correctness-equivalent to the old
 * global lock while letting N workers update N different holders in parallel.
 */
@Mixin(value = ChunkHolder.class, priority = 1500)
public class ChunkHolderMixin {

    @WrapMethod(method = "broadcastChanges")
    private void wrapBroadcastChanges(LevelChunk chunk, Operation<Void> original) {
        synchronized (this) {
            original.call(chunk);
        }
    }

    @WrapMethod(method = "blockChanged")
    private boolean wrapBlockChanged(BlockPos pos, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(pos);
        }
    }

    @WrapMethod(method = "sectionLightChanged")
    private boolean wrapSectionLightChanged(LightLayer layer, int chunkY, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(layer, chunkY);
        }
    }
}