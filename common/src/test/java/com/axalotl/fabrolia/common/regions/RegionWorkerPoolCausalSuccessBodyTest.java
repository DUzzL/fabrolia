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

final class RegionWorkerPoolCausalSuccessBodyTest {

    @Test
    void workerAndForeignCausalBodiesReuseTheirEnclosingLifetimeAuthority() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger bodies = new AtomicInteger();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    try {
                        assertEquals(0L, externalObligations(pool));
                        pool.runSuccessBodyIfHealthy(() -> {
                            assertEquals(0L, externalObligations(pool),
                                    "a nested worker body allocated an ExternalPermit");
                            bodies.incrementAndGet();
                        });
                        assertTrue(pool.callSuccessBooleanIfHealthy(() -> {
                            assertEquals(0L, externalObligations(pool),
                                    "a nested worker boolean allocated an ExternalPermit");
                            bodies.incrementAndGet();
                            return true;
                        }));

                        pool.wrapCausalHandoff(() -> {
                            assertEquals(1L, externalObligations(pool));
                            pool.runSuccessBodyIfHealthy(bodies::incrementAndGet);
                        }).run();
                        assertEquals(0L, externalObligations(pool),
                                "the same-thread handoff did not restore its parent token");

                        CountDownLatch handoffFinished = new CountDownLatch(1);
                        AtomicReference<Throwable> handoffFailure = new AtomicReference<>();
                        Runnable causalHandoff = pool.wrapCausalHandoff(() -> {
                            assertEquals(1L, externalObligations(pool),
                                    "the foreign handoff itself must remain the sole permit");
                            pool.runSuccessBodyIfHealthy(() -> {
                                assertEquals(1L, externalObligations(pool),
                                        "a nested foreign body allocated a second permit");
                                bodies.incrementAndGet();
                            });
                            assertTrue(pool.callSuccessBooleanIfHealthy(() -> {
                                assertEquals(1L, externalObligations(pool),
                                        "a nested foreign boolean allocated a second permit");
                                bodies.incrementAndGet();
                                return true;
                            }));
                        });
                        Thread foreign = Thread.ofPlatform()
                                .name("fabrolia-causal-success-foreign")
                                .unstarted(() -> {
                                    try {
                                        causalHandoff.run();
                                    } catch (Throwable thrown) {
                                        handoffFailure.set(thrown);
                                    } finally {
                                        handoffFinished.countDown();
                                    }
                                });
                        foreign.start();
                        await(handoffFinished);
                        foreign.join(Duration.ofSeconds(1));
                        assertFalse(foreign.isAlive());
                        assertNull(handoffFailure.get());
                        assertEquals(0L, externalObligations(pool),
                                "the enclosing foreign permit did not terminalize exactly once");
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    } finally {
                        finished.countDown();
                    }
                }));
                pool.wakeAll();
                assertTrue(finished.await(2, TimeUnit.SECONDS));
                assertNull(failure.get());
                assertEquals(5, bodies.get());
                assertNull(pool.fatalFailureOrNull());
            } finally {
                stopPreservingSelectedFailure(pool);
            }
        });
    }

    @Test
    void synchronousExternalBodyPublishesOneReusableCausalAuthority() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            Object lifecycleLock = field(RegionWorkerPool.class, "lifecycleLock")
                    .get(pool);
            CountDownLatch lockHeld = new CountDownLatch(1);
            CountDownLatch releaseLock = new CountDownLatch(1);
            AtomicInteger bodies = new AtomicInteger();
            AtomicInteger commits = new AtomicInteger();
            pool.start();
            try {
                assertFalse(RegionWorkerPool.currentThreadHasLiveCausalStep());
                assertEquals(0L, externalObligations(pool));
                pool.runSuccessBodyIfHealthy(() -> {
                    assertTrue(RegionWorkerPool.currentThreadHasLiveCausalStep(),
                            "the synchronous Main body has no reusable StepToken");
                    assertEquals(1L, externalObligations(pool));

                    Thread holder = Thread.ofPlatform()
                            .name("external-main-pool-lock-holder")
                            .unstarted(() -> {
                                synchronized (lifecycleLock) {
                                    lockHeld.countDown();
                                    await(releaseLock);
                                }
                            });
                    holder.start();
                    await(lockHeld);
                    try {
                        pool.runSuccessBodyIfHealthy(bodies::incrementAndGet);
                        assertTrue(pool.callSuccessBooleanIfHealthy(() -> {
                            bodies.incrementAndGet();
                            return true;
                        }));
                        pool.commitSuccessIfHealthy(commits::incrementAndGet);
                    } finally {
                        releaseLock.countDown();
                        try {
                            holder.join(Duration.ofSeconds(1));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }
                    assertFalse(holder.isAlive());
                    assertEquals(1L, externalObligations(pool),
                            "a nested causal stage allocated another permit");
                });

                assertFalse(RegionWorkerPool.currentThreadHasLiveCausalStep(),
                        "the external StepToken escaped its permit terminal edge");
                assertEquals(0L, externalObligations(pool));
                assertEquals(2, bodies.get());
                assertEquals(1, commits.get());
                assertNull(pool.fatalFailureOrNull());
            } finally {
                releaseLock.countDown();
                stopPreservingSelectedFailure(pool);
            }
        });
    }

    @Test
    void causalTerminalObservesPeerWinnerAndLaterEntryCannotStart() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError winner = new AssertionError("causal-peer-W");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
            AtomicReference<Throwable> refusedFailure = new AtomicReference<>();
            AtomicInteger laterBodies = new AtomicInteger();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    try {
                        pool.runSuccessBodyIfHealthy(() -> {
                            assertEquals(0L, externalObligations(pool));
                            entered.countDown();
                            await(release);
                        });
                    } catch (Throwable thrown) {
                        terminalFailure.set(thrown);
                    }
                    try {
                        pool.callSuccessBooleanIfHealthy(() -> {
                            laterBodies.incrementAndGet();
                            return true;
                        });
                    } catch (Throwable thrown) {
                        refusedFailure.set(thrown);
                    } finally {
                        finished.countDown();
                    }
                }));
                pool.wakeAll();
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                pool.publishFatalFailure(winner);
                release.countDown();
                assertTrue(finished.await(1, TimeUnit.SECONDS));

                assertSame(winner, terminalFailure.get(),
                        "the clean terminal edge did not observe the peer-selected W");
                assertSame(winner, refusedFailure.get(),
                        "the next causal stage did not lose the atomic W-vs-entry cut");
                assertEquals(0, laterBodies.get());
                assertSame(winner, pool.fatalFailureOrNull());
            } finally {
                release.countDown();
                stopPreservingSelectedFailure(pool);
            }
        });
    }

    @Test
    void admittedCausalCommitFinishesExactWorkThenSurfacesPeerWinner() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError winner = new AssertionError("causal-commit-peer-W");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
            AtomicReference<Throwable> refusedFailure = new AtomicReference<>();
            AtomicInteger exactCommits = new AtomicInteger();
            AtomicInteger laterCommits = new AtomicInteger();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    try {
                        pool.commitSuccessIfHealthy(() -> {
                            entered.countDown();
                            await(release);
                            exactCommits.incrementAndGet();
                        });
                    } catch (Throwable thrown) {
                        terminalFailure.set(thrown);
                    }
                    try {
                        pool.commitSuccessIfHealthy(laterCommits::incrementAndGet);
                    } catch (Throwable thrown) {
                        refusedFailure.set(thrown);
                    } finally {
                        finished.countDown();
                    }
                }));
                pool.wakeAll();
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                pool.publishFatalFailure(winner);
                release.countDown();
                assertTrue(finished.await(1, TimeUnit.SECONDS));

                assertEquals(1, exactCommits.get(),
                        "an already-admitted exact commit was skipped after W");
                assertEquals(0, laterCommits.get(),
                        "a commit entered after the generation selected W");
                assertSame(winner, terminalFailure.get());
                assertSame(winner, refusedFailure.get());
                assertSame(winner, pool.fatalFailureOrNull());
            } finally {
                release.countDown();
                stopPreservingSelectedFailure(pool);
            }
        });
    }

    @Test
    void causalBodyThrowableIsTheExactGenerationWinner() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError winner = new AssertionError("causal-body-W");
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> surfaced = new AtomicReference<>();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    try {
                        pool.runSuccessBodyIfHealthy(() -> {
                            throw winner;
                        });
                    } catch (Throwable thrown) {
                        surfaced.set(thrown);
                    } finally {
                        finished.countDown();
                    }
                }));
                pool.wakeAll();
                assertTrue(finished.await(1, TimeUnit.SECONDS));
                assertSame(winner, surfaced.get());
                assertSame(winner, pool.fatalFailureOrNull());
            } finally {
                stopPreservingSelectedFailure(pool);
            }
        });
    }

    private static long externalObligations(RegionWorkerPool pool) {
        try {
            Field lockField = RegionWorkerPool.class.getDeclaredField("lifecycleLock");
            Field obligationsField = RegionWorkerPool.class.getDeclaredField(
                    "externalObligations");
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

    private static Field field(Class<?> owner, String name)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void stopPreservingSelectedFailure(RegionWorkerPool pool) {
        if (pool.isTerminated()) return;
        Throwable selected = pool.fatalFailureOrNull();
        if (selected == null) {
            pool.stop();
            return;
        }
        Throwable stopped = assertThrows(Throwable.class, pool::stop);
        assertSame(selected, stopped,
                "pool teardown replaced the causal generation's exact W");
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
