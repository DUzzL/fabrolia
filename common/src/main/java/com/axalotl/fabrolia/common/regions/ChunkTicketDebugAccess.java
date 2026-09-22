package com.axalotl.fabrolia.common.regions;

/** Main-thread snapshot of the tickets retaining one chunk. */
public interface ChunkTicketDebugAccess {
    String fabrolia$ticketDebug(long chunkKey);

    /** Live propagator level; call only on physical Main after draining its fixpoint. */
    int fabrolia$liveSimulationLevel(long chunkKey);
}
