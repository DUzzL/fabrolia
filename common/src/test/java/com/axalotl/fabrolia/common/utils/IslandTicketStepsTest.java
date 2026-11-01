package com.axalotl.fabrolia.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Island-tick ticket expiry steps (stopped portal loader fix). */
final class IslandTicketStepsTest {

    @Test
    void firstPassCountsOneTick() {
        assertEquals(1L, IslandTicketSteps.steps(0L, 0L, 5000L, 4200L));
    }

    @Test
    void consecutivePassesCountTheIslandTicksBetweenThem() {
        assertEquals(0L, IslandTicketSteps.steps(5000L, 4200L, 5001L, 4200L), "island stood still");
        assertEquals(1L, IslandTicketSteps.steps(5000L, 4200L, 5001L, 4201L));
        assertEquals(2L, IslandTicketSteps.steps(5000L, 4200L, 5001L, 4202L), "out of phase: two units");
    }

    @Test
    void staleMemoNeverBurnsAFreshTicket() {
        // The island had no timed ticket for a minute: 1200 island ticks since the memo.
        assertEquals(1L, IslandTicketSteps.steps(5000L, 4200L, 6200L, 5400L));
    }

    @Test
    void aMainStallNeverBurnsARenewedTicketAtOnce() {
        assertEquals(2L, IslandTicketSteps.steps(5000L, 4200L, 5001L, 4300L));
    }

    @Test
    void rebornIslandClockCountsOneTick() {
        assertEquals(1L, IslandTicketSteps.steps(5000L, 4200L, 5001L, 100L));
    }

    @Test
    void aStalledIslandOwesNothingAndARunningOneKeepsPace() {
        long seenWall = 5000L, seenL = 4200L, owed = 0L;
        for (int i = 1; i <= 100; i++) {
            long l = 4200L + (i / 2); // ten seconds at half speed
            owed += IslandTicketSteps.steps(seenWall, seenL, 5000L + i, l);
            seenWall = 5000L + i;
            seenL = l;
        }
        assertEquals(50L, owed, "one decrement per island tick");
    }
}
