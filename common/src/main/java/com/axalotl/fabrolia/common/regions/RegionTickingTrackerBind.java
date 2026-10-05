package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ChunkMap;

/** Implemented by {@code SimulationChunkTrackerMixin} so the owning {@link ChunkMap}
 *  can hand the tracker a back-reference at construction. The tracker has no vanilla
 *  link to its ChunkMap/level; its {@code setLevel} seam needs the level for the
 *  pearl-freeze trace. */
public interface RegionTickingTrackerBind {
    void fabrolia$setChunkMap(ChunkMap chunkMap);
}
