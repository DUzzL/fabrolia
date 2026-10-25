package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.RedstoneWireBlock;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code RedstoneWireBlock#getBlockSignal} clears the singleton's
 * {@code shouldSignal} field around {@code getBestNeighborSignal}, and
 * {@code getSignal}/{@code getDirectSignal}/{@code isSignalSource} read it. Islands
 * evaluate redstone at once, so one island's wire evaluation made every other
 * island's wire report no power (pistons retracting, repeaters switching off),
 * or ended another's suppression early (self-holding wires) (audit B-04). The
 * flag is per thread. Lithium's block.redstone_wire cancels getBlockSignal at
 * its head, so with it the flag is never cleared and reads stay true.
 */
@Mixin(RedstoneWireBlock.class)
public abstract class RedstoneWireSignalMixin {

    @Unique
    private static final ThreadLocal<boolean[]> fabrolia$wireSignalSuppressed =
            ThreadLocal.withInitial(() -> new boolean[1]);

    @WrapOperation(method = {"getSignal", "getDirectSignal", "isSignalSource"},
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/level/block/RedstoneWireBlock;shouldSignal:Z",
                    opcode = Opcodes.GETFIELD),
            require = 3)
    private boolean fabrolia$readThreadSignal(RedstoneWireBlock self, Operation<Boolean> original) {
        return !fabrolia$wireSignalSuppressed.get()[0];
    }

    @WrapOperation(method = "getBlockSignal",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/level/block/RedstoneWireBlock;shouldSignal:Z",
                    opcode = Opcodes.PUTFIELD),
            require = 2)
    private void fabrolia$writeThreadSignal(RedstoneWireBlock self, boolean value, Operation<Void> original) {
        fabrolia$wireSignalSuppressed.get()[0] = !value;
    }
}
