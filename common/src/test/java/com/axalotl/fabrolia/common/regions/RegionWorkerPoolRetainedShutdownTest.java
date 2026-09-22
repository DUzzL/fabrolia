package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionWorkerPoolRetainedShutdownTest {
    @Test
    void stopRunsSealedGlobalQueueAndItsCausalChildWithoutAnotherTick() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> observed = new ArrayList<>();
            assertTrue(fixture.pool.enqueueRegionTask(fixture.region, () -> {
                observed.add("first");
                assertTrue(fixture.pool.enqueueRegionTask(
                        fixture.region, () -> observed.add("child")));
            }));
            assertTrue(fixture.pool.enqueueRegionTask(
                    fixture.region, () -> observed.add("second")));
            fixture.commitEntityPhase();
            fixture.runtime.prepareForSchedulerStop();

            fixture.stopWithProgress();

            assertEquals(List.of("first", "second", "child"), observed);
            assertEquals(8L, fixture.domain.epoch,
                    "stopping must not invent a simulation tick");
            assertTrue(fixture.region.hasSealedDone(8L),
                    "draining retained work must not reopen the entity phase");
        }
    }

    @Test
    void retainedBodyCannotRunBeforeReleasedClaimTailFinishes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger executions = new AtomicInteger();
            assertTrue(fixture.region.tryClaim(
                    Thread.currentThread(), fixture.domain, fixture.domain.epoch));
            boolean tailOpen = true;
            try {
                assertTrue(fixture.pool.enqueueRegionTask(
                        fixture.region, executions::incrementAndGet));
                fixture.region.entityEpoch = fixture.domain.epoch;
                fixture.region.release(Thread.currentThread());
                fixture.commitEntityPhase();
                fixture.pool.beginDraining(Thread.currentThread());

                fixture.progress();
                assertEquals(0, executions.get(),
                        "retained body overlapped the old claim's release-successor tail");

                fixture.region.finishClaimTransaction();
                tailOpen = false;
                fixture.stopWithProgress();
                assertEquals(1, executions.get());
            } finally {
                fixture.region.release(Thread.currentThread());
                if (tailOpen) fixture.region.finishClaimTransaction();
            }
        }
    }

    @Test
    void competingConsumersCannotOverlapRetainedBodiesFromOneRegion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Thread drainOwner = Thread.currentThread();
            CountDownLatch firstConsumerObserved = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicBoolean firstFinished = new AtomicBoolean();
            AtomicBoolean overlapped = new AtomicBoolean();
            AtomicInteger executions = new AtomicInteger();
            AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
            assertTrue(fixture.pool.enqueueRegionTask(fixture.region, () -> {
                firstConsumerObserved.countDown();
                if (Thread.currentThread() != drainOwner) {
                    try {
                        if (!releaseFirst.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("first consumer was not released");
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                }
                firstFinished.set(true);
                executions.incrementAndGet();
            }));
            assertTrue(fixture.pool.enqueueRegionTask(fixture.region, () -> {
                overlapped.set(!firstFinished.get());
                executions.incrementAndGet();
            }));
            fixture.commitEntityPhase();
            fixture.pool.beginDraining(drainOwner);
            fixture.rearm.invoke(null, fixture.pool, List.of(fixture.runtime));
            Thread consumer = new RegionWorkerThread(() -> {
                try {
                    fixture.pool.stealOne(false);
                } catch (Throwable failure) {
                    consumerFailure.set(failure);
                } finally {
                    firstConsumerObserved.countDown();
                }
            }, "shutdown-first-consumer");
            consumer.start();
            try {
                assertTrue(firstConsumerObserved.await(5, TimeUnit.SECONDS));
                // If one helper has detached the first body, a competing
                // consumer must not enter the second before it finishes.
                fixture.pool.stealOne(false);
            } finally {
                releaseFirst.countDown();
                consumer.join(5_000L);
            }
            assertFalse(consumer.isAlive());
            if (consumerFailure.get() != null)
                throw new AssertionError(consumerFailure.get());
            fixture.stopWithProgress();
            assertEquals(2, executions.get());
            assertFalse(overlapped.get(),
                    "retained tasks from one region ran out of FIFO order");
        }
    }

    @Test
    void postCutAdmissionCannotOvertakeTheRetainedPrefix() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> observed = new ArrayList<>();
            assertTrue(fixture.pool.enqueueRegionTask(
                    fixture.region, () -> observed.add("retained")));
            fixture.commitEntityPhase();
            fixture.pool.beginDraining(Thread.currentThread());
            assertTrue(fixture.pool.enqueueRegionTask(
                    fixture.region, () -> observed.add("late")));
            // Physical Main drains this lane before its first region rearm.
            fixture.pool.drainMainSafeAll(true);
            fixture.stopWithProgress();
            assertEquals(List.of("retained", "late"), observed);
        }
    }

    @Test
    void normalAdmissionCannotOvertakeTheRetainedRegionPrefix() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> observed = new ArrayList<>();
            assertTrue(fixture.pool.enqueueRegionTask(
                    fixture.region, () -> observed.add("retained")));
            fixture.commitEntityPhase();
            assertTrue(fixture.pool.enqueueRegionTask(
                    fixture.region, () -> observed.add("late")));
            fixture.pool.drainMainSafeAll(true);
            assertEquals(List.of(), observed,
                    "a later Main-safe callback cannot bypass the region's next-epoch prefix");
            fixture.stopWithProgress();
            assertEquals(List.of("retained", "late"), observed);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RegionWorkerPool pool = new RegionWorkerPool(1);
        final EpochDomain domain = new EpochDomain();
        final ServerLevel level;
        final RegionizedServer runtime;
        final Region region;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool;
        final Object previousActive;
        final Object previousOrphanFailure;
        final Method rearm;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(
                    sun.misc.Unsafe.class, null, "theUnsafe");
            level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            domain.epoch = 8L;
            domain.dispatchedEpoch = 8L;
            domain.entityDispatchCutEpoch = 7L;
            ThreadedRegionizer regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
            regionizer.owner = runtime;
            set(RegionizedServer.class, runtime, "level", level);
            set(RegionizedServer.class, runtime, "globalDomain", domain);
            set(RegionizedServer.class, runtime, "regionizer", regionizer);
            region = regionizer.regionForGridKey(0L);
            region.groupedEpoch = domain.epoch;
            rearm = RegionScheduler.class.getDeclaredMethod(
                    "rearmPendingRegionWork", RegionWorkerPool.class, List.class);
            rearm.setAccessible(true);
            previousPool = get(RegionScheduler.class, null, "pool");
            previousActive = get(RegionScheduler.class, null, "active");
            previousOrphanFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
            levels = (Map<ServerLevel, RegionizedServer>) get(
                    RegionScheduler.class, null, "PER_LEVEL");
            // No unrelated polling worker: progress explicitly drives the actual
            // consumer, so the claim-tail boundary is deterministic.
            set(RegionWorkerPool.class, pool, "running", true);
            set(RegionWorkerPool.class, pool, "accepting", true);
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "active", true);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            levels.put(level, runtime);
        }

        void commitEntityPhase() {
            region.entityEpoch = domain.epoch;
            assertTrue(ColorGate.publishDoneIfQuiescent(
                    region, domain, domain.epoch, true, false));
        }

        void progress() {
            try {
                rearm.invoke(null, pool, List.of(runtime));
                pool.drainMainSafeAll(true);
                pool.stealOne(false);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        void stopWithProgress() {
            AtomicInteger passes = new AtomicInteger();
            AtomicBoolean neededAnotherTick = new AtomicBoolean();
            pool.stop(() -> {
                progress();
                if (passes.incrementAndGet() == 500) {
                    // Bounded pre-fix reproduction: release the stranded task
                    // only to let the fixture terminate, then fail the test.
                    neededAnotherTick.set(true);
                    domain.epoch++;
                }
                if (passes.get() > 1000) {
                    throw new AssertionError("shutdown failed even after fixture escape");
                }
            });
            assertFalse(neededAnotherTick.get(),
                    "healthy shutdown stranded an accepted next-epoch task");
            assertTrue(pool.isTerminated());
        }

        @Override
        public void close() throws Exception {
            try {
                if (!pool.isTerminated()) {
                    IllegalStateException cleanup = new IllegalStateException("fixture teardown");
                    pool.publishFatalFailure(cleanup);
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
