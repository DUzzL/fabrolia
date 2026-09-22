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
 *   <li>a provider failure propagates through the vanilla entity-tick crash
 *       boundary; only an explicit {@code null} is a supported vanilla fallback.</li>
 *   <li>register during bootstrap, or on physical Main once island workers
 *       have started; live replacement acquires exclusive gameplay ownership.</li>
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
    private static final Object BOOTSTRAP = new Object();
    private static boolean bootstrapSealed;

    private SensorCandidateBridge() {}

    /** Last registration wins; live publication excludes every entered island E. */
    public static void register(Provider p) {
        java.util.Objects.requireNonNull(p);
        boolean bootstrap;
        synchronized (BOOTSTRAP) {
            bootstrap = !bootstrapSealed;
            if (bootstrap) provider = p;
        }
        if (bootstrap) {
            LOGGER.info("Sensor candidate provider registered: {}", p.getClass().getName());
            return;
        }
        com.axalotl.fabrolia.common.regions.RegionScheduler.runGlobalMainBody(
                com.axalotl.fabrolia.common.ParallelProcessor.getServer(), () -> {
                    provider = p;
                    LOGGER.info("Sensor candidate provider registered: {}", p.getClass().getName());
                });
    }
    /** Closes bootstrap publication before the first worker can enter gameplay. */
    public static void sealBootstrap() {
        synchronized (BOOTSTRAP) {
            bootstrapSealed = true;
        }
    }

    /** Absence remains stable while a real island or global claim is retained. */
    public static boolean hasProvider() {
        return provider != null;
    }


    /**
     * Query the provider, or return {@code null} when absent/declining. A thrown
     * provider failure is not converted into a successful vanilla fallback because
     * the partially entered provider may already have observed or mutated state.
     */
    public static List<LivingEntity> tryNearestLiving(ServerLevel level, LivingEntity body, double followRange) {
        Provider p = provider;
        if (p == null) return null;
        return p.nearestLiving(level, body, followRange);
    }
}
