package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionWorkerPoolShutdownActivityEpochTest {

    @Test
    void ordinaryWorkedStepDoesNotAcquireLifecycleMonitorForActivityAccounting() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            Object lifecycleLock = lifecycleLock(pool);
            CountDownLatch monitorHeld = new CountDownLatch(1);
            CountDownLatch releaseMonitor = new CountDownLatch(1);
            CountDownLatch workReturned = new CountDownLatch(1);
            AtomicBoolean worked = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            globalTasks(pool).add(() -> {});

            Thread holder = Thread.ofPlatform().name("shutdown-epoch-monitor-holder").start(() -> {
                synchronized (lifecycleLock) {
                    monitorHeld.countDown();
                    await(releaseMonitor);
                }
            });
            assertTrue(monitorHeld.await(1, TimeUnit.SECONDS));

            Thread caller = Thread.ofPlatform().name("ordinary-work-once").start(() -> {
                try {
                    worked.set(pool.workOnce(null, true));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    workReturned.countDown();
                }
            });
            try {
                assertTrue(workReturned.await(1, TimeUnit.SECONDS),
                        "ordinary work serialized on the shutdown lifecycle monitor");
            } finally {
                releaseMonitor.countDown();
                holder.join();
                caller.join();
            }

            assertNull(failure.get());
            assertTrue(worked.get());
            assertEquals(0L, activityEpoch(pool));
        });
    }

    @Test
    void workedStepAfterRootAdmissionCloseAdvancesShutdownEpoch() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            pool.start();
            try {
                Object lifecycleLock = lifecycleLock(pool);
                synchronized (lifecycleLock) {
                    globalTasks(pool).add(() -> {});
                    pool.beginDraining(Thread.currentThread());
                    long before = activityEpoch(pool);

                    assertTrue(pool.workOnce(null, true));
                    assertEquals(before + 1L, activityEpoch(pool),
                            "post-cut work was invisible to the shutdown fixed point");
                }
                pool.stop();
            } finally {
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void preCutBodyCompletingAfterFirstDrainSnapshotInvalidatesTheFixedPoint() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch bodyEntered = new CountDownLatch(1);
            CountDownLatch releaseBody = new CountDownLatch(1);
            CountDownLatch workReturned = new CountDownLatch(1);
            AtomicReference<Throwable> workFailure = new AtomicReference<>();
            AtomicInteger progressPasses = new AtomicInteger();

            // Activate the lifecycle without starting an unrelated polling
            // worker.  The test drives the one retained workOnce explicitly,
            // making each shutdown snapshot deterministic.
            activateWithoutWorkers(pool);
            // The explicit caller detaches this body with no background worker.
            // This models a helper-pumped workOnce whose queue witness has
            // already disappeared when shutdown takes its snapshot.
            globalTasks(pool).add(() -> {
                bodyEntered.countDown();
                await(releaseBody);
            });
            Thread caller = Thread.ofPlatform().name("pre-cut-work-once").start(() -> {
                try {
                    assertTrue(pool.workOnce(null, true));
                } catch (Throwable thrown) {
                    workFailure.set(thrown);
                } finally {
                    workReturned.countDown();
                }
            });

            try {
                assertTrue(bodyEntered.await(1, TimeUnit.SECONDS));
                assertEquals(0L, activityEpoch(pool));

                pool.beginDraining(Thread.currentThread());
                long before = activityEpoch(pool);

                pool.stop(() -> {
                    int pass = progressPasses.incrementAndGet();
                    if (pass == 1) {
                        assertEquals(1L, workReturned.getCount(),
                                "first drain progress did not bisect the pre-cut body");
                        assertEquals(before, activityEpoch(pool));
                    } else if (pass == 2) {
                        // Pass one has already installed the zero/epoch snapshot.
                        // Complete the detached body now: workOnce must observe
                        // rootAdmissionClosed and advance the epoch before this
                        // pass is allowed to perform the next fixed-point cut.
                        releaseBody.countDown();
                        try {
                            assertTrue(workReturned.await(1, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        assertNull(workFailure.get());
                        assertEquals(before + 1L, activityEpoch(pool),
                                "post-cut completion was invisible to shutdown");
                    }
                });

                caller.join();
                assertNull(workFailure.get());
                assertEquals(before + 1L, activityEpoch(pool));
                assertEquals(3, progressPasses.get(),
                        "the changed epoch must invalidate pass one's snapshot and "
                                + "require one further unchanged progress pass");
            } finally {
                releaseBody.countDown();
                caller.join();
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    private static void activateWithoutWorkers(RegionWorkerPool pool) {
        Object lifecycleLock = lifecycleLock(pool);
        synchronized (lifecycleLock) {
            setBooleanField(pool, "running", true);
            setBooleanField(pool, "accepting", true);
        }
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

    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<Runnable> globalTasks(RegionWorkerPool pool) {
        try {
            var field = RegionWorkerPool.class.getDeclaredField("globalTasks");
            field.setAccessible(true);
            return (ConcurrentLinkedQueue<Runnable>) field.get(pool);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static Object lifecycleLock(RegionWorkerPool pool) {
        try {
            var field = RegionWorkerPool.class.getDeclaredField("lifecycleLock");
            field.setAccessible(true);
            return field.get(pool);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static long activityEpoch(RegionWorkerPool pool) {
        try {
            var field = RegionWorkerPool.class.getDeclaredField("shutdownActivityEpoch");
            field.setAccessible(true);
            return field.getLong(pool);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
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
}
