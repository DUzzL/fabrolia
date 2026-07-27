package com.axalotl.fabrolia.common.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Pluggable provider for the nearest-living-entities sensor query — the
 * expensive {@code level.getEntitiesOfClass(LivingEntity.class, box, ...)}
 * section walk that {@code NearestLivingEntitiesSensorMixin} performs for
 * every sensing mob. Oxidizium registers a region-batch-backed provider
 * (candidate superset from the region snapshot, re-checked live); without one
 * — or when the provider declines — the mixin runs the vanilla query.
 *
 * <p>Provider contract:
 * <ul>
 *   <li>return EXACTLY the set the vanilla query would return
 *       ({@code e != body && e.isAlive()}, live box intersection) in ANY
 *       order — the caller sorts;</li>
 *   <li>return {@code null} to decline (body not batched, sensor box exceeds
 *       coverage, candidate overflow) — the caller falls back to vanilla;</li>
 *   <li>never wedge the entity tick: a throw drops the provider for the
 *       session, same containment rule as {@link
 *       com.axalotl.fabrolia.common.regions.RegionTickHooks}.</li>
 * </ul>
 */
public final class SensorCandidateBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(SensorCandidateBridge.class);

    public interface Provider {
        /**
         * Living entities the vanilla sensor query would return for
         * {@code body} with the given follow range, or {@code null} to
         * decline (caller runs the vanilla query).
         */
        List<LivingEntity> nearestLiving(ServerLevel level, LivingEntity body, double followRange);
    }

    private static volatile Provider provider;

    private SensorCandidateBridge() {}

    /** Register the (single) sensor query provider. Last registration wins. */
    public static void register(Provider p) {
        provider = p;
        LOGGER.info("Sensor candidate provider registered: {}", p.getClass().getName());
    }

    /**
     * Query the provider, or return {@code null} when absent / declining /
     * broken (callers always have the vanilla query as the fallback).
     */
    public static List<LivingEntity> tryNearestLiving(ServerLevel level, LivingEntity body, double followRange) {
        Provider p = provider;
        if (p == null) return null;
        try {
            return p.nearestLiving(level, body, followRange);
        } catch (Throwable t) {
            provider = null;
            LOGGER.error("Sensor candidate provider threw — provider disabled for this session", t);
            return null;
        }
    }
}
