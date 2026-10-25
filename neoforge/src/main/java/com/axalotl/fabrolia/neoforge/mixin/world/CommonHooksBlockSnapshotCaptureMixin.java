package com.axalotl.fabrolia.neoforge.mixin.world;

import com.axalotl.fabrolia.neoforge.world.BlockSnapshotCapture;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;

/** The item use that raises, reads and clears the snapshot capture
 *  ({@link BlockSnapshotCapture}) does so for its own thread only. */
@Mixin(CommonHooks.class)
public abstract class CommonHooksBlockSnapshotCaptureMixin {

    @Unique
    private static final String PLACE = "onPlaceItemIntoWorld("
            + "Lnet/minecraft/world/item/context/UseOnContext;)"
            + "Lnet/minecraft/world/InteractionResult;";

    @Redirect(method = PLACE,
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;captureBlockSnapshots:Z",
                    opcode = Opcodes.PUTFIELD),
            require = 2, allow = 2)
    private static void fabrolia$setThreadCapturing(Level level, boolean capturing) {
        BlockSnapshotCapture.of(level).capturing = capturing;
    }

    @Redirect(method = PLACE,
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/level/Level;capturedBlockSnapshots:Ljava/util/ArrayList;",
                    opcode = Opcodes.GETFIELD),
            require = 3, allow = 3)
    private static ArrayList<BlockSnapshot> fabrolia$threadSnapshots(Level level) {
        return BlockSnapshotCapture.of(level).captured;
    }

    @Redirect(method = PLACE,
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;restoringBlockSnapshots:Z",
                    opcode = Opcodes.PUTFIELD),
            require = 2, allow = 2)
    private static void fabrolia$setThreadRestoring(Level level, boolean restoring) {
        BlockSnapshotCapture.of(level).restoring = restoring;
    }
}
