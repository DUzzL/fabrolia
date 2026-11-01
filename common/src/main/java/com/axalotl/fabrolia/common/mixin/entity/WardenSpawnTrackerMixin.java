package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.WardenWarningClock;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.warden.WardenSpawnTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Warden switch fix (2026-11-01) — the defect write-up is on
 * {@link WardenWarningClock}.
 *
 * <ol>
 *   <li><b>Arming clock.</b> {@code tryWarn} runs in the shrieker's venue and
 *       may warn a player who ticks on another island (the owner of a
 *       projectile that reached the shrieker). The wrap reads the shrieker's
 *       clock once; an arm inside that warning records it, and
 *       {@code onCooldown} measures the cooldown in it for every reader.
 *       Outside a warning, or with async islands off, the record is cleared
 *       and the vanilla counter decides.</li>
 *   <li><b>Cross-island access.</b> That warning writes the foreign player's
 *       tracker while the player's own island ticks it. Each tracker operation
 *       is atomic under the tracker's monitor; {@code copyData} takes only the
 *       target's monitor (no nested locks), so a copy can read a source that
 *       its owner ticks at the same moment, one tick apart at most. Routing
 *       the write to the owner instead would delay the answer the shrieker
 *       needs in this tick.</li>
 * </ol>
 */
@Mixin(WardenSpawnTracker.class)
public abstract class WardenSpawnTrackerMixin implements WardenWarningClock.Tracked {

    @Shadow private int cooldownTicks;
    @Shadow private int ticksSinceLastWarning;

    /** The clock that armed {@link #cooldownTicks}; null: the counter decides. */
    @Unique
    private volatile WardenWarningClock.Arm fabrolia$arm;

    @Override
    public WardenWarningClock.Arm fabrolia$warningArm() {
        return this.fabrolia$arm;
    }

    /** Callers hold this tracker's monitor, which also orders a re-arm before
     *  the arming island completes that tick: a reader that sees the tick
     *  completed sees the new arm. */
    @Unique
    private boolean fabrolia$onCooldown() {
        WardenWarningClock.Arm reader = WardenWarningClock.currentWarning();
        return WardenWarningClock.onCooldown(WardenWarningClock.elapsed(this.fabrolia$arm, reader),
                this.cooldownTicks, this.ticksSinceLastWarning);
    }

    @WrapMethod(method = "tryWarn")
    private static OptionalInt fabrolia$warnInShriekerClock(ServerLevel level, BlockPos pos, ServerPlayer triggerPlayer,
                                                            Operation<OptionalInt> original) {
        WardenWarningClock.Arm previous = WardenWarningClock.beginWarning(WardenWarningClock.clockAt(level, pos));
        try {
            return original.call(level, pos, triggerPlayer);
        } finally {
            WardenWarningClock.endWarning(previous);
        }
    }

    @WrapMethod(method = "onCooldown")
    private synchronized boolean fabrolia$onCooldownInArmingClock(Operation<Boolean> original) {
        return this.fabrolia$onCooldown();
    }

    @WrapMethod(method = "increaseWarningLevel")
    private synchronized void fabrolia$recordArmingClock(Operation<Void> original) {
        // The check vanilla makes first (through the wrapped onCooldown): only
        // an increase that is off cooldown arms it.
        boolean arms = !this.fabrolia$onCooldown();
        original.call();
        if (arms) {
            this.fabrolia$arm = WardenWarningClock.currentWarning();
        }
    }

    @WrapMethod(method = "tick")
    private synchronized void fabrolia$tickAtomically(Operation<Void> original) {
        original.call();
    }

    @WrapMethod(method = "reset")
    private synchronized void fabrolia$resetAtomically(Operation<Void> original) {
        original.call();
        this.fabrolia$arm = null;
    }

    @WrapMethod(method = "setWarningLevel")
    private synchronized void fabrolia$setWarningLevelAtomically(int warningLevel, Operation<Void> original) {
        original.call(warningLevel);
    }

    @WrapMethod(method = "copyData")
    private synchronized void fabrolia$copyArmingClock(WardenSpawnTracker copyFrom, Operation<Void> original) {
        original.call(copyFrom);
        this.fabrolia$arm = ((WardenWarningClock.Tracked) copyFrom).fabrolia$warningArm();
    }
}
