package com.axalotl.fabrolia.common.utils;

import java.util.concurrent.CompletableFuture;

/**
 * Duck interface implemented by ServerChunkCacheMixin. When
 * {@code FabroliaConfig.enableSpawnBarrierOverlap} is on, the parallel-spawn future launched
 * in tickChunks is NOT awaited at the end of the chunk phase — ServerLevelMixin's entity
 * barrier takes it via this interface and awaits {@code allOf(entityBatch, spawn)} in a
 * single park. Random/fluid block ticks are still awaited inside the chunk phase (vanilla
 * order: block mutations complete before scheduled ticks/entities); spawn only ADDS
 * entities, which the entity phase already tolerates concurrently.
 */
public interface SpawnFutureTaker {
    /** @return the level's in-flight spawn future, clearing it — or null if none. */
    CompletableFuture<Void> fabrolia$takeSpawnFuture();

    /**
     * @return the level's in-flight per-region random/fluid-tick future, clearing it — or
     *         null if none. Region mode only: per-region block-tick tasks are queued ahead
     *         of the region's entity task (FIFO), so awaiting this at the entity barrier
     *         guarantees each region ran its chunks' random ticks before its entities —
     *         vanilla's blocks-then-entities order, enforced per-area instead of globally.
     */
    CompletableFuture<Void> fabrolia$takeBlockTicksFuture();

    /**
     * Region mode: submit the chunk-phase-collected spawn / random-tick work to the
     * region queues NOW (entity-phase start — after main's runBlockEvents, before
     * entity dispatch). No-op when nothing was deferred. Must run on main BEFORE
     * dispatchOwnedEntityTicks so the futures are set for the takers above and the
     * ColorGate submit-before-dispatch invariant holds. Rationale: worker-side chunk
     * WRITERS (random ticks / precipitation / thunder / spawn) must never overlap
     * main's piston moveBlocks inside runBlockEvents — that overlap tore chunk state
     * in player-area chunks (flying-machine breakage reports).
     */
    default void fabrolia$armDeferredChunkWork() {}

}
