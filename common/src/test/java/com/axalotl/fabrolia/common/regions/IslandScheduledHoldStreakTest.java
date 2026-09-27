package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static com.axalotl.fabrolia.common.regions.IslandScheduledTicks.SCHEDULED_HOLD_STUCK_STREAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IslandScheduledHoldStreakTest {

    @Test
    void stuckIsReportedAtTheThresholdAndAtEveryFurtherMultiple() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(55L, 0L);
        long l = 12_345L;
        for (int attempt = 1; attempt < 3 * SCHEDULED_HOLD_STUCK_STREAK + 1; attempt++) {
            int streak = IslandScheduledTicks.recordHold(island, l);
            assertEquals(attempt, streak, "the streak keeps counting past the threshold");
            assertEquals(attempt % SCHEDULED_HOLD_STUCK_STREAK == 0,
                    IslandScheduledTicks.holdStreakStuck(streak),
                    "attempt " + attempt);
        }
        assertFalse(IslandScheduledTicks.holdStreakStuck(0));
    }

    @Test
    void aDifferentLStartsAFreshStreak() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(56L, 0L);
        for (int i = 0; i < SCHEDULED_HOLD_STUCK_STREAK; i++) {
            IslandScheduledTicks.recordHold(island, 10L);
        }
        assertTrue(IslandScheduledTicks.holdStreakStuck(island.scheduledHoldStreak));
        assertEquals(1, IslandScheduledTicks.recordHold(island, 11L));
        assertEquals(11L, island.scheduledHoldL);
    }

    @Test
    void admissionClearsTheStreak() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(57L, 0L);
        for (int i = 0; i < SCHEDULED_HOLD_STUCK_STREAK; i++) {
            IslandScheduledTicks.recordHold(island, 10L);
        }
        IslandScheduledTicks.clearHoldStreak(island);
        assertEquals(0, island.scheduledHoldStreak);
        assertEquals(1, IslandScheduledTicks.recordHold(island, 10L));
    }
}
