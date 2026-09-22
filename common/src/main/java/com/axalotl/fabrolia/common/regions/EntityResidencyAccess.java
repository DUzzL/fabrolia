package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.Entity;

/** Loaded-section ownership remains meaningful when an entity stops ticking. */
public interface EntityResidencyAccess {
    long NO_SECTION = Long.MIN_VALUE;

    /** Published section metadata only; callers must claim it and revalidate before live reads. */
    long fabrolia$residentSection(Entity expected);

    /** Revalidate callback storage identity and exact membership under the claimed section owner. */
    boolean fabrolia$isResident(Entity expected, long sectionKey);
}
