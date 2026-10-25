package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #41 — the two remaining Lithium {@code world.tick_scheduler} hazards.
 *
 * <p><b>Hazard A</b> (Lithium audit §4.6): {@code getAll()} and {@code pack()}
 * look like reads and are not. Both route through
 * {@code OrderedTickQueue.iterator() -> sort() -> removeNullsAndConsumed() ->
 * handleCompaction()}, which reallocates the backing array, moves live entries
 * to index 0 and resets {@code firstIndex}. {@code getAll()} does it
 * <em>lazily</em> — {@code Collection.spliterator()} defers {@code iterator()}
 * and {@code flatMap} builds inner streams during traversal — so the mutation
 * lands at the caller's terminal operation, outside the synchronized frame the
 * blanket {@code SyncAllMixin} bit gives the method. The blanket therefore does
 * <em>not</em> cover {@code getAll()} today. Same shape as the lazy
 * {@code PoiSection.getRecords} stream that made the blanket bit useless there.
 *
 * <p><b>Hazard B</b> (audit §4.5): {@code updateNextTickQueue(true)} removes the
 * first map entry <em>before</em> checking it was the right one, so its guard
 * destroys a bucket of live scheduled ticks and only then throws — out of
 * {@code LevelTicks.tick}. The destroyed ticks' dedup keys survive in
 * {@code allTicks}, so {@code hasScheduledTick} answers true at those positions
 * forever and they never reschedule.
 *
 * <p>Lithium's mixins do not apply on the test classpath, so
 * {@link LithiumBucketChunkTicks} below reimplements Lithium 0.25.1's
 * {@code LevelChunkTicksMixin} + {@code OrderedTickQueue} faithfully enough to
 * exhibit all three defects (§4.4 wedge, §4.5 destructive guard, §4.6 lazy
 * compaction) — the established house pattern from
 * {@code TickRemoveIfImplAgnosticTest.LithiumContractChunkTicks}. The tests
 * then drive the SAME {@code TickDomains} entry points the priority-2000
 * {@code LevelChunkTicksMixin} wraps delegate to; source pins at the bottom
 * keep the wraps and the delegation honest.
 */
class TickContainerWriteShapedApiTest {

    private static ScheduledTick<String> tick(String type, int x, long trigger,
                                              TickPriority priority, long subTick) {
        return new ScheduledTick<>(type, new BlockPos(x, 64, 0), trigger, priority, subTick);
    }

    private static ScheduledTick<String> tick(String type, int x, long trigger, long subTick) {
        return tick(type, x, trigger, TickPriority.NORMAL, subTick);
    }

    // ==================================================================
    // Hazard A — getAll() mutates, and mutates late
    // ==================================================================

    /** The laziness itself, pinned: nothing has touched a bucket when
     *  {@code getAll()} returns. Every claim below rests on this. */
    @Test
    void lithiumGetAllDefersItsMutationToTheCallersTerminalOperation() {
        LithiumBucketChunkTicks<String> c = new LithiumBucketChunkTicks<>();
        c.schedule(tick("a", 0, 100, 0));
        c.schedule(tick("b", 1, 100, 1));

        Stream<ScheduledTick<String>> lazy = c.getAll();
        assertEquals(0, c.traversals,
                "getAll() must be lazy for the hazard to exist at all: the compaction "
                        + "happens at the caller's terminal operation, not in the frame that "
                        + "carries the container monitor");
        assertEquals(2, lazy.toList().size());
        assertEquals(1, c.traversals, "the terminal operation is what iterates the bucket");
        assertEquals(Boolean.FALSE, c.traversalHeldMonitor,
                "and it iterates with no monitor held — this is the escape");
    }

    /** The mutation, made visible: an out-of-order offer marks the bucket
     *  unsorted, and the traversal then compacts it — reallocating the array
     *  and resetting {@code firstIndex} under whoever is draining. */
    @Test
    void lithiumGetAllTraversalCompactsTheBucketUnderneathADrainer() {
        LithiumBucketChunkTicks<String> c = new LithiumBucketChunkTicks<>();
        c.schedule(tick("a", 0, 100, 0));
        c.schedule(tick("b", 1, 100, 1));
        assertEquals("a", c.poll().type()); // mid-drain: slot 0 nulled, firstIndex = 1
        c.schedule(tick("c", 2, 100, 0));   // subTickOrder goes backwards -> isSorted = false

        LithiumBucketChunkTicks.Bucket<String> bucket = c.headBucket();
        assertEquals(1, bucket.firstIndex, "one slot consumed");
        assertEquals(0, c.compactions, "nothing has compacted yet");

        assertEquals(2, c.getAll().toList().size());

        assertEquals(1, c.compactions,
                "a read-shaped call reallocated the queue array");
        assertEquals(0, bucket.firstIndex,
                "and reset firstIndex — the field the concurrent drainer is advancing");
    }

    /**
     * The fix: {@link TickDomains#snapshotAll} drains the stream inside the
     * container monitor and hands back a stream over the snapshot.
     *
     * <p>FAILS PRE-FIX. Against the naive body ({@code return raw.get();} —
     * what every call site did before) {@code traversals} is still 0 when the
     * helper returns and the monitor flag is {@code FALSE}, because the
     * traversal happens later, in the caller, unguarded.
     */
    @Test
    void snapshotAllTraversesInsideTheContainerMonitorAndNotAfterwards() {
        LithiumBucketChunkTicks<String> c = new LithiumBucketChunkTicks<>();
        c.schedule(tick("a", 0, 100, 0));
        c.schedule(tick("b", 1, 100, 1));

        Stream<ScheduledTick<String>> snapshot = TickDomains.snapshotAll(c, c::getAll);

        assertEquals(1, c.traversals,
                "the container must already have been traversed when snapshotAll returns — "
                        + "a mutation that escapes the frame escapes the monitor with it");
        assertEquals(Boolean.TRUE, c.traversalHeldMonitor,
                "and the traversal must have run while holding the container monitor, which "
                        + "is what excludes a concurrent poll()");

        assertEquals(2, snapshot.toList().size());
        assertEquals(1, c.traversals,
                "consuming the returned stream must not touch the container a second time");
    }

    /**
     * Same fix, from the caller's side: the snapshot survives a mutation of the
     * container. Pre-fix the returned stream is a live view over the bucket
     * tree, so the caller either sees the mutated state or gets a
     * ConcurrentModificationException out of a method it classified as a read.
     */
    @Test
    void snapshotAllIsStableAcrossAConcurrentDrainOfTheSameContainer() {
        LithiumBucketChunkTicks<String> c = new LithiumBucketChunkTicks<>();
        c.schedule(tick("a", 0, 100, 0));
        c.schedule(tick("b", 1, 200, 1));

        Stream<ScheduledTick<String>> snapshot = TickDomains.snapshotAll(c, c::getAll);
        // whatever the drainer does next, the snapshot is already a snapshot
        while (c.peek() != null) {
            c.poll();
        }
        c.schedule(tick("z", 9, 300, 2));

        List<String> seen = snapshot.map(ScheduledTick::type).toList();
        assertEquals(List.of("a", "b"), seen,
                "snapshotAll must return the state at call time, not a live view");
    }

    /** Contract test (cannot fail pre-fix — the helper is new): {@code pack()}
     *  is eager, so a monitor is sufficient for it, but it must actually be
     *  taken. Chunk serialisation calls pack off the server thread under c2me. */
    @Test
    void packUnderMonitorHoldsTheContainerMonitorForTheWholePack() {
        LithiumBucketChunkTicks<String> c = new LithiumBucketChunkTicks<>();
        c.schedule(tick("a", 0, 100, 0));
        c.schedule(tick("b", 1, 200, 1));

        List<SavedTick<String>> packed = TickDomains.packUnderMonitor(c, () -> c.pack(90L));
        assertEquals(2, packed.size());
        assertEquals(Boolean.TRUE, c.packHeldMonitor,
                "pack() reaches the same compaction path as getAll(); it is a write");
    }

    // ==================================================================
    // Hazard B — updateNextTickQueue(true) removes before it validates
    // ==================================================================

    /** Upstream defect, documented on the faithful emulation: the guard has
     *  already destroyed a NON-EMPTY bucket when it throws, and the dedup keys
     *  of the ticks it destroyed survive — those positions are blocked from
     *  ever rescheduling. */
    @Test
    void lithiumBucketGuardDestroysLiveTicksBeforeItThrows() {
        LithiumBucketChunkTicks<String> c = primedForBucketInvariantViolation();
        ScheduledTick<String> lost = tick("t0", 7, 100, TickPriority.HIGH, 0);

        IllegalStateException e = assertThrows(IllegalStateException.class, c::poll);
        assertEquals("Next tick queue doesn't have the lowest key!", e.getMessage());

        assertFalse(c.getAll().anyMatch(t -> t.type().equals("t0")),
                "the lowest-key bucket was removed by the guard itself");
        assertTrue(c.hasScheduledTick(lost.pos(), lost.type()),
                "…while its dedup key survives: hasScheduledTick stays true forever, so that "
                        + "position never reschedules");
    }

    /** {@code compact} must be a true no-op on a healthy container: not one
     *  tick added, removed or reordered, on either implementation. */
    @Test
    void compactPreservesEveryTickOnBothImplementations() {
        for (LevelChunkTicks<String> c : List.<LevelChunkTicks<String>>of(
                new LevelChunkTicks<>(), new LithiumBucketChunkTicks<>())) {
            c.schedule(tick("a", 0, 100, 0));
            c.schedule(tick("b", 1, 100, 1));
            c.schedule(tick("c", 2, 200, 2));

            TickDomains.compact(c);

            List<String> order = new ArrayList<>();
            for (ScheduledTick<String> t; c.peek() != null && (t = c.poll()) != null; ) {
                order.add(t.type());
            }
            assertEquals(List.of("a", "b", "c"), order,
                    c.getClass().getSimpleName() + ": compact must not disturb drain order");
        }
    }

    /**
     * Container primed into the state audit §4.5 describes: a bucket with a
     * LOWER key exists (same triggerTick, higher priority — ordinary redstone)
     * while {@code nextTickQueue} still points at the higher-key bucket being
     * drained. Under Fabrolia that is a {@code schedule()} from another worker
     * interleaving a drain's {@code poll()}; single-threaded Lithium never
     * produces it, which is why the container monitor is the actual fix and
     * this is only the containment path.
     */
    @SafeVarargs
    private static LithiumBucketChunkTicks<String> primedForBucketInvariantViolation(
            ScheduledTick<String>... untouchedByTheGuard) {
        LithiumBucketChunkTicks<String> c = new LithiumBucketChunkTicks<>();
        for (ScheduledTick<String> t : untouchedByTheGuard) {
            c.schedule(t); // must land before the head is primed
        }
        c.schedule(tick("t1", 8, 100, TickPriority.NORMAL, 0));
        c.offerWithoutRepointingHead(tick("t0", 7, 100, TickPriority.HIGH, 0));
        return c;
    }

    // ==================================================================
    // Source pins
    // ==================================================================

    private static Path sourceRoot() {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia");
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia");
            if (Files.isDirectory(direct)) return direct;
            if (Files.isDirectory(viaCommon)) return viaCommon;
        }
        return null;
    }

    private static String source(String relative) throws Exception {
        Path src = sourceRoot();
        Assumptions.assumeTrue(src != null, "source tree not available");
        Path f = src.resolve(relative);
        Assumptions.assumeTrue(Files.isRegularFile(f), relative + " not found");
        return Files.readString(f);
    }

    /** The wraps must exist and must delegate to the helpers this file tests —
     *  otherwise everything above is true of {@code TickDomains} and false of
     *  the running server. */
    @Test
    void getAllAndPackAreWrappedAsWritesWhilePollRemainsFailStop() throws Exception {
        String text = source("common/mixin/world/LevelChunkTicksMixin.java");
        assertTrue(text.contains("WrapMethod(method = \"getAll\")"),
                "getAll() is write-shaped on Lithium AND mutates lazily, after the blanket-"
                        + "synchronized frame returns — it must be wrapped and snapshotted.");
        assertTrue(text.contains("WrapMethod(method = \"pack\")"),
                "pack() reaches the same compaction path and runs off-thread under c2me.");
        assertFalse(text.contains("WrapMethod(method = \"poll\")"),
                "poll() failures must leave the original body unchanged and propagate exactly");
        assertTrue(text.contains("TickDomains.snapshotAll"), "getAll wrap must delegate here");
        assertTrue(text.contains("TickDomains.packUnderMonitor"), "pack wrap must delegate here");
        assertFalse(text.contains("recoverFromBucketInvariantViolation"),
                "a partially-mutated Lithium poll must never be repaired and retried");
        assertTrue(text.contains("TickDomains.COMPACT_ONLY"),
                "the removeIf wrap must forward the compaction sentinel to the underlying "
                        + "implementation — on Lithium it is the only public operation that "
                        + "evicts stale-empty buckets and re-derives nextTickQueue.");
    }

    /**
     * The blanket monitor is the actual fix for both hazards; the wraps only
     * cover only getAll/pack/removeIf of the container's methods. {@code schedule}, {@code peek},
     * {@code hasScheduledTick} and {@code unpack} — the other half of every
     * invariant Lithium's cached head pointer depends on — have no guard but
     * this entry.
     *
     * <p>A pin, not a regression test: it passes before and after, by design.
     * Its whole job is to fail the build for the next person, and the runtime
     * counterpart ({@code fabrolia$assertBlanketSyncOnce}) fails loudly for
     * anyone who gets past it.
     */
    @Test
    void levelChunkTicksIsStillInTheBlanketSyncSet() throws Exception {
        String text = source("common/mixin/utils/SyncAllMixin.java");
        assertTrue(text.contains("LevelChunkTicks.class,"),
                "LevelChunkTicks was removed from SyncAllMixin. Lithium's tick_scheduler "
                        + "overwrite keeps a cached nextTickQueue head pointer, a bucket tree "
                        + "and a dedup set that are only mutually consistent if every public "
                        + "method is serialised on the container monitor. Removing this entry "
                        + "re-arms audit §4.4 (a chunk's scheduled ticks die silently until it "
                        + "reloads), §4.5 (the invariant guard destroys a bucket of live ticks) "
                        + "and §4.6 (getAll/pack compact the queue under a drainer). Guard "
                        + "schedule/peek/hasScheduledTick/unpack first.");
    }

    /** {@code count()} is the dedup-set cardinality on Lithium, not the queue
     *  size (audit §4.7). It diverges from vanilla the moment a queue holds
     *  duplicates, which {@code unpack} can create on either implementation, so
     *  it can never be a liveness gate. {@code peek()} is. */
    @Test
    void countIsNeverUsedAsALivenessGate() throws Exception {
        for (String file : List.of("common/regions/TickDomains.java",
                "common/regions/IslandScheduledTicks.java")) {
            String text = source(file);
            List<String> offenders = new ArrayList<>();
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
                if (trimmed.contains("count() > 0") || trimmed.contains("count() != 0")
                        || trimmed.contains("count() == 0")) {
                    offenders.add(file + ": " + trimmed);
                }
            }
            assertTrue(offenders.isEmpty(),
                    "LevelChunkTicks.count() is allTicks.size() on Lithium — the (pos,type) "
                            + "dedup cardinality, not the queue size. Use peek() != null for "
                            + "liveness. Offenders: " + offenders);
        }
    }

    /** The divergence is documented where it is deliberately tolerated, so the
     *  pin above does not just push the mistake into TickRepair. */
    @Test
    void tickRepairDocumentsWhyItStillReadsCount() throws Exception {
        String text = source("common/regions/TickRepair.java");
        assertTrue(text.contains("c.count() > 0 || c.peek() != null"),
                "the chunk-selection scan must not miss containers whose duplicate entries "
                        + "collapse to count()==0 on Lithium");
        assertTrue(text.contains("§4.7"),
                "the packed-state probe keeps using count(); the imprecision it inherits must "
                        + "be written down next to it");
    }

    // ==================================================================
    // Lithium 0.25.1 emulation
    // ==================================================================

    /**
     * Faithful-enough reimplementation of
     * {@code net.caffeinemc.mods.lithium.mixin.world.tick_scheduler.LevelChunkTicksMixin}
     * plus {@code common.world.scheduler.OrderedTickQueue}, at tag
     * {@code mc26.2-0.25.1}. Structure copied method-for-method, including the
     * three defects; a {@code TreeMap} stands in for
     * {@code Long2ReferenceAVLTreeMap} and an {@code ArrayList} for the
     * {@code ScheduledTick[]} ring, neither of which changes any behaviour
     * under test.
     */
    static final class LithiumBucketChunkTicks<T> extends LevelChunkTicks<T> {

        final TreeMap<Long, Bucket<T>> tickQueuesByTimeAndPriority = new TreeMap<>();
        private Bucket<T> nextTickQueue;
        private final Set<Object> allTicks = new HashSet<>();

        /** Bucket iterations observed (i.e. how often getAll/pack reached the
         *  compaction path) and whether the container monitor was held then. */
        int traversals;
        int compactions;
        Boolean traversalHeldMonitor;
        Boolean packHeldMonitor;

        private static long bucketKey(ScheduledTick<?> t) {
            return (t.triggerTick() << 4L) | (t.priority().ordinal() & 15);
        }

        private static Object dedupKey(BlockPos pos, Object type) {
            return List.of(pos, type);
        }

        Bucket<T> headBucket() {
            return this.nextTickQueue;
        }

        /**
         * The §4.5 race, made deterministic: a tick lands in a bucket with a
         * LOWER key while {@code nextTickQueue} still points at the bucket being
         * drained. Real Lithium reaches this state when worker B's
         * {@code queueTick} interleaves worker A's {@code poll}; the resulting
         * container state is exactly this.
         */
        void offerWithoutRepointingHead(ScheduledTick<T> t) {
            this.allTicks.add(dedupKey(t.pos(), t.type()));
            this.tickQueuesByTimeAndPriority
                    .computeIfAbsent(bucketKey(t), k -> new Bucket<>(this))
                    .offer(t);
        }

        @Override
        public void schedule(ScheduledTick<T> t) {
            if (this.allTicks.add(dedupKey(t.pos(), t.type()))) {
                this.queueTick(t);
            }
        }

        private void queueTick(ScheduledTick<T> t) {
            Bucket<T> bucket = this.tickQueuesByTimeAndPriority
                    .computeIfAbsent(bucketKey(t), k -> new Bucket<>(this));
            if (bucket.isEmpty()) {
                this.updateNextTickQueue(false);
            }
            bucket.offer(t);
        }

        private void updateNextTickQueue(boolean checkEmpty) {
            if (checkEmpty && this.nextTickQueue != null && this.nextTickQueue.isEmpty()) {
                // THE DEFECT: removed first, validated second.
                Bucket<T> removed = this.tickQueuesByTimeAndPriority
                        .remove(this.tickQueuesByTimeAndPriority.firstKey());
                if (removed != this.nextTickQueue) {
                    throw new IllegalStateException("Next tick queue doesn't have the lowest key!");
                }
            }
            if (this.tickQueuesByTimeAndPriority.isEmpty()) {
                this.nextTickQueue = null;
                return;
            }
            this.nextTickQueue = this.tickQueuesByTimeAndPriority
                    .get(this.tickQueuesByTimeAndPriority.firstKey());
        }

        @Override
        public ScheduledTick<T> peek() {
            return this.nextTickQueue == null ? null : this.nextTickQueue.peek();
        }

        @Override
        public ScheduledTick<T> poll() {
            // Lithium dereferences the field with no null check (audit §4.3).
            ScheduledTick<T> t = this.nextTickQueue.poll();
            if (t != null) {
                if (this.nextTickQueue.isEmpty()) {
                    this.updateNextTickQueue(true);
                }
                this.allTicks.remove(dedupKey(t.pos(), t.type()));
                return t;
            }
            return null; // §4.4: no cleanup, the empty bucket stays wired as the head
        }

        @Override
        public boolean hasScheduledTick(BlockPos pos, T type) {
            return this.allTicks.contains(dedupKey(pos, type));
        }

        @Override
        public int count() {
            return this.allTicks.size(); // §4.7: the dedup cardinality, not the queue size
        }

        @Override
        public Stream<ScheduledTick<T>> getAll() {
            return this.tickQueuesByTimeAndPriority.values().stream().flatMap(Collection::stream);
        }

        @Override
        public List<SavedTick<T>> pack(long currentTick) {
            this.packHeldMonitor = Thread.holdsLock(this);
            List<SavedTick<T>> out = new ArrayList<>(this.count());
            for (Bucket<T> bucket : this.tickQueuesByTimeAndPriority.values()) {
                for (ScheduledTick<T> t : bucket) {
                    out.add(t.toSavedTick(currentTick));
                }
            }
            return out;
        }

        @Override
        public void removeIf(Predicate<ScheduledTick<T>> predicate) {
            for (Iterator<Bucket<T>> it = this.tickQueuesByTimeAndPriority.values().iterator();
                 it.hasNext(); ) {
                Bucket<T> bucket = it.next();
                bucket.sort();
                boolean removed = false;
                for (int i = 0; i < bucket.size(); i++) { // absolute indices (audit §4.2)
                    ScheduledTick<T> t = bucket.at(i);
                    if (predicate.test(t)) {
                        bucket.set(i, null);
                        this.allTicks.remove(dedupKey(t.pos(), t.type()));
                        removed = true;
                    }
                }
                if (removed) {
                    bucket.removeNullsAndConsumed();
                }
                if (bucket.isEmpty()) {
                    it.remove();
                }
            }
            this.updateNextTickQueue(false);
        }

        /** {@code OrderedTickQueue}: a partially-consumed array with a relative
         *  {@code size()} and an {@code iterator()} that sorts (and therefore
         *  may compact) before handing anything out. */
        static final class Bucket<T> extends AbstractCollection<ScheduledTick<T>> {
            private final LithiumBucketChunkTicks<T> owner;
            private final ArrayList<ScheduledTick<T>> arr = new ArrayList<>();
            int firstIndex;
            private boolean isSorted = true;
            private long currentMaxSubTickOrder = Long.MIN_VALUE;

            Bucket(LithiumBucketChunkTicks<T> owner) {
                this.owner = owner;
            }

            void offer(ScheduledTick<T> t) {
                if (t.subTickOrder() <= this.currentMaxSubTickOrder) {
                    this.isSorted = false;
                } else {
                    this.currentMaxSubTickOrder = t.subTickOrder();
                }
                this.arr.add(t);
            }

            ScheduledTick<T> peek() {
                return this.isEmpty() ? null : this.arr.get(this.firstIndex);
            }

            ScheduledTick<T> poll() {
                if (this.isEmpty()) {
                    return null;
                }
                if (!this.isSorted) {
                    this.sort();
                }
                ScheduledTick<T> t = this.arr.get(this.firstIndex);
                this.arr.set(this.firstIndex, null);
                this.firstIndex++;
                return t;
            }

            void sort() {
                this.owner.traversals++;
                this.owner.traversalHeldMonitor = Thread.holdsLock(this.owner);
                if (this.isSorted) {
                    return;
                }
                this.removeNullsAndConsumed();
                this.arr.subList(this.firstIndex, this.arr.size())
                        .sort(Comparator.comparingLong(ScheduledTick::subTickOrder));
                this.isSorted = true;
            }

            void removeNullsAndConsumed() {
                List<ScheduledTick<T>> live = new ArrayList<>();
                for (int i = this.firstIndex; i < this.arr.size(); i++) {
                    ScheduledTick<T> t = this.arr.get(i);
                    if (t != null) {
                        live.add(t);
                    }
                }
                // handleCompaction: reallocate, move to index 0, reset firstIndex
                this.arr.clear();
                this.arr.addAll(live);
                this.firstIndex = 0;
                this.owner.compactions++;
                if (live.isEmpty()) {
                    this.currentMaxSubTickOrder = Long.MIN_VALUE;
                }
            }

            ScheduledTick<T> at(int i) {
                return this.arr.get(i);
            }

            void set(int i, ScheduledTick<T> t) {
                this.arr.set(i, t);
            }

            @Override
            public int size() {
                return this.arr.size() - this.firstIndex;
            }

            @Override
            public boolean isEmpty() {
                return this.arr.size() <= this.firstIndex;
            }

            @Override
            public Iterator<ScheduledTick<T>> iterator() {
                if (this.isEmpty()) {
                    return Collections.emptyIterator();
                }
                this.sort();
                return new ArrayList<>(this.arr.subList(this.firstIndex, this.arr.size())).iterator();
            }
        }
    }
}
