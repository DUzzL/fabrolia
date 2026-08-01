package com.axalotl.fabrolia.common.utils;

/**
 * Duck interface on {@code SectionStorage} (task #38): lets the C2ME unload
 * guard ({@code mixin/c2me/PoiUnloadGuardMixin}) see the unpack handshake state
 * owned by {@code mixin/world/SectionStorageMixin}. Two mixins cannot share a
 * {@code @Unique} field, so the fence is published through this interface —
 * same pattern as {@code FabroliaPoiTracker}.
 */
public interface FabroliaPoiLoadGate {

    /**
     * True while a {@code getOrLoad}/{@code unpackChunk} winner is between
     * "claimed the chunk" and "fan-out complete" for this chunk. MUST be
     * called while holding the storage {@code loadLock}.
     */
    boolean fabrolia$poiLoadInFlight(long chunkKey);
}
