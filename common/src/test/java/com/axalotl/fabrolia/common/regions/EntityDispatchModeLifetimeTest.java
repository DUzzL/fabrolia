package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real dispatch planner, ready consumer and empty-entity tickOwned body.
 * The setting writes isolate a mode-lifetime boundary; they do not establish that
 * a command/config reload caused any particular production watchdog incident.
 */
final class EntityDispatchModeLifetimeTest {
    private enum Step { QUEUE, PRE, POST }

    @Test
    void stableEventDispatchExecutesItsQueueAndEntityPhaseOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.enqueueQueueBody();
            CompletableFuture<Void> barrier = fixture.dispatch();
            fixture.drainWorker();

            fixture.assertEventFinished(barrier);
            fixture.healAndDrain();
            fixture.assertEventFinished(barrier);
            assertEquals(List.of(Step.QUEUE, Step.PRE, Step.POST), fixture.entered);
        }
    }

    @Test
    void healerCannotAmplifyRetainedReadyWorkAndStarveAPendingUnit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.enqueueQueueBody();
            CompletableFuture<Void> barrier = fixture.dispatch();
            var unitFinished = new java.util.concurrent.atomic.AtomicBoolean();
            assertTrue(fixture.pool.enqueueUnit(() -> unitFinished.set(true)));
            // Main can revisit the heal path while a legitimate ready notification
            // still awaits its worker. Elapsed time does not prove that it was lost.
            for (int pass = 0; pass < 16; pass++) {
                set(RegionScheduler.class, null, "lastHealNanos", System.nanoTime() - 1_000_000_000L);
                RegionScheduler.healColorGate();
            }
            fixture.runWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                for (int step = 0; step < 8; step++) {
                    if (!fixture.pool.workOnce(worker, true)) break;
                }
            });
            fixture.assertEventFinished(barrier);
            assertTrue(unitFinished.get(), "healer duplicates must not bury the pending unit");
            assertEquals(List.of(Step.QUEUE, Step.PRE, Step.POST), fixture.entered);
        }
    }

    @Test
    void acceptedEventDispatchSurvivesDesiredModeOffBeforeReadyConsumption() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.enqueueQueueBody();
            CompletableFuture<Void> barrier = fixture.dispatch();
            assertSame(barrier, fixture.domain.entityBarrier);
            assertEquals(1, fixture.domain.entityPending.get());
            assertFalse(barrier.isDone());

            // Thread.start publishes this write before the real ready consumer.
            FabroliaConfig.regionEventScheduler = false;
            fixture.drainWorker();
            boolean completedByWorker = barrier.isDone();
            fixture.assertNoLeakedWork();
            fixture.healAndDrain();

            fixture.assertEventFinished(barrier);
            assertTrue(completedByWorker, "the accepted worker tail must not require healer repair");
            assertEquals(List.of(Step.QUEUE, Step.PRE, Step.POST), fixture.entered);
        }
    }

    @Test
    void desiredModeOffInPostHookCannotLoseTheCommittedPhaseContribution() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.afterEntityTick = () -> FabroliaConfig.regionEventScheduler = false;
            fixture.enqueueQueueBody();
            CompletableFuture<Void> barrier = fixture.dispatch();
            fixture.drainWorker();
            boolean completedByWorker = barrier.isDone();

            // Distinguish a lost completion tail from a body that never ran.
            assertEquals(List.of(Step.QUEUE, Step.PRE, Step.POST), fixture.entered);
            assertEquals(fixture.domain.epoch, fixture.region.entityEpoch);
            assertTrue(fixture.region.hasSealedDone(fixture.domain.epoch));
            fixture.assertNoLeakedWork();
            fixture.healAndDrain();

            fixture.assertEventFinished(barrier);
            assertTrue(completedByWorker, "post-hook mode changes must not orphan the worker contribution");
            assertEquals(List.of(Step.QUEUE, Step.PRE, Step.POST), fixture.entered);
        }
    }

    @Test
    void nextLegacyDispatchCannotBorrowThePreviousEventEpochAfterReverseToggle() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.enqueueQueueBody();
            CompletableFuture<Void> previousBarrier = fixture.dispatch();
            fixture.drainWorker();
            fixture.assertEventFinished(previousBarrier);
            long eventEpoch = fixture.domain.epoch;

            fixture.domain.epoch++;
            FabroliaConfig.regionEventScheduler = false;
            fixture.enqueueQueueBody();
            CompletableFuture<Void> legacy = fixture.dispatch();
            FabroliaConfig.regionEventScheduler = true;

            // workOnce selects the actually published READY before the global
            // group root. READY may drain the queue, but only that legacy root
            // owns this epoch's entity body, even with the desired flag now ON.
            fixture.runWorker(() -> assertTrue(fixture.pool.workOnce(
                    (RegionWorkerThread) Thread.currentThread(), false)));
            assertEquals(List.of(Step.QUEUE, Step.PRE, Step.POST, Step.QUEUE), fixture.entered,
                    "a legacy READY consumer acquired event entity-body authority");
            assertFalse(legacy.isDone());
            fixture.drainWorker();
            assertTrue(legacy.isDone(), "the real legacy group root must complete its own future");
            legacy.join();
            fixture.healAndDrain();

            assertEquals(List.of(Step.QUEUE, Step.PRE, Step.POST,
                    Step.QUEUE, Step.PRE, Step.POST), fixture.entered);
            assertSame(previousBarrier, fixture.domain.entityBarrier);
            assertEquals(eventEpoch, fixture.region.entityBarrierEpoch.get(),
                    "legacy completion must not contribute to the old event barrier");
            assertEquals(0, fixture.domain.entityPending.get());
            assertEquals(fixture.domain.epoch, fixture.region.entityEpoch);
            fixture.assertNoLeakedWork();
        }
    }

    @Test
    void queueOnlyDispatchDoesNotGainAnEntityBodyWhenDesiredModeTurnsOn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            FabroliaConfig.regionEventScheduler = false;
            fixture.enqueueQueueBody();
            // The exact-player prepass uses this overload with tickEntities=false;
            // it still prepares/publishes the real queue-only group and result.
            Method dispatch = RegionizedServer.class.getDeclaredMethod(
                    "dispatchEntityPhase", EpochDomain.class, List.class, boolean.class);
            dispatch.setAccessible(true);
            List<Region> members = fixture.regionizer.snapshotAtEntityDispatchCut(fixture.domain);
            CompletableFuture<?> queueOnly = (CompletableFuture<?>) dispatch.invoke(
                    fixture.runtime, fixture.domain, members, false);
            FabroliaConfig.regionEventScheduler = true;
            fixture.drainWorker();
            fixture.healAndDrain();

            assertTrue(queueOnly.isDone());
            queueOnly.join();
            assertEquals(List.of(Step.QUEUE), fixture.entered);
            assertTrue(fixture.region.entityEpoch < fixture.domain.epoch);
            assertTrue(fixture.region.hasSealedDone(fixture.domain.epoch));
            assertNull(fixture.domain.entityBarrier);
            assertEquals(0, fixture.domain.entityPending.get());
            fixture.assertNoLeakedWork();
        }
    }

    @Test
    void preDispatchQueueClaimCannotEnterTheEntityBodyBeforePlanAcceptance() throws Exception {
        CountDownLatch queueEntered = new CountDownLatch(1);
        CountDownLatch releaseQueue = new CountDownLatch(1);
        CountDownLatch workerStepped = new CountDownLatch(1);
        try (Fixture fixture = new Fixture()) {
            fixture.enqueueQueueBody(() -> {
                queueEntered.countDown();
                await(releaseQueue);
            });
            // A still-queued body makes this region an actual dispatch member;
            // the first body has already been detached by the pre-dispatch claim.
            fixture.enqueueQueueBody();
            fixture.startWorker(() -> {
                try {
                    assertTrue(fixture.pool.workOnce(
                            (RegionWorkerThread) Thread.currentThread(), false));
                } finally {
                    workerStepped.countDown();
                }
            });
            try {
                await(queueEntered);
                List<Region> members = fixture.regionizer.snapshotAtEntityDispatchCut(fixture.domain);
                // Pause the dispatch sweep after its real epoch/group publication,
                // but before acceptance. The already-entered queue claimant read
                // the previous accepted receipt before this dispatch even began.
                List<Region> fencedMembers = new AbstractList<>() {
                    @Override
                    public Region get(int index) {
                        if (fixture.domain.dispatchedEpoch == fixture.domain.epoch
                                && !fixture.domain.entityDispatchPublication.isAccepted()) {
                            releaseQueue.countDown();
                            await(workerStepped);
                            assertEquals(List.of(Step.QUEUE, Step.QUEUE), fixture.entered,
                                    "a pre-dispatch claim entered an unaccepted entity plan");
                        }
                        return members.get(index);
                    }

                    @Override
                    public int size() {
                        return members.size();
                    }
                };
                CompletableFuture<Void> barrier = fixture.runtime.dispatchEntityPhase(
                        fixture.domain, fencedMembers);
                assertEquals(0L, releaseQueue.getCount(), "the pre-acceptance overlap was not exercised");
                fixture.joinWorker();
                fixture.drainWorker();
                fixture.assertEventFinished(barrier);
                assertEquals(List.of(Step.QUEUE, Step.QUEUE, Step.PRE, Step.POST), fixture.entered);
            } finally {
                releaseQueue.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "dispatch scheduling boundary timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RegionWorkerPool pool = new RegionWorkerPool(1);
        final EpochDomain domain = new EpochDomain();
        final TestLevel level;
        final RegionizedServer runtime;
        final ThreadedRegionizer regionizer;
        final Region region;
        final List<Step> entered = new ArrayList<>();
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool;
        final Object previousActive;
        final Object previousOrphanFailure;
        final Object previousHook;
        final Object previousLastHeal;
        final Object previousHealInterval;
        final boolean previousEventScheduler = FabroliaConfig.regionEventScheduler;
        final boolean previousRegionThreading = FabroliaConfig.enableRegionThreading;
        Runnable afterEntityTick = () -> {};
        Thread worker;
        final AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            level.rate = new ServerTickRateManager(null);
            level.chunkSource = (ServerChunkCache) unsafe.allocateInstance(ServerChunkCache.class);
            // getDistanceManager is a field read; the empty entity set never
            // dereferences its result. No chunks, tickets or entities are mocked.
            set(ServerChunkCache.class, level.chunkSource, "chunkMap",
                    unsafe.allocateInstance(ChunkMap.class));
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            domain.epoch = 8L;
            regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
            regionizer.owner = runtime;
            set(RegionizedServer.class, runtime, "level", level);
            set(RegionizedServer.class, runtime, "globalDomain", domain);
            set(RegionizedServer.class, runtime, "regionizer", regionizer);
            set(RegionizedServer.class, runtime, "queueOnlyEntityEpoch", Long.MIN_VALUE);
            region = regionizer.regionForGridKey(0L);

            previousPool = get(RegionScheduler.class, null, "pool");
            previousActive = get(RegionScheduler.class, null, "active");
            previousOrphanFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
            previousHook = get(RegionTickHooks.class, null, "hook");
            previousLastHeal = get(RegionScheduler.class, null, "lastHealNanos");
            previousHealInterval = get(RegionScheduler.class, null, "healIntervalNanos");
            levels = (Map<ServerLevel, RegionizedServer>) get(RegionScheduler.class, null, "PER_LEVEL");
            set(RegionWorkerPool.class, pool, "running", true);
            set(RegionWorkerPool.class, pool, "accepting", true);
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "active", true);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            levels.put(level, runtime);
            FabroliaConfig.regionEventScheduler = true;
            FabroliaConfig.enableRegionThreading = true;
            RegionTickHooks.register(new RegionTickHooks.Hook() {
                @Override
                public void preEntityTick(Region current) {
                    assertOwned(current);
                    entered.add(Step.PRE);
                }

                @Override
                public void postEntityTick(Region current) {
                    assertOwned(current);
                    entered.add(Step.POST);
                    afterEntityTick.run();
                }
            });
        }

        void assertOwned(Region current) {
            assertSame(region, current);
            RegionWorkerThread currentWorker = (RegionWorkerThread) Thread.currentThread();
            assertSame(currentWorker, current.currentWorker());
            assertSame(current, currentWorker.currentRegion);
        }

        void enqueueQueueBody() {
            enqueueQueueBody(() -> {});
        }

        void enqueueQueueBody(Runnable afterQueueEntry) {
            assertTrue(RegionScheduler.enqueueRegionTask(region, () -> {
                assertOwned(region);
                entered.add(Step.QUEUE);
                afterQueueEntry.run();
            }));
        }

        CompletableFuture<Void> dispatch() {
            List<Region> members = regionizer.snapshotAtEntityDispatchCut(domain);
            CompletableFuture<Void> result = runtime.dispatchEntityPhase(domain, members);
            assertTrue(domain.entityDispatchPublication.isAccepted());
            return result;
        }

        void drainWorker() throws Exception {
            runWorker(() -> {
                RegionWorkerThread current = (RegionWorkerThread) Thread.currentThread();
                for (int step = 0; step < 32; step++) {
                    if (!pool.workOnce(current, false)) return;
                }
                throw new AssertionError("dispatch lanes did not settle in 32 steps");
            });
        }

        void runWorker(Runnable body) throws Exception {
            startWorker(body);
            joinWorker();
        }

        void startWorker(Runnable body) {
            workerFailure.set(null);
            worker = new RegionWorkerThread(body, "dispatch-mode-lifetime");
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((thread, thrown) -> workerFailure.set(thrown));
            worker.start();
        }

        void joinWorker() throws Exception {
            worker.join(5_000L);
            assertFalse(worker.isAlive(), "dispatch consumer did not finish");
            if (workerFailure.get() != null) throw new AssertionError(workerFailure.get());
        }

        void healAndDrain() throws Exception {
            // Admit the real Main-side healer without wall-clock sleeps. This
            // changes cadence only, never a dispatch/body/completion receipt.
            set(RegionScheduler.class, null, "lastHealNanos", System.nanoTime() - 1_000_000_000L);
            RegionScheduler.healColorGate();
            drainWorker();
        }

        void assertEventFinished(CompletableFuture<Void> barrier) {
            assertTrue(barrier.isDone(), "the dispatch-produced barrier lost its accepted obligation");
            barrier.join();
            assertEquals(0, domain.entityPending.get());
            assertEquals(domain.epoch, region.entityEpoch);
            assertEquals(domain.epoch, region.entityBarrierEpoch.get());
            assertTrue(region.hasSealedDone(domain.epoch));
            assertNoLeakedWork();
        }

        void assertNoLeakedWork() {
            assertNull(region.currentWorker());
            assertEquals(0, region.activeClaimTransactions());
            assertTrue(region.queue().isEmpty());
            assertTrue(region.helpQueue.isEmpty());
            assertEquals(0, domain.entityDispatchPublication.readyReceiptCount());
            assertNull(pool.fatalFailureOrNull());
        }

        @Override
        public void close() throws Exception {
            try {
                Throwable cleanup = pool.fatalFailureOrNull();
                if (cleanup == null) cleanup = new IllegalStateException("fixture teardown");
                pool.publishFatalFailure(cleanup);
                if (worker != null && worker.isAlive()) {
                    worker.interrupt();
                    worker.join(5_000L);
                    assertFalse(worker.isAlive(), "dispatch worker survived failure cleanup");
                }
                if (!pool.isTerminated()) {
                    for (Region current : regionizer.snapshot()) {
                        RegionScheduler.cancelFailedGenerationRegionQueues(pool, current, cleanup);
                    }
                    try {
                        pool.stop(() -> {});
                    } catch (Throwable failure) {
                        if (failure != cleanup) throw failure;
                    }
                }
            } finally {
                levels.remove(level, runtime);
                set(RegionScheduler.class, null, "pool", previousPool);
                set(RegionScheduler.class, null, "active", previousActive);
                set(RegionScheduler.class, null, "orphanFatalFailure", previousOrphanFailure);
                set(RegionScheduler.class, null, "lastHealNanos", previousLastHeal);
                set(RegionScheduler.class, null, "healIntervalNanos", previousHealInterval);
                set(RegionTickHooks.class, null, "hook", previousHook);
                FabroliaConfig.regionEventScheduler = previousEventScheduler;
                FabroliaConfig.enableRegionThreading = previousRegionThreading;
            }
        }
    }

    /** Only world accessors reached by the real empty-entity body are supplied. */
    private static final class TestLevel extends ServerLevel {
        ServerTickRateManager rate;
        ServerChunkCache chunkSource;

        private TestLevel() {
            super(null, null, null, null, null, null, false, 0L, null, false);
            throw new AssertionError("allocated via Unsafe");
        }

        @Override public ServerTickRateManager tickRateManager() { return rate; }
        @Override public ServerChunkCache getChunkSource() { return chunkSource; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static Object get(Class<?> owner, Object target, String name)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> owner, Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
