package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the production coordinator wait, real ready consumers and barrier contribution. */
final class IslandEntityBarrierWaitTest {
    @Test
    void completedOwnBarrierDoesNotWaitForUnrelatedReadyBody() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch reachedFallback = new CountDownLatch(1);
            CountDownLatch releaseForeign = new CountDownLatch(1);
            AtomicInteger foreignRuns = new AtomicInteger();
            AtomicReference<Thread> foreignConsumer = new AtomicReference<>();
            assertTrue(fixture.pool.enqueueRegionTask(fixture.foreignRegion, () -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                assertSame(worker, fixture.foreignRegion.currentWorker());
                assertSame(fixture.foreignRegion, worker.currentRegion);
                foreignConsumer.set(worker);
                foreignRuns.incrementAndGet();
                reachedFallback.countDown();
                await(releaseForeign);
            }));
            // Rendezvous on either admission policy: the old fallback enters B
            // first; a ready-excluding fallback reaches this dependency lane.
            // A stays incomplete until the coordinator has made that decision.
            assertTrue(fixture.pool.enqueueGlobal(reachedFallback::countDown));
            Worker waiter = fixture.startWait();
            boolean returnedWithoutForeign;
            try {
                assertTrue(reachedFallback.await(2, TimeUnit.SECONDS),
                        "coordinator never reached the real fallback consumer");
                fixture.completeOwnPhase();
                assertTrue(fixture.barrier.isDone());
                assertEquals(0, fixture.source.domain.entityPending.get());
                returnedWithoutForeign = waiter.finished.await(2, TimeUnit.SECONDS);
            } finally {
                releaseForeign.countDown();
                fixture.finishWait(waiter);
            }
            assertNull(waiter.failure.get());
            assertTrue(returnedWithoutForeign,
                    "A completed, but its coordinator waited for B's unrelated ready body");
            assertEquals(0, foreignRuns.get(),
                    "the claimed coordinator consumed unrelated ready work instead of leaving it queued");

            Worker ordinary = fixture.startWorker(fixture::drainReady);
            ordinary.join();
            assertNull(ordinary.failure.get());
            assertEquals(1, foreignRuns.get(),
                    "ordinary consumers must still run the accepted foreign body exactly once");
            assertSame(ordinary.thread, foreignConsumer.get());
            Worker duplicateDrain = fixture.startWorker(fixture::drainReady);
            duplicateDrain.join();
            assertNull(duplicateDrain.failure.get());
            assertEquals(1, foreignRuns.get());
        }
    }

    @Test
    void ownPublishedSubBatchAdvancesTheRealBarrierWithoutAnOrdinaryWorker() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch helped = new CountDownLatch(1);
            AtomicInteger helperRuns = new AtomicInteger();
            AtomicReference<Thread> helperThread = new AtomicReference<>();
            assertTrue(fixture.pool.enqueueHelpTask(fixture.sourceRegion, () -> {
                helperThread.set(Thread.currentThread());
                helperRuns.incrementAndGet();
                helped.countDown();
            }));
            Worker waiter = fixture.startWait();
            try {
                assertTrue(helped.await(2, TimeUnit.SECONDS),
                        "all available workers are coordinators; own fan-in must still progress");
                fixture.completeOwnPhase();
            } finally {
                fixture.finishWait(waiter);
            }
            assertNull(waiter.failure.get());
            assertSame(waiter.thread, helperThread.get());
            assertEquals(1, helperRuns.get());
            assertEquals(0, fixture.source.domain.entityPending.get());
            Worker duplicateDrain = fixture.startWorker(fixture::drainReady);
            duplicateDrain.join();
            assertNull(duplicateDrain.failure.get());
            assertEquals(1, helperRuns.get(), "the published ready duplicate replayed the own helper");
        }
    }

    @Test
    void globalAndMainSafeDependenciesCanUnblockTheOwnMember() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch dependenciesFinished = new CountDownLatch(2);
            List<String> order = new ArrayList<>();
            assertTrue(fixture.pool.enqueueGlobal(() -> {
                order.add("global");
                dependenciesFinished.countDown();
            }));
            assertTrue(fixture.pool.enqueueGlobalMainSafe(() -> {
                order.add("mainSafe");
                dependenciesFinished.countDown();
            }));
            Worker waiter = fixture.startWait();
            try {
                assertTrue(dependenciesFinished.await(2, TimeUnit.SECONDS),
                        "own members may depend on global and Main-safe child bodies");
                fixture.completeOwnPhase();
            } finally {
                fixture.finishWait(waiter);
            }
            assertNull(waiter.failure.get());
            assertEquals(List.of("global", "mainSafe"), order);
            assertEquals(0, fixture.source.domain.entityPending.get());
            assertFalse(fixture.barrier.isCompletedExceptionally());
        }
    }

    @Test
    void ownHelperFatalEscapesUnchangedWithoutManufacturingBarrierSuccess() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AssertionError original = new AssertionError("own sub-batch failed");
            assertTrue(fixture.pool.enqueueHelpTask(fixture.sourceRegion, () -> {
                throw original;
            }));
            Worker waiter = fixture.startWait();
            waiter.join();
            assertSame(original, waiter.failure.get());
            assertSame(original, fixture.pool.fatalFailureOrNull());
            assertFalse(fixture.barrier.isDone(), "failed own work cannot contribute success");
            assertEquals(1, fixture.source.domain.entityPending.get());
        }
    }

    @Test
    void exceptionalBarrierJoinPreservesTheOriginalFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AssertionError original = new AssertionError("entity barrier failed");
            fixture.barrier.completeExceptionally(original);
            Worker waiter = fixture.startWait();
            waiter.join();
            assertSame(original, waiter.failure.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test rendezvous was not released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Worker {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch finished = new CountDownLatch(1);
        final RegionWorkerThread thread;

        Worker(Runnable body) {
            thread = new RegionWorkerThread(() -> {
                try {
                    body.run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    finished.countDown();
                }
            }, "entity-barrier-regression");
            thread.start();
        }

        void join() throws InterruptedException {
            thread.join(5_000L);
            assertFalse(thread.isAlive(), "barrier test worker did not finish");
        }
    }

    /** Same ordinary Bootstrap/Unsafe runtime fixture as ColdCrossIslandTaskDomainTest. */
    private static final class Fixture implements AutoCloseable {
        final RegionWorkerPool pool = new RegionWorkerPool(1);
        final ServerLevel level;
        final RegionizedServer runtime;
        final ThreadedRegionizer regionizer;
        final IslandRegistry.PIsland source;
        final IslandRegistry.PIsland foreign;
        final Region sourceRegion;
        final Region foreignRegion;
        final CompletableFuture<Void> barrier = new CompletableFuture<>();
        final ArrayList<Region> members = new ArrayList<>();
        final ArrayList<Worker> workers = new ArrayList<>();
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool;
        final Object previousActive;
        final Object previousOrphanFailure;
        final boolean previousEventScheduler = FabroliaConfig.regionEventScheduler;
        final boolean previousRegionThreading = FabroliaConfig.enableRegionThreading;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            EpochDomain global = new EpochDomain();
            global.epoch = 8L;
            global.entityDispatchCutEpoch = 7L;
            regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
            regionizer.owner = runtime;
            set(RegionizedServer.class, runtime, "level", level);
            set(RegionizedServer.class, runtime, "globalDomain", global);
            set(RegionizedServer.class, runtime, "regionizer", regionizer);
            set(RegionizedServer.class, runtime, "islandLastL", new Long2LongOpenHashMap());
            runtime.asyncActive = true;
            source = island(1L);
            foreign = island(2L);
            Long2ObjectOpenHashMap<EpochDomain> cells = new Long2ObjectOpenHashMap<>();
            cells.put(0L, source.domain);
            cells.put(4L, foreign.domain);
            set(RegionizedServer.class, runtime, "cellDomainSnapshot", cells);
            sourceRegion = regionizer.regionForGridKey(0L);
            foreignRegion = regionizer.regionForGridKey(4L);
            sourceRegion.groupedEpoch = source.domain.epoch;
            foreignRegion.groupedEpoch = foreign.domain.epoch;
            source.domain.entityBarrier = barrier;
            source.domain.entityBarrierArmedEpoch = source.domain.epoch;
            source.domain.entityPending.set(1);
            members.add(sourceRegion);
            previousPool = get(RegionScheduler.class, null, "pool");
            previousActive = get(RegionScheduler.class, null, "active");
            previousOrphanFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
            levels = (Map<ServerLevel, RegionizedServer>) get(RegionScheduler.class, null, "PER_LEVEL");
            set(RegionWorkerPool.class, pool, "running", true);
            set(RegionWorkerPool.class, pool, "accepting", true);
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "active", true);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            levels.put(level, runtime);
            // No vanilla entity body is booted. Tests control the already-entered
            // own phase's completion and use its real commit/seal/contribution tail.
            FabroliaConfig.regionEventScheduler = false;
            FabroliaConfig.enableRegionThreading = true;
            assertTrue(sourceRegion.tryClaim(Thread.currentThread(), source.domain, source.domain.epoch));
        }

        private IslandRegistry.PIsland island(long id) {
            IslandRegistry.PIsland island = new IslandRegistry.PIsland(id, id * 101L);
            island.level = level;
            island.domain.epoch = 8L;
            island.domain.entityDispatchCutEpoch = 7L;
            island.domain.dispatchedEpoch = 8L;
            island.domain.entityDispatchPublication = new EpochDomain.EntityDispatchPublication();
            island.domain.entityDispatchPublication.accept();
            return island;
        }

        Worker startWait() {
            return startWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                worker.currentDomain = source.domain;
                IslandTickUnit.awaitEntityBarrier(runtime, source, source.domain, members, barrier);
            });
        }

        Worker startWorker(Runnable body) {
            Worker worker = new Worker(body);
            workers.add(worker);
            return worker;
        }

        void completeOwnPhase() {
            assertSame(Thread.currentThread(), sourceRegion.currentWorker());
            RegionScheduler.commitSuccessIfHealthy(() -> runtime.commitEntityPhase(sourceRegion));
            sourceRegion.release(Thread.currentThread());
            sourceRegion.finishClaimTransaction();
            assertTrue(ColorGate.publishDoneIfQuiescent(
                    sourceRegion, source.domain, source.domain.epoch, true, false));
            runtime.contributeEntityBarrier(sourceRegion);
        }

        void finishWait(Worker waiter) throws InterruptedException {
            if (!barrier.isDone()) {
                barrier.completeExceptionally(new AssertionError("test did not complete its own phase"));
            }
            waiter.join();
        }

        void drainReady() {
            RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
            for (int steps = 0; pool.workOnce(worker, false); steps++) {
                if (steps == 20) throw new AssertionError("ready lane failed to settle");
            }
        }

        @Override
        public void close() throws Exception {
            try {
                Throwable cleanup = pool.fatalFailureOrNull();
                if (cleanup == null) cleanup = new IllegalStateException("fixture teardown");
                pool.publishFatalFailure(cleanup);
                if (!barrier.isDone()) barrier.completeExceptionally(cleanup);
                for (Worker worker : workers) worker.join();
                if (sourceRegion.currentWorker() == Thread.currentThread()) {
                    sourceRegion.release(Thread.currentThread());
                    sourceRegion.finishClaimTransaction();
                }
                if (!pool.isTerminated()) {
                    for (Region region : regionizer.snapshot()) {
                        RegionScheduler.cancelFailedGenerationRegionQueues(pool, region, cleanup);
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
                FabroliaConfig.regionEventScheduler = previousEventScheduler;
                FabroliaConfig.enableRegionThreading = previousRegionThreading;
            }
        }
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
