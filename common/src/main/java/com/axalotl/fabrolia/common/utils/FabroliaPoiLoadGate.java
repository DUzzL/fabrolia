package com.axalotl.fabrolia.common.utils;

/**
 * Column lifecycle shared by the vanilla storage, Lithium query adapters and
 * C2ME's unload/residency adapters. The storage owns the fence so all three
 * paths use the same admission and result-acquisition boundary.
 */
public interface FabroliaPoiLoadGate {

    /** Lease the column through result acquisition, without holding a lock. */
    <T> T fabrolia$acquirePoiColumn(long chunkKey, java.util.function.Supplier<T> body);

    /** Main-only unload request; deferred while a column result is being acquired. */
    void fabrolia$unloadPoiColumn(long chunkKey, Runnable body);

    /** A C2ME disk upgrade, not an ordinary query/prefetch, begins a new residency. */
    void fabrolia$beginPoiResidency(long chunkKey);

    /** Null means not fully materialized; empty means a known absent section. */
    java.util.Optional<com.axalotl.fabrolia.common.utils.PoiSectionReadSnapshot>
            fabrolia$portalSectionSnapshot(long sectionKey);

    /** Called only after prefetch completion, outside transfer claims. */
    void fabrolia$materializePortalColumn(net.minecraft.world.level.ChunkPos chunk, int sectionY);
}
