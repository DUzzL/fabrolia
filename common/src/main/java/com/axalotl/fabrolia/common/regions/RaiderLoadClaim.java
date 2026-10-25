package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.ChunkPos;

/**
 * Main loading a raider from disk rejoins it to its raid
 * ({@code Raider#readAdditionalSaveData} → {@code Raid#addWaveMob},
 * {@code setLeader}, boss-bar update) while the raid's island may be running
 * {@code Raid#tick} (player set of the boss bar, leaders). On Main the load
 * holds the raid island's claim, as the dragon fight tick does (audit D-14).
 */
public final class RaiderLoadClaim {
    private RaiderLoadClaim() {}

    public static void runLoad(ServerLevel level, java.util.OptionalInt raidId, Runnable load) {
        RegionizedServer rs = raidId.isPresent() ? RegionScheduler.forLevel(level) : null;
        if (rs == null || !rs.asyncActive || rs.currentThreadHoldsAllClaims()
                || IslandCommands.fenceWindowHeld()
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) {
            load.run();
            return;
        }
        Raid raid = level.getRaids().get(raidId.getAsInt());
        IslandRegistry.PIsland owner = raid == null ? null
                : rs.islandOwnerOfChunk(ChunkPos.pack(raid.getCenter()));
        if (owner == null || IslandCommands.mainHolds(owner)) {
            load.run();
            return;
        }
        IslandCommands.mainClaim(owner);
        Throwable failure = null;
        try {
            load.run();
        } catch (Throwable bodyFailure) {
            failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure);
        }
        try {
            IslandCommands.mainRelease(owner);
        } catch (Throwable cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            failure = RegionScheduler.publishFatalAndSelectWinner(failure);
        }
        if (failure != null) IslandCommands.publishAndPropagate(failure);
    }
}
