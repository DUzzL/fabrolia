package com.axalotl.fabrolia.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import com.axalotl.fabrolia.common.regions.FabroliaLocks;

@Mixin(CopperGolem.class)
public class CopperGolemMixin {
    @Unique
    private static final Object lock = new Object();

    @Shadow
    private BlockPos openedChestPos;

    /**
     * CROSS-THREAD SNAPSHOT (2026-08-09 production NPE): vanilla reads
     * {@code openedChestPos} three times inside {@code hasContainerOpen}
     * (null-guard, getBlockState argument, equals). A concurrent
     * {@code clearOpenedChestPos} from ANOTHER region's lane can null it
     * between the guard and the equals — {@code tickLockOr} scopes the
     * monitor per region, so cross-region lanes do not exclude each other,
     * and vanilla single-threaded can never see the interleave. Answer from
     * ONE atomic read of the field (taken under the lane lock for
     * same-region exclusivity): the two possible answers (still-open /
     * just-closed) are exactly the two answers vanilla could give on
     * consecutive ticks, so behaviour is unchanged and the NPE is gone.
     */
    @WrapMethod(method = "hasContainerOpen")
    private boolean hasContainerOpen(ContainerOpenersCounter container, BlockPos blockPos, Operation<Boolean> original) {
        BlockPos open;
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            open = this.openedChestPos;
        }
        if (open == null) {
            return false;
        }
        CopperGolem self = (CopperGolem) (Object) this;
        net.minecraft.world.level.block.state.BlockState state = self.level().getBlockState(open);
        if (open.equals(blockPos)) {
            return true;
        }
        return state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                        != net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                && net.minecraft.world.level.block.ChestBlock.getConnectedBlockPos(open, state).equals(blockPos);
    }

    @WrapMethod(method = "setOpenedChestPos")
    private void setOpenedChestPos(BlockPos openedChestPos, Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            original.call(openedChestPos);
        }
    }

    @WrapMethod(method = "clearOpenedChestPos")
    private void clearOpenedChestPos(Operation<Void> original) {
        synchronized (FabroliaLocks.tickLockOr(lock)) {
            original.call();
        }
    }
}