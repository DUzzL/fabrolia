package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Globally phase-coherent daylight-detector updates (2026-07-18 wireless-
 * redstone compatibility). Vanilla gates the detector refresh on
 * {@code getGameTime() % 20 == 0}; under async islands that getter is the
 * island L-clock, so every island stepped the (global!) daytime power ramp at
 * its own offset — up to ±19 ticks between healthy same-rate islands, seconds
 * under rate overrides. 2No2Name-style wireless redstone uses the detector
 * edge as the shared phase reference between stations, so the reference
 * itself must fire on the same WALL tick everywhere. This wrap re-gates the
 * refresh on wall-clock epoch crossings (floor(W/20) changed since this
 * detector's last update): every detector on every island — any rate, any
 * phase — refreshes at the same wall boundaries with the same global
 * daytime/weather inputs, WITHOUT synchronizing any island. Edge-triggered
 * rather than exact-match because a slow island's BE ticks can skip over the
 * boundary tick entirely; in sync mode (context clock == wall clock, ticked
 * every tick) this is byte-equivalent to vanilla's modulo. Decorative
 * detectors on rated islands also stop lagging their dusk/dawn switches.
 * The machine-phase half (hopper/item cadence, L ≡ W) is the opt-in
 * {@code /fabrolia regions coherence} pin — see TemporalControl.
 */
@Mixin(DaylightDetectorBlock.class)
public abstract class DaylightDetectorBlockMixin {

    @WrapOperation(method = "tickEntity",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private static long fabrolia$wallEpochGate(Level level, Operation<Long> original,
            Level lvl, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.entity.DaylightDetectorBlockEntity be) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) {
            return original.call(level); // client-side ticker: vanilla gate
        }
        // wallTime unwraps DerivedLevelData, so NETHER/END detectors get the
        // wall-epoch reference too (the earlier RawGameTime-instanceof
        // fallback silently left them on the island clock).
        long epoch = Math.floorDiv(com.axalotl.fabrolia.common.regions.IslandClock.wallTime(sl), 20L);
        var holder = (com.axalotl.fabrolia.common.regions.DetectorEpochHolder) be;
        if (holder.fabrolia$lastDetectorEpoch() == epoch) {
            return 1L; // not due: 1 % 20 != 0 -> vanilla gate skips
        }
        holder.fabrolia$setDetectorEpoch(epoch);
        return 0L; // due: 0 % 20 == 0 -> vanilla gate fires updateSignalStrength
    }

}
