package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
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
            try {
                original.call();
            } finally {
                if (took) rs.releaseAllIslands();
            }
        } else {
            original.call();
        }
    }
}
