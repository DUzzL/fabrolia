package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ChunkMap;

/** Implemented by {@code SimulationChunkTrackerMixin} so the owning {@link ChunkMap}
 *  can hand the tracker a back-reference at construction. The tracker has no vanilla
 *  link to its ChunkMap/level, but it IS the authoritative source the entity-ticking
 *  incremental list must follow (its {@code chunks} map is exactly what
 *  {@code DistanceManager.forEachEntityTickingChunk} iterates). Wiring the back-ref
 *  lets the tracker's {@code setLevel} seam feed {@link RegionTickingChunks}. */
public interface RegionTickingTrackerBind {
    void fabrolia$setChunkMap(ChunkMap chunkMap);
}
