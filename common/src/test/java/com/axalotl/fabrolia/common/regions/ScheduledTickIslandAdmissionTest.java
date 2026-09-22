package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure truth table and topology model for coherent scheduled-tick admission. */
class ScheduledTickIslandAdmissionTest {

    @Test
    void exactVanillaEligibilityAlwaysAdmits() {
        assertEquals(ScheduledTickIslandAdmission.Decision.ADMIT,
                ScheduledTickIslandAdmission.classify(true, true, true));
        assertEquals(ScheduledTickIslandAdmission.Decision.ADMIT,
                ScheduledTickIslandAdmission.classify(true, false, false));
    }

    @Test
    void normalSimulationFringeRemainsDormantWithoutFreezingIsland() {
        assertEquals(ScheduledTickIslandAdmission.Decision.DORMANT,
                ScheduledTickIslandAdmission.classify(false, false, true));
    }

    @Test
    void ordinaryWorldgenRefusalNeverFreezesTheIsland() {
        assertEquals(ScheduledTickIslandAdmission.Decision.DORMANT,
                ScheduledTickIslandAdmission.classify(false, true, false));
    }

    @Test
    void terrainRefusalStormProducesNoLogicalHolds() {
        int holds = 0;
        for (int i = 0; i < 100_000; i++) {
            if (ScheduledTickIslandAdmission.classify(false, true, false)
                    == ScheduledTickIslandAdmission.Decision.HOLD) {
                holds++;
            }
        }
        assertEquals(0, holds,
                "leaves/sand/gravel publication churn must never stop island L");
    }

    @Test
    void pulseSensitiveExactRefusalHoldsTheCompleteUnit() {
        assertEquals(ScheduledTickIslandAdmission.Decision.HOLD,
                ScheduledTickIslandAdmission.classify(false, true, true));
    }

    @Test
    void sameLogicalTickHoldPreventsVanillaUniqueTickFromSwallowingTheNextEdge() {
        boolean[] tornTargetPending = {true};
        assertFalse(scheduleObserverIfAbsent(tornTargetPending),
                "old refused target + advancing source reproduces the swallowed edge");

        assertEquals(ScheduledTickIslandAdmission.Decision.HOLD,
                ScheduledTickIslandAdmission.classify(false, true, true),
                "the source phase cannot advance while the old target tick is retained");

        boolean[] coherentTargetPending = {true};
        coherentTargetPending[0] = false; // retained target executes on the retry frontier
        assertTrue(scheduleObserverIfAbsent(coherentTargetPending),
                "the following source edge is now represented by a fresh unique tick");
    }

    @Test
    void cadenceHeadRunsOnlyOnceForEveryCandidateLogicalTick() {
        assertTrue(ScheduledTickIslandAdmission.shouldApplyUnitHead(41, 42));
        assertFalse(ScheduledTickIslandAdmission.shouldApplyUnitHead(42, 42),
                "a repeated admission of one candidate must not replay cadence tasks");
        assertTrue(ScheduledTickIslandAdmission.shouldApplyUnitHead(42, 43),
                "the next candidate L must reopen the unit head");
    }

    @Test
    void preparedShapeRequiresCanonicalIdentityIncludingAbsentAndFutureHeads() {
        Object container = new Object();
        Object head = new Object();
        assertTrue(ScheduledTickIslandAdmission.preparedShapeStable(
                container, container, head, head));
        assertTrue(ScheduledTickIslandAdmission.preparedShapeStable(
                null, null, null, null), "a still-absent due-key observation is stable");
        assertTrue(ScheduledTickIslandAdmission.preparedShapeStable(
                container, container, null, null), "a still-empty canonical container is stable");
        assertFalse(ScheduledTickIslandAdmission.preparedShapeStable(
                container, new Object(), head, head),
                "an equal-looking replacement container is not the prepared canonical one");
        assertFalse(ScheduledTickIslandAdmission.preparedShapeStable(
                container, container, head, new Object()),
                "a replacement head requires a fresh block-frontier admission pass");
        assertFalse(ScheduledTickIslandAdmission.preparedShapeStable(
                container, container, null, head),
                "an empty/future observation becoming due must reject the block commit");
        assertFalse(ScheduledTickIslandAdmission.preparedShapeStable(
                null, container, null, head),
                "a newly installed due container must reject the block commit");
    }

    private static boolean scheduleObserverIfAbsent(boolean[] pending) {
        if (pending[0]) return false;
        pending[0] = true;
        return true;
    }

}
