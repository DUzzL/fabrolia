package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.locks.LockSupport;

/**
 * Production audit (2026-07-12) — raids tick on MAIN and mutate raider
 * entities, wave spawns and village state owned by an island that may be
 * mid-unit: an entity-state race every tick of an active raid. Claim the
 * raid-center's owning island for the raid tick — the raid's island runs
 * effectively at W cadence for the raid duration (vanilla semantics for THE
 * raided village), neighbors stay independent. Blocking claim uses the
 * routed-batch-aware spin (waiter islands can only finish via the batch).
 */
@Mixin(Raid.class)
public abstract class RaidTickMixin {

    @WrapMethod(method = "tick")
    private void fabrolia$ownerSafeRaidTick(ServerLevel level, Operation<Void> original) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive || rs.mainHoldsAllClaims()) {
            original.call(level);
            return;
        }
        Raid self = (Raid) (Object) this;
        IslandRegistry.PIsland owner =
                rs.islandOwnerOfChunk(ChunkPos.pack(self.getCenter()));
        if (owner == null) {
            original.call(level);
            return;
        }
        IslandCommands.mainClaim(owner);
        try {
            original.call(level);
        } finally {
            IslandCommands.mainRelease(owner);
        }
    }
}
