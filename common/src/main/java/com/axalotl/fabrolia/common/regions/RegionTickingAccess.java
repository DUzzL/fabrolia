package com.axalotl.fabrolia.common.regions;

/** Implemented by ChunkMapMixin — exposes the incremental per-region holder
 *  lists for {@code ChunkMap.forEachBlockTickingChunk}. In 26.2 that vanilla
 *  method is sourced from {@code DistanceManager.forEachEntityTickingChunk},
 *  so the tracker follows ENTITY_TICKING threshold crossings. */
public interface RegionTickingAccess {

    RegionTickingChunks fabrolia$regionTicking();

    /** Resolve the (live) ChunkHolder for a chunk by packed position — used by the
     *  {@code SimulationChunkTracker.setLevel} seam to attach a holder to a chunk that
     *  just crossed the ENTITY_TICKING threshold. Reads the live updating map (same
     *  ChunkHolder objects the visible snapshot exposes), so a just-promoted chunk that
     *  isn't in the visible snapshot yet still resolves. Returns null if not loaded. */
    net.minecraft.server.level.ChunkHolder fabrolia$holderForTicking(long packedChunkPos);

    /** Direct view over the visible holder map. Kept for diagnostics. */
    Iterable<net.minecraft.server.level.ChunkHolder> fabrolia$visibleHolders();

    /** Updating holder map. Kept for diagnostics; the runtime self-check uses
     *  the exact post-mixin {@code ChunkMap.forEachBlockTickingChunk} call. */
    Iterable<net.minecraft.server.level.ChunkHolder> fabrolia$updatingHolders();

    /** Pending-unloads holder map. Kept for diagnostics. */
    Iterable<net.minecraft.server.level.ChunkHolder> fabrolia$pendingUnloadHolders();
}
