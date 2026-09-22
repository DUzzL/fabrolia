package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.regions.IslandClock;
import com.axalotl.fabrolia.common.regions.NativePlayerObservations;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable per-generation player census and chunk buckets for producer preflight.
 *
 * <p>Rebuilt under real global ownership, then read-only. Buckets describe the
 * captured positions, not current membership or a bound on arbitrary native
 * callbacks. Admission also validates the live census, current positions and
 * actual target claims. Runtime queries retain native live-list traversal.
 */
public final class PlayerChunkIndex {

    private static final ConcurrentHashMap<ServerLevel, PlayerChunkIndex> BY_LEVEL =
            new ConcurrentHashMap<>();

    /** Level-unload teardown (audit #9). */
    public static void onLevelUnload(ServerLevel level) {
        BY_LEVEL.remove(level);
    }
    /** Probe margin over the query radius: covers intra-tick drift. */
    private static final double MARGIN = 16.0;

    private final Long2ObjectOpenHashMap<ArrayList<ServerPlayer>> buckets =
            new Long2ObjectOpenHashMap<>(64);
    private final Reference2IntOpenHashMap<ServerPlayer> ordinals =
            new Reference2IntOpenHashMap<>(64);
    private List<ServerPlayer> players = List.of();
    private final Comparator<ServerPlayer> nativeOrder =
            Comparator.comparingInt(ordinals::getInt);
    private boolean supportsLiveObservations = true;
    /**
     * Worker-context-free W-clock heartbeat of the snapshot. {@code ServerLevel#getGameTime()}
     * is deliberately not used here: island workers observe their owner-local L
     * clock through Fabrolia's redirect, while Main builds the snapshot on wall
     * time W.  Comparing W with L made the index permanently unusable on every
     * autonomous island and silently restored the O(players) vanilla scan.
     */
    private long builtAtWallTime = Long.MIN_VALUE;

    private PlayerChunkIndex() {
        ordinals.defaultReturnValue(Integer.MAX_VALUE);
    }

    /**
     * Main samples players under its global grant and publishes a complete,
     * immutable structural generation. Existing readers retain that generation;
     * predicates and distances still observe current native player state.
     */
    public static void rebuild(ServerLevel level) {
        PlayerChunkIndex idx = new PlayerChunkIndex();
        ArrayList<ServerPlayer> snapshot = new ArrayList<>();
        int ordinal = 0;
        // SnapshotList's iterator pins one generation; separate size/get calls do not.
        for (ServerPlayer p : level.players()) {
            snapshot.add(p);
            idx.supportsLiveObservations &= NativePlayerObservations.isSupportedPlayer(p);
            long key = bucketKey(Mth.floor(p.getX()) >> 4, Mth.floor(p.getZ()) >> 4);
            ArrayList<ServerPlayer> bucket = idx.buckets.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>(2);
                idx.buckets.put(key, bucket);
            }
            bucket.add(p);
            idx.ordinals.put(p, ordinal++);
        }
        idx.players = java.util.Collections.unmodifiableList(snapshot);
        idx.builtAtWallTime = IslandClock.wallTime(level);
        BY_LEVEL.put(level, idx);
    }

    /** The index for this level if it was built this tick, else null. */
    public static PlayerChunkIndex usable(ServerLevel level) {
        PlayerChunkIndex idx = BY_LEVEL.get(level);
        return idx != null
                && idx.builtAtWallTime == IslandClock.wallTime(level)
                ? idx : null;
    }

    public List<ServerPlayer> allPlayers() {
        return players;
    }

    public boolean supportsLiveObservations() {
        return supportsLiveObservations;
    }

    private static long bucketKey(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32);
    }

    /**
     * Players whose tick-start bucket lies within {@code radius + MARGIN} of
     * (x, z). Each result has an independent structural lifetime: predicates
     * and targeting callbacks may re-enter player queries on the same thread.
     */
    public List<ServerPlayer> candidates(double x, double z, double radius) {
        double r = radius + MARGIN;
        double minX = x - r, maxX = x + r;
        double minZ = z - r, maxZ = z + r;
        // Mth.floor narrows to int and can wrap at the negative saturation
        // boundary. Unrepresentable/NaN bounds must not prune a valid player.
        if (!(minX >= Integer.MIN_VALUE && maxX < Integer.MAX_VALUE
                && minZ >= Integer.MIN_VALUE && maxZ < Integer.MAX_VALUE)) return players;
        int cx0 = Mth.floor(minX) >> 4;
        int cx1 = Mth.floor(maxX) >> 4;
        int cz0 = Mth.floor(minZ) >> 4;
        int cz1 = Mth.floor(maxZ) >> 4;
        // Never enumerate more empty buckets than there are occupied buckets.
        // Huge/infinite radii therefore cost O(players), not O(radius squared).
        long width = (long) cx1 - cx0 + 1;
        long height = (long) cz1 - cz0 + 1;
        if (width <= 0 || height <= 0) return players;
        ArrayList<ServerPlayer> out = new ArrayList<>(8);
        if (width * height > buckets.size()) {
            var entries = buckets.long2ObjectEntrySet().fastIterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                long key = entry.getLongKey();
                int cx = (int) key;
                int cz = (int) (key >>> 32);
                if (cx >= cx0 && cx <= cx1 && cz >= cz0 && cz <= cz1) {
                    out.addAll(entry.getValue());
                }
            }
            // This branch used to visit the full player list in native order.
            // Keep that order, but never enlarge a finite candidate rectangle.
            out.sort(nativeOrder);
            return out;
        }
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                ArrayList<ServerPlayer> bucket = buckets.get(bucketKey(cx, cz));
                if (bucket != null) {
                    out.addAll(bucket);
                }
            }
        }
        return out;
    }


}
