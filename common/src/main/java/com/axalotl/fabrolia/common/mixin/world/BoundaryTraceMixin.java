package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.BoundaryTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boundary event-order tracer, ServerLevel hooks (tasks #12/#13): block-event
 * enqueue/execution + scheduled block/fluid tick execution inside the
 * {@code -Dfabrolia.debug.boundaryTrace} window. See {@link BoundaryTrace}.
 */
@Mixin(ServerLevel.class)
public abstract class BoundaryTraceMixin {

    @Inject(method = "blockEvent", at = @At("HEAD"))
    private void fabrolia$traceEnqueue(BlockPos pos, Block block, int type, int data, CallbackInfo ci) {
        if (BoundaryTrace.WINDOW != null && BoundaryTrace.in(pos)) {
            BoundaryTrace.log((ServerLevel) (Object) this, "ENQ", pos,
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block) + " a=" + type + " b=" + data);
        }
    }

    @Inject(method = "doBlockEvent", at = @At("HEAD"))
    private void fabrolia$traceBlockEvent(BlockEventData ev, CallbackInfoReturnable<Boolean> cir) {
        if (BoundaryTrace.WINDOW != null && BoundaryTrace.in(ev.pos())) {
            BoundaryTrace.log((ServerLevel) (Object) this, "EVT", ev.pos(),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ev.block()) + " a=" + ev.paramA() + " b=" + ev.paramB());
        }
    }

    @Inject(method = "tickBlock", at = @At("HEAD"))
    private void fabrolia$traceScheduledTick(BlockPos pos, Block block, CallbackInfo ci) {
        if (BoundaryTrace.WINDOW != null && BoundaryTrace.in(pos)) {
            BoundaryTrace.log((ServerLevel) (Object) this, "TCK", pos,
                    String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block)));
        }
    }

    @Inject(method = "tickFluid", at = @At("HEAD"))
    private void fabrolia$traceFluidTick(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        if (BoundaryTrace.WINDOW != null && BoundaryTrace.in(pos)) {
            BoundaryTrace.log((ServerLevel) (Object) this, "FLT", pos, "fluid");
        }
    }
}
