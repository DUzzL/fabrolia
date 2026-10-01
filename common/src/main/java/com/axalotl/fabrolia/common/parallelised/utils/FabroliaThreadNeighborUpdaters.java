package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.level.redstone.CollectingNeighborUpdater;

/**
 * Level duck: one vanilla {@link CollectingNeighborUpdater} per calling thread.
 *
 * <p>Vanilla's updater is single-threaded state (stack / addedThisLayer /
 * count). Islands run neighbour cascades concurrently on worker threads, and
 * {@code IslandScheduledTicks} keeps cascades position-isolated per thread, so
 * each thread drives its own unmodified vanilla instance: the vanilla body and
 * every other mod's injections into it run exactly as in vanilla. The level's
 * own field instance (the "primary") never runs updates; it only carries the
 * debug listener that {@code ServerLevel.tick} sets each tick.
 */
public interface FabroliaThreadNeighborUpdaters {
    CollectingNeighborUpdater fabrolia$threadNeighborUpdater();

    /** Level unload: drop every worker thread's instance (each retains the level). */
    void fabrolia$releaseThreadNeighborUpdaters();

    /**
     * A worker thread's ThreadLocal value. It holds the per-thread updater by a
     * field the level can clear on unload: ThreadLocalMap only expunges stale
     * entries lazily, so a value that referenced the updater (and through it
     * the level) directly would keep an unloaded level alive on every worker.
     */
    final class Slot {
        public CollectingNeighborUpdater updater;
    }
}
