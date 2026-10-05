package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;

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

    /** Bound on the sources one thread keeps stripes for (one per level is usual;
     *  a region worker ticks every level, so the bound must exceed any level count). */
    static final int MAX_SOURCES = 256;

    /** Contended only when a worker first cuts a stripe for a shared source. */
    private static final AtomicLong SEED_NONCE = new AtomicLong(1);

    /**
     * One thread's private LCG states, keyed by source identity. A thread draws
     * from a handful of level sources, so an identity scan over a short array is
     * cheaper than hashing on every draw.
     */
    static final class Stripes {
        Object[] sources = new Object[4];
        long[] seeds = new long[4];
        int size;
    }

    private static final ThreadLocal<Stripes> STRIPES = ThreadLocal.withInitial(Stripes::new);

    private FabroliaRandomStriping() {}

    public static boolean onWorker() {
        return ParallelProcessor.isServerExecutionThread();
    }

    /** Region workers keep their stripes in a thread field: no ThreadLocal lookup per draw. */
    private static Stripes currentStripes() {
        if (Thread.currentThread() instanceof RegionWorkerThread worker) {
            Stripes stripes = (Stripes) worker.randomStripes;
            if (stripes == null) {
                stripes = new Stripes();
                worker.randomStripes = stripes;
            }
            return stripes;
        }
        return STRIPES.get();
    }

    /** Contention-free draw from an explicitly opted-in shared world source. */
    public static int next(Object instance, AtomicLong masterSeed, int bits) {
        return draw(currentStripes(), instance, masterSeed, bits);
    }

    static int draw(Stripes stripes, Object instance, AtomicLong masterSeed, int bits) {
        Object[] sources = stripes.sources;
        int size = stripes.size;
        int slot = -1;
        for (int i = 0; i < size; i++) {
            if (sources[i] == instance) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            slot = cut(stripes, instance, masterSeed);
        }
        long next = (stripes.seeds[slot] * MULTIPLIER + INCREMENT) & MASK;
        stripes.seeds[slot] = next;
        return (int) (next >>> (48 - bits));
    }

    private static int cut(Stripes stripes, Object instance, AtomicLong masterSeed) {
        int size = stripes.size;
        if (size == MAX_SOURCES) {
            // Bound retained sources across world unload/reload. A fresh nonce
            // avoids replaying the unchanged master after a hygiene clear.
            java.util.Arrays.fill(stripes.sources, null);
            size = 0;
        } else if (size == stripes.sources.length) {
            int grown = Math.min(MAX_SOURCES, size * 2);
            stripes.sources = java.util.Arrays.copyOf(stripes.sources, grown);
            stripes.seeds = java.util.Arrays.copyOf(stripes.seeds, grown);
        }
        stripes.sources[size] = instance;
        stripes.seeds[size] = (masterSeed.get() ^ mix64(SEED_NONCE.getAndIncrement())) & MASK;
        stripes.size = size + 1;
        return size;
    }


    /** SplitMix64 finalizer — full-avalanche 64-bit mixer, bijective. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
