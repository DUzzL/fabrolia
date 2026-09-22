package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavioural concurrency pins for the scheduler-owned slice registry. */
class ScheduledTickSliceRegistryTest {

    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        TickDomainsProductionPathTest.bootstrapMinecraft();
    }

    private static final class TestLevelTicks<T> extends LevelTicks<T>
            implements LevelTicksIslandAccess<T> {
        final Long2ObjectMap<LevelChunkTicks<T>> containers =
                new Long2ObjectOpenHashMap<>();
        final Long2LongMap next = new Long2LongOpenHashMap();
        final Object tickLock = new Object();
        final IslandScheduledTicks.SliceRegistry registry =
                new IslandScheduledTicks.SliceRegistry();
        final Queue<ScheduledTick<T>> vanillaToRun = new ArrayDeque<>();
        final List<ScheduledTick<T>> vanillaAlreadyRun = new ArrayList<>();
        final List<ScheduledTick<T>> scheduled = new ArrayList<>();
        volatile LongPredicate check = key -> true;

        TestLevelTicks() {
            super(key -> true);
            next.defaultReturnValue(Long.MAX_VALUE);
        }

        @Override public Long2ObjectMap<LevelChunkTicks<T>> fabrolia$allContainers() {
            return containers;
        }

        @Override public Long2LongMap fabrolia$nextTickForContainer() {
            return next;
        }

        @Override public LongPredicate fabrolia$tickCheck() {
            return check;
        }

        @Override public Object fabrolia$tickLock() {
            return tickLock;
        }

        @Override public IslandScheduledTicks.SliceRegistry fabrolia$sliceRegistry() {
            return registry;
        }

        @Override public Queue<ScheduledTick<T>> fabrolia$vanillaToRunThisTick() {
            return vanillaToRun;
        }

        @Override public List<ScheduledTick<T>> fabrolia$vanillaAlreadyRunThisTick() {
            return vanillaAlreadyRun;
        }

        @Override public void schedule(ScheduledTick<T> tick) {
            scheduled.add(tick);
            LevelChunkTicks<T> container = containers.get(ChunkPos.pack(tick.pos()));
            if (container != null) {
                container.schedule(IslandCommands.rebaseScheduledTick(this, tick));
            }
        }
    }

    private static final class TaggedChunkTicks<T> extends LevelChunkTicks<T>
            implements FabroliaTickDomain, AcceptedTickAccess<T> {
        private long island = WALL;
        private long gen = -1L;

        TaggedChunkTicks() {}
        TaggedChunkTicks(List<net.minecraft.world.ticks.SavedTick<T>> saved) { super(saved); }

        @Override public long fabrolia$domainIsland() { return island; }
        @Override public long fabrolia$domainGen() { return gen; }
        @Override public void fabrolia$setDomain(long islandId, long generation) {
            island = islandId;
            gen = generation;
        }

        @Override public void fabrolia$restoreAccepted(ScheduledTick<T> tick) {
            // The untransformed fixture exposes vanilla's actual unpack insertion;
            // production reaches the same queue through its admission wrapper.
            try {
                Method unchecked = LevelChunkTicks.class.getDeclaredMethod(
                        "scheduleUnchecked", ScheduledTick.class);
                unchecked.setAccessible(true);
                unchecked.invoke(this, tick);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static ScheduledTick<String> tick(String type, int x, long order) {
        return new ScheduledTick<>(type, new BlockPos(x, 64, 1), 40L,
                TickPriority.NORMAL, order);
    }

    private static <T> IslandScheduledTicks.SliceExecution<T> addSlice(
            TestLevelTicks<T> ticks, long island, long gen) {
        IslandScheduledTicks.SliceExecution<T> slice =
                new IslandScheduledTicks.SliceExecution<>(
                        ticks, ticks.registry, island, gen);
        synchronized (ticks.registry.membership) {
            ticks.registry.active.add(slice);
        }
        return slice;
    }

    @Test
    void twoSlicesAndVanillaMainQueuesShareCopyAndClearVisibility() {
        TestLevelTicks<String> source = new TestLevelTicks<>();
        TestLevelTicks<String> target = new TestLevelTicks<>();
        IslandScheduledTicks.SliceExecution<String> a = addSlice(
                source, FabroliaTickDomain.WALL, -1L);
        IslandScheduledTicks.SliceExecution<String> b = addSlice(
                source, FabroliaTickDomain.WALL, -1L);

        ScheduledTick<String> mainAlready = tick("main-already", 1, 10L);
        ScheduledTick<String> aAlready = tick("a-already", 2, 11L);
        ScheduledTick<String> bAlready = tick("b-already", 3, 12L);
        ScheduledTick<String> mainPending = tick("main-pending", 4, 13L);
        ScheduledTick<String> aPending = tick("a-pending", 5, 14L);
        ScheduledTick<String> bPending = tick("b-pending", 6, 15L);
        source.vanillaAlreadyRun.add(mainAlready);
        source.vanillaToRun.add(mainPending);
        synchronized (a.state) {
            a.alreadyRun.add(aAlready);
            a.toRun.add(aPending);
        }
        synchronized (b.state) {
            b.alreadyRun.add(bAlready);
            b.toRun.add(bPending);
        }

        BoundingBox box = new BoundingBox(0, 0, 0, 31, 255, 31);
        Vec3i offset = new Vec3i(100, 0, 0);
        IslandScheduledTicks.copyCollectedAreaFrom(
                target, source, box, offset);

        assertEquals(List.of("main-already", "a-already", "b-already",
                        "main-pending", "a-pending", "b-pending"),
                target.scheduled.stream().map(ScheduledTick::type).toList(),
                "copy must retain vanilla category order across Main and two slices");
        assertEquals(List.of(16L, 17L, 18L, 19L, 20L, 21L),
                target.scheduled.stream().map(ScheduledTick::subTickOrder).toList(),
                "copy must use vanilla's min/max sub-order shift");
        assertTrue(target.scheduled.stream().allMatch(t -> t.pos().getX() >= 101));

        synchronized (a.state) {
            a.pending.addAll(a.toRun);
        }
        AtomicBoolean originalRanUnderTickLock = new AtomicBoolean();
        IslandScheduledTicks.clearCollectedArea(source, box, () -> {
            originalRanUnderTickLock.set(Thread.holdsLock(source.tickLock));
            source.vanillaAlreadyRun.removeIf(t -> box.isInside(t.pos()));
            source.vanillaToRun.removeIf(t -> box.isInside(t.pos()));
        });

        assertTrue(originalRanUnderTickLock.get(),
                "durable/private vanilla clear must share the structural cut");
        synchronized (a.state) {
            assertTrue(a.alreadyRun.isEmpty());
            assertTrue(a.toRun.isEmpty());
        }
        synchronized (b.state) {
            assertTrue(b.alreadyRun.isEmpty());
            assertTrue(b.toRun.isEmpty());
        }
        synchronized (a.state) {
            assertTrue(a.pending.contains(aPending),
                    "vanilla clearArea deliberately leaves an already-built pending set stale");
        }

        TestLevelTicks<String> neverMaterialized = new TestLevelTicks<>();
        IslandScheduledTicks.SliceExecution<String> fresh = addSlice(
                neverMaterialized, FabroliaTickDomain.WALL, -1L);
        ScheduledTick<String> removedBeforeQuery = tick("lazy", 7, 1L);
        synchronized (fresh.state) {
            fresh.toRun.add(removedBeforeQuery);
        }
        IslandScheduledTicks.clearCollectedArea(neverMaterialized, box, () -> {});
        synchronized (fresh.state) {
            assertTrue(fresh.pending.isEmpty());
            assertTrue(fresh.toRun.isEmpty(),
                    "before first query clear must remove the queue source of lazy materialization");
        }
    }

    @Test
    void willTickUsesOnlyCurrentSliceWithExactVanillaLazyClearSemantics()
            throws Exception {
        TestLevelTicks<String> source = new TestLevelTicks<>();
        IslandScheduledTicks.SliceExecution<String> current = addSlice(
                source, FabroliaTickDomain.WALL, -1L);
        IslandScheduledTicks.SliceExecution<String> foreign = addSlice(
                source, FabroliaTickDomain.WALL, -1L);
        ScheduledTick<String> first = tick("first", 1, 1L);
        ScheduledTick<String> pending = tick("pending", 2, 2L);
        ScheduledTick<String> foreignPending = tick("foreign", 20, 3L);
        current.toRun.add(first);
        current.toRun.add(pending);
        foreign.toRun.add(foreignPending);
        List<Boolean> observed = new ArrayList<>();

        runCollected(current, (pos, type) -> {
            if (!type.equals("first")) return;
            observed.add(IslandScheduledTicks.willTickCollected(
                    source, pending.pos(), pending.type()));
            IslandScheduledTicks.clearCollectedArea(
                    source, new BoundingBox(pending.pos()), () -> {});
            observed.add(IslandScheduledTicks.willTickCollected(
                    source, pending.pos(), pending.type()));
            observed.add(IslandScheduledTicks.willTickCollected(
                    source, foreignPending.pos(), foreignPending.type()));
        });

        assertEquals(List.of(true, true, false), observed,
                "current phase materializes lazily, clear leaves that set stale, "
                        + "and a foreign island is not part of this logical phase");
        assertNull(IslandScheduledTicks.willTickCollected(
                source, pending.pos(), pending.type()),
                "the current-slice context must be restored after the handler loop");

        TestLevelTicks<String> clearFirstSource = new TestLevelTicks<>();
        IslandScheduledTicks.SliceExecution<String> clearFirst = addSlice(
                clearFirstSource, FabroliaTickDomain.WALL, -1L);
        ScheduledTick<String> trigger = tick("trigger", 3, 1L);
        ScheduledTick<String> removed = tick("removed", 4, 2L);
        clearFirst.toRun.add(trigger);
        clearFirst.toRun.add(removed);
        AtomicReference<Boolean> clearBeforeMaterialize = new AtomicReference<>();
        runCollected(clearFirst, (pos, type) -> {
            if (!type.equals("trigger")) return;
            IslandScheduledTicks.clearCollectedArea(
                    clearFirstSource, new BoundingBox(removed.pos()), () -> {});
            clearBeforeMaterialize.set(IslandScheduledTicks.willTickCollected(
                    clearFirstSource, removed.pos(), removed.type()));
        });
        assertEquals(Boolean.FALSE, clearBeforeMaterialize.get(),
                "clear before the first query removes the lazy set's queue source");
    }

    @Test
    void materializedQueriesOnDifferentSlicesDoNotShareAStateMonitor()
            throws Exception {
        TestLevelTicks<String> source = new TestLevelTicks<>();
        IslandScheduledTicks.SliceExecution<String> a = addSlice(
                source, FabroliaTickDomain.WALL, -1L);
        IslandScheduledTicks.SliceExecution<String> b = addSlice(
                source, FabroliaTickDomain.WALL, -1L);
        ScheduledTick<String> aFirst = tick("a-first", 1, 1L);
        ScheduledTick<String> aPending = tick("a-pending", 2, 2L);
        ScheduledTick<String> bFirst = tick("b-first", 3, 3L);
        ScheduledTick<String> bPending = tick("b-pending", 4, 4L);
        a.toRun.add(aFirst);
        a.toRun.add(aPending);
        b.toRun.add(bFirst);
        b.toRun.add(bPending);

        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch bEntered = new CountDownLatch(1);
        CountDownLatch queryA = new CountDownLatch(1);
        CountDownLatch queryB = new CountDownLatch(1);
        AtomicReference<Boolean> aAnswer = new AtomicReference<>();
        AtomicReference<Boolean> bAnswer = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread aThread = new Thread(() -> runCollectedCaptured(a, (pos, type) -> {
            if (!type.equals("a-first")) return;
            aEntered.countDown();
            await(queryA);
            aAnswer.set(IslandScheduledTicks.willTickCollected(
                    source, aPending.pos(), aPending.type()));
        }, failure), "slice-will-a");
        Thread bThread = new Thread(() -> runCollectedCaptured(b, (pos, type) -> {
            if (!type.equals("b-first")) return;
            bEntered.countDown();
            await(queryB);
            bAnswer.set(IslandScheduledTicks.willTickCollected(
                    source, bPending.pos(), bPending.type()));
        }, failure), "slice-will-b");
        aThread.setDaemon(true);
        bThread.setDaemon(true);
        aThread.start();
        bThread.start();
        assertTrue(aEntered.await(2, TimeUnit.SECONDS));
        assertTrue(bEntered.await(2, TimeUnit.SECONDS));

        boolean bFinishedWhileAStateBlocked;
        synchronized (a.state) {
            queryA.countDown();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (aThread.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            queryB.countDown();
            bThread.join(2_000L);
            bFinishedWhileAStateBlocked = !bThread.isAlive();
        }
        aThread.join(2_000L);
        bThread.join(2_000L);

        assertTrue(bFinishedWhileAStateBlocked,
                "slice B's materialized query must complete while slice A state is blocked");
        assertNull(failure.get());
        assertEquals(Boolean.TRUE, aAnswer.get());
        assertEquals(Boolean.TRUE, bAnswer.get());
    }

    @Test
    void ordinaryPrepareAllowsClearAndCommitRejectsTheDriftedFuture() throws Exception {
        TestLevelTicks<String> source = new TestLevelTicks<>();
        LevelChunkTicks<String> container = new LevelChunkTicks<>();
        ScheduledTick<String> due = new ScheduledTick<>("due",
                new BlockPos(1, 64, 1), 5L, TickPriority.NORMAL, 1L);
        ScheduledTick<String> future = new ScheduledTick<>("future",
                new BlockPos(2, 64, 1), 50L, TickPriority.NORMAL, 2L);
        container.schedule(due);
        container.schedule(future);
        long chunk = ChunkPos.pack(0, 0);
        source.containers.put(chunk, container);
        source.next.put(chunk, due.triggerTick());

        CountDownLatch selectionReachedTickCheck = new CountDownLatch(1);
        CountDownLatch releaseSelection = new CountDownLatch(1);
        AtomicBoolean tickCheckHeldStructuralLock = new AtomicBoolean();
        source.check = ignored -> {
            tickCheckHeldStructuralLock.set(Thread.holdsLock(source.tickLock));
            selectionReachedTickCheck.countDown();
            await(releaseSelection);
            return true;
        };
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runner = new Thread(() -> runSliceCaptured(
                source, LongArrayList.of(chunk), 5L,
                (pos, type) -> ran.add(type), failure), "ordinary-slice-race");
        runner.setDaemon(true);
        runner.start();
        assertTrue(selectionReachedTickCheck.await(2, TimeUnit.SECONDS));

        CountDownLatch clearCalled = new CountDownLatch(1);
        BoundingBox dueOnly = new BoundingBox(due.pos());
        Thread clearer = new Thread(() -> {
            clearCalled.countDown();
            IslandScheduledTicks.clearCollectedArea(source, dueOnly,
                    () -> container.removeIf(t -> dueOnly.isInside(t.pos())));
        }, "ordinary-slice-clear-race");
        clearer.setDaemon(true);
        clearer.start();
        assertTrue(clearCalled.await(2, TimeUnit.SECONDS));

        boolean clearCompletedDuringReadOnlyPrepare;
        try {
            clearer.join(2_000L);
            clearCompletedDuringReadOnlyPrepare = !clearer.isAlive();
        } finally {
            releaseSelection.countDown();
        }
        runner.join(2_000L);
        clearer.join(2_000L);

        assertFalse(tickCheckHeldStructuralLock.get(),
                "tickCheck must stay outside tickLock to avoid holder<->tickLock inversion");
        assertTrue(clearCompletedDuringReadOnlyPrepare,
                "clear must be able to mutate between read-only prepare and locked commit");
        assertFalse(runner.isAlive());
        assertFalse(clearer.isAlive());
        assertNull(failure.get());
        assertFalse(ran.contains("future"),
                "removing the selected due head may never expose and poll a future head early");
        assertEquals(future, container.peek());
        assertEquals(50L, source.next.get(chunk),
                "the untouched future head must remain durably indexed");
    }

    @Test
    void differentSlicePollTransitionsDoNotShareAGlobalMonitor() throws Exception {
        TestLevelTicks<String> ticks = new TestLevelTicks<>();
        IslandScheduledTicks.SliceExecution<String> a = addSlice(
                ticks, FabroliaTickDomain.WALL, -1L);
        IslandScheduledTicks.SliceExecution<String> b = addSlice(
                ticks, FabroliaTickDomain.WALL, -1L);
        ScheduledTick<String> aTick = tick("a", 1, 1L);
        ScheduledTick<String> bTick = tick("b", 2, 2L);
        a.toRun.add(aTick);
        b.toRun.add(bTick);

        AtomicReference<ScheduledTick<String>> aResult = new AtomicReference<>();
        AtomicReference<ScheduledTick<String>> bResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> poll(a, aResult, failure), "slice-poll-a");
        Thread second = new Thread(() -> poll(b, bResult, failure), "slice-poll-b");
        first.setDaemon(true);
        second.setDaemon(true);

        boolean secondFinishedWhileFirstStateBlocked;
        synchronized (a.state) {
            first.start();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (ticks.registry.visibility.getReadLockCount() == 0
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(ticks.registry.visibility.getReadLockCount() > 0,
                    "first poll never entered the short read transition");
            second.start();
            second.join(2_000L);
            secondFinishedWhileFirstStateBlocked = !second.isAlive();
        }
        first.join(2_000L);
        second.join(2_000L);

        assertTrue(secondFinishedWhileFirstStateBlocked,
                "slice B must poll while slice A's private state monitor is blocked");
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(failure.get());
        assertEquals(aTick, aResult.get());
        assertEquals(bTick, bResult.get());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mainCopyTranslatesMixedSourceClocksIntoIslandAndWallContainers() throws Exception {
        TestLevelTicks<String> source = new TestLevelTicks<>();
        TestLevelTicks<String> target = new TestLevelTicks<>();
        TickDomainsProductionPathTest.TestLevel level =
                TickDomainsProductionPathTest.TestLevel.alloc();
        level.fabricatedGameTime = 1_000L;
        level.fabricatedLevelData = new TickDomainsProductionPathTest.TestLevelData(1_000L);
        level.fabricatedBlockTicks = (LevelTicks) source;
        level.fabricatedFluidTicks = (LevelTicks) target;
        RegionizedServer rs = new RegionizedServer(level, new RegionGrid(2));
        rs.asyncActive = true;
        rs.blockTicksAccess = source;
        rs.fluidTicksAccess = target;
        rs.islandLastL.put(42L, 100L);
        rs.islandLastL.put(43L, 400L);
        rs.islandLastL.put(44L, 600L);
        java.lang.reflect.Field active = schedulerField("active");
        java.lang.reflect.Field levels = schedulerField("PER_LEVEL");
        java.util.Map perLevel = (java.util.Map) levels.get(null);
        Object oldActive = active.get(null);
        perLevel.put(level, rs);
        active.set(null, true);
        try {
            TaggedChunkTicks<String> islandSource = new TaggedChunkTicks<>();
            islandSource.fabrolia$setDomain(42L, 7L);
            ScheduledTick<String> first = new ScheduledTick<>("first", new BlockPos(1, 64, 1),
                    107L, TickPriority.HIGH, 10L);
            ScheduledTick<String> second = new ScheduledTick<>("second", new BlockPos(2, 64, 1),
                    107L, TickPriority.HIGH, 11L);
            islandSource.schedule(first);
            islandSource.schedule(second);
            source.containers.put(ChunkPos.pack(0, 0), islandSource);
            TaggedChunkTicks<String> wallSource = new TaggedChunkTicks<>();
            wallSource.schedule(new ScheduledTick<>("wall", new BlockPos(17, 64, 1),
                    1_007L, TickPriority.LOW, 12L));
            source.containers.put(ChunkPos.pack(1, 0), wallSource);
            IslandScheduledTicks.SliceExecution<String> slice = addSlice(source, 44L, 2L);
            slice.toRun.add(new ScheduledTick<>("slice", new BlockPos(3, 64, 1),
                    607L, TickPriority.NORMAL, 13L));
            TaggedChunkTicks<String> islandTarget = new TaggedChunkTicks<>();
            islandTarget.fabrolia$setDomain(43L, 8L);
            TaggedChunkTicks<String> wallTarget = new TaggedChunkTicks<>();
            target.containers.put(ChunkPos.pack(2, 0), islandTarget);
            target.containers.put(ChunkPos.pack(3, 0), wallTarget);

            IslandScheduledTicks.copyCollectedAreaFrom(target, source,
                    new BoundingBox(0, 0, 0, 31, 255, 15), new Vec3i(32, 0, 0));

            assertEquals(new ScheduledTick<>("first", new BlockPos(33, 64, 1),
                    407L, TickPriority.HIGH, 14L), islandTarget.poll());
            assertEquals(new ScheduledTick<>("second", new BlockPos(34, 64, 1),
                    407L, TickPriority.HIGH, 15L), islandTarget.poll());
            assertEquals(new ScheduledTick<>("slice", new BlockPos(35, 64, 1),
                    407L, TickPriority.NORMAL, 17L), islandTarget.poll());
            assertEquals(new ScheduledTick<>("wall", new BlockPos(49, 64, 1),
                    1_007L, TickPriority.LOW, 16L), wallTarget.poll());
            assertEquals(List.of(first, second), islandSource.getAll()
                    .sorted(ScheduledTick.DRAIN_ORDER).toList());
            IslandScheduledTicks.copyCollectedAreaFrom(target, source,
                    new BoundingBox(0, 0, 0, 15, 255, 15), new Vec3i(48, 0, 0));
            assertEquals(new ScheduledTick<>("first", new BlockPos(49, 64, 1),
                    1_007L, TickPriority.HIGH, 14L), wallTarget.poll());
            assertEquals(new ScheduledTick<>("second", new BlockPos(50, 64, 1),
                    1_007L, TickPriority.HIGH, 15L), wallTarget.poll());
            assertEquals(new ScheduledTick<>("slice", new BlockPos(51, 64, 1),
                    1_007L, TickPriority.NORMAL, 17L), wallTarget.poll());
        } finally {
            perLevel.remove(level);
            active.set(null, oldActive);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void routedMainCopyPreservesDelayAcrossSameIslandForeignIslandAndWall() throws Exception {
        TestLevelTicks<String> ticks = new TestLevelTicks<>();
        TickDomainsProductionPathTest.TestLevel level =
                TickDomainsProductionPathTest.TestLevel.alloc();
        // The untransformed fixture supplies the routed Main clock while raw time stays W.
        level.fabricatedGameTime = 100L;
        level.fabricatedLevelData = new TickDomainsProductionPathTest.TestLevelData(1_000L);
        level.fabricatedBlockTicks = (LevelTicks) ticks;
        RegionizedServer rs = new RegionizedServer(level, new RegionGrid(2));
        rs.asyncActive = true;
        rs.blockTicksAccess = ticks;
        rs.islandLastL.put(42L, 100L);
        rs.islandLastL.put(43L, 400L);
        rs.islandLastL.put(44L, 600L);
        java.lang.reflect.Field active = schedulerField("active");
        java.lang.reflect.Field levels = schedulerField("PER_LEVEL");
        java.util.Map perLevel = (java.util.Map) levels.get(null);
        Object oldActive = active.get(null);
        perLevel.put(level, rs);
        active.set(null, true);
        try {
            TaggedChunkTicks<String> source = new TaggedChunkTicks<>();
            source.fabrolia$setDomain(42L, 7L);
            ScheduledTick<String> durable = new ScheduledTick<>("durable",
                    new BlockPos(1, 64, 1), 107L, TickPriority.HIGH, 10L);
            source.schedule(durable);
            ticks.containers.put(ChunkPos.pack(0, 0), source);
            IslandScheduledTicks.SliceExecution<String> slice = addSlice(ticks, 44L, 2L);
            slice.alreadyRun.add(new ScheduledTick<>("slice", new BlockPos(2, 64, 1),
                    607L, TickPriority.NORMAL, 11L));
            ticks.vanillaToRun.add(new ScheduledTick<>("wall", new BlockPos(3, 64, 1),
                    1_007L, TickPriority.LOW, 12L));
            TaggedChunkTicks<String> sameIsland = new TaggedChunkTicks<>();
            sameIsland.fabrolia$setDomain(42L, 7L);
            TaggedChunkTicks<String> otherIsland = new TaggedChunkTicks<>();
            otherIsland.fabrolia$setDomain(43L, 8L);
            TaggedChunkTicks<String> wall = new TaggedChunkTicks<>();
            ticks.containers.put(ChunkPos.pack(2, 0), sameIsland);
            ticks.containers.put(ChunkPos.pack(3, 0), otherIsland);
            ticks.containers.put(ChunkPos.pack(4, 0), wall);
            BoundingBox box = new BoundingBox(0, 0, 0, 15, 255, 15);
            IslandClock.callMainUnitSource(
                    new IslandClock.MainUnitSource(level, 100L, 42L, true), () -> {
                        IslandScheduledTicks.copyCollectedAreaFrom(
                                ticks, ticks, box, new Vec3i(32, 0, 0));
                        IslandScheduledTicks.copyCollectedAreaFrom(
                                ticks, ticks, box, new Vec3i(48, 0, 0));
                        IslandScheduledTicks.copyCollectedAreaFrom(
                                ticks, ticks, box, new Vec3i(64, 0, 0));
                        return true;
                    });
            // All source domains have seven ticks left, anchored to each target's clock.
            assertEquals(new ScheduledTick<>("durable", new BlockPos(33, 64, 1),
                    107L, TickPriority.HIGH, 13L), sameIsland.poll());
            assertEquals(new ScheduledTick<>("slice", new BlockPos(34, 64, 1),
                    107L, TickPriority.NORMAL, 14L), sameIsland.poll());
            assertEquals(new ScheduledTick<>("wall", new BlockPos(35, 64, 1),
                    107L, TickPriority.LOW, 15L), sameIsland.poll());
            assertEquals(new ScheduledTick<>("durable", new BlockPos(49, 64, 1),
                    407L, TickPriority.HIGH, 13L), otherIsland.poll());
            assertEquals(new ScheduledTick<>("slice", new BlockPos(50, 64, 1),
                    407L, TickPriority.NORMAL, 14L), otherIsland.poll());
            assertEquals(new ScheduledTick<>("wall", new BlockPos(51, 64, 1),
                    407L, TickPriority.LOW, 15L), otherIsland.poll());
            assertEquals(new ScheduledTick<>("durable", new BlockPos(65, 64, 1),
                    1_007L, TickPriority.HIGH, 13L), wall.poll());
            assertEquals(new ScheduledTick<>("slice", new BlockPos(66, 64, 1),
                    1_007L, TickPriority.NORMAL, 14L), wall.poll());
            assertEquals(new ScheduledTick<>("wall", new BlockPos(67, 64, 1),
                    1_007L, TickPriority.LOW, 15L), wall.poll());
            assertEquals(List.of("slice", "wall", "durable",
                            "slice", "wall", "durable", "slice", "wall", "durable"),
                    ticks.scheduled.stream().map(ScheduledTick::type).toList());
            assertEquals(durable, source.peek());
        } finally {
            perLevel.remove(level);
            active.set(null, oldActive);
        }
    }

    @Test
    void rebasingUnpackedSameKeyTicksPreservesBothPriorities() {
        BlockPos pos = new BlockPos(7, 64, 7);
        TaggedChunkTicks<String> ticks = new TaggedChunkTicks<>(List.of(
                new net.minecraft.world.ticks.SavedTick<>("observer", pos, 0, TickPriority.LOW),
                new net.minecraft.world.ticks.SavedTick<>("observer", pos, 0, TickPriority.HIGH)));
        ticks.unpack(100L);
        TickDomains.shiftAll(ticks, 50L);
        ScheduledTick<String> high = ticks.poll();
        ScheduledTick<String> low = ticks.poll();
        assertEquals(TickPriority.HIGH, high.priority());
        assertEquals(TickPriority.LOW, low.priority());
        assertEquals(150L, high.triggerTick());
        assertEquals(150L, low.triggerTick());
        assertTrue(high.subTickOrder() > low.subTickOrder(),
                "priority takes precedence over the saved-list sub-order");
        assertNull(ticks.peek());
    }

    @Test
    void failedPredecessorPreservesBothAcceptedSameKeyObligationsThroughSave() throws Exception {
        TestLevelTicks<String> ticks = new TestLevelTicks<>();
        TaggedChunkTicks<String> container = new TaggedChunkTicks<>();
        ScheduledTick<String> entered = tick("entered", 1, 1L);
        ScheduledTick<String> unentered = tick("pending", 2, 2L);
        ScheduledTick<String> newer = new ScheduledTick<>(unentered.type(), unentered.pos(),
                90L, TickPriority.HIGH, 3L);
        container.schedule(entered);
        container.schedule(unentered);
        long chunk = ChunkPos.pack(0, 0);
        ticks.containers.put(chunk, container);
        ticks.next.put(chunk, 40L);
        RuntimeException sentinel = new RuntimeException("entered handler failed");
        java.lang.reflect.Field orphan = schedulerField("orphanFatalFailure");
        Object previousFailure = orphan.get(null);
        java.lang.reflect.Field transition = schedulerField("transitionPool");
        Object previousTransition = transition.get(null);
        java.lang.reflect.Field pool = schedulerField("pool");
        Object previousPool = pool.get(null);
        orphan.set(null, null);
        transition.set(null, null);
        pool.set(null, null);
        try {
            List<String> ran = new ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            runSliceCaptured(ticks, LongArrayList.of(chunk), 40L, (pos, type) -> {
                ran.add(type);
                container.schedule(newer);
                throw sentinel;
            }, failure);
            assertSame(sentinel, failure.get());
            assertEquals(List.of("entered"), ran);
            assertEquals(List.of(unentered, newer), container.getAll()
                    .sorted(ScheduledTick.DRAIN_ORDER).toList());
            assertEquals(40L, ticks.next.get(chunk));

            // Shutdown rebases and clearArea drains must not rededuplicate.
            TickDomains.shiftAll(container, 100L);
            TickDomains.drainRemoveIf(container, t -> t.type().equals("unrelated"));
            var saved = container.pack(140L);
            assertEquals(List.of(0, 50), saved.stream().map(t -> t.delay()).toList());
            LevelChunkTicks<String> loaded = new LevelChunkTicks<>(saved);
            loaded.unpack(1_000L);
            ScheduledTick<String> restored = loaded.poll();
            ScheduledTick<String> future = loaded.poll();
            assertEquals(unentered.type(), restored.type());
            assertEquals(unentered.pos(), restored.pos());
            assertEquals(1_000L, restored.triggerTick());
            assertEquals(unentered.priority(), restored.priority());
            assertEquals(newer.type(), future.type());
            assertEquals(newer.pos(), future.pos());
            assertEquals(1_050L, future.triggerTick());
            assertEquals(newer.priority(), future.priority());
            assertTrue(restored.subTickOrder() < future.subTickOrder());
            assertNull(loaded.peek());
        } finally {
            pool.set(null, previousPool);
            transition.set(null, previousTransition);
            orphan.set(null, previousFailure);
        }
    }

    private static java.lang.reflect.Field schedulerField(String name) throws Exception {
        java.lang.reflect.Field field = RegionScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> void poll(
            IslandScheduledTicks.SliceExecution<T> slice,
            AtomicReference<ScheduledTick<T>> result,
            AtomicReference<Throwable> failure) {
        try {
            result.set(IslandScheduledTicks.pollCollectedTick(slice));
        } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
        }
    }

    private static <T> void runCollected(
            IslandScheduledTicks.SliceExecution<T> slice,
            BiConsumer<BlockPos, T> ticker) throws Exception {
        Method method = IslandScheduledTicks.class.getDeclaredMethod(
                "runCollectedSlice", IslandScheduledTicks.SliceExecution.class,
                BiConsumer.class);
        method.setAccessible(true);
        try {
            method.invoke(null, slice, ticker);
        } catch (InvocationTargetException wrapped) {
            rethrowCause(wrapped.getCause());
        }
    }

    private static <T> void runCollectedCaptured(
            IslandScheduledTicks.SliceExecution<T> slice,
            BiConsumer<BlockPos, T> ticker,
            AtomicReference<Throwable> failure) {
        try {
            runCollected(slice, ticker);
        } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
        }
    }

    private static <T> void runSliceCaptured(
            TestLevelTicks<T> source, LongArrayList due, long gameTime,
            BiConsumer<BlockPos, T> ticker,
            AtomicReference<Throwable> failure) {
        try {
            Method method = IslandScheduledTicks.class.getDeclaredMethod(
                    "runSlice", LevelTicksIslandAccess.class, LongArrayList.class,
                    long.class, AtomicInteger.class, BiConsumer.class);
            method.setAccessible(true);
            method.invoke(null, source, due, gameTime,
                    new AtomicInteger(64), ticker);
        } catch (InvocationTargetException wrapped) {
            failure.compareAndSet(null, wrapped.getCause());
        } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void rethrowCause(Throwable cause) throws Exception {
        if (cause instanceof Error error) throw error;
        if (cause instanceof Exception exception) throw exception;
        throw new AssertionError(cause);
    }
}
