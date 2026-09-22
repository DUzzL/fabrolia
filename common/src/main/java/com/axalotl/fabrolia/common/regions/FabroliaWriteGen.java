package com.axalotl.fabrolia.common.regions;

/** Per-chunk write witness and final full-snapshot enqueue commit gate. */
public interface FabroliaWriteGen {
    long fabrolia$writeGen();

    void fabrolia$bumpWriteGen();

    /** Non-blocking generation reservation immediately before transport enqueue. */
    boolean fabrolia$tryReserveFullChunkEnqueue(long expectedGeneration);

    /** Releases the exact reservation after its ordered send task was accepted. */
    void fabrolia$releaseFullChunkEnqueue(long expectedGeneration);
}
