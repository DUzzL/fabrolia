package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/** Pearl-only nonblocking exact membership proof supplied by a PESM callback. */
public interface PearlSectionMembershipAccess {

    boolean fabrolia$isResidentPearl(PersistentEntitySectionManager<?> manager, Entity pearl);
}
