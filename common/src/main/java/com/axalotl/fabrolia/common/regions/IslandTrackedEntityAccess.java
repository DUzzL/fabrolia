package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;

/** Duck installed on {@code ChunkMap.TrackedEntity}. */
public interface IslandTrackedEntityAccess {

    int TRACKING_ACTIVE = 0;
    int TRACKING_RETIRED = 1;
    int TRACKING_QUARANTINED = 2;

    Entity fabrolia$trackedEntity();

    /** Guarded by the exact TrackedEntity intrinsic monitor. */
    int fabrolia$islandTrackingState();

    /** Exact current pairing set, read under the tracker monitor. */
    boolean fabrolia$hasIslandTrackingViewers();

    /** Viewers accepted by tracking but not visible until their spawn bundle. */
    boolean fabrolia$hasPendingIslandPairings();

    /** Deduplicates VMP tickStaging's updatePlayers(all players) burst. */
    boolean fabrolia$queueIslandBulkPairingRefresh();

    /** Identity-deduplicated; caller owns the exact tracker monitor. */
    boolean fabrolia$queueIslandPairing(ServerPlayer player);

    /** Stages desired-ABSENT membership after cancelling an unseen add. */
    boolean fabrolia$queueIslandRemoval(ServerPlayer player);

    /** Cancels an uncommitted viewer even though it was never in seenBy. */
    void fabrolia$cancelIslandPairing(ServerPlayer player);

    /** Refresh cache first, then atomically expose viewer and send its bundle. */
    void fabrolia$commitIslandPairings();

    IslandRegistry.PIsland fabrolia$islandTrackingOwner();

    void fabrolia$setIslandTrackingOwner(IslandRegistry.PIsland island);

    /** Marks this tracker generation dead before vanilla pairing teardown. */
    void fabrolia$deactivateIslandTracking();

    /** Permanently falls this live tracker back to its physical-Main publisher. */
    void fabrolia$quarantineIslandTracking();

    /**
     * Deduplicates the non-blocking worker -> physical-Main fallback task and
     * returns its exact generation token; zero means another generation owns it.
     */
    long fabrolia$tryScheduleMainTracking();

    /** Compare-and-clear only the exact scheduling generation owned by the caller. */
    void fabrolia$finishMainTrackingSchedule(long token);

    /** At most one persistent Main-owned ServerEntity clock step per server tick. */
    boolean fabrolia$tryBeginMainTrackingPublication(int serverTick);

    /** Monotonic proof that the wrapped ServerEntity original actually ran. */
    long fabrolia$islandTrackingPublicationGeneration();

    void fabrolia$markIslandTrackingPublished();

    /** @return false when this tracker was retired before publication. */
    boolean fabrolia$sendIslandChanges();
}
