package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;

/**
 * Duck installed on {@code DistanceManager$FixedPlayerDistanceChunkTracker}:
 * player-proximity chunk levels as of the last COMPLETED propagation wave.
 * Sibling of {@link CompletedSimulationView} — same incident class, one tracker
 * over (finding #1 of the 2026-08-01 SyncAll audit).
 *
 * <p>Why this exists: vanilla's spawn-proximity readers
 * ({@code DistanceManager.hasPlayersNearby} / {@code getSpawnCandidateChunks} /
 * {@code getNaturalSpawnChunkCount}) each START by running
 * {@code naturalSpawnChunkCounter.runAllUpdates()} — an entire
 * {@code DynamicGraphMinFixedPoint} relaxation wave whose blanket monitor is
 * held for the wave's whole (unbounded) duration. Spawn workers hit
 * {@code hasPlayersNearby} once per spawn position (via
 * {@code ChunkMap.getPlayersCloseForSpawning} under the ChunkMap monitor), so a
 * worker could park on main's wave WHILE HOLDING the ChunkMap monitor —
 * convoying every other worker in {@code ChunkMap.move} behind it, one added
 * lock away from a real cycle ({@code SimulationChunkTracker.setLevel} already
 * calls Fabrolia code under the tracker monitor). Worse, the unsynchronized
 * door {@code ChunkMap.anyPlayerCloseEnoughForSpawning} let a worker run the
 * wave itself, reading the plain {@code playersPerChunk} map with no ChunkMap
 * monitor against concurrent {@code ChunkMap.move} rehashes (the 2026-06-10
 * AIOOBE class).
 *
 * <p>With this view, readers never run — or wait on — the propagator: waves run
 * only inside {@code DistanceManager.runAllUpdates(ChunkMap)} (main-only:
 * {@code ServerChunkCacheMixin.fabrolia$serializeDistanceUpdatesWithPlayerMove}
 * refuses off-main callers and takes the ChunkMap monitor on main), and the
 * snapshot is republished at {@code runAllUpdates} RETURN. Staleness bound is
 * one consolidation (≤ 1 tick) — the same class {@link CompletedSimulationView}
 * already accepted for the simulation gates, and vanilla's own per-tick order
 * (consolidate, then collect spawn chunks) means main observes fresh values.
 */
public interface CompletedPlayerProximityView {

    /** Level from the last completed wave; defaultReturnValue (= maxDistance+2,
     *  i.e. "no player nearby") when absent. */
    int fabrolia$completedLevel(long chunkPos);

    /** The published snapshot itself (read-only — NEVER mutate). */
    Long2ByteMap fabrolia$completedChunks();
}
