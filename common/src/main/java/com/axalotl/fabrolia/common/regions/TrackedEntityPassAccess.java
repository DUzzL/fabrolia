package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Duck installed on {@code ChunkMap.TrackedEntity} for Fabrolia's VMP tracker tick.
 *
 * <p>The snapshot is written once per tracker tick by the position pre-pass and
 * read by the viewer passes after that pre-pass completed (fan-out join). The
 * decisions of one tick use exactly the position recorded as the next tick's
 * "previous" position, so a move after the snapshot is always seen as a move on
 * the next tick.</p>
 */
public interface TrackedEntityPassAccess {

    /**
     * Records this tick's position snapshot (and keeps {@code lastSectionPos} current).
     *
     * @param areaChunkChanged the area-index chunk read for this tick differs from
     *                         the indexed one (read before the snapshot position)
     * @return true if the tracker moved since the previous snapshot
     */
    boolean fabrolia$passSnapshot(boolean areaChunkChanged);

    /** Movement flag of the current snapshot. */
    boolean fabrolia$passMoved();

    /** Packed chunk of the entity's own section (no position transformers) at the snapshot. */
    long fabrolia$passSectionChunk();

    /** True for exactly one caller per stamp. */
    boolean fabrolia$passClaimTick(long stamp);

    /**
     * Exact vanilla {@code updatePlayer} outcome equals the current pairing, the
     * tracker is live and nothing is staged or committing for it. False means the
     * real (staged) body must run.
     */
    boolean fabrolia$passPairingCurrent(ServerPlayer viewer, Vec3 viewerPos, int viewDistance);

    /** {@code removePlayer(viewer)} would change nothing: not paired, nothing staged. */
    boolean fabrolia$passAbsentAndIdle(ServerPlayer viewer);

    /** Lock-free read of the tracking state (writes stay under the tracker monitor). */
    int fabrolia$islandTrackingStateRelaxed();

    /** The tracker's state publication ({@code ServerEntity.sendChanges}). */
    void fabrolia$passSendChanges();

    /** Lock-free read of the island cadence owner (writes stay under the tracker monitor). */
    IslandRegistry.PIsland fabrolia$islandTrackingOwnerRelaxed();

    /**
     * When no membership is staged or committing, feeds every current viewer to
     * {@code viewerSink} and returns true; otherwise returns false and feeds none.
     */
    boolean fabrolia$passViewersSettled(java.util.function.Consumer<ServerPlayer> viewerSink);

    /** VMP's removeEntityTracker was entered for this tracker generation. */
    void fabrolia$markVmpRemoved();

    boolean fabrolia$vmpRemoved();

    /** Position + 1 in the tracker index's dense area-index mirror, 0 when absent
     *  (index lock). */
    int fabrolia$denseSlot();

    void fabrolia$setDenseSlot(int slot);
}
