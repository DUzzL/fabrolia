package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionWorkerPoolMainOnlyLaneTest {

    @Test
    void workerConsumersCannotEnterMainOnlyLaneAndPhysicalMainRunsEachRootOnce() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            activateWithoutWorkers(pool);

            AtomicInteger mainOnlyRuns = new AtomicInteger();
            AtomicReference<Thread> mainOnlyThread = new AtomicReference<>();
            AtomicBoolean workOnceResult = new AtomicBoolean(true);
            AtomicBoolean nonMainStealResult = new AtomicBoolean(true);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            assertTrue(pool.enqueueGlobalMainOnly(() -> {
                mainOnlyThread.set(Thread.currentThread());
                mainOnlyRuns.incrementAndGet();
            }));

            RegionWorkerThread worker = new RegionWorkerThread(() -> {
                try {
                    RegionWorkerThread self = (RegionWorkerThread) Thread.currentThread();
                    workOnceResult.set(pool.workOnce(self, true));
                    nonMainStealResult.set(pool.stealOne(false));
                    pool.drainMainSafeAll(false);
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            }, "fabrolia-main-only-negative-consumer");
            worker.start();
            worker.join();

            assertNull(workerFailure.get());
            assertFalse(workOnceResult.get(),
                    "workOnce detached a physical-Main-only root");
            assertFalse(nonMainStealResult.get(),
                    "a non-Main steal detached a physical-Main-only root");
            assertEquals(0, mainOnlyRuns.get(),
                    "a worker-side consumer entered the Main-only body");

            AtomicInteger workerSafeRuns = new AtomicInteger();
            assertTrue(pool.enqueueGlobalMainSafe(workerSafeRuns::incrementAndGet));
            AtomicBoolean ordinaryWorked = new AtomicBoolean();
            RegionWorkerThread ordinaryWorker = new RegionWorkerThread(() ->
                    ordinaryWorked.set(pool.workOnce(
                            (RegionWorkerThread) Thread.currentThread(), true)),
                    "fabrolia-main-safe-positive-consumer");
            ordinaryWorker.start();
            ordinaryWorker.join();
            assertTrue(ordinaryWorked.get(),
                    "separating the Main-only lane accidentally made main-safe work Main-only");
            assertEquals(1, workerSafeRuns.get());
            assertEquals(0, mainOnlyRuns.get());

            Thread physicalMain = Thread.currentThread();
            assertTrue(pool.stealOne(true));
            assertEquals(1, mainOnlyRuns.get());
            assertSame(physicalMain, mainOnlyThread.get());
            assertFalse(pool.stealOne(true),
                    "the one detached Main-only root remained visible after execution");
            pool.drainMainSafeAll(true);
            assertEquals(1, mainOnlyRuns.get(),
                    "drain replayed a root already consumed by Main steal");

            AtomicInteger drainedRuns = new AtomicInteger();
            assertTrue(pool.enqueueGlobalMainOnly(drainedRuns::incrementAndGet));
            pool.drainMainSafeAll(true);
            pool.drainMainSafeAll(true);
            assertEquals(1, drainedRuns.get(),
                    "physical-Main drain did not terminalize its root exactly once");
            assertFalse(pool.stealOne(true));

            pool.stop();
            assertTrue(pool.isTerminated());
        });
    }

    @Test
    void routedWrapperReentersOnPhysicalMainWithoutRepublishingItself() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            activateWithoutWorkers(pool);

            AtomicInteger workerDeferrals = new AtomicInteger();
            AtomicInteger vanillaBodies = new AtomicInteger();
            AtomicReference<Runnable> wrappedCall = new AtomicReference<>();
            wrappedCall.set(() -> {
                if (Thread.currentThread() instanceof RegionWorkerThread) {
                    workerDeferrals.incrementAndGet();
                    assertTrue(pool.enqueueGlobalMainOnly(wrappedCall.get()));
                    return;
                }
                vanillaBodies.incrementAndGet();
            });

            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            RegionWorkerThread worker = new RegionWorkerThread(() -> {
                try {
                    wrappedCall.get().run();
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            }, "fabrolia-main-only-wrapper-origin");
            worker.start();
            worker.join();

            assertNull(workerFailure.get());
            assertEquals(1, workerDeferrals.get());
            assertEquals(0, vanillaBodies.get());
            assertFalse(pool.stealOne(false));
            pool.drainMainSafeAll(false);
            assertEquals(0, vanillaBodies.get());

            pool.drainMainSafeAll(true);
            pool.drainMainSafeAll(true);
            assertEquals(1, workerDeferrals.get(),
                    "Main re-entry was mistaken for another worker deferral");
            assertEquals(1, vanillaBodies.get(),
                    "the routed vanilla body was lost or replayed");
            assertFalse(pool.stealOne(true),
                    "wrapper re-entry left another copy in the Main-only lane");

            pool.stop();
        });
    }

    @Test
    void physicalMainLeavesAutonomousReadyWitnessAndRegionWorkForWorkers() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            activateWithoutWorkers(pool);
            Region autonomous = new Region(null, 0L);
            AtomicInteger regionBodies = new AtomicInteger();
            autonomous.queue().submit(regionBodies::incrementAndGet);
            publishReady(pool, autonomous);
            Object exactReadyWitness = readyHead(pool);
            assertTrue(exactReadyWitness != null);
            AtomicInteger mainLaneBodies = new AtomicInteger();
            assertTrue(pool.enqueueGlobalMainSafe(mainLaneBodies::incrementAndGet));

            assertTrue(pool.stealOne(true),
                    "physical Main did not service its explicit Main-safe lane");
            assertEquals(1, mainLaneBodies.get());
            assertSame(exactReadyWitness, readyHead(pool),
                    "Main-lane execution fell through into the autonomous ready lane");

            assertFalse(pool.stealOne(true),
                    "physical Main consumed an autonomous ready/help witness");
            assertSame(exactReadyWitness, readyHead(pool),
                    "physical Main detached the workers' exact progress witness");
            assertEquals(0, regionBodies.get(),
                    "physical Main entered autonomous region work");
            assertEquals(1, autonomous.queue().size(),
                    "declining the Main steal discarded autonomous work");

            // A non-Main consumer still owns the ready lane. This synthetic
            // region is intentionally NEW and therefore declines execution,
            // but it must detach the exact witness Main left in place.
            assertFalse(pool.stealOne(false));
            assertNull(readyHead(pool));
            assertEquals(1, autonomous.queue().size(),
                    "ready-lane ownership confused a hint with completed work");
            assertEquals(1, autonomous.queue().drain(Integer.MAX_VALUE));
            assertEquals(1, regionBodies.get());

            pool.stop();
            assertTrue(pool.isTerminated());
        });
    }

    @Test
    void failedGenerationCancelsQueuedMainOnlyRootWithoutEnteringItsBody() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            activateWithoutWorkers(pool);
            AssertionError primary = new AssertionError("main-only-generation-W");
            CancellableProbe queued = new CancellableProbe();

            assertTrue(pool.enqueueGlobalMainOnly(queued));
            pool.publishFatalFailure(primary);
            assertSame(primary, assertThrows(AssertionError.class, pool::stop));

            assertEquals(0, queued.bodyRuns.get(),
                    "failed-generation cleanup entered a Main-only vanilla body");
            assertEquals(1, queued.cancellations.get(),
                    "the queued Main-only receipt was not cancelled exactly once");
            assertSame(primary, queued.cancellationPrimary.get());
            assertTrue(pool.isTerminated());
        });
    }

    @Test
    void normalStopCountsMainOnlyQueueInItsFixedPoint() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            activateWithoutWorkers(pool);
            AtomicInteger progressPasses = new AtomicInteger();
            AtomicInteger bodyRuns = new AtomicInteger();

            pool.stop(() -> {
                int pass = progressPasses.incrementAndGet();
                if (pass == 1) {
                    assertTrue(pool.enqueueGlobalMainOnly(bodyRuns::incrementAndGet));
                } else if (pass == 2) {
                    pool.drainMainSafeAll(true);
                }
            });

            assertEquals(1, bodyRuns.get());
            assertEquals(3, progressPasses.get(),
                    "shutdown ignored the Main-only queue instead of requiring a fresh "
                            + "empty snapshot after Main drained it");
            assertTrue(pool.isTerminated());
        });
    }


    private static final class CancellableProbe
            implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        final AtomicInteger bodyRuns = new AtomicInteger();
        final AtomicInteger cancellations = new AtomicInteger();
        final AtomicReference<Throwable> cancellationPrimary = new AtomicReference<>();

        @Override
        public void run() {
            bodyRuns.incrementAndGet();
        }

        @Override
        public void cancelQueuedBeforeStart() {
            cancelQueuedBeforeStartWithFailure(null);
        }

        @Override
        public void cancelQueuedBeforeStartWithFailure(Throwable primary) {
            cancellations.incrementAndGet();
            cancellationPrimary.compareAndSet(null, primary);
        }
    }

    private static void activateWithoutWorkers(RegionWorkerPool pool) {
        setBooleanField(pool, "running", true);
        setBooleanField(pool, "accepting", true);
    }

    private static void setBooleanField(
            RegionWorkerPool pool, String fieldName, boolean value) {
        try {
            var field = RegionWorkerPool.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(pool, value);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static void publishReady(RegionWorkerPool pool, Region region) {
        try {
            var method = RegionWorkerPool.class.getDeclaredMethod(
                    "publishReady", Region.class);
            method.setAccessible(true);
            method.invoke(pool, region);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static Object readyHead(RegionWorkerPool pool) {
        try {
            var field = RegionWorkerPool.class.getDeclaredField("ready");
            field.setAccessible(true);
            return ((java.util.Queue<?>) field.get(pool)).peek();
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

}
