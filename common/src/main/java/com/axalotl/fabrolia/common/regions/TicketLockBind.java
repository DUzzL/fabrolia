package com.axalotl.fabrolia.common.regions;

/** Implemented by {@code TicketStorageSyncMixin} so the owning {@code ServerChunkCache}
 *  can hand the ticket storage its serialization monitor (the ChunkMap instance) at
 *  construction. TicketStorage has no vanilla link to its ChunkMap, but every
 *  off-main ticket write (region-player {@code ChunkMap.move} →
 *  {@code DistanceManager.addPlayer/removePlayer}) already runs under the ChunkMap
 *  monitor — binding it here lets the storage take the SAME monitor for every other
 *  access, restoring vanilla's single-thread invariant for the whole ticket system. */
public interface TicketLockBind {
    void fabrolia$setTicketLock(Object lock);
}
