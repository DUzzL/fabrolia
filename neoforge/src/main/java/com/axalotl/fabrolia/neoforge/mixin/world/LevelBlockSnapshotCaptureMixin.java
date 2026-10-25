package com.axalotl.fabrolia.neoforge.mixin.world;

import com.axalotl.fabrolia.neoforge.world.BlockSnapshotCapture;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;

/** Per-thread block-snapshot capture of this level ({@link BlockSnapshotCapture}):
 *  the state itself and NeoForge's reads of it in {@code setBlock}. */
@Mixin(Level.class)
public abstract class LevelBlockSnapshotCaptureMixin implements BlockSnapshotCapture.Holder {

    @Unique
    private final ThreadLocal<BlockSnapshotCapture> fabrolia$snapshotCapture =
            ThreadLocal.withInitial(BlockSnapshotCapture::new);

    @Override
    public BlockSnapshotCapture fabrolia$blockSnapshotCapture() {
        return this.fabrolia$snapshotCapture.get();
    }

    @Redirect(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;captureBlockSnapshots:Z",
                    opcode = Opcodes.GETFIELD),
            require = 1, allow = 1)
    private boolean fabrolia$threadCapturing(Level self) {
        return BlockSnapshotCapture.of(self).capturing;
    }

    @Redirect(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/level/Level;capturedBlockSnapshots:Ljava/util/ArrayList;",
                    opcode = Opcodes.GETFIELD),
            require = 2, allow = 2)
    private ArrayList<BlockSnapshot> fabrolia$threadSnapshots(Level self) {
        return BlockSnapshotCapture.of(self).captured;
    }
}
