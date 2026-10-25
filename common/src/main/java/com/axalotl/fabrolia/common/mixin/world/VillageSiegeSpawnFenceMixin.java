package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.VillageSiege;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A village siege adds its zombies in a village a running island may own
 * (audit C-02). Each spawn runs under the global fence; the siege's setup scan
 * stays a Main read, since a night without a valid village repeats it every tick.
 */
@Mixin(VillageSiege.class)
public abstract class VillageSiegeSpawnFenceMixin {

    @WrapMethod(method = "trySpawn")
    private void fabrolia$fencedZombie(ServerLevel level, Operation<Void> original) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive || rs.currentThreadHoldsAllClaims() || IslandCommands.fenceWindowHeld()) {
            original.call(level);
            return;
        }
        AsyncIslandPlayerTicks.runFencedMainMutation("village siege spawn", () -> original.call(level));
    }
}
