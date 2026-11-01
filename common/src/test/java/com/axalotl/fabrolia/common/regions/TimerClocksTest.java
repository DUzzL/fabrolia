package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Absolute entity and block-entity timers follow their clock (timer clock fix). */
final class TimerClocksTest {

    /** A store with one timer, as an entity or a trial spawner holds it. */
    private static final class OneTimer implements TimerClocks.Stamped {
        IslandRegistry.PIsland basis;
        long stamp;
        final long floor;

        OneTimer(IslandRegistry.PIsland basis, long stamp, long floor) {
            this.basis = basis;
            this.stamp = stamp;
            this.floor = floor;
        }

        @Override public IslandRegistry.PIsland fabrolia$timerBasis() { return basis; }
        @Override public void fabrolia$setTimerBasis(IslandRegistry.PIsland b) { basis = b; }
        @Override public void fabrolia$shiftTimers(long delta, boolean clamp) {
            stamp = TimerClocks.shift(stamp, delta, floor, clamp);
        }
    }

    private static IslandRegistry.PIsland island(long id, long logicalTick) {
        IslandRegistry.PIsland p = new IslandRegistry.PIsland(id, 0L);
        p.logicalTick = logicalTick;
        return p;
    }

    @Test
    void mergeIntoALaggedIslandKeepsTheRemainingTime() {
        // Run B of the review: a 60 s cooldown on a fresh island (L=2000), merged
        // into the player's island 1300 ticks behind. Before the fix it ended
        // 1300 ticks late; now 1200 ticks remain on the survivor's clock.
        IslandRegistry.PIsland fresh = island(1, 2000L);
        IslandRegistry.PIsland lagged = island(2, 700L);
        OneTimer cooldown = new OneTimer(fresh, 3200L, 0L);
        TimerClocks.moveTo(cooldown, lagged, 2010L);
        assertSame(lagged, cooldown.basis);
        assertEquals(1200L, cooldown.stamp - lagged.logicalTick);
    }

    @Test
    void reloadIntoANewIslandKeepsTheRemainingTime() {
        // Run R: stamped on an island 1300 ticks behind W, saved (written in W),
        // loaded into a new island born at W.
        IslandRegistry.PIsland lagged = island(1, 700L);
        OneTimer cooldown = new OneTimer(lagged, 1900L, 0L);
        long wall = 2000L;
        long d = wall - TimerClocks.now(lagged, wall);
        cooldown.fabrolia$shiftTimers(d, false);
        assertEquals(3200L, cooldown.stamp, "saved in W");
        cooldown.basis = null; // load
        IslandRegistry.PIsland reborn = island(3, 2030L);
        TimerClocks.moveTo(cooldown, reborn, 2030L);
        assertEquals(1170L, cooldown.stamp - reborn.logicalTick, "30 ticks passed while unloaded, as in vanilla");
    }

    @Test
    void sameClockIsANoOp() {
        IslandRegistry.PIsland p = island(1, 500L);
        OneTimer t = new OneTimer(p, 900L, 0L);
        TimerClocks.moveTo(t, p, 10_000L);
        assertEquals(900L, t.stamp);
        assertEquals(0L, TimerClocks.delta(null, null, 10_000L));
    }

    @Test
    void wallAndLiveAndRetiredClocks() {
        assertEquals(4000L, TimerClocks.now(null, 4000L));
        IslandRegistry.PIsland p = island(1, 1000L);
        assertEquals(1000L, TimerClocks.now(p, 4000L));
        p.domain.unitGameTime = 1001L; // unit running
        assertEquals(1001L, TimerClocks.now(p, 4000L));
        p.retiredWallTime = 3000L;
        p.retired = true;
        assertEquals(1000L + 1000L, TimerClocks.now(p, 4000L), "final L plus wall time since it retired");
    }

    @Test
    void mainThreadReadsTheWallClock() {
        assertNull(TimerClocks.currentBasis());
    }

    @Test
    void savingAndRestoringIsExact() {
        for (long floor : new long[] {0L, -1L, TimerClocks.NO_SENTINEL}) {
            for (long v : new long[] {1L, 2L, 77L, 1_000_000L}) {
                long saved = TimerClocks.shift(v, 1300L, floor, false);
                assertEquals(v + 1300L, saved);
                assertEquals(v, TimerClocks.shift(saved, -1300L, floor, false));
            }
        }
    }

    @Test
    void unsetTimersNeverMove() {
        // Trial spawner cooldown/next spawn, villager restock/gossip, WorkAtPoi,
        // bed sensor, bell: 0 = unset.
        assertEquals(0L, TimerClocks.shift(0L, 500L, 0L, true));
        assertEquals(0L, TimerClocks.shift(0L, -500L, 0L, true));
        // Neutral-mob anger: -1 = none.
        assertEquals(-1L, TimerClocks.shift(-1L, 500L, 0L, true));
        // Brain schedule check: -9999 = never ran.
        assertEquals(-9999L, TimerClocks.shift(-9999L, 500L, TimerClocks.BRAIN_SCHEDULE_UNSET, true));
        assertEquals(-9998L, TimerClocks.shift(300L, -50_000L, TimerClocks.BRAIN_SCHEDULE_UNSET, true));
        // Copper golem: -1 unset, -2 waxed, 0 fully oxidized.
        for (long sentinel : new long[] {-1L, -2L, 0L}) {
            assertEquals(sentinel, TimerClocks.shift(sentinel, 500L, 0L, true));
            assertEquals(sentinel, TimerClocks.shift(sentinel, -500L, 0L, true));
        }
    }

    @Test
    void timersMovedIntoThePastStayExpired() {
        // Anger that ended long ago on a clock far ahead: it must not become the
        // "no anger" sentinel or 0 (unset), and it stays in the past.
        assertEquals(1L, TimerClocks.shift(300L, -5000L, 0L, true));
        // Memories have no sentinel: they move freely.
        assertEquals(-4700L, TimerClocks.shift(300L, -5000L, TimerClocks.NO_SENTINEL, true));
    }

    @Test
    void camelPoseKeepsItsSittingSign() {
        assertEquals(0L, TimerClocks.shiftSigned(0L, 900L, true));
        assertEquals(1900L, TimerClocks.shiftSigned(1000L, 900L, true));
        assertEquals(-1900L, TimerClocks.shiftSigned(-1000L, 900L, true), "sitting stays sitting");
        assertEquals(-1L, TimerClocks.shiftSigned(-1000L, -5000L, true));
        assertEquals(1L, TimerClocks.shiftSigned(1000L, -5000L, true));
    }

    @Test
    void acquirePoiResidueIsAnyStampBeyondTheLegalLead() {
        long now = 10_000L;
        // Legal: now + nextInt(20), timestamp + 20 + nextInt(20).
        assertFalse(TimerClocks.isFutureResidue(now + 39L, now, TimerClocks.ACQUIRE_POI_MAX_LEAD));
        assertFalse(TimerClocks.isFutureResidue(now + 40L, now, TimerClocks.ACQUIRE_POI_MAX_LEAD));
        assertTrue(TimerClocks.isFutureResidue(now + 41L, now, TimerClocks.ACQUIRE_POI_MAX_LEAD));
        assertTrue(TimerClocks.isFutureResidue(now + 1300L, now, TimerClocks.ACQUIRE_POI_MAX_LEAD));
        // Retry: currentDelay <= 400.
        assertFalse(TimerClocks.isFutureResidue(now + 400L, now, TimerClocks.ACQUIRE_POI_RETRY_MAX_LEAD));
        assertTrue(TimerClocks.isFutureResidue(now + 401L, now, TimerClocks.ACQUIRE_POI_RETRY_MAX_LEAD));
    }

    @Test
    void homeSearchAndBannerResidueUseTheirLegalLeads() {
        long now = 10_000L;
        // Home search: throttle now + nextInt(20), cache entries throttle + 40.
        assertFalse(TimerClocks.isFutureResidue(now + 19L, now, TimerClocks.HOME_SEARCH_MAX_LEAD));
        assertTrue(TimerClocks.isFutureResidue(now + 21L, now, TimerClocks.HOME_SEARCH_MAX_LEAD));
        assertFalse(TimerClocks.isFutureResidue(now + 59L, now, TimerClocks.HOME_SEARCH_CACHE_MAX_LEAD));
        assertTrue(TimerClocks.isFutureResidue(now + 61L, now, TimerClocks.HOME_SEARCH_CACHE_MAX_LEAD));
        // A healed throttle reads as due: now - (now - 20) is not < 20.
        assertFalse(now - (now - TimerClocks.HOME_SEARCH_MAX_LEAD) < 20L);
        // Raider banner: unreachable until now + 600.
        assertFalse(TimerClocks.isFutureResidue(now + 600L, now, TimerClocks.BANNER_RETRY_MAX_LEAD));
        assertTrue(TimerClocks.isFutureResidue(now + 601L, now, TimerClocks.BANNER_RETRY_MAX_LEAD));
    }
}
