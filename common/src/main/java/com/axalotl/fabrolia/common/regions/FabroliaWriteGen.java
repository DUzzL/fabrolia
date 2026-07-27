package com.axalotl.fabrolia.common.regions;

/** Per-chunk write witness (delivery fence): bumped on every block/BE write
 *  through LevelChunk. A sealed packet is enqueued only if the chunk's gen
 *  still equals the gen captured under the owner's claim at seal time —
 *  catching exactly the writes whose dirty flags a pending player would have
 *  missed, with zero false failures for quiet chunks. */
public interface FabroliaWriteGen {
    long fabrolia$writeGen();

    void fabrolia$bumpWriteGen();
}
