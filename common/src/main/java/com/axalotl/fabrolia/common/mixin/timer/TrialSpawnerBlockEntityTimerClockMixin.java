package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.TimerClocks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Timer clock fix (2026-11-01, {@link TimerClocks}): the spawner's timers are
 * written in W, then restored; the owner (or a claim holder) saves.
 */
@Mixin(TrialSpawnerBlockEntity.class)
public abstract class TrialSpawnerBlockEntityTimerClockMixin {

    /** A load leaves the timers in W ({@code TrialSpawnerTimerClockMixin}). A
     *  spawner already in its level (a {@code /data} write; chunk loads set the
     *  level afterwards) goes straight back to its clock
     *  ({@link TimerClocks#reloadedInPlace}). */
    @WrapMethod(method = "loadAdditional")
    private void fabrolia$reloadKeepsClock(ValueInput input, Operation<Void> original) {
        TrialSpawnerBlockEntity self = (TrialSpawnerBlockEntity) (Object) this;
        TimerClocks.Stamped spawner = (TimerClocks.Stamped) (Object) self.getTrialSpawner();
        IslandRegistry.PIsland previous = spawner.fabrolia$timerBasis();
        original.call(input);
        if (self.getLevel() instanceof ServerLevel level) {
            TimerClocks.reloadedInPlace(spawner, previous, level);
        }
    }

    @WrapMethod(method = "saveAdditional")
    private void fabrolia$saveTimersInWall(ValueOutput output, Operation<Void> original) {
        TrialSpawnerBlockEntity self = (TrialSpawnerBlockEntity) (Object) this;
        TimerClocks.Stamped spawner = (TimerClocks.Stamped) (Object) self.getTrialSpawner();
        long d = self.getLevel() instanceof ServerLevel level ? TimerClocks.toWallForSave(spawner, level) : 0L;
        TimerClocks.saveInWallTime(spawner, d, output, original);
    }
}
