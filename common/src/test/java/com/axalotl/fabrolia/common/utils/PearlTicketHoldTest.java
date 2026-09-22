package com.axalotl.fabrolia.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ENDER_PEARL ticket-hold policy (2026-08-19 pearl chunk-loading
 * incident). Hard requirements from prod:
 * <ul>
 *   <li>A burning ticket (ticksLeft &gt; 0) follows the vanilla countdown —
 *       the hold must never engage early.</li>
 *   <li>A live pearl in the chunk pins its ticket at zero (self-healing after
 *       a freeze); a dead/landed/logged-out pearl expires on schedule.</li>
 *   <li>The hold is bounded — a permanently wedged pearl must eventually
 *       release the chunk (leak bound), never pin it forever.</li>
 *   <li>Regions off = vanilla passthrough, always.</li>
 * </ul>
 */
class PearlTicketHoldTest {

    @Test
    void burningTicketNeverHeld() {
        // the countdown is pure vanilla until the ticket actually reaches zero
        assertFalse(PearlTicketHold.shouldHold(true, 40, true, 0));
        assertFalse(PearlTicketHold.shouldHold(true, 1, true, 0));
        assertFalse(PearlTicketHold.shouldHold(true, 5, true, 100));
    }

    @Test
    void livePearlAtZeroIsHeld() {
        assertTrue(PearlTicketHold.shouldHold(true, 0, true, 0));
        assertTrue(PearlTicketHold.shouldHold(true, 0, true, 5999));
    }

    @Test
    void deadPearlExpiresOnVanillaSchedule() {
        assertFalse(PearlTicketHold.shouldHold(true, 0, false, 0));
        assertFalse(PearlTicketHold.shouldHold(true, 0, false, 1234));
    }

    @Test
    void regionsOffIsAlwaysVanilla() {
        // no deferral on a single thread → no race → no hold
        assertFalse(PearlTicketHold.shouldHold(false, 0, true, 0));
        assertFalse(PearlTicketHold.shouldHold(false, 40, true, 0));
    }

    @Test
    void budgetIsTheLeakBound() {
        // the last affordable hold, then release
        assertTrue(PearlTicketHold.shouldHold(true, 0, true,
                PearlTicketHold.HOLD_BUDGET - 1));
        assertFalse(PearlTicketHold.shouldHold(true, 0, true,
                PearlTicketHold.HOLD_BUDGET));
        assertFalse(PearlTicketHold.shouldHold(true, 0, true,
                PearlTicketHold.HOLD_BUDGET + 10_000));
    }

    @Test
    void renewedTicketLeavesTheHold() {
        // a renewal resets ticksLeft to 40 → the burning rule applies again
        assertFalse(PearlTicketHold.shouldHold(true, 40, true,
                PearlTicketHold.HOLD_BUDGET - 1));
    }
}
