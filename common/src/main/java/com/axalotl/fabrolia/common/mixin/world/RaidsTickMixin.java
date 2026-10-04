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
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;

/** Main enumerates published registrations; stop/removal and tick share the regional raid slot. */
@Mixin(Raids.class)
public abstract class RaidsTickMixin {
    @Shadow @Final private Int2ObjectMap<Raid> raidMap;
    @Shadow private int tick;

    /** Main-confined scratch list of the raids Main ticks this level tick. */
    @Unique private final ArrayList<Raid> fabrolia$mainTicks = new ArrayList<>();

    @WrapMethod(method = "tick")
    private void fabrolia$dispatchRaidLifecycle(ServerLevel level, Operation<Void> original) {
        tick++;
        ArrayList<Raid> mainTicks = fabrolia$mainTicks;
        try {
            for (Raid raid : raidMap.values()) {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    if (RaidIslandDispatch.dispatch(level, raid)) mainTicks.add(raid);
                });
            }
            // Unowned raids tick in map order under ONE all-island fence; stopped
            // ones leave raidMap there, after the enumeration.
            if (!mainTicks.isEmpty()) RaidIslandDispatch.tickOnMainFenced(level, mainTicks);
        } finally {
            mainTicks.clear();
        }
        if (tick % 200 == 0) ((Raids) (Object) this).setDirty();
        RegionScheduler.commitSuccessIfHealthy(() -> {});
    }
}
