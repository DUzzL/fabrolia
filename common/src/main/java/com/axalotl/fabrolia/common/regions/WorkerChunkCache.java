package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Region-worker equivalent of vanilla's main-thread last-chunk cache for
 * {@code ServerChunkCache.getChunk(x, z, FULL, load)}.
 *
 * <p>Off Main, C2ME's {@code shortcutGetChunk} answers every ready lookup with a
 * holder-map get, a holder monitor ({@code triggerDeferredLoad}), a second
 * monitor ({@code ItemHolder.getFutureForStatus0}) and two allocations. Its
 * answer for a holder whose SERVER_ACCESSIBLE future is complete is the
 * holder's current {@code ChunkState.chunk()}, which cannot change until
 * flowsched's {@code ItemHolder.setStatus} steps the holder down out of
 * SERVER_ACCESSIBLE ({@link #demotesFullShortcut}); the holder cannot leave the
 * map (FLAG_REMOVED, the only flag {@code isOpen} reads) before that step; and
 * the only skipped side effect, the deferred-load ticket, can only become due
 * again after {@code updateDeferredStatus}. That step and that method are
 * bracketed by {@link #beginTransition}/{@link #endTransition} on a
 * per-position stripe.
 *
 * <p>Exactness is a per-stripe seqlock. {@link #arm}, before the shortcut's
 * holder reads, reads {@code finished} then {@code started} and requires them
 * equal (no transition of the stripe in flight); that {@code started} value is
 * the entry's stamp, and {@link #capture} stores the shortcut's own answer at
 * its return. A hit requires {@code started} to still equal the stamp, i.e. no
 * transition began since before the answer was read. A transition that began is
 * therefore either invisible to the hit (the hit is ordered before it) or
 * invalidates the entry. Any thrown
 * transition leaves its stripe permanently in flight: arming is refused there,
 * never a stale answer. One lookup is armed at a time: a nested getChunk inside
 * the shortcut re-arms and disarms at its own return, so the outer one stores
 * nothing.
 *
 * <p>One instance per {@link RegionWorkerThread}, thread-confined, cleared after
 * every worker step so no unloaded chunk stays referenced. Only FULL requests
 * of plain worker bodies use it: datapack function partitions and the exact
 * player actor keep the uncached path (the actor is logical Main for C2ME's
 * shortcut and vanilla's own cache; partition bodies stay out by policy).
 * Values are opaque here; the mixin stores LevelChunks.
 */
public final class WorkerChunkCache {
    private static final int SIDE_BITS = 3;
    private static final int SIDE_MASK = (1 << SIDE_BITS) - 1;
    private static final int SLOTS = 1 << (2 * SIDE_BITS);
    private static final int STRIPE_BITS = 12;

    /** [2s] = transitions begun, [2s + 1] = transitions finished, for stripe s. */
    private static final AtomicLongArray TRANSITIONS = new AtomicLongArray(2 << STRIPE_BITS);

    private final Object[] sources = new Object[SLOTS];
    private final long[] keys = new long[SLOTS];
    private final long[] stamps = new long[SLOTS];
    private final Object[] values = new Object[SLOTS];
    private boolean populated;

    private Object armedSource;
    private long armedKey;
    private long armedStamp;

    /** The calling worker's cache, or null when the uncached path must run. */
    public static WorkerChunkCache forCurrentThread() {
        if (!(Thread.currentThread() instanceof RegionWorkerThread worker)) return null;
        WorkerChunkCache cache = worker.chunkCache;
        if (worker.functionScope != null || RegionalPlayerEntityTicksV2.inExactPlayerActor()) {
            cache.armedSource = null;
            return null;
        }
        return cache;
    }

    /** A C2ME holder status change or deferred-status update starts at {@code chunkPos}. */
    public static void beginTransition(long chunkPos) {
        TRANSITIONS.getAndIncrement(stripe(chunkPos) << 1);
    }

    /** The transition {@link #beginTransition} announced has completed. */
    public static void endTransition(long chunkPos) {
        TRANSITIONS.getAndIncrement((stripe(chunkPos) << 1) + 1);
    }

    /**
     * Whether a flowsched {@code setStatus(newStatus)} is the one demotion that
     * can invalidate a shortcut answer. setStatus moves exactly one step; a
     * downgrade from {@code old} resets only futures at indices {@code >= old},
     * so the completed future C2ME's FULL shortcut reads (the status
     * {@code NewChunkStatus.fromVanillaStatus(FULL)}) is reset only by the step
     * down to its predecessor. Leaving the holder map requires that step too.
     */
    public static boolean demotesFullShortcut(Object newStatus) {
        return newStatus == C2meFullStatus.PREVIOUS;
    }

    /** Resolved on the first status change, when C2ME's statuses exist. */
    private static final class C2meFullStatus {
        static final Object PREVIOUS = resolve();

        private static Object resolve() {
            ClassLoader loader = WorkerChunkCache.class.getClassLoader();
            try {
                Class<?> newStatus = Class.forName(
                        "com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus", true, loader);
                Class<?> itemStatus = Class.forName(
                        "com.ishland.flowsched.scheduler.ItemStatus", true, loader);
                Object full = newStatus.getMethod("fromVanillaStatus",
                                net.minecraft.world.level.chunk.status.ChunkStatus.class)
                        .invoke(null, net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
                Object previous = itemStatus.getMethod("getPrev").invoke(full);
                if (previous == null) throw new IllegalStateException("C2ME FULL status has no predecessor");
                return previous;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Brackets cannot be decided: fail-stop instead of letting C2ME
                // mark every holder that changes status broken.
                Throwable winner = RegionScheduler.publishFatalAndSelectWinner(
                        new IllegalStateException("C2ME chunk-status surface changed", e));
                throw winner instanceof RuntimeException r ? r : new IllegalStateException(winner);
            }
        }
    }

    /** The cached answer for {@code (source, x, z)}, or null. */
    public Object lookup(Object source, int x, int z) {
        int slot = slot(x, z);
        Object value = values[slot];
        if (value == null || sources[slot] != source) return null;
        long key = ChunkPos.pack(x, z);
        if (keys[slot] != key) return null;
        if (TRANSITIONS.get(stripe(key) << 1) != stamps[slot]) {
            values[slot] = null;
            sources[slot] = null;
            return null;
        }
        return value;
    }

    /** Before an uncached lookup: remember whether its answer may be stored. */
    public void arm(Object source, int x, int z) {
        long key = ChunkPos.pack(x, z);
        int base = stripe(key) << 1;
        long finished = TRANSITIONS.get(base + 1);
        long started = TRANSITIONS.get(base);
        if (finished != started) {
            armedSource = null;
            return;
        }
        armedSource = source;
        armedKey = key;
        armedStamp = started;
    }

    /** The C2ME shortcut armed for {@code (source, x, z)} returned; {@code value}
     *  is its answer, or null when it declined. Always disarms. */
    public void capture(Object source, int x, int z, Object value) {
        Object armed = armedSource;
        armedSource = null;
        if (armed != source || value == null) return;
        long key = ChunkPos.pack(x, z);
        // Exactness rests on the arm-time stamp alone (lookup compares it with
        // the current count, which only grows); this re-read just keeps a
        // dead entry from evicting a live one.
        if (armedKey != key || TRANSITIONS.get(stripe(key) << 1) != armedStamp) return;
        int slot = slot(x, z);
        sources[slot] = source;
        keys[slot] = key;
        stamps[slot] = armedStamp;
        values[slot] = value;
        populated = true;
    }

    /** Worker step boundary: drop every entry and any armed lookup. */
    public void clear() {
        armedSource = null;
        if (!populated) return;
        Arrays.fill(values, null);
        Arrays.fill(sources, null);
        populated = false;
    }

    private static int slot(int x, int z) {
        return ((x & SIDE_MASK) << SIDE_BITS) | (z & SIDE_MASK);
    }

    private static int stripe(long chunkPos) {
        return (int) ((chunkPos * 0x9E3779B97F4A7C15L) >>> (64 - STRIPE_BITS));
    }
}
