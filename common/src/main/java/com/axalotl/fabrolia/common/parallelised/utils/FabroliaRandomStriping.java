package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker stripes for explicitly shared, unseeded world entropy only.
 *
 * <p>Menu, entity, forked and other ordinary sources retain their canonical
 * per-instance LCG. Stripes are an owner opt-in for the contended level RNG,
 * not a property inferred from whichever thread happens to call next().
 *
 * <p>A new stripe mixes a nonce into the master seed so cache hygiene cannot
 * systematically replay a base that worker draws never advanced. There is no
 * reseed epoch: explicit setSeed revokes the source's opt-in instead.
 */
public final class FabroliaRandomStriping {

    /** Only the owning level constructor may opt in, before publishing its RNG. */
    public interface SharedWorldSource {
        /** Explicit setSeed after this initialization restores canonical behavior. */
        void fabrolia$markSharedWorldRandom();
    }

    private static final long MULTIPLIER = 25214903917L;
    private static final long INCREMENT = 11L;
    private static final long MASK = (1L << 48) - 1;

    /** Contended only when a worker first cuts a stripe for a shared source. */
    private static final AtomicLong SEED_NONCE = new AtomicLong(1);

    /** Private LCG state for one worker's shared-world source. */
    private static final class Stripe {
        long seed;
    }

    private static final ThreadLocal<Reference2ObjectOpenHashMap<Object, Stripe>> STRIPES =
            ThreadLocal.withInitial(() -> new Reference2ObjectOpenHashMap<>(16));

    private FabroliaRandomStriping() {}

    public static boolean onWorker() {
        return ParallelProcessor.isServerExecutionThread();
    }

    /** Contention-free draw from an explicitly opted-in shared world source. */
    public static int next(Object instance, AtomicLong masterSeed, int bits) {
        Reference2ObjectOpenHashMap<Object, Stripe> map = STRIPES.get();
        Stripe s = map.get(instance);
        if (s == null) {
            // Bound retained sources across world unload/reload. A fresh nonce
            // avoids replaying the unchanged master after a hygiene clear.
            if (map.size() > 4096) {
                map.clear();
            }
            s = new Stripe();
            s.seed = (masterSeed.get() ^ mix64(SEED_NONCE.getAndIncrement())) & MASK;
            map.put(instance, s);
        }
        long next = (s.seed * MULTIPLIER + INCREMENT) & MASK;
        s.seed = next;
        return (int) (next >>> (48 - bits));
    }


    /** SplitMix64 finalizer — full-avalanche 64-bit mixer, bijective. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
