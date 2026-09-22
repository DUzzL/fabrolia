package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RegionWorkerPoolLifecycleWaiterTest {


    @Test
    void pacerWaitsPermitFreeBehindExactTurnThenRunsExactlyOnce() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AtomicBoolean admitted = new AtomicBoolean();
            AtomicInteger bodies = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread pacer = null;
            boolean paused = false;
            pool.start();
            try {
                pool.pauseExactTurnAndAwait(() -> {});
                paused = true;
                pacer = new Thread(() -> {
                    try {
                        admitted.set(pool.runPacerBodyIfHealthy(
                                bodies::incrementAndGet));
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                }, "pacer-generation-gate-model");
                pacer.start();

                awaitWaiters(pool, 2);
                assertEquals(0L, externalObligations(pool),
                        "a pacer waiting behind Main must not pin the pause cut");
                assertEquals(0, bodies.get());

                pool.resumeExactTurn();
                paused = false;
                pacer.join(2_000L);
                assertFalse(pacer.isAlive());
                assertNull(failure.get());
                assertTrue(admitted.get());
                assertEquals(1, bodies.get());
            } finally {
                if (paused) pool.resumeExactTurn();
                if (pacer != null && pacer.isAlive()) {
                    pacer.interrupt();
                    pacer.join(2_000L);
                }
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void interruptEscapesAPermitFreePacerWaitWithoutSelectingW() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            AtomicInteger bodies = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread pacer = null;
            boolean paused = false;
            pool.start();
            try {
                pool.pauseExactTurnAndAwait(() -> {});
                paused = true;
                pacer = new Thread(() -> {
                    try {
                        pool.runPacerBodyIfHealthy(bodies::incrementAndGet);
                    } catch (InterruptedException expected) {
                        interrupted.set(true);
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                }, "pacer-normal-stop-interrupt-model");
                pacer.start();

                awaitWaiters(pool, 2);
                assertEquals(0L, externalObligations(pool));
                pacer.interrupt();
                pacer.join(2_000L);

                assertFalse(pacer.isAlive());
                assertTrue(interrupted.get());
                assertNull(failure.get());
                assertNull(pool.fatalFailureOrNull(),
                        "normal-stop wakeup must not become this generation's W");
                assertEquals(0, bodies.get());
            } finally {
                if (paused) {
                    pool.resumeExactTurn();
                    paused = false;
                }
                if (pacer != null && pacer.isAlive()) {
                    pacer.interrupt();
                    pacer.join(2_000L);
                }
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void pacerPermitWhichWinsFirstPinsALaterExactTurnUntilTerminal() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch bodyEntered = new CountDownLatch(1);
            CountDownLatch releaseBody = new CountDownLatch(1);
            CountDownLatch requestObserved = new CountDownLatch(1);
            CountDownLatch pausedReached = new CountDownLatch(1);
            CountDownLatch allowResume = new CountDownLatch(1);
            AtomicBoolean admitted = new AtomicBoolean();
            AtomicReference<Throwable> pacerFailure = new AtomicReference<>();
            AtomicReference<Throwable> requesterFailure = new AtomicReference<>();
            pool.start();

            Thread pacer = new Thread(() -> {
                try {
                    admitted.set(pool.runPacerBodyIfHealthy(() -> {
                        bodyEntered.countDown();
                        try {
                            releaseBody.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(
                                    "model pacer body was interrupted", interrupted);
                        }
                    }));
                } catch (Throwable thrown) {
                    pacerFailure.set(thrown);
                }
            }, "pacer-permit-first-model");
            Thread requester = new Thread(() -> {
                try {
                    pool.pauseExactTurnAndAwait(requestObserved::countDown);
                    pausedReached.countDown();
                    allowResume.await();
                    pool.resumeExactTurn();
                } catch (Throwable thrown) {
                    requesterFailure.set(thrown);
                }
            }, "pacer-permit-first-requester-model");

            try {
                pacer.start();
                assertTrue(bodyEntered.await(2, TimeUnit.SECONDS));
                assertEquals(1L, externalObligations(pool));

                requester.start();
                assertTrue(requestObserved.await(2, TimeUnit.SECONDS));
                assertEquals(1L, pausedReached.getCount(),
                        "Main cannot own the paused turn while the earlier pacer permit is live");
                assertEquals(1L, externalObligations(pool));

                releaseBody.countDown();
                pacer.join(2_000L);
                assertFalse(pacer.isAlive());
                assertTrue(pausedReached.await(2, TimeUnit.SECONDS));
                assertEquals(0L, externalObligations(pool));

                allowResume.countDown();
                requester.join(2_000L);
                assertFalse(requester.isAlive());
                assertTrue(admitted.get());
                assertNull(pacerFailure.get());
                assertNull(requesterFailure.get());
                assertNull(pool.fatalFailureOrNull());
            } finally {
                releaseBody.countDown();
                allowResume.countDown();
                pacer.join(2_000L);
                requester.join(2_000L);
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void exactTurnWorkerRegistersRewaitsAfterInterruptAndLeavesOnSignal() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            boolean paused = false;
            pool.start();
            try {
                pool.pauseExactTurnAndAwait(() -> {});
                paused = true;
                awaitWaiters(pool, 1);

                Thread worker = workers(pool)[0];
                worker.interrupt();
                awaitWorkerRewait(pool, worker);

                pool.resumeExactTurn();
                paused = false;
                awaitWaiters(pool, 0);
            } finally {
                if (paused) pool.resumeExactTurn();
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    private static void awaitWaiters(RegionWorkerPool pool, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (lifecycleWaiters(pool) != expected) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("lifecycleWaiters did not become " + expected
                        + "; actual=" + lifecycleWaiters(pool));
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitWorkerRewait(RegionWorkerPool pool, Thread worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (lifecycleWaiters(pool) != 1 || worker.isInterrupted()
                || worker.getState() != Thread.State.WAITING) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("interrupted exact-turn worker did not re-register; "
                        + "waiters=" + lifecycleWaiters(pool)
                        + ", interrupted=" + worker.isInterrupted()
                        + ", state=" + worker.getState());
            }
            Thread.onSpinWait();
        }
    }

    private static int lifecycleWaiters(RegionWorkerPool pool) {
        try {
            Field lockField = RegionWorkerPool.class.getDeclaredField("lifecycleLock");
            Field waitersField = RegionWorkerPool.class.getDeclaredField("lifecycleWaiters");
            lockField.setAccessible(true);
            waitersField.setAccessible(true);
            Object lock = lockField.get(pool);
            synchronized (lock) {
                return waitersField.getInt(pool);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static long externalObligations(RegionWorkerPool pool) {
        try {
            Field lockField = RegionWorkerPool.class.getDeclaredField("lifecycleLock");
            Field obligationsField = RegionWorkerPool.class
                    .getDeclaredField("externalObligations");
            lockField.setAccessible(true);
            obligationsField.setAccessible(true);
            Object lock = lockField.get(pool);
            synchronized (lock) {
                return obligationsField.getLong(pool);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Thread[] workers(RegionWorkerPool pool) {
        try {
            Field workers = RegionWorkerPool.class.getDeclaredField("workers");
            workers.setAccessible(true);
            return (Thread[]) workers.get(pool);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

}
