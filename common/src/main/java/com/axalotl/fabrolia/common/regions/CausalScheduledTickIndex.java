package com.axalotl.fabrolia.common.regions;

/**
 * Monotonic cold-path hint installed on every {@code LevelChunkTicks}.
 *
 * <p>{@code false} is exact: no pulse-sensitive tick has ever entered this
 * container generation. {@code true} is deliberately only a may-contain bit;
 * removals do not clear it. That makes schedule/unpack maintenance constant
 * time while allowing the rare exact-readiness refusal to inspect only machine
 * containers, never the hundreds of thousands of leaves/sand/gravel containers
 * seen during terrain generation.
 */
public interface CausalScheduledTickIndex {

    boolean fabrolia$mayContainPulseSensitiveTick();

    /** Publish-before-insert mark used by the already-existing LevelTicks
     * schedule wrapper, avoiding another allocation-bearing hot-path wrapper
     * on every terrain LevelChunkTicks schedule. */
    void fabrolia$markPulseSensitiveTick();
}
