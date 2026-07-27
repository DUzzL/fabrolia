package com.axalotl.fabrolia.common.regions;

/**
 * Lifecycle of a {@link Region}. Transitions are one-way except NEW→ACTIVE,
 * which the regionizer flips on creation.
 *
 * <p>MERGING is a brief intermediate state held only by {@link ThreadedRegionizer#merge}
 * while the source region's chunks are being grafted into the target. Workers
 * MUST refuse to claim a region whose state is not ACTIVE — see
 * {@link RegionWorkerPool#workerLoop}.
 */
public enum RegionState {
    NEW,
    ACTIVE,
    MERGING,
    STOPPING,
    STOPPED
}
