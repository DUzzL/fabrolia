package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Caches {@code PlayerDetector.inLineOfSight} results — vanilla's per-detection raytrace
 * via {@code level.clip(ClipContext)} dominates trial_spawner ticks under multi-player
 * load because every (spawner, candidate-player) pair triggers a fresh voxel raymarch
 * across ~30 block reads + shape checks.
 *
 * <p>Cache key: packed (from-blockpos, to-blockpos) — sub-block player position doesn't
 * change LOS through static dungeon geometry, and {@code BlockPos.asLong} matches the
 * packing the chunk system already uses (no extra cost). TTL = {@value #TTL_TICKS} game
 * ticks bounds staleness when blocks DO change (TNT, redstone-door, falling block).
 *
 * <p>Single-threaded: vanilla calls inLineOfSight only from {@code PlayerDetector}
 * lambdas, which run inside block-entity tick on the server main thread. A flat
 * {@link LinkedHashMap} with access-order LRU eviction is enough — no concurrent reads.
 * If a future mod calls it off main, the cache lookup falls through to vanilla via the
 * {@code !isSameThread} guard.
 */
public final class TrialSpawnerLineOfSightCache {

    /**
     * 20 ticks (1 second) — matches the trial_spawner detection gate cycle. Most players
     * don't traverse a block boundary in 1 second of standing/AFK, so a per-detection-cycle
     * cache hit rate near 100% is achievable. Worst-case staleness: 1 second of LOS
     * thinking a player is visible when a block just changed in between — practically
     * imperceptible at the trial-spawner timescale (which is itself ticked every second).
     */
    public static final int TTL_TICKS = 20;
    public static final int MAX_ENTRIES = 1024;

    private static final LinkedHashMap<Long, Entry> CACHE =
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private record Entry(long tick, boolean result) {}

    private TrialSpawnerLineOfSightCache() {}

    /**
     * Returns Boolean.TRUE / Boolean.FALSE on cache hit, {@code null} on miss.
     */
    public static Boolean lookup(Level level, Vec3 from, Vec3 to) {
        if (!(level instanceof ServerLevel server)) return null;
        if (!server.getServer().isSameThread()) return null;
        long now = server.getGameTime();
        long key = packKey(from, to);
        Entry e = CACHE.get(key);
        if (e == null) return null;
        if (now - e.tick > TTL_TICKS) {
            CACHE.remove(key);
            return null;
        }
        return e.result;
    }

    public static void store(Level level, Vec3 from, Vec3 to, boolean result) {
        if (!(level instanceof ServerLevel server)) return;
        if (!server.getServer().isSameThread()) return;
        CACHE.put(packKey(from, to), new Entry(server.getGameTime(), result));
    }

    private static long packKey(Vec3 from, Vec3 to) {
        long fromKey = BlockPos.asLong(Mth.floor(from.x), Mth.floor(from.y), Mth.floor(from.z));
        long toKey = BlockPos.asLong(Mth.floor(to.x), Mth.floor(to.y), Mth.floor(to.z));
        // xor + bit-rotate: cheap, sufficient bucket dispersal even though from/to are
        // spatially clustered around the trial chamber. Collisions just cost an extra
        // compute on miss; no correctness impact.
        return fromKey ^ Long.rotateLeft(toKey, 17);
    }

    /** Vanilla logic from {@code PlayerDetector.inLineOfSight}, replicated so we can call
     *  it from worker threads without going through the private interface static. */
    public static boolean computeLineOfSight(Level level, Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(to, from, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty());
        BlockHitResult hit = level.clip(ctx);
        return hit.getBlockPos().equals(BlockPos.containing(from)) || hit.getType() == HitResult.Type.MISS;
    }

    /**
     * Parallel batch LOS prefetch. Given a list of {@code to} positions sharing the same
     * {@code from} (trial-spawner anchor), submits cache-miss raytraces to the FJP pool,
     * stores all results in the cache. Vanilla's subsequent serial detect() call then sees
     * every {@code inLineOfSight} as a cache hit.
     *
     * <p>Why prefetch instead of replacing detect(): vanilla's detector lambdas are static
     * fields with detector-specific filter logic (creative/spectator exclusions vary). By
     * pre-populating the cache and letting vanilla run its own serial filter, we avoid
     * reimplementing every detector variant — vanilla stays the source of truth, we only
     * parallelize the one expensive primitive (the raymarch).
     *
     * <p>Threshold: only parallelizes when there are at least {@value #PARALLEL_MIN_MISSES}
     * cache-misses to amortise FJP dispatch overhead. Below that, the serial vanilla path
     * inside detect() is faster than the dispatch round-trip.
     */
    public static final int PARALLEL_MIN_MISSES = 4;

    public static void parallelPrefetch(Level level, Vec3 from, List<Vec3> tos) {
        if (!(level instanceof ServerLevel server)) return;
        if (!server.getServer().isSameThread()) return;
        if (tos.isEmpty()) return;

        // Collect cache misses. Hits stay in cache — nothing to do.
        int n = tos.size();
        Vec3[] missTargets = new Vec3[n];
        int missCount = 0;
        for (int i = 0; i < n; i++) {
            Vec3 to = tos.get(i);
            if (lookup(level, from, to) == null) {
                missTargets[missCount++] = to;
            }
        }
        if (missCount < PARALLEL_MIN_MISSES) {
            // Too few — serial fill is cheaper than FJP dispatch (each dispatch ~1µs,
            // each raytrace ~5µs; for 1-3 misses, just block on main).
            for (int i = 0; i < missCount; i++) {
                Vec3 to = missTargets[i];
                store(level, from, to, computeLineOfSight(level, from, to));
            }
            return;
        }

        ExecutorService exec = ParallelProcessor.executor;
        if (exec == null || exec.isShutdown()) {
            // Fall back to serial — no worker pool available.
            for (int i = 0; i < missCount; i++) {
                Vec3 to = missTargets[i];
                store(level, from, to, computeLineOfSight(level, from, to));
            }
            return;
        }

        boolean[] results = new boolean[missCount];
        CompletableFuture<?>[] futures = new CompletableFuture<?>[missCount];
        for (int i = 0; i < missCount; i++) {
            final int idx = i;
            final Vec3 to = missTargets[idx];
            futures[idx] = CompletableFuture.runAsync(
                    () -> results[idx] = computeLineOfSight(level, from, to),
                    exec);
        }

        // Park-barrier wait. We're inside TrialSpawnerStateData.tryDetectPlayers which is
        // called from BlockEntity tick on main thread — outside the entity-tick iteration
        // loop, so ParallelProcessor.pumpUntilDone is safe here (no CME risk like
        // FabroliaPlayerChunkSenderMixin had).
        ParallelProcessor.pumpUntilDone(CompletableFuture.allOf(futures));

        // Bulk store. Even if one worker threw, the corresponding result[i] is false (boolean
        // default) — same as "no LOS", which fails-closed safely.
        for (int i = 0; i < missCount; i++) {
            store(level, from, missTargets[i], results[i]);
        }
    }
}
