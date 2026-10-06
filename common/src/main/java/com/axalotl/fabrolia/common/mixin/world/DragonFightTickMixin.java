package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Production audit — same class as RaidTickMixin: the dragon fight ticks on
 *  MAIN and mutates the dragon/crystals/portal owned by the End's central
 *  island. Claim that island for the fight tick (W cadence for the fight
 *  island — vanilla semantics; the rest of the End stays independent). */
@Mixin(EnderDragonFight.class)
public abstract class DragonFightTickMixin {

    @Shadow @Final private ServerLevel level;

    @WrapMethod(method = "tick")
    private void fabrolia$ownerSafeFightTick(Operation<Void> original) {
        RegionizedServer rs = RegionScheduler.forLevel(this.level);
        if (rs == null || !rs.asyncActive || rs.currentThreadHoldsAllClaims()) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call());
            return;
        }
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(ChunkPos.pack(0, 0));
        if (owner == null) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call());
            return;
        }
        IslandCommands.mainClaim(owner);
        Throwable failure = null;
        try {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call());
        } catch (Throwable bodyFailure) {
            failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure);
        }
        try {
            IslandCommands.mainRelease(owner);
        } catch (Throwable cleanupFailure) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    fabrolia$mergeFailure(failure, cleanupFailure));
        }
        if (failure == null) {
            try {
                // Claim release is mandatory cleanup, not a success edge.
                // Linearize the normal return against a peer generation W.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            IslandCommands.publishAndPropagate(failure);
        }
    }

    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (cleanup != null && cleanup != primary) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
                // Already fail-stop; suppression must not replace the fight primary.
            }
        }
        return primary;
    }
}
