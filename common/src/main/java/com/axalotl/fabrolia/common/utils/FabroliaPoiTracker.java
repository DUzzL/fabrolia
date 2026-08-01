package com.axalotl.fabrolia.common.utils;

/**
 * Duck interface on {@code PoiManager$DistanceTracker} (task #38).
 *
 * <p>The tracker is a package-private inner class, so the deferral state and the
 * drain live ON it rather than on {@code PoiManager}, and the {@code PoiManager}
 * call-site wraps reach it through this interface.
 *
 * <p>See {@code PoiDistanceTrackerMixin} for why notifications into the village
 * distance graph must be deferred at all, and why the drain has to be atomic
 * under the tracker monitor.
 */
public interface FabroliaPoiTracker {

    /**
     * Record that a section's village-centre status may have changed. Lock-free
     * and callable while holding any POI lock — that is the whole point.
     */
    void fabrolia$enqueueTrackerUpdate(long sectionPos);

    /**
     * Apply everything enqueued since the last drain, atomically under the
     * tracker monitor. MUST be called holding no POI section or storage lock.
     */
    void fabrolia$drainTrackerUpdates();
}
