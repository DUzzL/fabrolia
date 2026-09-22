package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local frontier and deadline boundaries; queued bodies have real-pool coverage. */
class IslandUnitAdmissionTest {


    @Test
    void delayedFollowerSubmittedBeforeCompletionIsRejected() {
        long runningL = 40;
        long followerStamp = runningL;
        long afterRunningUnitCompleted = runningL + 1;

        assertFalse(IslandUnitAdmission.isFresh(followerStamp, afterRunningUnitCompleted),
                "a follower queued from the predecessor's due deadline must not run after L advanced");
    }

    @Test
    void delayedFirstTaskAndPostCompletionSelfChainRemainValid() {
        assertTrue(IslandUnitAdmission.isFresh(40, 40),
                "queue latency alone must not discard a unit while its frontier is unchanged");
        assertTrue(IslandUnitAdmission.isFresh(41, 41),
                "a deliberate self-chain captured after completion belongs to the new frontier");
    }

    @Test
    void deadlineBoundTaskIsRevalidatedAtClaimTime() {
        assertTrue(IslandUnitAdmission.isDeadlineDue(0, 100, 0),
                "zero is the unscheduled/initially-due sentinel");
        assertTrue(IslandUnitAdmission.isDeadlineDue(110, 100, 10),
                "the normal drive's ten-millisecond early slack is inclusive");
        assertFalse(IslandUnitAdmission.isDeadlineDue(111, 100, 10),
                "a predecessor or pacing update that rolled the deadline future cancels the task");
        assertTrue(IslandUnitAdmission.isSameDeadline(123, 123),
                "an unchanged exact deadline stamp remains valid");
        assertFalse(IslandUnitAdmission.isSameDeadline(123, 100),
                "even another due value means completion/reanchor crossed the submission window");
    }

}
