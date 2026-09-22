package com.axalotl.fabrolia.common.utils;

/**
 * Decision logic for {@code RaidVillageDebounceMixin} — extracted so the
 * state machine is unit-testable without bootstrapping Minecraft. See the
 * mixin for the full incident write-up.
 *
 * <p><b>The two isVillage sites in {@code Raid.tick} are NOT equivalent</b>
 * (26.2, both run unconditionally every tick, in this order):
 * <ul>
 *   <li><b>Site 1</b> gates {@code moveRaidCenterToNearbyVillageSection} —
 *       the relocation is the LOAD-BEARING mechanic of stacked raid farms:
 *       the bottom village exists ~1s per cycle (villagers hold the bobbing
 *       workstation only that long), the raid starts there, then the bottom
 *       claim vanishes and relocation TRANSFERS the center up to the
 *       permanently-claimed top villager. Only after the transfer does the
 *       next raid omen create a NEW raid instead of extending this one —
 *       that is what "stacking" is. Site 1 must therefore stay on the raw
 *       vanilla answer, always: delaying relocation (the first version of
 *       this fix suppressed both sites) pins the center at the bottom and
 *       every new omen just extends the one raid — "one raid, no stack".</li>
 *   <li><b>Site 2</b> is the kill switch (no village after relocation →
 *       LOSS/stop). It fires when relocation found NO claimed POI anywhere —
 *       which under async regions also happens in transient global gap
 *       windows (all workstations mid-bobble at once), a phase vanilla never
 *       samples. ONLY this site is debounced.</li>
 * </ul>
 *
 * <p>Contract: called for EVERY {@code level.isVillage(center)} read inside
 * {@code Raid.tick} with the raid's own {@code ticksActive} as the time base
 * (scales with {@code /tick sprint} like the raid's other timers). Semantics:
 * <ul>
 *   <li>async off → raw passes through untouched on both sites.</li>
 *   <li>first call of a raid tick (site 1) → raw, always; the streak is
 *       neither counted nor reset here.</li>
 *   <li>second call of the same tick (site 2): raw true → streak resets
 *       (village exists somewhere — possibly after a transfer); raw false →
 *       streak counts ONE raid tick of "relocation found nothing" and the
 *       kill is suppressed while the streak is within tolerance.</li>
 * </ul>
 */
public final class RaidVillageDebounce {

    private RaidVillageDebounce() {
    }

    /** Per-raid mutable state; only ever touched from the raid's own tick. */
    public static final class State {
        /** {@code ticksActive} value whose site-1 call was already seen. */
        public long tickOfSiteOne = -1L;
        /** Consecutive raid ticks where relocation found no village. */
        public int villageGap = 0;
    }

    public static boolean answer(boolean raw, boolean regionsOn, long ticksActive,
                                 int tolerance, State s) {
        if (!regionsOn) {
            return raw;
        }
        if (s.tickOfSiteOne != ticksActive) {
            // Site 1: relocation trigger. Raw answer, always — the transfer
            // to the upper village IS the stacked-farm mechanic and must run
            // on the farm's own schedule, not on a debounced one.
            s.tickOfSiteOne = ticksActive;
            return raw;
        }
        // Site 2: the kill decision, once per raid tick.
        if (raw) {
            s.villageGap = 0;
            return true;
        }
        s.villageGap++;
        return s.villageGap <= tolerance;
    }
}
