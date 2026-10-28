package com.axalotl.fabrolia.common.regions;

/**
 * Bridge implemented by {@code ServerChunkCacheMixin}. Island-owned holder
 * changes are published opportunistically before the owning real player's
 * late listener tick. The predicted-block correction journal is the hard
 * update-before-prediction-ACK authority when a holder was demoted meanwhile.
 */
public interface IslandBlockUpdateAccess {

    void fabrolia$flushIslandBlockUpdates(RegionizedServer runtime,
                                          IslandRegistry.PIsland island);
}
