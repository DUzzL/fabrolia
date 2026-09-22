package com.axalotl.fabrolia.common.mixin.vmp;

import com.axalotl.fabrolia.common.vmp.PublishedLong2ObjectMap;

import com.ishland.vmp.common.chunkwatching.AreaPlayerChunkWatchingManager;
import com.ishland.vmp.common.chunkwatching.PlayerClientVDTracking;
import com.ishland.vmp.common.maps.AreaMap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Makes VMP's player-area index safe for asynchronous islands without putting
 * its read-mostly gameplay queries behind one dimension-wide lock.
 *
 * <p>The pinned VMP manager owns two mutable {@link AreaMap}s and one plain
 * position map. Its five mutation entrances remain exact and are serialized by
 * one non-fair reentrant lock. Reentrancy is required because VMP's
 * {@code tick} can call {@code movePlayer}. The original mutation bodies,
 * listener order, player predicates, view-distance calculation, and thrown
 * failures remain authoritative.</p>
 *
 * <p>The two array readers used by spawning, mob despawn, block spawners, and
 * chunk watching never touch live VMP storage. After a successful mutation the
 * writer clones VMP's exact raw array only for cells whose membership changed
 * and publishes those immutable arrays through primitive-long snapshot maps.
 * A reader therefore sees the complete old or new value for its one queried
 * chunk with no lock, allocation, key boxing, or partially-mutated fastutil
 * storage. The installed consumers each query one cell, so per-cell publication
 * retains their observable contract without copying a dimension-sized index on
 * every player step.</p>
 *
 * <p>The set-returning compatibility method is not used by the installed VMP
 * hot paths. It keeps the exact detached identity-set behavior under the
 * mutation lock rather than permanently duplicating a set for every indexed
 * chunk. Constructor initialization is fail-closed and every target seam is
 * required, so an upstream VMP topology change fails during boot instead of
 * silently reopening a race or serving incomplete snapshots.</p>
 */
@Pseudo
@Mixin(targets = "com.ishland.vmp.common.chunkwatching.AreaPlayerChunkWatchingManager",
        remap = false)
public abstract class FabroliaVmpAreaPlayerChunkWatchingManagerMixin {

    @Unique
    private static final Object[] fabrolia$EMPTY_PLAYERS = new Object[0];

    @Shadow
    @Final
    private Object2LongOpenHashMap<ServerPlayer> positions;

    @Shadow
    @Final
    private AreaMap<ServerPlayer> playerAreaMap;

    @Shadow
    @Final
    private AreaMap<ServerPlayer> generalPlayerAreaMap;

    @Unique
    private volatile ReentrantLock fabrolia$mutationLock;

    @Unique
    private volatile PublishedLong2ObjectMap<Object[]> fabrolia$watchingSnapshots;

    @Unique
    private volatile PublishedLong2ObjectMap<Object[]> fabrolia$generalSnapshots;

    /** Accessed only while {@link #fabrolia$mutationLock} is held. */
    @Unique
    private Reference2LongOpenHashMap<ServerPlayer> fabrolia$snapshotCenters;

    /** Accessed only while {@link #fabrolia$mutationLock} is held. */
    @Unique
    private Reference2IntOpenHashMap<ServerPlayer> fabrolia$snapshotWatchDistances;

    @Inject(method = "<init>(Lcom/ishland/vmp/common/chunkwatching/"
            + "AreaPlayerChunkWatchingManager$Listener;"
            + "Lcom/ishland/vmp/common/chunkwatching/"
            + "AreaPlayerChunkWatchingManager$Listener;"
            + "Lnet/minecraft/server/level/ChunkMap;)V",
            at = @At("RETURN"), require = 1, allow = 1)
    private void fabrolia$initializePublishedAreaIndex(CallbackInfo ci) {
        fabrolia$snapshotCenters = new Reference2LongOpenHashMap<>();
        fabrolia$snapshotWatchDistances = new Reference2IntOpenHashMap<>();
        fabrolia$watchingSnapshots = new PublishedLong2ObjectMap<>();
        fabrolia$generalSnapshots = new PublishedLong2ObjectMap<>();
        // Publish last: every mutator performs this volatile read before it can
        // access the writer-owned maps initialized above.
        fabrolia$mutationLock = new ReentrantLock();
    }

    @Unique
    private ReentrantLock fabrolia$mutationLock() {
        ReentrantLock lock = fabrolia$mutationLock;
        if (lock == null) {
            throw new IllegalStateException("VMP area-index writer lock is not initialized");
        }
        return lock;
    }

    @Unique
    private PublishedLong2ObjectMap<Object[]> fabrolia$watchingSnapshots() {
        PublishedLong2ObjectMap<Object[]> snapshots = fabrolia$watchingSnapshots;
        if (snapshots == null) {
            throw new IllegalStateException("VMP watching snapshots are not initialized");
        }
        return snapshots;
    }

    @Unique
    private PublishedLong2ObjectMap<Object[]> fabrolia$generalSnapshots() {
        PublishedLong2ObjectMap<Object[]> snapshots = fabrolia$generalSnapshots;
        if (snapshots == null) {
            throw new IllegalStateException("VMP general snapshots are not initialized");
        }
        return snapshots;
    }

    /**
     * Return the radius VMP committed to this AreaMap mutation. Reading the
     * mutable ChunkMap distance after the original call can observe a newer
     * request whose onWatchDistanceChange callback is still waiting for the
     * writer lock.
     */
    @Unique
    private static int fabrolia$appliedViewDistance(
            AreaMap<?> source, Object player) {
        Reference2IntOpenHashMap<Object> applied =
                ((FabroliaVmpAreaMapAccessor) (Object) source)
                        .fabrolia$getAppliedViewDistances();
        if (!applied.containsKey(player)) {
            throw new IllegalStateException(
                    "VMP AreaMap has no applied view distance for tracked player");
        }
        return applied.getInt(player);
    }

    @WrapMethod(method = "tick")
    private void fabrolia$lockedTick(Operation<Void> original) {
        ReentrantLock lock = fabrolia$mutationLock();
        lock.lock();
        try {
            // VMP's exact tick body only acts on this pure changed flag. Keep
            // the cheap preflight, but serialize its plain positions-map scan
            // with add/remove/move instead of making hot readers participate.
            for (ServerPlayer player : positions.keySet()) {
                if (((PlayerClientVDTracking) player).isClientViewDistanceChanged()) {
                    original.call();
                    return;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "onWatchDistanceChange")
    private void fabrolia$lockedWatchDistanceChange(Operation<Void> original) {
        ReentrantLock lock = fabrolia$mutationLock();
        lock.lock();
        try {
            original.call();
            PublishedLong2ObjectMap<Object[]> watching = fabrolia$watchingSnapshots();
            for (Reference2LongMap.Entry<ServerPlayer> entry
                    : fabrolia$snapshotCenters.reference2LongEntrySet()) {
                ServerPlayer player = entry.getKey();
                long center = entry.getLongValue();
                int oldDistance = fabrolia$snapshotWatchDistances.getInt(player);
                int newDistance =
                        fabrolia$appliedViewDistance(playerAreaMap, player);
                if (oldDistance != newDistance) {
                    fabrolia$snapshotWatchDistances.put(player, newDistance);
                    fabrolia$refreshTransition(
                            playerAreaMap, watching,
                            center, oldDistance, center, newDistance);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "add")
    private void fabrolia$lockedAdd(ServerPlayer player, long chunkPos,
                                    Operation<Void> original) {
        ReentrantLock lock = fabrolia$mutationLock();
        lock.lock();
        try {
            original.call(player, chunkPos);
            int watchDistance =
                    fabrolia$appliedViewDistance(playerAreaMap, player);
            fabrolia$snapshotCenters.put(player, chunkPos);
            fabrolia$snapshotWatchDistances.put(player, watchDistance);
            fabrolia$refreshTransition(
                    playerAreaMap, fabrolia$watchingSnapshots(),
                    0L, -1, chunkPos, watchDistance);
            fabrolia$refreshTransition(
                    generalPlayerAreaMap, fabrolia$generalSnapshots(),
                    0L, -1, chunkPos,
                    AreaPlayerChunkWatchingManager.GENERAL_PLAYER_AREA_MAP_DISTANCE);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "remove")
    private void fabrolia$lockedRemove(ServerPlayer player,
                                       Operation<Void> original) {
        ReentrantLock lock = fabrolia$mutationLock();
        lock.lock();
        try {
            boolean tracked = fabrolia$snapshotCenters.containsKey(player);
            long oldCenter = tracked ? fabrolia$snapshotCenters.getLong(player) : 0L;
            int oldWatchDistance = tracked
                    ? fabrolia$snapshotWatchDistances.getInt(player) : -1;
            original.call(player);
            if (!tracked) {
                return;
            }
            fabrolia$snapshotCenters.removeLong(player);
            fabrolia$snapshotWatchDistances.removeInt(player);
            fabrolia$refreshTransition(
                    playerAreaMap, fabrolia$watchingSnapshots(),
                    oldCenter, oldWatchDistance, 0L, -1);
            fabrolia$refreshTransition(
                    generalPlayerAreaMap, fabrolia$generalSnapshots(),
                    oldCenter,
                    AreaPlayerChunkWatchingManager.GENERAL_PLAYER_AREA_MAP_DISTANCE,
                    0L, -1);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "movePlayer")
    private void fabrolia$lockedMovePlayer(long chunkPos, ServerPlayer player,
                                           Operation<Void> original) {
        ReentrantLock lock = fabrolia$mutationLock();
        lock.lock();
        try {
            boolean tracked = fabrolia$snapshotCenters.containsKey(player);
            long oldCenter = tracked ? fabrolia$snapshotCenters.getLong(player) : 0L;
            int oldWatchDistance = tracked
                    ? fabrolia$snapshotWatchDistances.getInt(player) : -1;
            original.call(chunkPos, player);
            int newWatchDistance =
                    fabrolia$appliedViewDistance(playerAreaMap, player);
            fabrolia$snapshotCenters.put(player, chunkPos);
            fabrolia$snapshotWatchDistances.put(player, newWatchDistance);
            fabrolia$refreshTransition(
                    playerAreaMap, fabrolia$watchingSnapshots(),
                    oldCenter, oldWatchDistance, chunkPos, newWatchDistance);
            fabrolia$refreshTransition(
                    generalPlayerAreaMap, fabrolia$generalSnapshots(),
                    oldCenter, tracked
                            ? AreaPlayerChunkWatchingManager.GENERAL_PLAYER_AREA_MAP_DISTANCE
                            : -1,
                    chunkPos,
                    AreaPlayerChunkWatchingManager.GENERAL_PLAYER_AREA_MAP_DISTANCE);
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "getPlayersWatchingChunk")
    private Set<ServerPlayer> fabrolia$snapshotPlayersWatchingChunk(
            long chunkPos, Operation<Set<ServerPlayer>> original) {
        ReentrantLock lock = fabrolia$mutationLock();
        lock.lock();
        try {
            Set<ServerPlayer> live = original.call(chunkPos);
            ReferenceLinkedOpenHashSet<ServerPlayer> snapshot =
                    new ReferenceLinkedOpenHashSet<>(live.size());
            snapshot.addAll(live);
            return snapshot;
        } finally {
            lock.unlock();
        }
    }

    @WrapMethod(method = "getPlayersWatchingChunkArray")
    private Object[] fabrolia$publishedPlayersWatchingChunk(
            long chunkPos, Operation<Object[]> original) {
        Object[] snapshot = fabrolia$watchingSnapshots().get(chunkPos);
        return snapshot == null ? fabrolia$EMPTY_PLAYERS : snapshot;
    }

    @WrapMethod(method = "getPlayersInGeneralAreaMap")
    private Object[] fabrolia$publishedPlayersInGeneralArea(
            long chunkPos, Operation<Object[]> original) {
        Object[] snapshot = fabrolia$generalSnapshots().get(chunkPos);
        return snapshot == null ? fabrolia$EMPTY_PLAYERS : snapshot;
    }

    /**
     * Publish additions first and removals second, matching VMP AreaMap.update's
     * transition order. The two square walks are bounded by view distance even
     * for a teleport across millions of chunks.
     */
    @Unique
    private void fabrolia$refreshTransition(
            AreaMap<ServerPlayer> source,
            PublishedLong2ObjectMap<Object[]> snapshots,
            long oldCenter, int oldDistance,
            long newCenter, int newDistance) {
        int oldX = oldDistance >= 0 ? ChunkPos.getX(oldCenter) : 0;
        int oldZ = oldDistance >= 0 ? ChunkPos.getZ(oldCenter) : 0;
        int newX = newDistance >= 0 ? ChunkPos.getX(newCenter) : 0;
        int newZ = newDistance >= 0 ? ChunkPos.getZ(newCenter) : 0;

        if (newDistance >= 0) {
            for (int x = newX - newDistance; x <= newX + newDistance; x++) {
                for (int z = newZ - newDistance; z <= newZ + newDistance; z++) {
                    if (oldDistance < 0
                            || !fabrolia$insideSquare(
                            x, z, oldX, oldZ, oldDistance)) {
                        fabrolia$refreshCell(source, snapshots, x, z);
                    }
                }
            }
        }
        if (oldDistance >= 0) {
            for (int x = oldX - oldDistance; x <= oldX + oldDistance; x++) {
                for (int z = oldZ - oldDistance; z <= oldZ + oldDistance; z++) {
                    if (newDistance < 0
                            || !fabrolia$insideSquare(
                            x, z, newX, newZ, newDistance)) {
                        fabrolia$refreshCell(source, snapshots, x, z);
                    }
                }
            }
        }
    }

    @Unique
    private static boolean fabrolia$insideSquare(
            int x, int z, int centerX, int centerZ, int distance) {
        return x >= centerX - distance && x <= centerX + distance
                && z >= centerZ - distance && z <= centerZ + distance;
    }

    @Unique
    private static void fabrolia$refreshCell(
            AreaMap<ServerPlayer> source,
            PublishedLong2ObjectMap<Object[]> snapshots,
            int x, int z) {
        long chunkPos = ChunkPos.pack(x, z);
        Object[] live = source.getObjectsInRangeArray(chunkPos);
        if (live.length == 0) {
            snapshots.remove(chunkPos);
        } else {
            snapshots.put(chunkPos, live.clone());
        }
    }
}
