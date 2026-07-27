package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.VibrationDomains;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.vibrations.VibrationInfo;
import net.minecraft.world.level.gameevent.vibrations.VibrationSelector;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sculk deafness fix (2026-07-27, operator field report) — see the defect
 * write-up on {@link VibrationDomains}.
 *
 * <p>Vanilla stamps a vibration candidate with the EMITTING thread's
 * {@code getGameTime()}; under async islands that can be a foreign clock
 * (main's wall counter into an island sensor being the player case), which
 * both defers the candidate past the listener clock's reach and suppresses
 * every later candidate carrying a different stamp. The wrap replaces the
 * stamp with the LISTENER's own domain time, resolved from the listener
 * position ({@code dest}) — a strict no-op whenever emitter and listener
 * share a clock (same island, main→global-lane, async off), and a
 * boundary translation (route, never drop) when they don't.
 *
 * <p>{@code scheduleVibration} is the single choke point: both
 * {@code Listener.handleGameEvent} (in-range dispatch) and
 * {@code forceScheduleVibration} funnel through it.
 */
@Mixin(VibrationSystem.Listener.class)
public abstract class VibrationListenerClockMixin {

    @WrapOperation(method = "scheduleVibration",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/gameevent/vibrations/VibrationSelector;addCandidate(Lnet/minecraft/world/level/gameevent/vibrations/VibrationInfo;J)V"))
    private void fabrolia$stampInListenerDomain(VibrationSelector selector, VibrationInfo info,
                                                long emitterStamp, Operation<Void> original,
                                                @Local(argsOnly = true) ServerLevel level,
                                                @Local(argsOnly = true, ordinal = 1) Vec3 dest) {
        original.call(selector, info, VibrationDomains.listenerStamp(level, dest));
    }
}
