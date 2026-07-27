package com.axalotl.fabrolia.common.mixin.utils;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaRandomStriping;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe LegacyRandomSource with PER-INSTANCE streams.
 *
 * <p>Vanilla's seed is already an AtomicLong — its only concurrency problem
 * is the ThreadingDetector throw on a lost CAS. The fix is the obvious one:
 * loop the CAS. Lock-free, correct LCG semantics per source, zero allocation,
 * inlines to a handful of instructions (measured in the nalim campaign:
 * an FFI-per-call Rust RNG LOSES to this — C2 inlining beats the call
 * overhead; Rust randoms win only in bulk draws inside kernels).
 *
 * <p>This replaces the previous FabroliaRandomState scheme, which funneled EVERY
 * random source on a worker through one static per-thread stream (cross-source
 * pollution, master seed ignored after first use, a ThreadLocal lookup per
 * call) and left {@code nextGaussian}'s MarsagliaPolarGaussian pair-cache
 * fields racing entirely.
 *
 * <p>Contention profile: entity randoms are per-entity (one worker at a time
 * — uncontended). The shared {@code level.random} sees occasional cross-worker
 * CAS retries (~cache-line ping), which is the honest price of one shared
 * vanilla stream. Gaussian draws serialize on the pair-cache monitor —
 * uncontended in practice, correct under contention.
 */
@Mixin(LegacyRandomSource.class)
public abstract class ThreadSafeLegacyRandomSourceMixin {

    @Shadow
    @Final
    private AtomicLong seed;

    @Shadow
    @Final
    private MarsagliaPolarGaussian gaussianSource;

    /**
     * Reseed epoch for THIS source's worker stripes: a stripe cut before a
     * setSeed must not be drawn from after it. Per-instance ON PURPOSE
     * (identical-spawner-batches report, 2026-07-05): the previous GLOBAL
     * version was bumped from here — and LegacyRandomSource's constructor
     * calls setSeed, so every entity constructed anywhere invalidated every
     * stripe on the server; combined with the then-replayable reseed base,
     * each mob constructed by a spawner reset level.random's stripe and every
     * finalizeSpawn in the batch rolled the identical stream (all-gold-armor
     * batches). See FabroliaRandomStriping for the seeding half of the fix.
     *
     * <p>Plain non-atomic increment: concurrent setSeed on one instance has no
     * defined stream contract anyway (vanilla ThreadingDetector-throws there),
     * and a lost bump can only delay a stripe refresh — never cause a replay,
     * since stripe seeding is nonce-unique.
     */
    @Unique
    private volatile long fabrolia$stripeEpoch;

    /**
     * @author Fabrolia
     * @reason Thread-safe: vanilla body minus the ThreadingDetector throw.
     */
    @Overwrite
    public void setSeed(long seed) {
        this.seed.set((seed ^ 25214903917L) & 281474976710655L);
        this.gaussianSource.reset();
        this.fabrolia$stripeEpoch++;
    }

    /**
     * @author Fabrolia
     * @reason Thread-safe without contention. Workers draw from a private
     *         stripe per (thread, instance) — a 30-worker CAS loop on the
     *         shared level.random cache line was 52% of the server (prof19).
     *         Non-worker threads (main, worldgen) keep vanilla's CAS on the
     *         instance seed minus the ThreadingDetector throw — generation
     *         stays deterministic.
     */
    @Overwrite
    public int next(int bits) {
        if (FabroliaRandomStriping.onWorker()) {
            return FabroliaRandomStriping.next(this, this.seed, this.fabrolia$stripeEpoch, bits);
        }
        long current;
        long next;
        do {
            current = this.seed.get();
            next = (current * 25214903917L + 11L) & 281474976710655L;
        } while (!this.seed.compareAndSet(current, next));
        return (int) (next >>> (48 - bits));
    }

    /**
     * @author Fabrolia
     * @reason MarsagliaPolarGaussian caches the second draw of each polar
     *         pair in plain instance fields — serialize pair production.
     *         No nested locking: the inner nextDouble→next() path is the
     *         lock-free CAS above.
     */
    @Overwrite
    public double nextGaussian() {
        synchronized (this.gaussianSource) {
            return this.gaussianSource.nextGaussian();
        }
    }
}
