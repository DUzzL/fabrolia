package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.BoundaryTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boundary tracer, piston power side (task #13 QC stall): logs the result of
 * every {@link PistonBaseBlock#getNeighborSignal} evaluation — the check that
 * includes the quasi-connectivity read of {@code pos.above()} — for pistons
 * inside the trace window. Shows exactly what power the piston saw and when,
 * in the same ordered stream as the block-event and neighbor-update lines.
 */
@Mixin(PistonBaseBlock.class)
public abstract class BoundaryTracePistonMixin {

    @Inject(method = "getNeighborSignal", at = @At("RETURN"))
    private void fabrolia$traceQc(SignalGetter signal, BlockPos pos, Direction dir,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (BoundaryTrace.WINDOW != null && BoundaryTrace.in(pos)
                && signal instanceof ServerLevel sl) {
            BoundaryTrace.log(sl, "QC", pos, "facing=" + dir + " powered=" + cir.getReturnValue());
        }
    }
}
