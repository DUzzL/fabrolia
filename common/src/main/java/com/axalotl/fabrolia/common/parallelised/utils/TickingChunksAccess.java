package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap;

/**
 * Duck on {@code PersistentEntitySectionManager}: the incrementally-maintained
 * set of entity-TICKING chunk positions (mirror of {@code canPositionTick}'s
 * predicate; value 1 = ticking, absent = not). Lock-free concurrent reads;
 * writes ride the single {@code updateChunkStatus(ChunkPos, Visibility)}
 * mutation funnel.
 */
public interface TickingChunksAccess {
    Long2IntConcurrentHashMap fabrolia$tickingChunks();

    /** Raw section storage — census-side authoritative membership checks
     *  (SpawnIndex phantom purge). */
    net.minecraft.world.level.entity.EntitySectionStorage<net.minecraft.world.level.entity.EntityAccess> fabrolia$sectionStorage();
}