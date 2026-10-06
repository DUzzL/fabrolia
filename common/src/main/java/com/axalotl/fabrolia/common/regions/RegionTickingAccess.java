package com.axalotl.fabrolia.common.regions;

/** Implemented by ChunkMapMixin — ChunkMap views and hooks the region runtime
 *  needs: the visible holder map, deferred owner-safe unloads and the natural
 *  spawning player set. */
public interface RegionTickingAccess {

    /** Direct view over the visible holder map. */
    Iterable<net.minecraft.server.level.ChunkHolder> fabrolia$visibleHolders();

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
