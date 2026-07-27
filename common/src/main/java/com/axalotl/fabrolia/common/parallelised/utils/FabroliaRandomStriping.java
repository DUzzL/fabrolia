package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker-side striping for {@code LegacyRandomSource}.
 *
 * <p>The two failure modes this replaces, both measured live:
 * <ul>
 *   <li>the original FabroliaRandomState: ONE static per-thread stream shared by
 *       EVERY random source on the worker — cross-source pollution, the
 *       instance's own seed ignored;</li>
 *   <li>the honest per-instance CAS loop: correct, but 30 workers hammering
 *       the shared {@code level.random} cache line during random ticks turned
 *       into a CAS storm — 52% of the whole server (prof19, 100 bots).</li>
 * </ul>
 *
 * <p>Here a worker thread draws from a private LCG stream PER (thread,
 * instance) pair: zero contention, and distinct sources keep distinct streams.
 * Non-worker threads (main, c2me worldgen) never reach this path — they run
 * vanilla's CAS on the instance seed, preserving deterministic generation.
 *
 * <p>Seeding discipline (identical-spawner-batches report, 2026-07-05): a
 * stripe base must NEVER be reproducible. The original scheme seeded every
 * (re)seed as {@code masterSeed ^ threadId} — but worker draws don't advance
 * the master, so every reseed of one (thread, instance) pair replayed the
 * SAME stream from the SAME base. Invalidation was a GLOBAL version bumped by
 * {@code setSeed} — and LegacyRandomSource's <b>constructor calls setSeed</b>,
 * so every entity constructed anywhere invalidated every stripe on the
 * server. Net effect inside one spawner tick: each mob's construction reset
 * {@code level.random}'s stripe, so every {@code finalizeSpawn} in the batch
 * replayed byte-identical rolls — all-golden-armor / all-chicken-jockey
 * batches. Two changes close the whole replay class:
 * <ul>
 *   <li>invalidation is PER-INSTANCE (the mixin's stripe epoch) — reseeding
 *       source X no longer touches any other source's stripes;</li>
 *   <li>every seeding mixes a process-unique nonce, so no two stripe bases in
 *       a JVM lifetime coincide — reseeds and hygiene clears can't replay.</li>
 * </ul>
 */
public final class FabroliaRandomStriping {

    private static final long MULTIPLIER = 25214903917L;
    private static final long INCREMENT = 11L;
    private static final long MASK = (1L << 48) - 1;

    /** Process-unique seeding counter: every stripe seeding consumes one value,
     *  so two seedings can never derive the same base — the no-replay
     *  guarantee, independent of master seed or epoch behavior. Contended only
     *  at stripe creation / genuine reseed, both rare. */
    private static final AtomicLong SEED_NONCE = new AtomicLong(1);

    /** Per-instance stripe: local LCG state + the instance epoch it was seeded at. */
    private static final class Stripe {
        long seed;
        long epoch;
    }

    private static final ThreadLocal<Reference2ObjectOpenHashMap<Object, Stripe>> STRIPES =
            ThreadLocal.withInitial(() -> new Reference2ObjectOpenHashMap<>(16));

    private FabroliaRandomStriping() {}

    public static boolean onWorker() {
        return ParallelProcessor.isServerExecutionThread();
    }

    /**
     * Worker-side draw: contention-free stripe keyed by the source instance.
     *
     * @param instanceEpoch the source's reseed epoch (bumped by setSeed on THAT
     *                      instance only). A mismatch means the source was
     *                      reseeded after this stripe was cut — cut a fresh one.
     */
    public static int next(Object instance, AtomicLong masterSeed, long instanceEpoch, int bits) {
        Reference2ObjectOpenHashMap<Object, Stripe> map = STRIPES.get();
        Stripe s = map.get(instance);
        if (s == null) {
            // Unload hygiene: the map strongly pins dead sources (entity
            // randoms) until cleared. A re-cut stripe is harmless now that
            // seeding is nonce-mixed — the pre-fix clear re-derived
            // masterSeed ^ threadId and replayed the base stream.
            if (map.size() > 4096) {
                map.clear();
            }
            s = new Stripe();
            map.put(instance, s);
            seedStripe(s, masterSeed, instanceEpoch);
        } else if (s.epoch != instanceEpoch) {
            seedStripe(s, masterSeed, instanceEpoch);
        }
        long next = (s.seed * MULTIPLIER + INCREMENT) & MASK;
        s.seed = next;
        return (int) (next >>> (48 - bits));
    }

    /** Base = master ^ mix64(nonce): tied to the instance's seed lineage,
     *  spread by the mixer, unique per seeding by the nonce (mix64 is a
     *  bijection, so distinct nonces give distinct mixes). */
    private static void seedStripe(Stripe s, AtomicLong masterSeed, long epoch) {
        s.seed = (masterSeed.get() ^ mix64(SEED_NONCE.getAndIncrement())) & MASK;
        s.epoch = epoch;
    }

    /** SplitMix64 finalizer — full-avalanche 64-bit mixer, bijective. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
