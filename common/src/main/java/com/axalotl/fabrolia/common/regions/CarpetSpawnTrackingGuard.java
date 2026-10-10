package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;

/** Carpet's world-cycle spawn counters cannot observe independent or deferred spawn passes. */
public final class CarpetSpawnTrackingGuard {
    public static final String MESSAGE =
            "Carpet spawn tracking and /spawn test are disabled while Fabrolia's parallel spawning is active.";

    private CarpetSpawnTrackingGuard() {
    }

    public static boolean blocked() {
        return blocked(FabroliaConfig.disabled, RegionScheduler.asyncLive(),
                FabroliaConfig.asyncIslands, FabroliaConfig.enableRegionThreading,
                FabroliaConfig.enableFabroliaSpawn, ParallelProcessor.executor != null);
    }

    static boolean blocked(boolean disabled, boolean asyncLive, boolean asyncRequested,
                           boolean regionThreading, boolean parallelSpawn, boolean executorReady) {
        // The live flag covers an OFF request whose islands have not yet retired.
        // The requested mode covers an ON request before its first worker can spawn.
        return asyncLive || (!disabled && ((asyncRequested && regionThreading)
                || (parallelSpawn && executorReady)));
    }
}
