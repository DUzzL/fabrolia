package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMoveLockPolicyTest {

    @Test
    void unchangedSameSectionHeartbeatsStayOffTheGlobalMonitor() {
        assertFalse(PlayerMoveLockPolicy.requiresGlobalTransaction(
                17L, 17L, false, false));
        assertFalse(PlayerMoveLockPolicy.requiresGlobalTransaction(
                17L, 17L, true, true));
    }

    @Test
    void everyRealMembershipTransitionKeepsTheGlobalTransaction() {
        assertTrue(PlayerMoveLockPolicy.requiresGlobalTransaction(
                17L, 18L, false, false));
        assertTrue(PlayerMoveLockPolicy.requiresGlobalTransaction(
                17L, 17L, false, true));
        assertTrue(PlayerMoveLockPolicy.requiresGlobalTransaction(
                17L, 17L, true, false));
        assertTrue(PlayerMoveLockPolicy.requiresGlobalTransaction(
                17L, 18L, true, false));
    }
}
