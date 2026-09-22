package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainLoopTimingTest {
    @Test
    void includesPacketsControlsResidualGameplayAndNestedRequiredWaitButNotDeadlineIdle() {
        MainLoopTiming timing = new MainLoopTiming();
        timing.beginLoop(0);
        // Packet work 10, tickServer work 20, and its nested required wait 40.
        timing.enterDeadlineWait();
        park(timing, 40);
        timing.exitDeadlineWait();
        // The exact outer wait follows post-tick controls (5) and residual work (35).
        timing.enterTopLevelWait();
        timing.enterDeadlineWait();
        timing.enterPoll();
        // Required chunk/dependency service reached recursively inside pollTask.
        timing.enterPoll();
        park(timing, 15);
        timing.exitPoll();
        park(timing, 25);
        timing.exitPoll();
        // Native idle park after the poll and all its required work have returned.
        park(timing, 50);
        timing.exitDeadlineWait();
        timing.exitTopLevelWait();
        timing.completeLoop(10 + 20 + 40 + 5 + 35 + 15 + 25 + 50);
        assertEquals(150, timing.meanWorkNanos());
    }

    @Test
    void unfinishedLoopDoesNotPublishACostAndCompletedMeanIsBounded() {
        MainLoopTiming timing = new MainLoopTiming();
        timing.beginLoop(0);
        assertFalse(timing.hasCompletedLoop());
        assertEquals(-1, timing.meanWorkNanos());
        timing.completeLoop(1_000);
        for (int i = 0; i < 100; i++) {
            timing.beginLoop(2_000 + i * 100L);
            timing.completeLoop(2_010 + i * 100L);
        }
        assertEquals(10, timing.meanWorkNanos());
        timing.beginLoop(50_000);
        assertEquals(10, timing.meanWorkNanos());
    }

    @Test
    void slowActiveLoopCannotHideBehindItsCheapCompletedAverage() {
        MainLoopTiming timing = new MainLoopTiming();
        timing.beginLoop(0);
        timing.completeLoop(10);
        timing.beginLoop(20);
        timing.enterTopLevelWait();
        timing.enterDeadlineWait();
        park(timing, 50);
        timing.exitDeadlineWait();
        timing.exitTopLevelWait();
        assertEquals(10, timing.meanWorkNanos());
        assertEquals(300, timing.activeWorkNanos(370));
        timing.completeLoop(370);
        assertEquals(155, timing.meanWorkNanos());
        assertEquals(-1, timing.activeWorkNanos(400));
    }

    @Test
    void replacementServerObserverCannotInheritRateOrCost() {
        MainLoopTiming firstServer = new MainLoopTiming();
        firstServer.beginLoop(0);
        firstServer.completeLoop(100);
        firstServer.updateTps(100, 1_000_000_000L);
        firstServer.updateTps(120, 2_000_000_000L);
        assertEquals(20.0, firstServer.tps());
        MainLoopTiming replacementServer = new MainLoopTiming();
        replacementServer.updateTps(1_000, 3_000_000_000L);
        assertTrue(Double.isNaN(replacementServer.tps()));
        assertFalse(replacementServer.hasCompletedLoop());
        replacementServer.updateTps(1_000, 4_000_000_000L);
        assertEquals(0.0, replacementServer.tps());
    }

    private static void park(MainLoopTiming timing, long elapsed) {
        if (timing.isDeadlineIdlePark()) timing.recordDeadlineIdlePark(elapsed);
    }
}
