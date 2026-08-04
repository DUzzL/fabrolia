package com.axalotl.fabrolia.common.regions;

/** Duck on {@code HopperBlockEntity} (installed by
 *  {@code mixin.lithium.FabroliaLithiumHopperSleepMixin}): clamp Lithium's
 *  failed-search stamps that a clock-domain change left in the hopper's
 *  future — called from the head of {@code pushItemsTick} with the tick's
 *  freshly read clock. See {@link BlockEntitySleepDomains#healSearchStamp}. */
public interface HopperClockHeal {
    void fabrolia$healSearchStamps(long evaluatorNow);
}
