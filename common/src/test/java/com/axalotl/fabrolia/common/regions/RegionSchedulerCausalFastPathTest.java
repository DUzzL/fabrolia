package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSchedulerCausalFastPathTest {

    @Test
    void causalBodiesAndCommitsDoNotEnterEitherLifecycleMonitor() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool generation = new RegionWorkerPool(1);
            Object poolLock = field(RegionWorkerPool.class, "lifecycleLock")
                    .get(generation);
            Object schedulerLock = field(RegionScheduler.class, "LIFECYCLE_LOCK")
                    .get(null);
            Field schedulerPool = field(RegionScheduler.class, "pool");
            Field transitionPool = field(RegionScheduler.class, "transitionPool");
            Field orphanFatal = field(RegionScheduler.class, "orphanFatalFailure");

            Object previousPool;
            Object previousTransition;
            Object previousOrphan;
            synchronized (schedulerLock) {
                previousPool = schedulerPool.get(null);
                previousTransition = transitionPool.get(null);
                previousOrphan = orphanFatal.get(null);
                assertNull(previousPool, "test requires no published scheduler pool");
                assertNull(previousTransition,
                        "test requires no scheduler transition generation");
                assertNull(previousOrphan, "test requires no orphan W");
                schedulerPool.set(null, generation);
            }

            CountDownLatch causalReady = new CountDownLatch(1);
            CountDownLatch poolLockHeld = new CountDownLatch(1);
            CountDownLatch releasePoolLock = new CountDownLatch(1);
            CountDownLatch fastPathsFinished = new CountDownLatch(1);
            CountDownLatch causalFinished = new CountDownLatch(1);
            CountDownLatch observerEntered = new CountDownLatch(1);
            AtomicReference<Throwable> causalFailure = new AtomicReference<>();
            AtomicReference<Throwable> observerFailure = new AtomicReference<>();
            AtomicInteger bodies = new AtomicInteger();
            AtomicInteger booleans = new AtomicInteger();
            AtomicInteger commits = new AtomicInteger();
            AtomicInteger refusedBodies = new AtomicInteger();
            AssertionError winner = new AssertionError("causal-fast-path-W");

            Thread holder = Thread.ofPlatform().name("causal-pool-lock-holder")
                    .unstarted(() -> {
                        await(causalReady);
                        synchronized (poolLock) {
                            poolLockHeld.countDown();
                            await(releasePoolLock);
                        }
                    });
            Thread observer = null;
            generation.start();
            holder.start();
            try {
                assertTrue(generation.enqueueGlobal(() -> {
                    causalReady.countDown();
                    await(poolLockHeld);
                    try {
                        RegionScheduler.runSuccessBodyIfHealthy(
                                bodies::incrementAndGet);
                        assertTrue(RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                            booleans.incrementAndGet();
                            return true;
                        }));
                        RegionScheduler.commitSuccessIfHealthy(
                                commits::incrementAndGet);
                        fastPathsFinished.countDown();

                        generation.publishFatalFailure(winner);
                        assertSame(winner, assertThrows(AssertionError.class,
                                () -> RegionScheduler.runSuccessBodyIfHealthy(
                                        refusedBodies::incrementAndGet)));
                        assertSame(winner, assertThrows(AssertionError.class,
                                () -> RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                                    refusedBodies.incrementAndGet();
                                    return true;
                                })));
                        assertSame(winner, assertThrows(AssertionError.class,
                                () -> RegionScheduler.commitSuccessIfHealthy(
                                        refusedBodies::incrementAndGet)));
                    } catch (Throwable failure) {
                        causalFailure.compareAndSet(null, failure);
                    } finally {
                        causalFinished.countDown();
                    }
                }));
                generation.wakeAll();
                assertTrue(fastPathsFinished.await(1, TimeUnit.SECONDS),
                        "a causal success cut waited for the held pool monitor");

                // The causal commit callback also must not retain the scheduler
                // monitor while the unrelated holder owns the pool monitor.
                observer = Thread.ofPlatform().name("scheduler-orphan-observer")
                        .unstarted(() -> {
                            try {
                                synchronized (schedulerLock) {
                                    assertNull(orphanFatal.get(null));
                                    observerEntered.countDown();
                                }
                            } catch (Throwable failure) {
                                observerFailure.set(failure);
                            }
                        });
                observer.start();
                assertTrue(observerEntered.await(1, TimeUnit.SECONDS),
                        "causal caller retained RegionScheduler.LIFECYCLE_LOCK "
                                + "while blocked on its pool");

                releasePoolLock.countDown();
                assertTrue(causalFinished.await(1, TimeUnit.SECONDS));
                holder.join(Duration.ofSeconds(1));
                observer.join(Duration.ofSeconds(1));
                assertFalse(holder.isAlive());
                assertFalse(observer.isAlive());
                assertNull(observerFailure.get());
                assertNull(causalFailure.get());
                assertEquals(1, bodies.get());
                assertEquals(1, booleans.get());
                assertEquals(1, commits.get());
                assertEquals(0, refusedBodies.get(),
                        "a gate entered a later body after the causal generation chose W");
                assertSame(winner, generation.fatalFailureOrNull());
            } finally {
                releasePoolLock.countDown();
                holder.join(Duration.ofSeconds(1));
                if (observer != null) observer.join(Duration.ofSeconds(1));
                if (!generation.isTerminated()) {
                    Throwable expected = generation.fatalFailureOrNull();
                    if (expected == null) {
                        generation.stop();
                    } else {
                        Throwable stopped = assertThrows(Throwable.class,
                                generation::stop);
                        assertSame(expected, stopped,
                                "pool teardown replaced the causal generation's exact W");
                    }
                }
                synchronized (schedulerLock) {
                    schedulerPool.set(null, previousPool);
                    transitionPool.set(null, previousTransition);
                    orphanFatal.set(null, previousOrphan);
                }
            }
        });
    }

    private static Field field(Class<?> owner, String name)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
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

}
