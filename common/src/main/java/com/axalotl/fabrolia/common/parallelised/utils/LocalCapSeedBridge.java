package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;

/**
 * Static state for {@code LocalMobCapCalculatorMixin}, hosted in a PLAIN class.
 *
 * <p>HARD LESSON (task #32, 2026-07-21 crash): adding {@code @Unique} STATIC
 * fields with initializers to that mixin introduced a {@code <clinit>} the
 * mixin never had — and at runtime the target's instance {@code @Unique}
 * initializers (the long-proven {@code fabrolia$capFlight}) silently stopped
 * running: NPE in {@code detachAndFree}'s in-flight wait on the first arena
 * tick, world-tick crash. Mixin static-initializer merging is not a contract
 * to lean on; statics referenced by mixin code live HERE instead, where the
 * JVM guarantees initialization. Never add initialized static fields to an
 * existing mixin.
 */
public final class LocalCapSeedBridge {

    private LocalCapSeedBridge() {}

    /** Set while fabrolia$seedJavaShadow replays the census seed into the JAVA
     *  maps: the native routing must stand aside so the vanilla body runs (the
     *  arena already holds these counts from its own native seed). */
    public static final ThreadLocal<Boolean> SEED_BYPASS = new ThreadLocal<>();

    public static final MobCategory[] CATS = MobCategory.values();

    /** Non-null while one count-weighted seed step runs its single real
     *  {@code addMob}: every {@code MobCounts.add} on this thread records the
     *  counts it incremented, so the step can replay the remaining copies onto
     *  exactly the players the INSTALLED near-player rule chose (vanilla
     *  {@code getPlayersNear}, VMP's area index, ...). Seed and approval then
     *  share one rule (2026-10-24 tunnel-bore review). */
    private static final ThreadLocal<java.util.ArrayList<LocalMobCapCalculator.MobCounts>> SEED_RECORDING =
            new ThreadLocal<>();
    private static final ThreadLocal<java.util.ArrayList<LocalMobCapCalculator.MobCounts>> SEED_RECORDER_BUFFER =
            ThreadLocal.withInitial(java.util.ArrayList::new);

    /** Starts recording on this thread and bypasses native routing (the seed
     *  writes the Java maps only). Pair with {@link #endSeedRecording()}. */
    public static java.util.ArrayList<LocalMobCapCalculator.MobCounts> beginSeedRecording() {
        if (SEED_RECORDING.get() != null) {
            throw new IllegalStateException("nested local-cap seed recording");
        }
        java.util.ArrayList<LocalMobCapCalculator.MobCounts> touched = SEED_RECORDER_BUFFER.get();
        touched.clear();
        SEED_RECORDING.set(touched);
        SEED_BYPASS.set(Boolean.TRUE);
        return touched;
    }

    /** {@code set(null)}, not {@code remove()}: a removed entry is re-created by
     *  the next read, once per seed step. */
    public static void endSeedRecording() {
        SEED_RECORDING.set(null);
        SEED_BYPASS.set(null);
    }

    /** Called by every {@code MobCounts.add}; a no-op outside a seed step. */
    public static void recordSeedIncrement(LocalMobCapCalculator.MobCounts counts) {
        java.util.ArrayList<LocalMobCapCalculator.MobCounts> touched = SEED_RECORDING.get();
        if (touched != null) {
            touched.add(counts);
        }
    }

    /** Per-thread scratch for the pack-reservation near-player walk — one
     *  ThreadLocal (never removed, grow-only, overwritten each use) so the
     *  approval path allocates nothing per chunk pass. Slots past the current
     *  call's count may retain last use's small AtomicIntegerArray refs until
     *  overwritten — bounded (≤ array length) and deliberate. */
    private static final ThreadLocal<java.util.concurrent.atomic.AtomicIntegerArray[]> RESERVE_SCRATCH =
            ThreadLocal.withInitial(() -> new java.util.concurrent.atomic.AtomicIntegerArray[8]);

    public static java.util.concurrent.atomic.AtomicIntegerArray[] reserveScratch(int min) {
        java.util.concurrent.atomic.AtomicIntegerArray[] a = RESERVE_SCRATCH.get();
        if (a.length < min) {
            a = new java.util.concurrent.atomic.AtomicIntegerArray[Math.max(min, a.length * 2)];
            RESERVE_SCRATCH.set(a);
        }
        return a;
    }
}
