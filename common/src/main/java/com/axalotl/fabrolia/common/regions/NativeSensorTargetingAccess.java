package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.ai.targeting.TargetingConditions;

/** Source-owned native sensor state; callers must already own the source entity. */
public interface NativeSensorTargetingAccess {
    /** Returns a persistent copy of the native prototype, allocated only on first use. */
    TargetingConditions fabrolia$sensorCondition(int slot, TargetingConditions prototype);

    /** Reads existing source state without allocating a copy or resetting its range. */
    TargetingConditions fabrolia$existingSensorCondition(int slot, TargetingConditions prototype);
}
