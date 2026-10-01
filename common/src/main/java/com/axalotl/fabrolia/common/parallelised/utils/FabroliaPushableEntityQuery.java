package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/**
 * Optional server-level duck for a concurrent push-candidate index. Lithium's
 * implementation is mixed in only when Lithium is present; the always-loaded
 * Level wrapper uses an {@code instanceof} check and falls back to vanilla's
 * own {@code getEntities} call without a hard optional-dependency reference.
 */
public interface FabroliaPushableEntityQuery {
    /**
     * Same result, in the same order, as vanilla
     * {@code getEntities(pusher, boundingBox, selector)}; {@code null} when the
     * index is unavailable and the caller must run vanilla's query.
     */
    List<Entity> fabrolia$getPushableEntitiesOrNull(
            Entity pusher, AABB boundingBox, Predicate<? super Entity> selector);
}
