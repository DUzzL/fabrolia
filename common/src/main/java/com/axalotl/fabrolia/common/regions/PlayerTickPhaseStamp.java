package com.axalotl.fabrolia.common.regions;

/**
 * Per-ServerPlayer cooperative lifecycle generation paired with the entity lease.
 * Sequential direct/mod consumers remain legal, as in vanilla; the shared identity
 * lease only excludes actual overlap with an owner turn.
 */
public interface PlayerTickPhaseStamp {
    /** Monotonic cooperative lifecycle generation (mount/removal/teleport/session copy). */
    long fabrolia$playerLifecycleEpoch();
    void fabrolia$bumpPlayerLifecycleEpoch();
}
