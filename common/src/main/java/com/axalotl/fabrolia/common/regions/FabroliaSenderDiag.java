package com.axalotl.fabrolia.common.regions;

/** Duck interface installed on PlayerChunkSender by the sender mixin —
 *  per-player delivery diagnostics for /fabrolia regions chunksender. */
public interface FabroliaSenderDiag {
    String fabrolia$senderDiag();
}
