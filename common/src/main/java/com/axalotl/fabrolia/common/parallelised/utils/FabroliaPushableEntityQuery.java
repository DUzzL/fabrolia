package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Optional server-level duck for a concurrent push-candidate index. Lithium's
 * implementation is mixed in only when Lithium is present; the always-loaded
 * Level wrapper uses an {@code instanceof} check and retains its safe generic
 * fallback without a hard optional-dependency reference.
 */
public interface FabroliaPushableEntityQuery {
    List<Entity> fabrolia$getPushableEntities(Entity pusher, AABB boundingBox);
}
