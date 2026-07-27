package com.axalotl.fabrolia.common.regions;

/** ChunkMap → owning ServerLevel (implemented by ChunkMapMixin). */
public interface LevelAccess {
    net.minecraft.server.level.ServerLevel fabrolia$level();
}
