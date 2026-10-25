package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.ChunkPos;

/**
 * {@code EnderDragonFight#onCrystalDestroyed} counts the spike crystals and
 * re-phases the dragon, all on the End's central island (the island of chunk
 * 0,0, whose claim the fight tick takes). A crystal destroyed on an outer End
 * island ran it on that island's worker without the central claim, while Main
 * ticked the dragon (audit D-13). Such a call now runs later on Main under the
 * global fence; on the central island's own worker, or with the claim held, it
 * stays inline.
 */
public final class DragonCrystalRouting {
    private DragonCrystalRouting() {}

    public static void onCrystalDestroyed(ServerLevel level, EndCrystal crystal, Runnable body) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive || rs.currentThreadHoldsAllClaims()
                || IslandCommands.fenceWindowHeld()) {
            body.run();
            return;
        }
        IslandRegistry.PIsland central = rs.islandOwnerOfChunk(ChunkPos.pack(0, 0));
        if (Thread.currentThread() instanceof RegionWorkerThread) {
            if (central != null && rs.islandOwnerOfChunk(crystal.chunkPosition().pack()) == central) {
                body.run();
                return;
            }
        } else if (central == null || IslandCommands.mainHolds(central)) {
            body.run();
            return;
        }
        RegionScheduler.enqueueMainOnlyLater(() -> AsyncIslandPlayerTicks.runFencedMainMutation(
                "end crystal destroyed outside the central island", body));
    }
}
