package com.axalotl.fabrolia.neoforge.mixin.world;

import com.axalotl.fabrolia.neoforge.world.BlockSnapshotCapture;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code setBlockState} skips {@code onPlace} only while this thread captures
 *  snapshots ({@link BlockSnapshotCapture}), not while any thread of the level does. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkBlockSnapshotCaptureMixin {

    @Redirect(method = "setBlockState(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)"
            + "Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;captureBlockSnapshots:Z",
                    opcode = Opcodes.GETFIELD),
            require = 1, allow = 1)
    private boolean fabrolia$threadCapturing(Level level) {
        return BlockSnapshotCapture.of(level).capturing;
    }
}
