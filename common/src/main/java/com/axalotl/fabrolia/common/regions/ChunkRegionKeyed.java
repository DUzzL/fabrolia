package com.axalotl.fabrolia.common.regions;

/**
 * Implemented by LevelChunkMixin. Caches the chunk's region-grid key for the
 * life of the chunk — its position never changes, so the per-tick grouping
 * loop (fabrolia$groupAndSubmit over ~60k ticking chunks at 100 players) reads a
 * field instead of chasing ChunkPos and re-deriving shifts every tick. The
 * grid shift is stored alongside so a config change can never serve a stale
 * key (one int compare per read).
 */
public interface ChunkRegionKeyed {

    /** Region key under {@code shift}, computing and caching on first use. */
    long fabrolia$regionKey(int shift);
}
