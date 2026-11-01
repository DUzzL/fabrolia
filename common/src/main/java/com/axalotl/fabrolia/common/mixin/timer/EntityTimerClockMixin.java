package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.TimerClocks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Timer clock fix (2026-11-01) — the defect write-up is on {@link TimerClocks}.
 * An entity's absolute timers are in the clock of its basis: the clock of the
 * thread that created it, W after a load, and the clock of each tick
 * ({@code commonTick} opens every entity tick). Saves write them in W.
 */
@Mixin(Entity.class)
public abstract class EntityTimerClockMixin implements TimerClocks.Stamped {

    @Unique
    private IslandRegistry.PIsland fabrolia$timerBasis = TimerClocks.currentBasis();

    @Override
    public IslandRegistry.PIsland fabrolia$timerBasis() {
        return this.fabrolia$timerBasis;
    }

    @Override
    public void fabrolia$setTimerBasis(IslandRegistry.PIsland basis) {
        this.fabrolia$timerBasis = basis;
    }

    @Override
    public void fabrolia$shiftTimers(long delta, boolean clamp) {
        TimerClocks.shiftEntity((Entity) (Object) this, delta, clamp);
    }

    @Inject(method = "commonTick", at = @At("HEAD"))
    private void fabrolia$timersOnTickClock(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player) && self.level() instanceof ServerLevel level) {
            TimerClocks.ensureCurrent(this, level);
        }
    }

    /** Loaded values are in W, the clock saves use. */
    @Inject(method = "load", at = @At("TAIL"))
    private void fabrolia$loadedTimersAreWall(ValueInput input, CallbackInfo ci) {
        this.fabrolia$timerBasis = null;
    }

    /** Write the timers in W, then restore them: the owner (or a claim holder)
     *  saves, so the island never sees the shifted values. */
    @WrapMethod(method = "saveWithoutId")
    private void fabrolia$saveTimersInWall(ValueOutput output, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        long d = !(self instanceof Player) && self.level() instanceof ServerLevel level
                ? TimerClocks.toWallForSave(this, level) : 0L;
        if (d == 0L) {
            original.call(output);
            return;
        }
        TimerClocks.shiftEntity(self, d, false);
        try {
            original.call(output);
        } finally {
            TimerClocks.shiftEntity(self, -d, false);
        }
    }
}
