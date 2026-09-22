package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.RaidIslandDispatch;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Main enumerates published registrations; stop/removal and tick share the regional raid slot. */
@Mixin(Raids.class)
public abstract class RaidsTickMixin {
    @Shadow @Final private Int2ObjectMap<Raid> raidMap;
    @Shadow private int tick;

    @WrapMethod(method = "tick")
    private void fabrolia$dispatchRaidLifecycle(ServerLevel level, Operation<Void> original) {
        tick++;
        for (Raid raid : raidMap.values()) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> RaidIslandDispatch.dispatch(level, raid));
        }
        if (tick % 200 == 0) ((Raids) (Object) this).setDirty();
        RegionScheduler.commitSuccessIfHealthy(() -> {});
    }
}
