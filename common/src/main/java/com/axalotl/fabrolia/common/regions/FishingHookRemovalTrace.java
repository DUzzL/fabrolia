package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.Entity;

/** Receives the authoritative Entity.setRemoved transition for fishing-hook diagnostics. */
public interface FishingHookRemovalTrace {
    void fabrolia$traceRemoval(Entity.RemovalReason reason);
}
