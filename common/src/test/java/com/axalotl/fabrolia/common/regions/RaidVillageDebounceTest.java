package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.utils.RaidVillageDebounce;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the debounce state machine for the stacked-raid-farm fix
 * (2026-08-14 incident). Two hard requirements from prod:
 * <ul>
 *   <li>Site 1 (relocation trigger) must stay RAW — the bottom→top transfer
 *       of the raid center IS the stacking mechanic; the first shipped
 *       version debounced both sites, pinned the center at the bottom, and
 *       the farm degraded to "one raid, no stack".</li>
 *   <li>Site 2 (kill switch) must tolerate transient global claim gaps
 *       (all workstations mid-bobble) or the raid dies in 3-4 ticks.</li>
 * </ul>
 */
class RaidVillageDebounceTest {

    private static final int TOL = 3;

    @Test
    void siteOneAlwaysRawSoTransfersStayOnFarmSchedule() {
        RaidVillageDebounce.State s = new RaidVillageDebounce.State();
        // bottom claim gone at sample time → relocation must fire immediately,
        // even with a long gap history behind
        assertFalse(RaidVillageDebounce.answer(false, true, 10, TOL, s));
        s.villageGap = 2; // pretend two gap ticks already counted
        assertFalse(RaidVillageDebounce.answer(false, true, 11, TOL, s),
                "site 1 must never be suppressed, or the center pins and stacking dies");
    }

    @Test
    void siteTwoSuppressesTheKillWithinTheWindow() {
        RaidVillageDebounce.State s = new RaidVillageDebounce.State();
        for (long tick = 0; tick < TOL; tick++) {
            assertFalse(RaidVillageDebounce.answer(false, true, tick, TOL, s),
                    "site 1 raw (relocation may run)");
            assertTrue(RaidVillageDebounce.answer(false, true, tick, TOL, s),
                    "site 2 suppressed at tick " + tick);
        }
        // window exhausted: vanilla kill path gets the raw false
        RaidVillageDebounce.answer(false, true, TOL, TOL, s);   // site 1
        assertFalse(RaidVillageDebounce.answer(false, true, TOL, TOL, s),
                "site 2 releases after tolerance");
    }

    @Test
    void transferCycleNeverCountsAGap() {
        RaidVillageDebounce.State s = new RaidVillageDebounce.State();
        // farm cycle: site 1 false (bottom claim gone) → relocation up →
        // site 2 true (top village found) — repeated every cycle forever
        for (long tick = 0; tick < 50; tick++) {
            assertFalse(RaidVillageDebounce.answer(false, true, tick, TOL, s));
            assertTrue(RaidVillageDebounce.answer(true, true, tick, TOL, s));
        }
        assertEquals(0, s.villageGap, "successful transfers must never charge the streak");
    }

    @Test
    void gapRecoveryResetsForNextWindow() {
        RaidVillageDebounce.State s = new RaidVillageDebounce.State();
        for (long tick = 0; tick < 2; tick++) {
            RaidVillageDebounce.answer(false, true, tick, TOL, s); // site 1
            RaidVillageDebounce.answer(false, true, tick, TOL, s); // site 2
        }
        assertEquals(2, s.villageGap);
        // claim reappears at the center: site 1 true, site 2 true → reset
        assertTrue(RaidVillageDebounce.answer(true, true, 2, TOL, s));
        assertTrue(RaidVillageDebounce.answer(true, true, 2, TOL, s));
        assertEquals(0, s.villageGap);
        // next gap gets the full window again
        for (long tick = 3; tick < 3 + TOL; tick++) {
            RaidVillageDebounce.answer(false, true, tick, TOL, s);
            assertTrue(RaidVillageDebounce.answer(false, true, tick, TOL, s));
        }
        RaidVillageDebounce.answer(false, true, 3 + TOL, TOL, s);
        assertFalse(RaidVillageDebounce.answer(false, true, 3 + TOL, TOL, s));
    }

    @Test
    void regionsOffIsPureVanillaOnBothSites() {
        RaidVillageDebounce.State s = new RaidVillageDebounce.State();
        assertFalse(RaidVillageDebounce.answer(false, false, 10, TOL, s));
        assertFalse(RaidVillageDebounce.answer(false, false, 10, TOL, s));
        assertEquals(0, s.villageGap, "regions-off must not even count the streak");
        assertTrue(RaidVillageDebounce.answer(true, false, 11, TOL, s));
    }

    @Test
    void streakCountsOncePerRaidTickNotPerCall() {
        RaidVillageDebounce.State s = new RaidVillageDebounce.State();
        RaidVillageDebounce.answer(false, true, 42, TOL, s); // site 1 — never counts
        assertEquals(0, s.villageGap);
        RaidVillageDebounce.answer(false, true, 42, TOL, s); // site 2 — counts once
        assertEquals(1, s.villageGap);
    }
}
