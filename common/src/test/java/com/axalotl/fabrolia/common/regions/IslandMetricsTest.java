package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IslandMetricsTest {
    private static final long TARGET = 50_000_000L;

    @Test
    void measuredZeroWhileBehindCannotBeHealthy() {
        assertEquals("Idle", IslandMetrics.classifyProgress(0, 0, TARGET, 0, 0));
        assertEquals("Lagging", IslandMetrics.classifyProgress(0, 10, TARGET, 0, 0));
        assertEquals("Healthy", IslandMetrics.classifyProgress(20, 0, TARGET, 0, 0));
        assertEquals("Warming", IslandMetrics.classifyProgress(Double.NaN, 10, TARGET, 0, 0));
    }

    @Test
    void liveOverdueDeadlinePreventsStaleCaughtUpStateFromLookingIdle() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(1, 0);
        island.unitsCompleted = 1;
        island.lastCompletionNanos = 1_000_000_000L;
        island.nextDeadlineNanos = 1_050_000_000L;
        assertEquals(2_000_000_000L, IslandMetrics.completionAgeNanos(island, 3_000_000_000L));
        long overdue = IslandMetrics.overdueNanos(island, 3_000_000_000L);
        assertEquals(1_950_000_000L, overdue);
        assertEquals("Lagging", IslandMetrics.classifyProgress(0, 0, TARGET, 0, overdue));
        island.lastCompletionNanos = 3_000_000_000L;
        island.nextDeadlineNanos = 3_050_000_000L;
        assertEquals(0, IslandMetrics.completionAgeNanos(island, 3_000_000_000L));
        assertEquals(0, IslandMetrics.overdueNanos(island, 3_000_000_000L));
    }

    @Test
    void unmeasuredCostAndTpsCannotBeConfusedWithMeasuredZero() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(2, 0);
        assertEquals(-1, IslandMetrics.completionAgeNanos(island, 5_000_000_000L));
        assertNotEquals(IslandMetrics.formatTps(Double.NaN), IslandMetrics.formatTps(0));
    }
}
