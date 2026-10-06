package com.axalotl.fabrolia.common.regions;

/**
 * Lifecycle of a {@link Region}. Transitions are one-way except NEW→ACTIVE,
 * which the regionizer flips on creation.
 *
 * <p>The grid never merges regions, so nothing enters MERGING today; the constant is
 * kept so an unexpected state still fails the ACTIVE checks. Workers MUST refuse to
 * claim a region whose state is not ACTIVE — see {@link RegionWorkerPool#workerLoop}.
 */
public enum RegionState {
    NEW,
    ACTIVE,
    MERGING,
    STOPPING,
    STOPPED
}
