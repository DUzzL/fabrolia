package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.BoundaryTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Boundary tracer, moving-piston lifecycle (task #13): one line per
 *  technical-BE tick inside the window — start, progress, completion order
 *  relative to the surrounding events. */
@Mixin(PistonMovingBlockEntity.class)
public abstract class BoundaryTraceMovingPistonMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private static void fabrolia$traceMpb(Level level, BlockPos pos, BlockState state,
                                          PistonMovingBlockEntity be, CallbackInfo ci) {
        if (BoundaryTrace.WINDOW != null && BoundaryTrace.in(pos)
                && level instanceof ServerLevel sl) {
            BoundaryTrace.log(sl, "MPB", pos, "state=" + state.getBlock()
                    + " extending=" + be.isExtending() + " progress=" + be.getProgress(0.0F));
        }
    }
}
