package com.axalotl.fabrolia.common.regions;

/** Implemented by ChunkMapMixin — read access to ChunkMap internals for
 *  diagnostics, natural-spawn player counting and save-time unload flushing. */
public interface RegionTickingAccess {

    /** Direct view over the visible holder map. Kept for diagnostics. */
    Iterable<net.minecraft.server.level.ChunkHolder> fabrolia$visibleHolders();

    /** Updating holder map. Kept for diagnostics; the runtime self-check uses
     *  the exact post-mixin {@code ChunkMap.forEachBlockTickingChunk} call. */
    Iterable<net.minecraft.server.level.ChunkHolder> fabrolia$updatingHolders();

    /** Pending-unloads holder map. Kept for diagnostics. */
    Iterable<net.minecraft.server.level.ChunkHolder> fabrolia$pendingUnloadHolders();

    /**
     * Run every accepted ChunkMap unload continuation before a level save can
     * report success. The implementation requires physical Main and asserts
     * that the deferred queue is empty on return.
     */
    void fabrolia$flushDeferredOwnerUnloads();

    /**
     * Visit every player {@code DistanceManager.playersPerChunk} counts for
     * natural spawning (PlayerMap members with the ignored bit clear), with
     * the packed chunk of its last section position. Safe off-main.
     */
    void fabrolia$forEachSpawnCountedPlayer(
            java.util.function.ObjLongConsumer<net.minecraft.server.level.ServerPlayer> action);

    /** Vanilla {@code ChunkMap.playerIsCloseEnoughForSpawning}: non-spectator
     *  within 128 blocks of the chunk centre (mod wraps such as Vanish apply). */
    boolean fabrolia$playerCloseEnoughForSpawning(net.minecraft.server.level.ServerPlayer player,
                                                  net.minecraft.world.level.ChunkPos pos);
}
