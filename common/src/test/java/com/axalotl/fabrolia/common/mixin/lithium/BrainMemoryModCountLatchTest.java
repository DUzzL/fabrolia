package com.axalotl.fabrolia.common.mixin.lithium;

import net.minecraft.world.entity.ai.memory.MemorySlot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The villager freeze, reduced to its mechanism and pinned.
 *
 * <p>Lithium's {@code ai.task.memory_changes} module caches
 * {@code Behavior.hasRequiredMemories(E)} against a per-brain memory-modification
 * counter, and bumps that counter BEFORE the mutation it announces. A reader that
 * lands in the window between bump and mutation caches a result computed from
 * pre-mutation state against the post-bump count — and because nothing bumps the
 * counter afterwards, the behavior's {@code tryStart} gate stays latched on that
 * stale answer indefinitely. Vanilla re-reads {@code brain.checkMemory(...)} every
 * call, so the identical interleaving self-heals there: lithium converts a transient
 * race into a permanent latch.
 *
 * <p>Lithium's MIXINS do not apply in unit tests, so this drives faithful models of
 * lithium's two classes ({@code memory_changes.BrainMixin} and
 * {@code memory_changes.BehaviorMixin}), transcribed from the 0.25.1 source, over the
 * REAL vanilla {@link MemorySlot}. {@link BrainModel#trailingBump} toggles Fabrolia's
 * Fix B ({@code FabroliaLithiumBrainMemoryBumpMixin}) and {@link BrainModel} always
 * uses the {@link AtomicLong} of Fix A ({@code FabroliaLithiumBrainMemoryCounterMixin}),
 * so the latch tests isolate the ORDERING defect rather than a visibility one — the
 * recorded 2026-07-14 diagnosis blamed visibility and was wrong.
 *
 * @see com.axalotl.fabrolia.common.mixin.lithium.FabroliaLithiumBrainMemoryBumpMixin
 */
class BrainMemoryModCountLatchTest {

    private static final long TIMEOUT_MS = 5_000L;

    /**
     * lithium {@code ai/task/memory_changes/BrainMixin} + the vanilla
     * {@code Brain.setMemoryInternal} / {@code Brain.eraseMemory} bodies it wraps,
     * inlined over one real {@link MemorySlot}.
     */
    private static final class BrainModel {
        final MemorySlot<Object> slot = MemorySlot.create();
        /** Fix A: what lithium declares as {@code @Unique private long memoryModCount = 1;} */
        private final AtomicLong memoryModCount = new AtomicLong(1L);
        /** Fix B: bump again once the mutation has actually landed. */
        private final boolean trailingBump;
        /** Injection point for a reader thread, exactly between bump and mutation. */
        volatile Runnable bumpMutateWindow = () -> {
        };

        BrainModel(boolean trailingBump) {
            this.trailingBump = trailingBump;
        }

        long lithium$getMemoryValueModCount() {
            return this.memoryModCount.get();
        }

        void lithium$onMemoryModified() {
            this.memoryModCount.incrementAndGet();
        }

        /**
         * {@code Brain.setMemoryInternal(type, value)} with lithium's
         * {@code clearTrackingChanges} / {@code setTrackingChanges} handlers inlined.
         */
        void setMemory(Object value) {
            boolean hadValue = this.slot.hasValue();
            // lithium: `if (instance.hasValue() [== (value == null)]) onMemoryModified();`
            // then `original.call(...)`. The bump precedes the mutation.
            if (hadValue == (value == null)) {
                this.lithium$onMemoryModified();
            }
            this.bumpMutateWindow.run();
            if (value == null) {
                this.slot.clear();
            } else {
                this.slot.set(value);
            }
            if (this.trailingBump && hadValue != this.slot.hasValue()) {
                this.lithium$onMemoryModified();
            }
        }
    }

    /**
     * lithium {@code ai/task/memory_changes/BehaviorMixin.hasRequiredMemories}, for a
     * behavior whose single entry condition is {@code VALUE_PRESENT}.
     *
     * <p>The two cache fields are {@code volatile} here only so that the test measures
     * the ordering defect and not a JMM-visibility artefact of the harness. In
     * production they are plain and that is correct: they are touched only by the
     * thread ticking that entity, and cross-tick publication rides Fabrolia's
     * end-of-tick barrier.
     */
    private static final class BehaviorModel {
        private volatile long cachedMemoryModCount = -1L;
        private volatile boolean cachedHasRequiredMemoryState;

        boolean hasRequiredMemories(BrainModel brain) {
            long modCount = brain.lithium$getMemoryValueModCount();
            if (this.cachedMemoryModCount == modCount) {
                return this.cachedHasRequiredMemoryState;
            }
            this.cachedMemoryModCount = modCount;
            return this.cachedHasRequiredMemoryState = brain.slot.hasValue();
        }
    }

    /**
     * Parks a reader inside the bump/mutate window, runs the write to completion, and
     * returns what the reader observed.
     */
    private static boolean readInsideTheWindow(BrainModel brain, BehaviorModel behavior, Object value)
            throws InterruptedException {
        CountDownLatch readerParked = new CountDownLatch(1);
        CountDownLatch readerFinished = new CountDownLatch(1);
        AtomicBoolean observed = new AtomicBoolean();

        Thread reader = new Thread(() -> {
            try {
                assertTrue(readerParked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            observed.set(behavior.hasRequiredMemories(brain));
            readerFinished.countDown();
        }, "memory-mod-count-reader");
        reader.setDaemon(true);
        reader.start();

        brain.bumpMutateWindow = () -> {
            readerParked.countDown();
            try {
                assertTrue(readerFinished.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                        "reader must complete inside the bump/mutate window");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        brain.setMemory(value);
        brain.bumpMutateWindow = () -> {
        };
        reader.join(TIMEOUT_MS);
        return observed.get();
    }

    /**
     * WITHOUT Fix B: the gate latches on the stale answer and never re-arms, even
     * though the memory is present and the mutation completed long ago.
     */
    @Test
    void bumpBeforeMutationLatchesTheStartGateForever() throws InterruptedException {
        BrainModel brain = new BrainModel(false);
        BehaviorModel behavior = new BehaviorModel();

        assertFalse(behavior.hasRequiredMemories(brain), "memory starts absent");
        assertEquals(1L, brain.lithium$getMemoryValueModCount());

        boolean observed = readInsideTheWindow(brain, behavior, "walk-target");

        assertFalse(observed, "reader legitimately saw pre-mutation state — transient, not the bug");
        assertTrue(brain.slot.hasValue(), "the mutation completed");
        assertEquals(2L, brain.lithium$getMemoryValueModCount(),
                "lithium bumped once, BEFORE the mutation, and never again");

        // The bug: the count the reader cached is the count that is now current, so
        // every later call short-circuits to the stale answer.
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(behavior.hasRequiredMemories(brain),
                    "tick " + tick + ": tryStart gate latched false while the memory IS present — "
                            + "the behavior never starts, the villager never moves");
        }

        // It only ever un-sticks by accident, when some UNRELATED memory write bumps.
        brain.lithium$onMemoryModified();
        assertTrue(behavior.hasRequiredMemories(brain), "an unrelated bump is the only escape");
    }

    /**
     * WITH Fix B: the trailing bump invalidates the entry the reader cached from
     * pre-mutation state, so the very next call recomputes.
     */
    @Test
    void trailingBumpInvalidatesTheStaleCacheEntry() throws InterruptedException {
        BrainModel brain = new BrainModel(true);
        BehaviorModel behavior = new BehaviorModel();

        assertFalse(behavior.hasRequiredMemories(brain), "memory starts absent");

        boolean observed = readInsideTheWindow(brain, behavior, "walk-target");

        assertFalse(observed, "the reader still sees pre-mutation state — that part is unavoidable");
        assertTrue(brain.slot.hasValue(), "the mutation completed");
        assertEquals(3L, brain.lithium$getMemoryValueModCount(),
                "lithium's pre-bump plus Fabrolia's post-bump");
        assertTrue(behavior.hasRequiredMemories(brain),
                "the trailing bump invalidated the stale entry: the gate re-armed on the next call");
    }

    /**
     * Fix B must not over-invalidate: outside the race the trailing bump fires on
     * exactly the same presence transitions lithium already bumps on, so the cache
     * still hits across ticks. An unconditional trailing bump would pass the test
     * above and silently reduce the module to vanilla cost — sensors and
     * {@code LookAtTargetSink} rewrite already-present memories every tick.
     */
    @Test
    void trailingBumpFiresOnlyOnPresenceTransitions() {
        BrainModel brain = new BrainModel(true);
        BehaviorModel behavior = new BehaviorModel();

        brain.setMemory("first");                       // absent -> present: one transition
        long afterFirst = brain.lithium$getMemoryValueModCount();
        assertTrue(behavior.hasRequiredMemories(brain));

        for (int i = 0; i < 20; i++) {
            brain.setMemory("rewrite-" + i);            // present -> present: no transition
        }
        assertEquals(afterFirst, brain.lithium$getMemoryValueModCount(),
                "rewriting a present memory must not bump — that is the whole optimization");
        assertTrue(behavior.hasRequiredMemories(brain), "cache still hits");

        brain.setMemory(null);                          // present -> absent: one transition
        assertEquals(afterFirst + 2L, brain.lithium$getMemoryValueModCount(),
                "lithium's pre-bump plus Fabrolia's post-bump, once");
        assertFalse(behavior.hasRequiredMemories(brain), "and the gate saw it");
    }

    /**
     * The predicate Fabrolia's {@code fabrolia$bumpIfPresenceChanged} evaluates
     * (presence before the mutation != presence after) must agree with lithium's own
     * pre-mutation predicate on every input, or the trailing bump is either missing
     * or spurious. Checked against the real {@link MemorySlot} state machine and the
     * real {@code Brain.setMemoryInternal} normalisation of empty collections.
     */
    @Test
    void fabroliaPredicateMatchesLithiumPredicateOnEveryInput() {
        List<Object> values = new ArrayList<>();
        values.add(null);
        values.add("value");
        values.add(List.of());          // Brain.isEmptyCollection -> normalised to null
        values.add(List.of("nonempty"));

        for (boolean startPresent : new boolean[]{false, true}) {
            for (Object raw : values) {
                MemorySlot<Object> slot = MemorySlot.create();
                if (startPresent) {
                    slot.set("seed");
                }
                Object value = (raw instanceof java.util.Collection<?> c && c.isEmpty()) ? null : raw;

                // lithium, evaluated BEFORE the mutation:
                //   clear() path -> `instance.hasValue()`
                //   set()   path -> `instance.hasValue() == (value == null)`, and value != null there
                boolean lithiumBumps = value == null ? slot.hasValue() : !slot.hasValue();

                boolean before = slot.hasValue();
                if (value == null) {
                    slot.clear();
                } else {
                    slot.set(value);
                }
                boolean fabroliaBumps = before != slot.hasValue();

                assertEquals(lithiumBumps, fabroliaBumps,
                        "startPresent=" + startPresent + " value=" + raw);
            }
        }
    }

    /**
     * Fix A, D2: {@code memoryModCount++} is a non-atomic read-modify-write. Forced
     * interleaving — both threads read, then both write — loses a bump, and a lost
     * bump is exactly the one that would have re-armed a latched gate.
     */
    @Test
    void plainCounterLosesABumpUnderAForcedInterleaving() throws InterruptedException {
        long[] plain = {1L};
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Runnable bump = () -> {
            long read = plain[0];
            bothRead.countDown();
            try {
                assertTrue(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            plain[0] = read + 1L;
        };

        Thread a = new Thread(bump, "bump-a");
        Thread b = new Thread(bump, "bump-b");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        assertTrue(bothRead.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        release.countDown();
        a.join(TIMEOUT_MS);
        b.join(TIMEOUT_MS);

        assertEquals(2L, plain[0], "two bumps, one survived: lithium's plain long loses updates");
    }

    /** The same contention against Fix A's {@link AtomicLong} loses nothing. */
    @Test
    void atomicCounterLosesNoBumpsUnderContention() throws InterruptedException {
        AtomicLong counter = new AtomicLong(1L);
        int threads = 8;
        int bumpsPerThread = 20_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    assertTrue(start.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < bumpsPerThread; n++) {
                    counter.incrementAndGet();
                }
            }, "atomic-bump-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join(TIMEOUT_MS * 4);
        }

        assertEquals(1L + (long) threads * bumpsPerThread, counter.get());
    }
}
