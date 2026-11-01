package com.axalotl.fabrolia.common.mixin.timer;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.TimerClocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Timer clock fix (2026-11-01, {@link TimerClocks}): {@code cooldown_ends_at}
 * and {@code next_mob_spawns_at} follow the clock the spawner ticks in; saves
 * write W ({@code TrialSpawnerBlockEntityTimerClockMixin}). {@code tickServer} is
 * the only reader.
 */
@Mixin(TrialSpawner.class)
public abstract class TrialSpawnerTimerClockMixin implements TimerClocks.Stamped {

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
        TimerClocks.shiftTrialSpawner((TrialSpawner) (Object) this, delta, clamp);
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void fabrolia$timersOnTickClock(ServerLevel level, BlockPos pos, boolean ominous, CallbackInfo ci) {
        TimerClocks.ensureCurrent(this, level);
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void fabrolia$loadedTimersAreWall(ValueInput input, CallbackInfo ci) {
        this.fabrolia$timerBasis = null;
    }
}
