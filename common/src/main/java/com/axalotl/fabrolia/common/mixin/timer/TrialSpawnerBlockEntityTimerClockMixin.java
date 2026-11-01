package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.TimerClocks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Timer clock fix (2026-11-01, {@link TimerClocks}): the spawner's timers are
 * written in W, then restored; the owner (or a claim holder) saves.
 */
@Mixin(TrialSpawnerBlockEntity.class)
public abstract class TrialSpawnerBlockEntityTimerClockMixin {

    @WrapMethod(method = "saveAdditional")
    private void fabrolia$saveTimersInWall(ValueOutput output, Operation<Void> original) {
        TrialSpawnerBlockEntity self = (TrialSpawnerBlockEntity) (Object) this;
        TimerClocks.Stamped spawner = (TimerClocks.Stamped) (Object) self.getTrialSpawner();
        long d = self.getLevel() instanceof ServerLevel level ? TimerClocks.toWallForSave(spawner, level) : 0L;
        if (d == 0L) {
            original.call(output);
            return;
        }
        spawner.fabrolia$shiftTimers(d, false);
        try {
            original.call(output);
        } finally {
            spawner.fabrolia$shiftTimers(-d, false);
        }
    }
}
