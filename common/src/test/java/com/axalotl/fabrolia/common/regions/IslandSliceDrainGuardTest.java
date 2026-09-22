package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #39 — {@code IslandScheduledTicks.runSlice} drained containers with a bare
 * {@code top.poll()} straight after {@code containersToTick.poll()}, inheriting
 * vanilla's "a container only enters containersToTick with a non-null peek()"
 * invariant. Vanilla earns that invariant by being single-threaded. Under island
 * slicing the invariant spans a partitioning step and a slice handoff, and the
 * container is reachable off-slice besides ({@link TickDomains} drains from
 * clearArea/fill, {@code shiftAll}, and c2me's off-thread {@code pack()}).
 *
 * <p>Why an unguarded poll is not merely an NPE: on an empty-BUT-PRESENT head
 * bucket Lithium's {@code poll()} returns null <em>without cleanup</em>, leaving
 * that bucket wired as both {@code nextTickQueue} and the first key of
 * {@code tickQueuesByTimeAndPriority}. {@code queueTick}'s
 * {@code updateNextTickQueue(false)} re-selects it by {@code firstLongKey()}
 * forever after, so {@code peek()}/{@code poll()} return null permanently and
 * {@code LevelTicks} drops the chunk from {@code nextTickForContainer} — while
 * {@code schedule()} keeps filling {@code allTicks}, so {@code hasScheduledTick}
 * stays true and every repeater/comparator/observer/fluid in that chunk stops
 * rescheduling. Silent, persistent and chunk-local until the chunk reloads.
 * That is why the guard has to be structural, not a null check on the result.
 */
class IslandSliceDrainGuardTest {

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

    private static String islandScheduledTicksSource() throws Exception {
        Path src = sourceRoot();
        Assumptions.assumeTrue(src != null, "source tree not available");
        Path f = src.resolve("common/regions/IslandScheduledTicks.java");
        Assumptions.assumeTrue(Files.isRegularFile(f), "IslandScheduledTicks.java not found");
        return Files.readString(f);
    }

    /**
     * The head poll of each container must be peek-guarded. Pinning the source
     * because the drain is a private static over slice-local state — there is no
     * seam to call it through, and the defect is the <em>absence</em> of a guard.
     */
    @Test
    void sliceDrainPeekGuardsItsHeadPoll() throws Exception {
        String text = islandScheduledTicksSource();
        assertTrue(text.contains("top.peek() != null ? top.poll() : null"),
                "runSlice must peek-before-poll the container head: Lithium's poll() "
                        + "NPEs on a null nextTickQueue and silently wedges the container "
                        + "on an empty-but-present head bucket.");
        assertFalse(text.contains("ScheduledTick<T> first = top.poll();"),
                "the bare head poll is back — see this test's javadoc for what it costs.");
    }

    /**
     * peek/poll pairs are only meaningful if they are atomic against the
     * synchronized container API, and the drain does three of them per container.
     */
    @Test
    void sliceDrainHoldsTheContainerMonitorAcrossThePair() throws Exception {
        String text = islandScheduledTicksSource();
        assertTrue(text.contains("synchronized (top) {"),
                "the per-container drain must hold the container monitor so peek/poll "
                        + "pairs are atomic against off-slice mutators.");
    }

    /**
     * The bar container's head must be read BEFORE the drained container's monitor
     * is taken. Nesting two container monitors gives two slices draining crossing
     * pairs an A->B / B->A cycle — the exact shape of the POI
     * DistanceTracker/PoiSection deadlock (task #38).
     */
    @Test
    void barIsReadBeforeTheContainerMonitorIsTaken() throws Exception {
        String text = islandScheduledTicksSource();
        int bar = text.indexOf("ScheduledTick<T> bar = nextBest != null ? nextBest.peek() : null;");
        int monitor = text.indexOf("synchronized (top) {");
        assertTrue(bar > 0 && monitor > 0, "drain shape not recognised");
        assertTrue(bar < monitor,
                "nextBest.peek() enters ANOTHER container's monitor; reading it inside "
                        + "synchronized (top) nests two container monitors and reintroduces "
                        + "an A->B / B->A deadlock between slices.");
    }

    /**
     * Once empty containers are reachable in {@code containersToTick},
     * PriorityQueue re-compares on every sift — so the comparator sees null heads.
     * Vanilla's CONTAINER_DRAIN_ORDER replica would NPE there.
     */
    @Test
    void containerOrderSortsEmptyContainersLastInsteadOfThrowing() throws Exception {
        String text = islandScheduledTicksSource();
        assertFalse(text.contains("INTRA_TICK_DRAIN_ORDER.compare(a.peek(), b.peek())"),
                "containerOrder must tolerate a null head: a container can be emptied "
                        + "after collection by another slice or an off-slice mutator.");

        // Behavioural check of the ordering contract the drain relies on.
        Comparator<LevelChunkTicks<?>> order = nullTolerantOrder();
        LevelChunkTicks<String> full = new LevelChunkTicks<>();
        full.schedule(new ScheduledTick<>("a", new BlockPos(1, 1, 1), 5L, TickPriority.NORMAL, 0L));
        LevelChunkTicks<String> empty = new LevelChunkTicks<>();

        assertTrue(order.compare(full, empty) < 0, "non-empty must sort before empty");
        assertTrue(order.compare(empty, full) > 0, "empty must sort after non-empty");
        assertEquals(0, order.compare(empty, empty), "two empties are equal");

        PriorityQueue<LevelChunkTicks<?>> q = new PriorityQueue<>(order);
        q.add(empty);
        q.add(full);
        assertEquals(full, q.poll(), "the live container must come out first");
    }

    /**
     * A mutable tick container must enter the priority queue once. If the same
     * reference appears twice, polling one entry changes the comparator key of
     * the other without giving {@link PriorityQueue} a chance to re-heapify.
     * The smallest exact oracle is A=[1,3], B=[2]: a duplicate drains 1,3,2,
     * while one reference per key preserves vanilla's 1,2,3 merge.
     */
    @Test
    void duplicateMutableContainerReferenceInvertsCrossContainerOrder() {
        MutableHead aBroken = new MutableHead(1L, 3L);
        MutableHead bBroken = new MutableHead(2L);
        assertEquals(List.of(1L, 3L, 2L), drainMutableHeads(List.of(aBroken, aBroken, bBroken)),
                "oracle: a duplicate mutable PQ member must demonstrate the 1,3,2 inversion");

        MutableHead aUnique = new MutableHead(1L, 3L);
        MutableHead bUnique = new MutableHead(2L);
        assertEquals(List.of(1L, 2L, 3L), drainMutableHeads(List.of(aUnique, bUnique)),
                "one container identity per due key preserves the vanilla cross-container merge");
    }

    /** Mirror of the production comparator; kept here so the contract is executable. */
    private static Comparator<LevelChunkTicks<?>> nullTolerantOrder() {
        return (a, b) -> {
            ScheduledTick<?> ha = a.peek();
            ScheduledTick<?> hb = b.peek();
            if (ha == null) return hb == null ? 0 : 1;
            if (hb == null) return -1;
            return ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(ha, hb);
        };
    }

    /** Mutable-head stand-in for LevelChunkTicks; values are intra-tick order. */
    private static final class MutableHead {
        private final ArrayDeque<Long> ticks = new ArrayDeque<>();

        private MutableHead(long... order) {
            for (long value : order) ticks.add(value);
        }

        private Long peek() {
            return ticks.peek();
        }

        private Long poll() {
            return ticks.poll();
        }
    }

    /**
     * Pure mirror of runSlice's mutable-head PQ/bar loop. Every supplied tick is
     * due; the long value stands in for (priority, subTickOrder).
     */
    private static List<Long> drainMutableHeads(List<MutableHead> due) {
        Comparator<MutableHead> order = (a, b) -> {
            Long ha = a.peek();
            Long hb = b.peek();
            if (ha == null) return hb == null ? 0 : 1;
            if (hb == null) return -1;
            return Long.compare(ha, hb);
        };
        PriorityQueue<MutableHead> queue = new PriorityQueue<>(order);
        queue.addAll(due);
        ArrayList<Long> drained = new ArrayList<>();
        MutableHead top;
        while ((top = queue.poll()) != null) {
            MutableHead nextBest = queue.peek();
            Long bar = nextBest != null ? nextBest.peek() : null;
            Long first = top.peek() != null ? top.poll() : null;
            if (first == null) continue;
            drained.add(first);
            while (true) {
                Long candidate = top.peek();
                if (candidate == null || (bar != null && candidate > bar)) break;
                drained.add(top.poll());
            }
            if (top.peek() != null) queue.add(top);
        }
        return drained;
    }

    /**
     * No other container poll in the file may sit outside a peek guard. Cheap net
     * for the next person adding a drain path.
     */
    @Test
    void noUnguardedContainerPollsRemain() throws Exception {
        String text = islandScheduledTicksSource();
        List<String> offenders = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.contains("top.poll()") && !line.contains("container.poll()")) continue;
            // guarded either on the same line (ternary) or by a peek on the line above
            boolean sameLineGuard = line.contains("peek() != null");
            boolean priorPeek = i > 0 && lines[i - 1].contains("peek()");
            // the inner drain polls what it just peeked into `cand`
            boolean candGuarded = i > 0 && text.contains("ScheduledTick<T> cand = top.peek();");
            if (!sameLineGuard && !priorPeek && !candGuarded) {
                offenders.add((i + 1) + ": " + line.trim());
            }
        }
        assertTrue(offenders.isEmpty(),
                "unguarded LevelChunkTicks.poll() in IslandScheduledTicks: " + offenders);
    }
}
