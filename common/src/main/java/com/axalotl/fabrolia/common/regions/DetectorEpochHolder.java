package com.axalotl.fabrolia.common.regions;

/** Wall-epoch memo carried by each daylight detector block entity — the
 *  per-detector state for the globally phase-coherent update gate
 *  (DaylightDetectorBlockMixin). Per-detector, NOT per-thread: a shared
 *  memo makes the first detector a thread visits consume the epoch and
 *  permanently starves every other detector on that thread (rig dd2). */
public interface DetectorEpochHolder {
    long fabrolia$lastDetectorEpoch();
    void fabrolia$setDetectorEpoch(long e);
}
