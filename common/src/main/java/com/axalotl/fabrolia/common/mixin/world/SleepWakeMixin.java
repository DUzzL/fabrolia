package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.FailStops;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Production audit (2026-07-12) — sleep-skip wake mutates every sleeping
 * ServerPlayer (stopSleepInBed → pose/position writes) from MAIN while the
 * player's island unit may be mid-tick under the deadline drive: a real
 * entity-state data race. Quiesce every island for the wake — it fires once
 * per skipped night, so the fence cost is irrelevant; correctness class is
 * world/player integrity (priority 1). No-op when async is off (reentrant
 * via mainHoldsAllClaims like every other main-side fence).
 */
@Mixin(value = ServerLevel.class, priority = 1400)
public abstract class SleepWakeMixin {

    @WrapMethod(method = "wakeUpAllPlayers")
    private void fabrolia$ownerSafeWake(Operation<Void> original) {
        ServerLevel self = (ServerLevel) (Object) this;
        RegionizedServer rs = RegionScheduler.forLevel(self);
        if (rs != null && rs.asyncActive) {
            boolean took = rs.quiesceAllIslands();
            Throwable failure = null;
            try {
                RegionScheduler.runSuccessBodyIfHealthy(original::call);
            } catch (Throwable primary) {
                // Close the global start gate at the catch edge, before the
                // all-island claim fence is reopened.
                failure = RegionScheduler.publishFatalAndSelectWinner(primary);
            }
            // Claim release is mandatory failure cleanup. releaseAllIslands
            // exhausts every recorded claim and preserves the exact primary.
            if (took) failure = rs.releaseAllIslands(failure);
            if (failure == null) {
                try {
                    // Releasing the fence is cleanup, not a success edge. A
                    // peer W selected during release still forbids normal return.
                    RegionScheduler.commitSuccessIfHealthy(() -> {});
                } catch (Throwable peerFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
                }
            }
            if (failure != null) {
                FailStops.<RuntimeException>rethrow(failure);
            }
        } else {
            RegionScheduler.runSuccessBodyIfHealthy(original::call);
        }
    }
}
