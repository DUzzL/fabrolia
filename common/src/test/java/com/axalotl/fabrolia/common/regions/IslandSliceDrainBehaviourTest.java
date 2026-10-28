package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #39, behavioural half — drives the real {@code IslandScheduledTicks.runSlice}
 * with real containers and asserts on what comes out. The sibling
 * {@code IslandSliceDrainGuardTest} pins the source shape; this one proves the
 * behaviour.
 *
 * <p>Lithium's mixins do not apply on the test classpath, so Lithium's hostile
 * {@code LevelChunkTicks} contract is emulated with a subclass — the house
 * pattern established by {@code TickRemoveIfImplAgnosticTest}. Two behaviours matter:
 * <ul>
 *   <li>{@code poll()} NPEs when the container is empty (their {@code nextTickQueue}
 *       is null there; their contract is peek-first);</li>
 *   <li>on an empty-but-present head bucket {@code poll()} returns null WITHOUT
 *       cleanup, permanently wedging the container — {@code peek()}/{@code poll()}
 *       return null forever afterwards while {@code hasScheduledTick} stays true,
 *       so every repeater/observer/fluid in that chunk silently stops
 *       rescheduling until the chunk reloads.</li>
 * </ul>
 */
class IslandSliceDrainBehaviourTest {

    // ---------------------------------------------------------------- helpers

    /**
     * Emulates the two ways Lithium's poll() punishes an unguarded caller, plus
     * the interleaving that produces them.
     *
     * <p>{@code emptyAfterPeeks} is the load-bearing knob: the container reports a
     * live head for the first N {@code peek()} calls and is empty from then on.
     * With N = 1 that reproduces exactly "another slice or an off-slice mutator
     * (TickDomains drains, shiftAll, c2me's off-thread pack()) emptied this
     * container between the collection phase and the drain phase" — the window
     * the pre-fix code walked into, and the one a single-threaded setup cannot
     * express by draining up front, because then collection skips it too.
     */
    private static final class LithiumLikeTicks extends LevelChunkTicks<String> {
        private boolean wedged;
        private boolean emptyHeadBucket;
        private int emptyAfterPeeks = Integer.MAX_VALUE;
        private int peeks;
        int unguardedPolls;

        void emptyAfterPeeks(int n) {
            this.emptyAfterPeeks = n;
        }

        private boolean starved() {
            return this.peeks > this.emptyAfterPeeks;
        }

        /**
         * An empty head bucket makes peek() null even though later buckets still
         * hold ticks — peek and poll both consult {@code nextTickQueue}, so this
         * is the honest shape. Once wedged it is null forever.
         */
        @Override
        public ScheduledTick<String> peek() {
            this.peeks++;
            if (this.wedged || this.emptyHeadBucket || this.starved()) {
                return null;
            }
            return super.peek();
        }

        /**
         * Lithium's contract is peek-first. Polling past a null head is the abuse:
         * on a fully empty container it NPEs (their {@code nextTickQueue} is
         * null), and on an empty-but-present head bucket it returns null having
         * cleaned nothing — which re-points {@code nextTickQueue} at that dead
         * bucket forever after.
         */
        @Override
        public ScheduledTick<String> poll() {
            if (this.wedged) {
                return null;
            }
            if (this.emptyHeadBucket) {
                this.unguardedPolls++;
                this.wedged = true;
                return null;
            }
            if (this.starved()) {
                // Their fully-empty path: nextTickQueue is null.
                this.unguardedPolls++;
                throw new NullPointerException(
                        "Lithium poll() on an empty container (nextTickQueue == null)");
            }
            if (super.peek() == null) {
                this.unguardedPolls++;
                throw new NullPointerException(
                        "Lithium poll() on an empty container (nextTickQueue == null)");
            }
            return super.poll();
        }

        /** Their count() is the dedup-set size, so it disagrees with peek(). */
        @Override
        public int count() {
            return this.emptyHeadBucket && !this.wedged ? 1 : super.count();
        }

        boolean isWedged() {
            return this.wedged;
        }

        void makeHeadBucketEmpty() {
            this.emptyHeadBucket = true;
        }
    }

    private record Access(Long2ObjectMap<LevelChunkTicks<String>> containers,
                          Long2LongMap nextTick,
                          Object lock,
                          IslandScheduledTicks.SliceRegistry sliceRegistry)
            implements LevelTicksIslandAccess<String> {
        @Override
        public Long2ObjectMap<LevelChunkTicks<String>> fabrolia$allContainers() {
            return this.containers;
        }

        @Override
        public Long2LongMap fabrolia$nextTickForContainer() {
            return this.nextTick;
        }

        @Override
        public LongPredicate fabrolia$tickCheck() {
            return pos -> true;
        }

        @Override
        public Object fabrolia$tickLock() {
            return this.lock;
        }

        @Override
        public IslandScheduledTicks.SliceRegistry fabrolia$sliceRegistry() {
            return this.sliceRegistry;
        }

        @Override
        public java.util.Queue<ScheduledTick<String>> fabrolia$vanillaToRunThisTick() {
            return new ArrayDeque<>();
        }

        @Override
        public java.util.List<ScheduledTick<String>> fabrolia$vanillaAlreadyRunThisTick() {
            return new ArrayList<>();
        }
    }

    private static ScheduledTick<String> tick(int x, int z, long time, long order) {
        return new ScheduledTick<>("t" + order, new BlockPos(x, 64, z), time, TickPriority.NORMAL, order);
    }

    @SuppressWarnings("unchecked")
    private static void runSlice(Access access, LongArrayList due, long gameTime,
                                 AtomicInteger budget, List<String> ran) throws Exception {
        Method m = IslandScheduledTicks.class.getDeclaredMethod(
                "runSlice", LevelTicksIslandAccess.class, LongArrayList.class,
                long.class, AtomicInteger.class, BiConsumer.class);
        m.setAccessible(true);
        BiConsumer<BlockPos, String> ticker = (pos, t) -> ran.add(t);
        m.invoke(null, access, due, gameTime, budget, ticker);
    }

    private static Access accessWith(LevelChunkTicks<String>... containers) {
        Long2ObjectMap<LevelChunkTicks<String>> map = new Long2ObjectOpenHashMap<>();
        Long2LongMap nextTick = new Long2LongOpenHashMap();
        for (int i = 0; i < containers.length; i++) {
            map.put(ChunkPos.pack(i, 0), containers[i]);
        }
        return new Access(map, nextTick, new Object(),
                new IslandScheduledTicks.SliceRegistry());
    }

    // ------------------------------------------------------------------ tests

    /**
     * The straightforward path still works: every due tick runs exactly once, in
     * order, and nothing is left behind.
     */
    @Test
    void drainsEveryDueTickExactlyOnce() throws Exception {
        LithiumLikeTicks c = new LithiumLikeTicks();
        c.schedule(tick(0, 0, 5L, 0));
        c.schedule(tick(0, 1, 5L, 1));
        c.schedule(tick(0, 2, 5L, 2));

        Access access = accessWith(c);
        List<String> ran = new ArrayList<>();
        runSlice(access, LongArrayList.of(ChunkPos.pack(0, 0)), 5L, new AtomicInteger(64), ran);

        assertEquals(List.of("t0", "t1", "t2"), ran, "all due ticks run once, in order");
        assertEquals(0, c.count(), "container fully drained");
        assertFalse(c.isWedged(), "no unguarded poll happened");
        assertEquals(0, c.unguardedPolls, "no unguarded poll happened");
    }

    /**
     * The regression itself. The container is emptied AFTER collection decided it
     * was due — which under island slicing happens via another slice or an
     * off-slice mutator (TickDomains drains, shiftAll, c2me's off-thread pack()).
     * Pre-fix the bare {@code top.poll()} fired here; Lithium answers that with a
     * permanent wedge (or an NPE on a fully empty container).
     */
    @Test
    void containerEmptiedAfterCollectionIsSkippedNotPolled() throws Exception {
        LithiumLikeTicks c = new LithiumLikeTicks();
        c.schedule(tick(0, 0, 5L, 0));

        Access access = accessWith(c);
        Long2LongMap nextTick = access.nextTick();
        nextTick.put(ChunkPos.pack(0, 0), 5L);

        // Collection sees a live head (peek #1); the container is emptied out
        // from under the slice before the drain phase reaches it. This is the
        // window the pre-fix bare `top.poll()` walked into.
        c.emptyAfterPeeks(1);

        List<String> ran = new ArrayList<>();
        runSlice(access, LongArrayList.of(ChunkPos.pack(0, 0)), 5L, new AtomicInteger(64), ran);

        assertTrue(ran.isEmpty(), "nothing to run");
        assertEquals(0, c.unguardedPolls,
                "runSlice must peek before polling: Lithium's poll() NPEs on an empty "
                        + "container and wedges it on an empty-but-present head bucket");
        assertFalse(c.isWedged(), "the container must survive the slice");
    }

    /**
     * The nastier Lithium shape: {@code count()} still reports content while the
     * head bucket is empty, so {@code peek()} is null. Anything that gates on
     * count/hasScheduledTick rather than peek walks straight into the poll — and
     * that poll returns null having cleaned nothing, killing the container
     * permanently. Nothing in the slice may take that step.
     */
    @Test
    void emptyHeadBucketWithNonZeroCountIsNeverPolled() throws Exception {
        LithiumLikeTicks c = new LithiumLikeTicks();
        c.schedule(tick(0, 0, 5L, 0));
        c.makeHeadBucketEmpty();
        assertTrue(c.count() > 0, "precondition: count disagrees with peek, as in Lithium");

        Access access = accessWith(c);
        access.nextTick().put(ChunkPos.pack(0, 0), 5L);

        List<String> ran = new ArrayList<>();
        runSlice(access, LongArrayList.of(ChunkPos.pack(0, 0)), 5L, new AtomicInteger(64), ran);

        assertEquals(0, c.unguardedPolls,
                "collection and drain must both gate on peek(), never on count()");
        assertFalse(c.isWedged(),
                "an unguarded poll would have wedged this container permanently — "
                        + "peek/poll null forever, chunk dropped from nextTickForContainer, "
                        + "while hasScheduledTick stays true so nothing ever reschedules");
        assertFalse(ran.contains(null), "a null tick must never reach the ticker");
        assertFalse(access.nextTick().containsKey(ChunkPos.pack(0, 0)),
                "a container with no live head is dropped from the next-tick map, as vanilla does");
    }

    /**
     * Two containers, one of them emptied concurrently. The live one must still be
     * drained — the guard must skip the dead container, not abort the slice.
     */
    @Test
    void oneDeadContainerDoesNotStopTheOthers() throws Exception {
        LithiumLikeTicks live = new LithiumLikeTicks();
        live.schedule(tick(16, 0, 5L, 10));
        live.schedule(tick(16, 1, 5L, 11));

        LithiumLikeTicks emptied = new LithiumLikeTicks();
        emptied.schedule(tick(0, 0, 5L, 0));
        emptied.emptyAfterPeeks(1);   // survives collection, gone by drain time

        Long2ObjectMap<LevelChunkTicks<String>> map = new Long2ObjectOpenHashMap<>();
        map.put(ChunkPos.pack(0, 0), emptied);
        map.put(ChunkPos.pack(1, 0), live);
        Access access = new Access(map, new Long2LongOpenHashMap(), new Object(),
                new IslandScheduledTicks.SliceRegistry());

        List<String> ran = new ArrayList<>();
        runSlice(access, LongArrayList.of(ChunkPos.pack(0, 0), ChunkPos.pack(1, 0)),
                5L, new AtomicInteger(64), ran);

        assertEquals(List.of("t10", "t11"), ran, "the live container is still drained");
        assertEquals(0, emptied.unguardedPolls, "the dead container is skipped, not polled");
        assertFalse(live.isWedged(), "the live container survives");
    }

    /**
     * The budget path re-queues a container for a later slice. Its head is peeked
     * again on the way back in, so an emptied container must not wedge there
     * either — and the ticks that did not fit must still be present.
     */
    @Test
    void budgetExhaustionLeavesRemainingTicksIntact() throws Exception {
        LithiumLikeTicks c = new LithiumLikeTicks();
        for (int i = 0; i < 5; i++) {
            c.schedule(tick(0, i, 5L, i));
        }

        Access access = accessWith(c);
        List<String> ran = new ArrayList<>();
        runSlice(access, LongArrayList.of(ChunkPos.pack(0, 0)), 5L, new AtomicInteger(2), ran);

        assertEquals(2, ran.size(), "only the budgeted ticks run");
        assertEquals(3, c.count(), "the rest stay scheduled — none lost, none duplicated");
        assertFalse(c.isWedged(), "no wedge on the budget path");
    }
}
