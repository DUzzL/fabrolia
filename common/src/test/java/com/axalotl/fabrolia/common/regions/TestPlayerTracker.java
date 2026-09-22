package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real receipt/cadence logic and native clock admission; native packet emission is substituted. */
final class TestPlayerTracker implements IslandTrackedEntityAccess {
    final ServerPlayer player;
    IslandRegistry.PIsland owner;
    boolean pairingsPending = true;
    int publications;
    int pairingCommits;
    int lastMainTick = Integer.MIN_VALUE;
    Runnable onSend;

    TestPlayerTracker(ServerPlayer player, IslandRegistry.PIsland owner) {
        this.player = player;
        this.owner = owner;
    }

    @Override public Entity fabrolia$trackedEntity() { return player; }
    @Override public int fabrolia$islandTrackingState() { return TRACKING_ACTIVE; }
    @Override public boolean fabrolia$hasIslandTrackingViewers() { return false; }
    @Override public boolean fabrolia$hasPendingIslandPairings() { return pairingsPending; }
    @Override public boolean fabrolia$queueIslandBulkPairingRefresh() { throw new AssertionError(); }
    @Override public boolean fabrolia$queueIslandPairing(ServerPlayer viewer) { throw new AssertionError(); }
    @Override public boolean fabrolia$queueIslandRemoval(ServerPlayer viewer) { throw new AssertionError(); }
    @Override public void fabrolia$cancelIslandPairing(ServerPlayer viewer) { throw new AssertionError(); }
    @Override public void fabrolia$commitIslandPairings() { pairingCommits++; pairingsPending = false; }
    @Override public IslandRegistry.PIsland fabrolia$islandTrackingOwner() { return owner; }
    @Override public void fabrolia$setIslandTrackingOwner(IslandRegistry.PIsland island) { owner = island; }
    @Override public void fabrolia$deactivateIslandTracking() { throw new AssertionError(); }
    @Override public void fabrolia$quarantineIslandTracking() { throw new AssertionError(); }
    @Override public long fabrolia$tryScheduleMainTracking() { throw new AssertionError(); }
    @Override public void fabrolia$finishMainTrackingSchedule(long token) { throw new AssertionError(); }
    @Override public boolean fabrolia$tryBeginMainTrackingPublication(int tick) {
        if (lastMainTick == tick) return false;
        lastMainTick = tick;
        return true;
    }
    @Override public long fabrolia$islandTrackingPublicationGeneration() { return publications; }
    @Override public void fabrolia$markIslandTrackingPublished() { publications++; }
    @Override public boolean fabrolia$sendIslandChanges() {
        assertTrue(Thread.holdsLock(this));
        if (!IslandEntityTracking.beginMainPublication(this, player)) return false;
        assertNotNull(onSend, "native tracker effect must be installed");
        onSend.run();
        fabrolia$markIslandTrackingPublished();
        return true;
    }
}
