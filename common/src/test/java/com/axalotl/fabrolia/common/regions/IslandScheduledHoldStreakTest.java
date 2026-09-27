package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static com.axalotl.fabrolia.common.regions.IslandScheduledTicks.SCHEDULED_HOLD_MAX_STREAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded same-L fail-closed HOLD retry: pure streak accounting on PIsland. */
class IslandScheduledHoldStreakTest {

    @Test
    void budgetExhaustsOnlyAfterMaxStreakConsecutiveHoldsAtTheSameL() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(55L, 0L);
        long l = 884_293_201L;
        assertFalse(IslandScheduledTicks.holdBudgetExhausted(island, l));
        for (int attempt = 1; attempt < SCHEDULED_HOLD_MAX_STREAK; attempt++) {
            assertEquals(attempt, IslandScheduledTicks.recordHold(island, l));
            assertFalse(IslandScheduledTicks.holdBudgetExhausted(island, l),
                    "attempt " + attempt + " must still hold");
        }
        assertEquals(SCHEDULED_HOLD_MAX_STREAK, IslandScheduledTicks.recordHold(island, l));
        assertTrue(IslandScheduledTicks.holdBudgetExhausted(island, l));
        assertFalse(IslandScheduledTicks.holdBudgetExhausted(island, l + 1),
                "budget is keyed by L");
    }

    @Test
    void newLogicalTickStartsAFreshStreak() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(56L, 0L);
        for (int i = 0; i < SCHEDULED_HOLD_MAX_STREAK; i++) {
            IslandScheduledTicks.recordHold(island, 10L);
        }
        assertTrue(IslandScheduledTicks.holdBudgetExhausted(island, 10L));
        assertEquals(1, IslandScheduledTicks.recordHold(island, 11L));
        assertFalse(IslandScheduledTicks.holdBudgetExhausted(island, 11L));
        assertFalse(IslandScheduledTicks.holdBudgetExhausted(island, 10L));
    }

    @Test
    void admissionClearsTheStreakSoTheNextHoldAtAnyLStartsAtOne() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(57L, 0L);
        for (int i = 0; i < SCHEDULED_HOLD_MAX_STREAK; i++) {
            IslandScheduledTicks.recordHold(island, 10L);
        }
        IslandScheduledTicks.clearHoldStreak(island);
        assertFalse(IslandScheduledTicks.holdBudgetExhausted(island, 10L));
        assertEquals(1, IslandScheduledTicks.recordHold(island, 10L));
    }
}
