package com.axalotl.fabrolia.common.parallelised.utils;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tick chunk-bucketed snapshot of a level's player list.
 *
 * <p>Vanilla {@code getNearestPlayer} scans EVERY player linearly on every
 * call; mob goals (Tempt/Avoid/LookAt/NearestAttackableTarget) issue those
 * per mob per tick — at 100 players that was ~4% of all region-worker time
 * (distanceToSqr + isSpectator + the loop). This index turns one query into
 * a probe of the few chunk buckets within the goal's radius.
 *
 * <p><b>Semantics.</b> Rebuilt once per tick on the server thread BEFORE any
 * parallel phase launches, then read-only — workers race nothing. Distances
 * are computed LIVE at query time; only bucket membership is as of tick
 * start, so a player who crossed a chunk border mid-tick is found via the
 * probe margin (+1 chunk ≈ 16 blocks, far above legit per-tick movement).
 * Selection reproduces vanilla exactly: strict-{@code <} distance with
 * first-in-{@code players()}-order winning ties (= lexicographic min of
 * (distanceSqr, list ordinal)). Teleport-scale jumps (ender pearl mid-phase)
 * are seen next tick — the same snapshot doctrine the spawn index and sensor
 * bridge already follow.
 *
 * <p>Queries with no usable index (boot, unbounded radius, foreign level)
 * fall back to the caller's inlined vanilla loop.
 */
public final class PlayerChunkIndex {

    private static final ConcurrentHashMap<ServerLevel, PlayerChunkIndex> BY_LEVEL =
            new ConcurrentHashMap<>();

    /** Level-unload teardown (audit #9). */
    public static void onLevelUnload(ServerLevel level) {
        BY_LEVEL.remove(level);
    }
    private static final ThreadLocal<ArrayList<ServerPlayer>> SCRATCH =
            ThreadLocal.withInitial(() -> new ArrayList<>(8));
    /** Probe margin over the query radius: covers intra-tick drift. */
    private static final double MARGIN = 16.0;

    private final Long2ObjectOpenHashMap<ArrayList<ServerPlayer>> buckets =
            new Long2ObjectOpenHashMap<>(64);
    private final Reference2IntOpenHashMap<ServerPlayer> ordinals =
            new Reference2IntOpenHashMap<>(64);
    private long builtAtGameTime = Long.MIN_VALUE;

    private PlayerChunkIndex() {
        ordinals.defaultReturnValue(Integer.MAX_VALUE);
    }

    /** Server thread, once per level tick. COPY-AND-SWAP (task #32 despawn
     *  audit): under the async-islands deadline drive, island units run
     *  concurrently with ANY main-tick instant — including this rebuild — and
     *  their checkDespawn/goal queries read the index. In-place mutation of
     *  the live hashmap gave those readers torn views (missed/null "nearest
     *  player" → skipped or SPURIOUS despawns). Build a fresh instance and
     *  publish it whole through the CHM put (happens-before for readers);
     *  concurrent readers keep last tick's coherent snapshot — the same
     *  1-tick-stale doctrine the index already documents. Fresh maps per tick
     *  also make the old occupied-keys sweep unnecessary. */
    public static void rebuild(ServerLevel level) {
        PlayerChunkIndex idx = new PlayerChunkIndex();
        List<ServerPlayer> players = level.players();
        for (int i = 0, n = players.size(); i < n; i++) {
            ServerPlayer p = players.get(i);
            long key = bucketKey(Mth.floor(p.getX()) >> 4, Mth.floor(p.getZ()) >> 4);
            ArrayList<ServerPlayer> bucket = idx.buckets.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>(2);
                idx.buckets.put(key, bucket);
            }
            bucket.add(p);
            idx.ordinals.put(p, i);
        }
        idx.builtAtGameTime = level.getGameTime();
        BY_LEVEL.put(level, idx);
    }

    /** The index for this level if it was built this tick, else null. */
    public static PlayerChunkIndex usable(ServerLevel level) {
        PlayerChunkIndex idx = BY_LEVEL.get(level);
        return idx != null && idx.builtAtGameTime == level.getGameTime() ? idx : null;
    }

    private static long bucketKey(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32);
    }

    /**
     * Players whose tick-start bucket lies within {@code radius + MARGIN} of
     * (x, z). Returned list is a reused thread-local scratch — consume before
     * the next call on this thread.
     */
    public ArrayList<ServerPlayer> candidates(double x, double z, double radius) {
        ArrayList<ServerPlayer> out = SCRATCH.get();
        out.clear();
        double r = radius + MARGIN;
        int cx0 = Mth.floor(x - r) >> 4;
        int cx1 = Mth.floor(x + r) >> 4;
        int cz0 = Mth.floor(z - r) >> 4;
        int cz1 = Mth.floor(z + r) >> 4;
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

    /** Vanilla tie-breaking: earlier {@code players()} position wins. */
    public int ordinalOf(ServerPlayer p) {
        return ordinals.getInt(p);
    }

    /**
     * Unbounded nearest (checkDespawn's {@code maxDistance = -1}): expanding
     * chunk rings from the query point. After candidates appear, keep
     * expanding until the next ring's minimum POSSIBLE horizontal distance
     * (conservatively {@code (k-2)*16} — one full extra ring of slack to wash
     * exact-boundary ties, whose winner vanilla picks by list order) exceeds
     * the best 3D distance seen. Mobs only exist near players (despawn
     * radius), so this terminates within a handful of rings; an empty
     * dimension degenerates to one sweep over occupied buckets.
     */
    public ArrayList<ServerPlayer> nearestRing(double x, double y, double z) {
        ArrayList<ServerPlayer> out = SCRATCH.get();
        out.clear();
        if (buckets.isEmpty()) {
            return out;
        }
        int cx = Mth.floor(x) >> 4;
        int cz = Mth.floor(z) >> 4;
        double bestSq = Double.MAX_VALUE;
        for (int k = 0; k < 4096; k++) {
            int before = out.size();
            if (k == 0) {
                collect(out, cx, cz);
            } else {
                for (int i = -k; i <= k; i++) {
                    collect(out, cx + i, cz - k);
                    collect(out, cx + i, cz + k);
                }
                for (int j = -k + 1; j <= k - 1; j++) {
                    collect(out, cx - k, cz + j);
                    collect(out, cx + k, cz + j);
                }
            }
            for (int i = before, n = out.size(); i < n; i++) {
                double d = out.get(i).distanceToSqr(x, y, z);
                if (d < bestSq) {
                    bestSq = d;
                }
            }
            if (bestSq != Double.MAX_VALUE) {
                double nextRingMin = (k - 1) * 16.0; // ring k+1 lower bound minus a ring of slack
                if (nextRingMin > 0 && nextRingMin * nextRingMin > bestSq) {
                    return out;
                }
            }
        }
        return out;
    }

    private void collect(ArrayList<ServerPlayer> out, int cx, int cz) {
        ArrayList<ServerPlayer> b = buckets.get(bucketKey(cx, cz));
        if (b != null) {
            out.addAll(b);
        }
    }
}
