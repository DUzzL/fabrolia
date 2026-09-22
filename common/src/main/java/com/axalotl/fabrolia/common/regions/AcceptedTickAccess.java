package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.ticks.ScheduledTick;

/** Reinsert an already accepted obligation, not a new deduplicated request.
 * Uses the canonical queue, as vanilla unpack does for same-key saved ticks. */
public interface AcceptedTickAccess<T> {
    void fabrolia$restoreAccepted(ScheduledTick<T> tick);
}
