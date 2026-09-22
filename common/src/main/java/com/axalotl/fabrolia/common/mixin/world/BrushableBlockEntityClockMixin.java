package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.BlockEntitySleepDomains;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Brushing and its scheduled reset must stamp and compare on the block owner's clock. */
@Mixin(BrushableBlockEntity.class)
public abstract class BrushableBlockEntityClockMixin extends BlockEntity {
    @Shadow private long coolDownEndsAtTick;

    protected BrushableBlockEntityClockMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @ModifyVariable(method = "brush", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long fabrolia$ownerBrushTime(long suppliedTime) {
        if (!(getLevel() instanceof ServerLevel level)) return suppliedTime;
        long now = BlockEntitySleepDomains.ownerNow(level, getBlockPos());
        // A brush cooldown is ten owner ticks. A speculative unit can retreat by
        // one on HOLD; preserve that too. A larger future residue belongs to an
        // older clock: brush refreshes its reset even when cooldown rejects, so
        // holding the brush would otherwise prevent that stale cooldown clearing.
        if (coolDownEndsAtTick - now > 11L) coolDownEndsAtTick = now;
        return now;
    }
}
