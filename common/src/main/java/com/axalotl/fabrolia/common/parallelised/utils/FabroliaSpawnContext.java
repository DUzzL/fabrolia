package com.axalotl.fabrolia.common.parallelised.utils;

/**
 * Spawn-pipeline context.
 *
 * <p>The {@code SPAWN_STATE} ScopedValue that used to live here was removed
 * 2026-07-07 together with the mid-tick global-cap bails it existed to serve
 * (see NaturalSpawnerMixin / SpawnStateMixin) — vanilla checks the global cap
 * once per tick and the within-tick overshoot is load-bearing for farms.
 */
public final class FabroliaSpawnContext {

    /**
     * Set by {@code ServerChunkCacheMixin.fabrolia$captureTimeAndPassthrough} (the WrapOperation
     * around {@code NaturalSpawner.createState}) so {@link
     * com.axalotl.fabrolia.common.parallelised.utils.ParallelSpawnHelper#buildState} can read
     * the {@link net.minecraft.server.level.ServerLevel} that's currently running spawn —
     * vanilla's {@code createState} signature doesn't carry the level, but
     * {@link com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex} is keyed by it.
     *
     * <p>Safe as a static volatile because {@code createState} only runs on the main thread
     * (called from {@code ServerChunkCache.tickChunks}); per-level interleaving never happens
     * within a single tick.
     */
    public static volatile net.minecraft.server.level.ServerLevel currentLevel;

    private FabroliaSpawnContext() {}
}
