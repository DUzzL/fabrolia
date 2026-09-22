package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for the c2me idle-save vs queued island-unit claim race. */
class IslandIncrementalSaveAdmissionTest {

    @Test
    void opportunisticSaveYieldsToPendingSimulation() {
        assertFalse(IslandUnitAdmission.opportunisticSaveMayClaim(true),
                "a queued unit must win the queue-to-claim window");
        assertTrue(IslandUnitAdmission.opportunisticSaveMayClaim(false),
                "between units the opportunistic serializer may take the owner claim");
    }

}
