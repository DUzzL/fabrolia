package com.axalotl.fabrolia.common.regions;

/**
 * Implemented by {@code ServerChunkCacheMixin}: vanilla
 * {@code ServerChunkCache.tickChunks} restricted to one island, executed by
 * that island's unit coordinator at the island's cadence on the island's
 * L-clock (see {@link IslandTickUnit}).
 */
public interface IslandChunkPassAccess {

    void fabrolia$tickIslandChunks(RegionizedServer rs, IslandRegistry.PIsland island,
                                   long islandTime);
}
