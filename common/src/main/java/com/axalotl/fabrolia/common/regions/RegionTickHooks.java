package com.axalotl.fabrolia.common.regions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod-facing hooks around the per-region entity tick — the integration point
 * for region-scoped batch precomputation (Rustmi's regional cramming
 * candidate kernel is the first consumer).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link Hook#preEntityTick} runs on the coordinating worker, region
 *       claim held, BEFORE any entity of this region ticks this server tick —
 *       including before the dense fan-out publishes helper sub-batches, so
 *       anything the hook publishes is happens-before-visible to helpers.</li>
 *   <li>{@link Hook#postEntityTick} runs on the same worker, claim still held,
 *       after the region's entity tick fully completed (dense fan-in
 *       included).</li>
 *   <li>Fired only for region-owned entity ticking (scheduler active); the
 *       global/flat-pool path never fires these.</li>
 * </ul>
 *
 * <p>Zero overhead when unregistered: one volatile null check. Hook failures
 * propagate through the same vanilla/server crash boundary as the entity phase;
 * continuing after a partially-applied integration callback is not safe.
 */
public final class RegionTickHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionTickHooks.class);

    public interface Hook {
        /** Coordinator thread, claim held, before any entity of the region ticks. */
        void preEntityTick(Region region);

        /** Coordinator thread, claim held, after the region's entity tick (incl. fan-in). */
        void postEntityTick(Region region);
    }

    private static volatile Hook hook;

    private RegionTickHooks() {}

    /** Register the (single) region tick hook. Last registration wins. */
    public static void register(Hook h) {
        hook = h;
        LOGGER.info("Region tick hook registered: {}", h.getClass().getName());
    }

    public static void unregister(Hook h) {
        if (hook == h) {
            hook = null;
        }
    }

    static void firePre(Region region) {
        Hook h = hook;
        if (h == null) return;
        h.preEntityTick(region);
    }

    static void firePost(Region region) {
        Hook h = hook;
        if (h == null) return;
        h.postEntityTick(region);
    }
}
