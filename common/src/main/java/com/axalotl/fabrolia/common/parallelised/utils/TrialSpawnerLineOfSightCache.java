package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Caches {@code PlayerDetector.inLineOfSight} results — vanilla's per-detection raytrace
 * via {@code level.clip(ClipContext)} dominates trial_spawner ticks under multi-player
 * load because every (spawner, candidate-player) pair triggers a fresh voxel raymarch
 * across ~30 block reads + shape checks.
 *
 * <p>Cache key: dimension plus packed (from-blockpos, to-blockpos) — sub-block player
 * position doesn't change LOS through static dungeon geometry, and
 * {@code BlockPos.asLong} matches the packing the chunk system already uses (no extra cost).
 * TTL = {@value #TTL_TICKS} game ticks bounds staleness when blocks DO change
 * (TNT, redstone-door, falling block).
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

    /** Weak owner token separates successive servers without retaining a stopped one. */
    private static WeakReference<Object> cacheOwner = new WeakReference<>(null);

    /** Below this many cache misses the rays are traced serially on the caller. */
    public static final int PARALLEL_MIN_MISSES = 4;

    private record Entry(ResourceKey<Level> dimension, long fromKey, long toKey,
                         long tick, boolean result) {}

    private TrialSpawnerLineOfSightCache() {}

    /**
     * Returns Boolean.TRUE / Boolean.FALSE on cache hit, {@code null} on miss.
     */
    public static Boolean lookup(Level level, Vec3 from, Vec3 to) {
        if (!(level instanceof ServerLevel server) || !selectCacheServer(server)) {
            return null;
        }
        return lookupCached(server.getGameTime(), server.dimension(),
                blockKey(from), blockKey(to));
    }

    public static void store(Level level, Vec3 from, Vec3 to, boolean result) {
        if (!(level instanceof ServerLevel server) || !selectCacheServer(server)) {
            return;
        }
        storeCached(server.getGameTime(), server.dimension(),
                blockKey(from), blockKey(to), result);
    }

    private static boolean selectCacheServer(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return false;
        }
        selectCacheOwner(server);
        return true;
    }

    static void selectCacheOwner(Object owner) {
        if (cacheOwner.get() != owner) {
            CACHE.clear();
            cacheOwner = new WeakReference<>(owner);
        }
    }

    /**
     * Hash collisions are ordinary cache misses. Dimension and endpoint identity
     * stay in the value, so no ray can borrow another ray's visibility result.
     */
    static Boolean lookupCached(long now, ResourceKey<Level> dimension,
                                long fromKey, long toKey) {
        long hash = cacheHash(dimension, fromKey, toKey);
        Entry entry = CACHE.get(hash);
        if (entry == null
                || !entry.dimension.equals(dimension)
                || entry.fromKey != fromKey
                || entry.toKey != toKey) {
            return null;
        }
        if (now - entry.tick > TTL_TICKS) {
            CACHE.remove(hash);
            return null;
        }
        return entry.result;
    }

    static void storeCached(long now, ResourceKey<Level> dimension,
                            long fromKey, long toKey, boolean result) {
        CACHE.put(cacheHash(dimension, fromKey, toKey),
                new Entry(dimension, fromKey, toKey, now, result));
    }

    private static long blockKey(Vec3 position) {
        return BlockPos.asLong(Mth.floor(position.x), Mth.floor(position.y),
                Mth.floor(position.z));
    }

    static long endpointHash(long fromKey, long toKey) {
        return fromKey ^ Long.rotateLeft(toKey, 17);
    }

    private static long cacheHash(ResourceKey<Level> dimension,
                                  long fromKey, long toKey) {
        return endpointHash(fromKey, toKey)
                ^ Integer.toUnsignedLong(dimension.hashCode()) * 0x9E3779B97F4A7C15L;
    }

    /** Vanilla ray logic, evaluated only on the source caller's gameplay owner. */
    public static boolean computeLineOfSight(Level level, Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(to, from, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty());
        BlockHitResult hit = level.clip(ctx);
        return hit.getBlockPos().equals(BlockPos.containing(from)) || hit.getType() == HitResult.Type.MISS;
    }

    /** One serial miss: admit the vanilla ray and its success publication separately. */
    private static void computeAndStoreIfHealthy(Level level, Vec3 from, Vec3 to) {
        boolean result = RegionScheduler.callSuccessBooleanIfHealthy(
                () -> computeLineOfSight(level, from, to));
        RegionScheduler.runSuccessBodyIfHealthy(() -> store(level, from, to, result));
    }

    private static void storeResultIfHealthy(Level level, Vec3 from, Vec3 to, boolean result) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> store(level, from, to, result));
    }

    /**
     * Seeds misses in candidate order on the source owner. Native detector filters
     * remain authoritative; only successful, admitted ray results enter the cache.
     */
    public static void parallelPrefetch(Level level, Vec3 from, List<Vec3> tos) {
        if (!(level instanceof ServerLevel server)) return;
        if (!server.getServer().isSameThread()) return;
        if (tos.isEmpty()) return;
        RegionScheduler.throwWorkerFailureIfAny();

        // Snapshot misses before evaluation, preserving repeated-target ray order.
        int n = tos.size();
        Vec3[] missTargets = new Vec3[n];
        int missCount = 0;
        for (int i = 0; i < n; i++) {
            Vec3 to = tos.get(i);
            if (lookup(level, from, to) == null) missTargets[missCount++] = to;
        }

        if (missCount < PARALLEL_MIN_MISSES) {
            for (int i = 0; i < missCount; i++) {
                computeAndStoreIfHealthy(level, from, missTargets[i]);
            }
            return;
        }

        ExecutorService exec = ParallelProcessor.executor;
        if (exec == null || exec.isShutdown()) {
            for (int i = 0; i < missCount; i++) {
                computeAndStoreIfHealthy(level, from, missTargets[i]);
            }
            return;
        }

        boolean[] results = new boolean[missCount];
        RayFailureGate gate = new RayFailureGate();
        RayTask[] tasks = new RayTask[missCount];
        for (int i = 0; i < missCount; i++) {
            int idx = i;
            Vec3 to = missTargets[idx];
            tasks[idx] = new RayTask(gate, () -> results[idx] = computeLineOfSight(level, from, to));
        }

        int submitted = 0;
        try {
            while (submitted < missCount) {
                gate.admitNext();
                RayTask task = tasks[submitted];
                submitted++;
                RegionScheduler.runSuccessBodyIfHealthy(() -> exec.execute(task));
            }
        } catch (Throwable submissionFailure) {
            gate.publishFailure(submissionFailure);
        }

        fabrolia$awaitRayFanIn(server, tasks, submitted, gate);

        for (int i = 0; i < missCount; i++) {
            storeResultIfHealthy(level, from, missTargets[i], results[i]);
        }
    }

    private static void fabrolia$awaitRayFanIn(ServerLevel level, RayTask[] tasks, int count, RayFailureGate gate) {
        boolean interrupted = Thread.interrupted();
        boolean main = level.getServer().isSameThread();
        if (main) {
            level.getServer().blockingCount++;
        }
        try {
            while (true) {
                try {
                    RegionScheduler.throwWorkerFailureIfAny();
                } catch (Throwable fatal) {
                    gate.publishFailure(fatal);
                }

                Throwable failure = gate.failureOrNull();
                if (failure != null) {
                    for (int i = 0; i < count; i++) {
                        tasks[i].cancelBeforeRun();
                    }
                }

                boolean complete = true;
                for (int i = 0; i < count; i++) {
                    if (!tasks[i].completion.isDone()) {
                        complete = false;
                        break;
                    }
                }

                if (!complete) {
                    if (Thread.interrupted()) {
                        interrupted = true;
                    }
                    boolean droveChunkCallback = false;
                    if (failure == null && main) {
                        droveChunkCallback = fabrolia$pollHealthyRayCallback(gate,
                                () -> level.getChunkSource().mainThreadProcessor.pollTask());
                    }
                    if (!droveChunkCallback) {
                        LockSupport.parkNanos("Fabrolia trial-LOS quiescence", 25_000L);
                    }
                }

                if (complete) {
                    if (Thread.interrupted()) {
                        interrupted = true;
                    }
                    break;
                }
            }
        } finally {
            if (main) {
                level.getServer().blockingCount--;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable fatal) {
            gate.publishFailure(fatal);
        }

        Throwable failure = gate.failureOrNull();
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static boolean fabrolia$pollHealthyRayCallback(RayFailureGate gate, BooleanSupplier callback) {
        if (gate.failureOrNull() != null) {
            return false;
        }
        try {
            return RegionScheduler.callSuccessBooleanIfHealthy(callback);
        } catch (Throwable callbackFailure) {
            gate.publishFailure(callbackFailure);
            return false;
        }
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    private static final class RayFailureGate {
        private Throwable failure;

        void admitNext() {
            Throwable observed = failureOrNull();
            if (observed != null) {
                rethrow(observed);
            }
            try {
                RegionScheduler.throwWorkerFailureIfAny();
            } catch (Throwable fatal) {
                rethrow(publishFailure(fatal));
            }
            observed = failureOrNull();
            if (observed != null) {
                rethrow(observed);
            }
        }

        synchronized Throwable failureOrNull() {
            return failure;
        }

        synchronized Throwable publishFailure(Throwable candidate) {
            if (candidate == null) {
                candidate = new NullPointerException("trial LOS failure");
            }
            if (failure == null) {
                failure = candidate;
            } else if (failure != candidate) {
                mergeFailure(failure, candidate);
            }
            failure = RegionScheduler.publishFatalAndSelectWinner(failure);
            return failure;
        }

        synchronized void completeTerminal(CompletableFuture<Void> completion) {
            if (failure == null) {
                try {
                    RegionScheduler.commitSuccessIfHealthy(() -> {
                        if (!completion.complete(null)) {
                            throw new IllegalStateException("trial LOS prefetch completed more than once");
                        }
                    });
                } catch (Throwable fatal) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(fatal);
                }
            }
            if (failure != null) {
                completion.completeExceptionally(failure);
            }
        }
    }

    private static final class RayTask implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final RayFailureGate gate;
        private final Runnable body;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private RayTask(RayFailureGate gate, Runnable body) {
            this.gate = gate;
            this.body = body;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return;
            try {
                gate.admitNext();
                RegionScheduler.runSuccessBodyIfHealthy(body);
                gate.admitNext();
            } catch (Throwable thrown) {
                gate.publishFailure(thrown);
            } finally {
                state.set(TERMINAL);
                gate.completeTerminal(completion);
            }
        }

        void cancelBeforeRun() {
            if (state.compareAndSet(QUEUED, CANCELLED)) {
                gate.completeTerminal(completion);
            }
        }
    }
}
