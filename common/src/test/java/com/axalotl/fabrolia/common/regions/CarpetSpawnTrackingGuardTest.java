package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarpetSpawnTrackingGuardTest {
    @Test
    void independentIslandSpawningIsBlockedEvenWithParallelSpawnToggleOff() {
        assertTrue(CarpetSpawnTrackingGuard.blocked(false, true, true, true, false, true));
        assertTrue(CarpetSpawnTrackingGuard.blocked(false, false, true, true, false, true));
    }

    @Test
    void offRequestsCannotAllowTrackingBeforeTheLastIslandRetires() {
        assertTrue(CarpetSpawnTrackingGuard.blocked(false, true, false, true, false, true));
        assertTrue(CarpetSpawnTrackingGuard.blocked(true, true, false, false, false, true));
    }

    @Test
    void deferredSpawnPassesAreBlockedWithoutRegionThreading() {
        assertTrue(CarpetSpawnTrackingGuard.blocked(false, false, false, false, true, true));
    }

    @Test
    void serialSpawningRetainsCarpetTracking() {
        assertFalse(CarpetSpawnTrackingGuard.blocked(true, false, true, true, true, true));
        assertFalse(CarpetSpawnTrackingGuard.blocked(false, false, false, true, false, true));
        assertFalse(CarpetSpawnTrackingGuard.blocked(false, false, true, false, false, true));
        assertFalse(CarpetSpawnTrackingGuard.blocked(false, false, false, false, true, false));
    }
}
