package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegionWorkerPoolPlayerLaneTest {

    @Test
    void atomicQueueDetachAndBodyLeavesSuccessorOwnedAfterPeerWinner() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError winner = new AssertionError("queue-body-peer-W");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger successorEntries = new AtomicInteger();
            AtomicReference<Throwable> surfaced = new AtomicReference<>();
            Runnable first = () -> {
                entered.countDown();
                await(release);
            };
            Runnable successor = successorEntries::incrementAndGet;
            Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
            queue.add(first);
            queue.add(successor);
            pool.start();
            try {
                Thread consumer = new Thread(() -> {
                    try {
                        while (pool.callSuccessBooleanIfHealthy(() -> {
                            Runnable body = queue.poll();
                            if (body == null) return false;
                            body.run();
                            return true;
                        })) {
                            // one exact FIFO obligation per admission
                        }
                    } catch (Throwable thrown) {
                        surfaced.set(thrown);
                    }
                }, "fabrolia-atomic-queue-detach-W-race");
                consumer.start();
                assertTrue(entered.await(1, TimeUnit.SECONDS));

                pool.publishFatalFailure(winner);
                release.countDown();
                consumer.join(1_000L);

                assertFalse(consumer.isAlive());
                assertSame(winner, surfaced.get());
                assertSame(successor, queue.peek(),
                        "peer W at the entered body's terminal must leave the next FIFO owner intact");
                assertEquals(0, successorEntries.get());
            } finally {
                release.countDown();
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable expectedWinner) {
                        assertSame(winner, expectedWinner);
                    }
                }
            }
        });
    }

    @Test
    void atomicBooleanPumpSurfacesPeerWinnerAndRefusesTheNextCallback() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError winner = new AssertionError("chunk-pump-peer-W");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> surfaced = new AtomicReference<>();
            AtomicInteger nextCallbackEntries = new AtomicInteger();
            pool.start();
            try {
                Thread admitted = new Thread(() -> {
                    try {
                        pool.callSuccessBooleanIfHealthy(() -> {
                            entered.countDown();
                            await(release);
                            return false;
                        });
                    } catch (Throwable thrown) {
                        surfaced.set(thrown);
                    }
                }, "fabrolia-main-chunk-pump-W-race");
                admitted.start();
                assertTrue(entered.await(1, TimeUnit.SECONDS));

                pool.publishFatalFailure(winner);
                release.countDown();
                admitted.join(1_000L);

                assertFalse(admitted.isAlive());
                assertSame(winner, surfaced.get(),
                        "the admitted callback terminal edge must surface peer W");
                Throwable refused = assertThrows(Throwable.class,
                        () -> pool.callSuccessBooleanIfHealthy(() -> {
                            nextCallbackEntries.incrementAndGet();
                            return true;
                        }));
                assertSame(winner, refused);
                assertEquals(0, nextCallbackEntries.get(),
                        "no later pump callback may enter after W");
            } finally {
                release.countDown();
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable expectedWinner) {
                        assertSame(winner, expectedWinner);
                    }
                }
            }
        });
    }

    @Test
    void successCommitCannotPassAnAlreadySelectedFatalWinner() {
        RegionWorkerPool pool = new RegionWorkerPool(1);
        Error winner = new AssertionError("winner");
        AtomicInteger commits = new AtomicInteger();

        pool.publishFatalFailure(winner);
        Error surfaced = assertThrows(Error.class,
                () -> pool.commitSuccessIfHealthy(commits::incrementAndGet));

        assertSame(winner, surfaced);
        assertEquals(0, commits.get());
    }

    @Test
    void throwingSuccessCommitSelectsItsExactThrowableBeforeTheNextCommit() {
        RegionWorkerPool pool = new RegionWorkerPool(1);
        Error winner = new AssertionError("commit failed");
        AtomicInteger laterCommits = new AtomicInteger();

        Error first = assertThrows(Error.class,
                () -> pool.commitSuccessIfHealthy(() -> { throw winner; }));
        Error second = assertThrows(Error.class,
                () -> pool.commitSuccessIfHealthy(laterCommits::incrementAndGet));

        assertSame(winner, first);
        assertSame(winner, second);
        assertSame(winner, pool.fatalFailureOrNull());
        assertEquals(0, laterCommits.get());
    }

    @Test
    void admittedSuccessBodySurfacesPeerWinnerBeforeAnyLaterStage() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError winner = new AssertionError("peer winner");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> surfaced = new AtomicReference<>();
            AtomicInteger laterStages = new AtomicInteger();
            pool.start();
            try {
                Thread admitted = new Thread(() -> {
                    try {
                        pool.runSuccessBodyIfHealthy(() -> {
                            entered.countDown();
                            await(release);
                        });
                        laterStages.incrementAndGet();
                    } catch (Throwable thrown) {
                        surfaced.set(thrown);
                    }
                }, "fabrolia-success-body-race-test");
                admitted.start();
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                pool.publishFatalFailure(winner);
                release.countDown();
                admitted.join(1_000L);

                assertFalse(admitted.isAlive());
                assertSame(winner, surfaced.get());
                assertEquals(0, laterStages.get());
                assertSame(winner, assertThrows(AssertionError.class,
                        () -> pool.runSuccessBodyIfHealthy(
                                laterStages::incrementAndGet)));
                assertEquals(0, laterStages.get());
                assertSame(winner, assertThrows(AssertionError.class, pool::stop));
            } finally {
                release.countDown();
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }


    @Test
    void schedulerSuccessBodyWithoutPoolPublishesTheExactOrphanWinner()
            throws ReflectiveOperationException {
        var poolField = RegionScheduler.class.getDeclaredField("pool");
        var fatalField = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        poolField.setAccessible(true);
        fatalField.setAccessible(true);
        Object previousPool = poolField.get(null);
        Object previousFatal = fatalField.get(null);
        AtomicInteger laterBodies = new AtomicInteger();
        AssertionError runnableWinner = new AssertionError("orphan runnable W");
        AssertionError booleanWinner = new AssertionError("orphan boolean W");
        try {
            poolField.set(null, null);
            fatalField.set(null, null);

            assertSame(runnableWinner, assertThrows(AssertionError.class,
                    () -> RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        throw runnableWinner;
                    })));
            assertSame(runnableWinner, assertThrows(AssertionError.class,
                    () -> RegionScheduler.runSuccessBodyIfHealthy(
                            laterBodies::incrementAndGet)));
            assertEquals(0, laterBodies.get());

            fatalField.set(null, null);
            assertSame(booleanWinner, assertThrows(AssertionError.class,
                    () -> RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                        throw booleanWinner;
                    })));
            assertSame(booleanWinner, assertThrows(AssertionError.class,
                    () -> RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                        laterBodies.incrementAndGet();
                        return true;
                    })));
            assertEquals(0, laterBodies.get());
        } finally {
            poolField.set(null, previousPool);
            fatalField.set(null, previousFatal);
        }
    }

    @Test
    void failedGenerationCancelsQueuedRootsAndExternalPermitWithoutRunningBodies() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            IllegalStateException primary = new IllegalStateException("failed-generation-primary");
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch failFirst = new CountDownLatch(1);
            CancellableProbe global = new CancellableProbe();
            CancellableProbe mainSafe = new CancellableProbe();
            CancellableProbe unit = new CancellableProbe();
            CancellableProbe nestedExternal = new CancellableProbe();
            AtomicInteger futureBodyRuns = new AtomicInteger();
            CompletableFuture<Void> queuedFuture = new CompletableFuture<>();
            AtomicReference<Throwable> futureCancellationPrimary = new AtomicReference<>();
            RuntimeException cancellationFailure =
                    new RuntimeException("cancellation-cleanup");
            Runnable futureRoot = RegionScheduler.cancellableRoot(
                    futureBodyRuns::incrementAndGet,
                    failure -> {
                        futureCancellationPrimary.set(failure);
                        queuedFuture.completeExceptionally(failure);
                        throw cancellationFailure;
                    });
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    firstEntered.countDown();
                    await(failFirst);
                    throw primary;
                }));
                pool.wakeAll();
                assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

                assertTrue(pool.enqueueGlobal(global));
                assertTrue(pool.enqueueGlobalMainSafe(mainSafe));
                assertTrue(pool.enqueueUnit(unit));
                assertTrue(pool.enqueueGlobal(futureRoot));
                Runnable external = reserveExternalTask(pool, nestedExternal);
                rawMainSafeQueue(pool).add(external);

                failFirst.countDown();
                awaitFatal(pool, primary);
                assertSame(primary, assertThrows(IllegalStateException.class, pool::stop));

                for (CancellableProbe probe : java.util.List.of(
                        global, mainSafe, unit, nestedExternal)) {
                    assertEquals(0, probe.bodyRuns.get(),
                            "fail-stop entered a queued successor body");
                    assertEquals(1, probe.cancellations.get(),
                            "queued root was not terminalized exactly once");
                    assertSame(primary, probe.cancellationPrimary.get(),
                            "cancellation lost the generation's exact primary");
                }
                assertEquals(0L, longField(pool, "externalObligations"),
                        "queued ExternalTask retained its owner permit");
                assertEquals(0, futureBodyRuns.get());
                assertTrue(queuedFuture.isCompletedExceptionally());
                assertSame(primary, futureCancellationPrimary.get());
                assertSame(primary, assertThrows(java.util.concurrent.CompletionException.class,
                        queuedFuture::join).getCause());
                assertTrue(java.util.Arrays.asList(primary.getSuppressed())
                                .contains(cancellationFailure),
                        "cancellation diagnostics did not remain suppressed on exact primary");
                assertTrue(pool.isTerminated());
            } finally {
                failFirst.countDown();
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    @Test
    void fatalStopWaitsRunningBodyThenCancelsQueuedSuccessor() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            RuntimeException primary = new RuntimeException("external fatal");
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch stopped = new CountDownLatch(1);
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            CancellableProbe queued = new CancellableProbe();
            pool.start();
            try {
                Runnable runningExternal = reserveExternalTask(pool, () -> {
                    running.countDown();
                    await(release);
                });
                rawMainSafeQueue(pool).add(runningExternal);
                pool.wakeAll();
                assertTrue(running.await(1, TimeUnit.SECONDS));
                assertTrue(pool.enqueueGlobal(queued));

                pool.publishFatalFailure(primary);
                Thread stopper = Thread.ofPlatform().start(() -> {
                    try {
                        pool.stop();
                    } catch (Throwable failure) {
                        stopFailure.set(failure);
                    } finally {
                        stopped.countDown();
                    }
                });
                assertFalse(stopped.await(100, TimeUnit.MILLISECONDS),
                        "failed stop forged a running body terminal before its finally");
                release.countDown();
                assertTrue(stopped.await(1, TimeUnit.SECONDS));
                stopper.join();

                assertSame(primary, stopFailure.get());
                assertEquals(0, queued.bodyRuns.get());
                assertEquals(1, queued.cancellations.get());
                assertSame(primary, queued.cancellationPrimary.get());
                assertEquals(0L, longField(pool, "externalObligations"),
                        "RUNNING ExternalTask did not reach its permit finally");
            } finally {
                release.countDown();
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    @Test
    void failedGenerationCancelsQueuedMainSyncPermitWithoutAdmissionOrCompletion() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError primary = new AssertionError("main-sync-primary");
            AtomicInteger admissionRuns = new AtomicInteger();
            AtomicInteger completionRuns = new AtomicInteger();
            var poolField = RegionScheduler.class.getDeclaredField("pool");
            poolField.setAccessible(true);
            Object previousPool = poolField.get(null);
            pool.start();
            try {
                poolField.set(null, pool);
                RegionWorkerPool.ExternalPermit permit = pool.reserveExternalPermit();
                assertTrue(permit != null);
                rawMainSyncQueue().add(newMainSyncTask(
                        permit, admissionRuns, completionRuns));

                pool.publishFatalFailure(primary);
                assertSame(primary, assertThrows(AssertionError.class, pool::stop));

                assertEquals(0, admissionRuns.get(),
                        "failed sweep evaluated MAIN_SYNC admission");
                assertEquals(0, completionRuns.get(),
                        "failed sweep ran MAIN_SYNC success completion");
                assertEquals(0L, longField(pool, "externalObligations"));
                assertTrue(rawMainSyncQueue().isEmpty());
            } finally {
                try {
                    if (!pool.isTerminated()) {
                        pool.publishFatalFailure(primary);
                        assertSame(primary, assertThrows(AssertionError.class, pool::stop));
                    }
                } finally {
                    poolField.set(null, previousPool);
                }
            }
        });
    }

    @Test
    void retiredFailureCannotCancelTheRegisteredGenerationsQueuedPermit() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var poolField = RegionScheduler.class.getDeclaredField("pool");
            poolField.setAccessible(true);
            Object previousPool = poolField.get(null);
            RegionWorkerPool retired = new RegionWorkerPool(1);
            RegionWorkerPool current = new RegionWorkerPool(1);
            AssertionError retiredWinner = new AssertionError("retired-generation-W");
            AssertionError currentWinner = new AssertionError("current-generation-W");
            AtomicInteger admissionRuns = new AtomicInteger();
            AtomicInteger completionRuns = new AtomicInteger();
            retired.start();
            current.start();
            try {
                poolField.set(null, retired);
                retired.publishFatalFailure(retiredWinner);
                poolField.set(null, current);
                RegionWorkerPool.ExternalPermit permit = current.reserveExternalPermit();
                assertTrue(permit != null);
                Object queued = newMainSyncTask(permit, admissionRuns, completionRuns);
                rawMainSyncQueue().add(queued);

                assertSame(retiredWinner, assertThrows(AssertionError.class, retired::stop));
                assertSame(queued, rawMainSyncQueue().peek(),
                        "retired cancellation detached the current generation's obligation");
                AtomicInteger healthyBodies = new AtomicInteger();
                RegionScheduler.runSuccessBodyIfHealthy(healthyBodies::incrementAndGet);
                assertEquals(1, healthyBodies.get(),
                        "retired failure poisoned the current generation's admission");
                assertTrue(RegionScheduler.failedGenerationQueuesEmpty(retired),
                        "retired stop must not await a newer generation's queue");
                assertFalse(RegionScheduler.failedGenerationQueuesEmpty(current));

                current.publishFatalFailure(currentWinner);
                assertSame(currentWinner, assertThrows(AssertionError.class, current::stop));
                assertTrue(rawMainSyncQueue().isEmpty());
                assertEquals(0, admissionRuns.get());
                assertEquals(0, completionRuns.get());
                assertEquals(0L, longField(current, "externalObligations"));
            } finally {
                try {
                    if (!retired.isTerminated()) {
                        assertSame(retiredWinner, assertThrows(AssertionError.class, retired::stop));
                    }
                    if (!current.isTerminated()) {
                        current.publishFatalFailure(currentWinner);
                        assertSame(currentWinner, assertThrows(AssertionError.class, current::stop));
                    }
                } finally {
                    poolField.set(null, previousPool);
                }
            }
        });
    }

    @Test
    void detachedMainSyncPermitCannotBeginAfterFatalPublication() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError primary = new AssertionError("detached-main-sync-primary");
            pool.start();
            try {
                RegionWorkerPool.ExternalPermit permit = pool.reserveExternalPermit();
                assertTrue(permit != null);

                pool.publishFatalFailure(primary);
                assertSame(primary, assertThrows(AssertionError.class,
                        permit::beginMainSync));
                assertEquals(0L, longField(pool, "externalObligations"),
                        "fatal-losing detached permit retained its obligation");
                assertSame(primary, assertThrows(AssertionError.class, pool::stop));
            } finally {
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    @Test
    void fatalStopCancelsExactTurnDeferredRootWithoutReplay() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError primary = new AssertionError("deferred-root fatal");
            CancellableProbe deferred = new CancellableProbe();
            pool.start();
            try {
                pool.pauseExactTurnAndAwait(() -> {});
                assertTrue(CompletableFuture.supplyAsync(() ->
                        pool.enqueueGlobal(deferred)).join());
                pool.publishFatalFailure(primary);

                assertSame(primary, assertThrows(AssertionError.class, pool::stop));
                assertEquals(0, deferred.bodyRuns.get(),
                        "fatal stop replayed a deferred successor publication");
                assertEquals(1, deferred.cancellations.get());
                assertSame(primary, deferred.cancellationPrimary.get());
            } finally {
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    @Test
    void firstWorkerFailureClosesAdmissionAndRethrowsExactPrimary() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            IllegalStateException primary = new IllegalStateException("worker-primary");
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> { throw primary; }));
                pool.wakeAll();
                Throwable observed = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (observed == null && System.nanoTime() < deadline) {
                    try {
                        pool.throwIfFailed();
                    } catch (Throwable thrown) {
                        observed = thrown;
                    }
                    if (observed == null) Thread.onSpinWait();
                }
                assertSame(primary, observed,
                        "the pool replaced its first worker Throwable");
                assertSame(primary, assertThrows(IllegalStateException.class,
                                () -> pool.enqueueGlobal(() -> {})),
                        "fatal generation did not exact-rethrow W at root admission");
                assertSame(primary, assertThrows(IllegalStateException.class,
                        () -> pool.stealOne(true)));
                assertSame(primary, assertThrows(IllegalStateException.class, pool::stop));
                assertTrue(pool.isTerminated());
            } finally {
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    @Test
    void regionTaskQueueStopsAtFirstFailureAndLeavesLaterWorkQueued() {
        RegionizedTaskQueue queue = new RegionizedTaskQueue();
        IllegalStateException primary = new IllegalStateException("queue-primary");
        AtomicInteger later = new AtomicInteger();
        queue.submit(() -> { throw primary; });
        queue.submit(later::incrementAndGet);

        assertSame(primary, assertThrows(IllegalStateException.class,
                () -> queue.drain(Integer.MAX_VALUE)));
        assertEquals(0, later.get(), "queue continued after a partial vanilla mutation");
        assertEquals(1, queue.size(), "later work was discarded instead of remaining queued");
        assertEquals(1, queue.drain(Integer.MAX_VALUE));
        assertEquals(1, later.get());
    }

    @Test
    void acceptedOrdinaryBacklogDrainsBeforeStopReturns() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch blockerEntered = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            AtomicInteger drained = new AtomicInteger();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    blockerEntered.countDown();
                    await(releaseBlocker);
                }));
                pool.wakeAll();
                assertTrue(blockerEntered.await(1, TimeUnit.SECONDS));
                for (int i = 0; i < 32; i++) {
                    assertTrue(pool.enqueueGlobal(drained::incrementAndGet));
                }

                CompletableFuture<Void> stopped = CompletableFuture.runAsync(pool::stop);
                assertFalse(stopped.isDone(), "stop returned before the accepted backlog could drain");
                releaseBlocker.countDown();
                stopped.join();

                assertEquals(32, drained.get());
                assertTrue(pool.isTerminated());
            } finally {
                releaseBlocker.countDown();
                pool.stop();
            }
        });
    }

    @Test
    void unrelatedExternalSubmissionsAreRejectedOnceDrainingStarts() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch blockerEntered = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            CountDownLatch drainingEntered = new CountDownLatch(1);
            CountDownLatch releaseProgress = new CountDownLatch(1);
            AtomicBoolean firstProgress = new AtomicBoolean(true);
            AtomicInteger lateBodies = new AtomicInteger();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    blockerEntered.countDown();
                    await(releaseBlocker);
                }));
                pool.wakeAll();
                assertTrue(blockerEntered.await(1, TimeUnit.SECONDS));

                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> pool.stop(() -> {
                    if (firstProgress.compareAndSet(true, false)) {
                        drainingEntered.countDown();
                        await(releaseProgress);
                    }
                }));
                assertTrue(drainingEntered.await(1, TimeUnit.SECONDS));
                assertFalse(pool.enqueueGlobal(lateBodies::incrementAndGet));
                assertFalse(pool.enqueueGlobalMainSafe(lateBodies::incrementAndGet));
                assertFalse(pool.enqueueUnit(lateBodies::incrementAndGet));

                releaseProgress.countDown();
                releaseBlocker.countDown();
                stopped.join();
                assertEquals(0, lateBodies.get());
                assertFalse(pool.enqueueGlobal(lateBodies::incrementAndGet));
                assertFalse(pool.enqueueGlobalMainSafe(lateBodies::incrementAndGet));
                assertFalse(pool.enqueueUnit(lateBodies::incrementAndGet));
                assertEquals(0, lateBodies.get());
            } finally {
                releaseProgress.countDown();
                releaseBlocker.countDown();
                pool.stop();
            }
        });
    }

    @Test
    void schedulerDrainBoundaryClosesRootsBeforePoolStopButKeepsOwnerCausality() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AtomicInteger bodies = new AtomicInteger();
            pool.start();
            try {
                pool.beginDraining(Thread.currentThread());
                assertFalse(CompletableFuture.supplyAsync(() ->
                        pool.enqueueGlobal(bodies::incrementAndGet)).join(),
                        "unrelated producer crossed the scheduler STOPPING boundary");
                assertTrue(pool.enqueueGlobal(bodies::incrementAndGet),
                        "the exact drain owner lost a causal child");
                pool.wakeAll();
                pool.stop();
                assertEquals(1, bodies.get());
            } finally {
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void causalChildFromRunningPoolWorkerDuringDrainingIsAcceptedAndDrained() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch parentEntered = new CountDownLatch(1);
            CountDownLatch drainingEntered = new CountDownLatch(1);
            CountDownLatch allowChild = new CountDownLatch(1);
            CountDownLatch childRan = new CountDownLatch(1);
            AtomicBoolean childAccepted = new AtomicBoolean();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    parentEntered.countDown();
                    await(allowChild);
                    childAccepted.set(pool.enqueueGlobal(childRan::countDown));
                }));
                pool.wakeAll();
                assertTrue(parentEntered.await(1, TimeUnit.SECONDS));

                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() ->
                        pool.stop(drainingEntered::countDown));
                assertTrue(drainingEntered.await(1, TimeUnit.SECONDS));
                allowChild.countDown();
                stopped.join();

                assertTrue(childAccepted.get(),
                        "a running pool worker lost its causal continuation during draining");
                assertEquals(0, childRan.getCount(),
                        "stop returned before the accepted causal continuation ran");
            } finally {
                allowChild.countDown();
                pool.stop();
            }
        });
    }

    @Test
    void enqueueRacingAdmissionCloseIsEitherRejectedOrExecutedExactlyOnce() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            final int attempts = 64;
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch blockerEntered = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            CyclicBarrier start = new CyclicBarrier(attempts + 2);
            AtomicIntegerArray accepted = new AtomicIntegerArray(attempts);
            AtomicIntegerArray executions = new AtomicIntegerArray(attempts);
            Thread[] submitters = new Thread[attempts];
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    blockerEntered.countDown();
                    await(releaseBlocker);
                }));
                pool.wakeAll();
                assertTrue(blockerEntered.await(1, TimeUnit.SECONDS));

                for (int i = 0; i < attempts; i++) {
                    int index = i;
                    submitters[i] = Thread.ofPlatform().start(() -> {
                        await(start);
                        if (pool.enqueueGlobal(() -> executions.incrementAndGet(index))) {
                            accepted.set(index, 1);
                        }
                    });
                }
                Thread stopper = Thread.ofPlatform().start(() -> {
                    await(start);
                    pool.stop();
                });
                await(start);
                for (Thread submitter : submitters) submitter.join();
                releaseBlocker.countDown();
                stopper.join();

                for (int i = 0; i < attempts; i++) {
                    assertEquals(accepted.get(i), executions.get(i),
                            "submission " + i + " was accepted-but-lost or rejected-but-executed");
                }
            } finally {
                releaseBlocker.countDown();
                pool.stop();
            }
        });
    }

    @Test
    void publishedExternalObligationsBlockStopUntilEveryExactCompletion() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch draining = new CountDownLatch(1);
            AtomicInteger publications = new AtomicInteger();
            java.util.ArrayList<RegionWorkerPool.ExternalPermit> permits = new java.util.ArrayList<>();
            pool.start();
            try {
                RegionWorkerPool.ExternalPermit first = pool.reserveExternalPermit();
                RegionWorkerPool.ExternalPermit second = pool.reserveExternalPermit();
                assertTrue(first != null && second != null);
                permits.add(first);
                permits.add(second);
                publications.addAndGet(2);
                assertEquals(2, publications.get());

                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() ->
                        pool.stop(draining::countDown));
                assertTrue(draining.await(1, TimeUnit.SECONDS));
                assertFalse(stopped.isDone(), "published external work did not hold stop open");

                assertTrue(first.begin());
                assertNull(first.finish(null));
                permits.remove(first);
                assertFalse(stopped.isDone(),
                        "one completion released two independently published obligations");

                assertTrue(second.begin());
                assertNull(second.finish(null));
                permits.remove(second);
                stopped.join();
                assertTrue(pool.isTerminated());
                assertTrue(second.finish(null) instanceof IllegalStateException,
                        "an external permit may be completed only once");
            } finally {
                for (RegionWorkerPool.ExternalPermit permit : permits) {
                    if (permit.begin()) assertNull(permit.finish(null));
                }
                pool.stop();
            }
        });
    }

    @Test
    void trackedForeignExecutorTaskKeepsStopOpenAndRetainsNestedCausalAdmission() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AtomicReference<Runnable> queued = new AtomicReference<>();
            CountDownLatch published = new CountDownLatch(1);
            CountDownLatch draining = new CountDownLatch(1);
            AtomicInteger bodies = new AtomicInteger();
            Executor delayed = task -> {
                if (!queued.compareAndSet(null, task)) {
                    throw new IllegalStateException("foreign executor accepted two tasks");
                }
                published.countDown();
            };
            pool.start();
            try {
                pool.executeTrackedExternal(delayed,
                        () -> pool.runSuccessBodyIfHealthy(bodies::incrementAndGet));
                assertTrue(published.await(1, TimeUnit.SECONDS));

                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() ->
                        pool.stop(draining::countDown));
                assertTrue(draining.await(1, TimeUnit.SECONDS));
                assertFalse(stopped.isDone(),
                        "a queued foreign task lost its pre-publication generation permit");

                Runnable exact = queued.getAndSet(null);
                assertTrue(exact != null);
                exact.run();
                stopped.join();

                assertEquals(1, bodies.get());
                assertNull(pool.fatalFailureOrNull(),
                        "nested EvalTask success cuts must inherit the tracked task causality");
                assertTrue(pool.isTerminated());
            } finally {
                Runnable exact = queued.getAndSet(null);
                if (exact != null) exact.run();
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void normalStopRequiresProgressAfterTheFirstZeroLedgerSnapshot() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            ConcurrentLinkedQueue<Integer> retained = new ConcurrentLinkedQueue<>();
            AtomicReference<Runnable> queued = new AtomicReference<>();
            Executor delayed = task -> {
                if (!queued.compareAndSet(null, task)) {
                    throw new IllegalStateException("foreign executor accepted two tasks");
                }
            };
            AtomicInteger progressPasses = new AtomicInteger();
            AtomicInteger applied = new AtomicInteger();
            pool.start();
            try {
                pool.executeTrackedExternal(delayed, () -> retained.add(1));

                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() ->
                        pool.stop(() -> {
                            int pass = progressPasses.incrementAndGet();
                            if (pass == 1) {
                                assertTrue(retained.isEmpty());
                                Runnable exact = queued.getAndSet(null);
                                assertTrue(exact != null,
                                        "tracked wrapper was not published before stop");
                                // Exact race: the retained result becomes visible and
                                // its pre-publication permit terminalizes after this
                                // pass already observed the result queue empty, but
                                // before stop takes its first zero-ledger snapshot.
                                exact.run();
                                return;
                            }
                            Integer result;
                            while ((result = retained.poll()) != null) {
                                applied.addAndGet(result);
                            }
                        }));
                stopped.join();

                assertTrue(progressPasses.get() >= 2,
                        "the first zero ledger cut must force one final progress pass");
                assertEquals(1, applied.get(),
                        "a result published after progress but before zero-ledger observation "
                                + "must be applied before generation close");
                assertTrue(retained.isEmpty());
            } finally {
                Runnable exact = queued.getAndSet(null);
                if (exact != null) exact.run();
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void normalStopRepeatsProgressWhenAWorkerCompletesBetweenDrainAndSecondZeroCut() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            ConcurrentLinkedQueue<Integer> retained = new ConcurrentLinkedQueue<>();
            AtomicInteger progressPasses = new AtomicInteger();
            AtomicInteger applied = new AtomicInteger();
            CountDownLatch workerPublished = new CountDownLatch(1);
            pool.start();
            try {
                pool.stop(() -> {
                    int pass = progressPasses.incrementAndGet();
                    Integer result;
                    while ((result = retained.poll()) != null) {
                        applied.addAndGet(result);
                    }
                    if (pass == 2) {
                        assertTrue(pool.enqueueGlobal(() -> {
                            retained.add(1);
                            workerPublished.countDown();
                        }));
                        pool.wakeAll();
                        await(workerPublished);
                        awaitNoActiveSteps(pool);
                    }
                });

                assertTrue(progressPasses.get() >= 3,
                        "a producer completion after pass-two drain must invalidate its idle cut");
                assertEquals(1, applied.get(),
                        "the retained result must be applied on the forced third progress pass");
                assertTrue(retained.isEmpty());
            } finally {
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void normalStopRepeatsProgressWhenTrackedExternalCompletesAfterSecondDrain() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            ConcurrentLinkedQueue<Integer> retained = new ConcurrentLinkedQueue<>();
            AtomicReference<Runnable> queued = new AtomicReference<>();
            Executor delayed = task -> {
                if (!queued.compareAndSet(null, task)) {
                    throw new IllegalStateException("foreign executor accepted two tasks");
                }
            };
            AtomicInteger progressPasses = new AtomicInteger();
            AtomicInteger applied = new AtomicInteger();
            pool.start();
            try {
                pool.stop(() -> {
                    int pass = progressPasses.incrementAndGet();
                    Integer result;
                    while ((result = retained.poll()) != null) {
                        applied.addAndGet(result);
                    }
                    if (pass == 2) {
                        pool.executeTrackedExternal(delayed, () -> retained.add(1));
                        Runnable exact = queued.getAndSet(null);
                        assertTrue(exact != null);
                        exact.run();
                    }
                });

                assertTrue(progressPasses.get() >= 3,
                        "a tracked external completion after pass-two drain must invalidate its cut");
                assertEquals(1, applied.get());
                assertTrue(retained.isEmpty());
            } finally {
                Runnable exact = queued.getAndSet(null);
                if (exact != null) exact.run();
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void normalStopCannotCloseAcrossAnInFlightRootAdmission() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch secondProgressEntered = new CountDownLatch(1);
            CountDownLatch releaseSecondProgress = new CountDownLatch(1);
            CountDownLatch thirdProgressEntered = new CountDownLatch(1);
            AtomicInteger progressPasses = new AtomicInteger();
            CompletableFuture<Void> stopped = null;
            pool.start();
            enterRootAdmission(pool);
            try {
                stopped = CompletableFuture.runAsync(() -> pool.stop(() -> {
                    int pass = progressPasses.incrementAndGet();
                    if (pass == 2) {
                        secondProgressEntered.countDown();
                        await(releaseSecondProgress);
                    } else if (pass == 3) {
                        thirdProgressEntered.countDown();
                    }
                }));
                assertTrue(secondProgressEntered.await(1, TimeUnit.SECONDS));
                releaseSecondProgress.countDown();
                assertTrue(thirdProgressEntered.await(1, TimeUnit.SECONDS),
                        "two idle snapshots crossed a root publisher which had already entered");
                assertFalse(stopped.isDone(),
                        "stop closed the generation before the admitted publisher retired");
            } finally {
                releaseSecondProgress.countDown();
                leaveRootAdmission(pool);
                if (stopped != null) stopped.join();
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void externalPublicationRacingStopIsRejectedOrRequiresItsExactCompletion() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CyclicBarrier start = new CyclicBarrier(3);
            CountDownLatch publicationDecided = new CountDownLatch(1);
            CountDownLatch allowCompletion = new CountDownLatch(1);
            AtomicBoolean accepted = new AtomicBoolean();
            AtomicInteger publicationBodies = new AtomicInteger();
            pool.start();

            CompletableFuture<Void> publisher = CompletableFuture.runAsync(() -> {
                await(start);
                RegionWorkerPool.ExternalPermit permit = pool.reserveExternalPermit();
                boolean admitted = permit != null;
                accepted.set(admitted);
                if (admitted) publicationBodies.incrementAndGet();
                publicationDecided.countDown();
                if (admitted) {
                    await(allowCompletion);
                    assertTrue(permit.begin());
                    assertNull(permit.finish(null));
                }
            });
            CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> {
                await(start);
                pool.stop();
            });
            try {
                await(start);
                assertTrue(publicationDecided.await(1, TimeUnit.SECONDS));
                if (accepted.get()) {
                    assertEquals(1, publicationBodies.get());
                    assertFalse(stopped.isDone(),
                            "accepted publication did not create a shutdown obligation");
                } else {
                    assertEquals(0, publicationBodies.get(),
                            "rejected publication executed its body");
                }
                allowCompletion.countDown();
                publisher.join();
                stopped.join();
                assertEquals(accepted.get() ? 1 : 0, publicationBodies.get());
            } finally {
                allowCompletion.countDown();
                publisher.join();
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void externalRegionAndHelpThrowableCompleteObligationsAndRethrowToConsumer() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RuntimeException regionFailure = new RuntimeException("region external body");
            RuntimeException helpFailure = new RuntimeException("help external body");
            // One failed generation is terminal. Exercise the region/help wrapper
            // shapes in independent pools; expecting the second body to run in the
            // first failed pool would encode the forbidden catch-and-continue policy.
            assertExternalFailureCompletesItsPermit(regionFailure);
            assertExternalFailureCompletesItsPermit(helpFailure);
        });
    }

    private static void assertExternalFailureCompletesItsPermit(RuntimeException expected) {
        RegionWorkerPool pool = new RegionWorkerPool(1);
        Runnable consumer = null;
        boolean consumed = false;
        pool.start();
        try {
            // Region/help admission now deliberately requires a live scheduler generation
            // under Region.admissionLock. Keep this test MC-free: the ASM wiring test pins
            // both enqueue paths to reserveExternalTask, while this executes that exact
            // production wrapper and its obligation/failure protocol directly.
            consumer = reserveExternalTask(pool, () -> { throw expected; });
            consumed = true;
            assertSame(expected, assertThrows(RuntimeException.class, consumer::run));
            assertSame(expected, assertThrows(RuntimeException.class, pool::stop));
            assertTrue(pool.isTerminated(),
                    "external body failure stranded its shutdown obligation");
        } finally {
            if (consumer != null && !consumed) {
                assertThrows(RuntimeException.class, consumer::run);
            }
            if (!pool.isTerminated()) {
                assertSame(expected, assertThrows(RuntimeException.class, pool::stop));
            }
        }
    }

    @Test
    void orphanHelpPermitIsConsumedBeforeShutdownCanReachItsFixedPoint() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            Region orphan = new Region(null, 0L);
            CountDownLatch draining = new CountDownLatch(1);
            AtomicInteger bodies = new AtomicInteger();
            pool.start();
            try {
                orphan.helpQueue.add(reserveExternalTask(pool, bodies::incrementAndGet));
                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() ->
                        pool.stop(draining::countDown));
                assertTrue(draining.await(1, TimeUnit.SECONDS));
                assertFalse(stopped.isDone(), "orphan helper permit did not hold shutdown open");

                drainOrphanHelp(pool, orphan);
                stopped.join();
                assertEquals(1, bodies.get());
                assertTrue(orphan.helpQueue.isEmpty());
            } finally {
                if (!orphan.helpQueue.isEmpty()) drainOrphanHelp(pool, orphan);
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void workerStepRacingFinalCutCannotStartItsBodyAfterStopReturns() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch bodyEntered = new CountDownLatch(1);
            CountDownLatch releaseBody = new CountDownLatch(1);
            AtomicBoolean published = new AtomicBoolean();
            AtomicBoolean stopReturned = new AtomicBoolean();
            AtomicBoolean bodyStartedLate = new AtomicBoolean();
            pool.start();
            try {
                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> {
                    pool.stop(() -> {
                        if (published.compareAndSet(false, true)) {
                            assertTrue(pool.enqueueGlobal(() -> {
                                if (stopReturned.get()) bodyStartedLate.set(true);
                                bodyEntered.countDown();
                                await(releaseBody);
                            }));
                        }
                    });
                    stopReturned.set(true);
                });

                assertTrue(bodyEntered.await(1, TimeUnit.SECONDS));
                assertFalse(stopped.isDone(),
                        "stop crossed its final cut while a worker body was still live");
                releaseBody.countDown();
                stopped.join();

                assertTrue(stopReturned.get());
                assertFalse(bodyStartedLate.get(), "worker body began after stop returned");
                assertFalse(pool.enqueueGlobal(() -> bodyStartedLate.set(true)));
                assertFalse(bodyStartedLate.get());
            } finally {
                releaseBody.countDown();
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void exactTurnPauseWaitsForLiveStepAndBlocksEveryNextBodyUntilOwnerResume() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch pauseReturned = new CountDownLatch(1);
            CountDownLatch allowResume = new CountDownLatch(1);
            CountDownLatch requestObserved = new CountDownLatch(1);
            CountDownLatch causalChildEntered = new CountDownLatch(1);
            CountDownLatch deferredRootEntered = new CountDownLatch(1);
            AtomicInteger progressCalls = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<Throwable> pauseFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            pool.start();

            Thread pauseOwner = Thread.ofPlatform().name("exact-turn-pause-owner").unstarted(() -> {
                try {
                    pool.pauseExactTurnAndAwait(() -> {
                        progressCalls.incrementAndGet();
                        requestObserved.countDown();
                    });
                    pauseReturned.countDown();
                    await(allowResume);
                    pool.resumeExactTurn();
                } catch (Throwable thrown) {
                    pauseFailure.set(thrown);
                    pauseReturned.countDown();
                }
            });
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    firstEntered.countDown();
                    await(releaseFirst);
                    assertTrue(pool.enqueueGlobal(causalChildEntered::countDown),
                            "a running worker lost its causal child at the exact-turn cut");
                }));
                pool.wakeAll();
                assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

                pauseOwner.start();
                assertTrue(requestObserved.await(1, TimeUnit.SECONDS));
                assertTrue(pool.enqueueGlobal(deferredRootEntered::countDown),
                        "an unrelated post-cut root must be accepted for replay");
                assertFalse(pauseReturned.await(100, TimeUnit.MILLISECONDS),
                        "pause returned while the previous worker step was still live");

                releaseFirst.countDown();
                assertTrue(pauseReturned.await(1, TimeUnit.SECONDS));
                assertNull(pauseFailure.get());
                assertEquals(0L, causalChildEntered.getCount(),
                        "the pause cut skipped a causal child of a pre-cut worker step");
                assertEquals(1L, deferredRootEntered.getCount(),
                        "an unrelated post-cut root ran before the exclusive turn");
                assertTrue(progressCalls.get() > 0,
                        "pause wait never serviced its bounded progress hook");
                assertThrows(IllegalStateException.class, pool::resumeExactTurn,
                        "a non-owner thread released the exact turn");

                allowResume.countDown();
                pauseOwner.join(Duration.ofSeconds(1));
                assertFalse(pauseOwner.isAlive());
                pool.wakeAll();
                assertTrue(deferredRootEntered.await(1, TimeUnit.SECONDS),
                        "accepted post-cut root was not replayed after owner release");
            } finally {
                releaseFirst.countDown();
                allowResume.countDown();
                if (pauseOwner.getState() != Thread.State.NEW) {
                    pauseOwner.join(Duration.ofSeconds(1));
                }
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void liveCausalGenericExecutorExternalRootUnblocksRequestAndUnrelatedRootDefers() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch workerEntered = new CountDownLatch(1);
            CountDownLatch requestObserved = new CountDownLatch(1);
            CountDownLatch rescuedPublicationVisible = new CountDownLatch(1);
            CountDownLatch workerReleased = new CountDownLatch(1);
            AtomicBoolean rescuedAccepted = new AtomicBoolean();
            AtomicBoolean rescuedPermitCancelled = new AtomicBoolean();
            CountDownLatch resumePublicationVisible = new CountDownLatch(1);
            AtomicBoolean resumeAccepted = new AtomicBoolean();
            AtomicBoolean resumePermitCancelled = new AtomicBoolean();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    workerEntered.countDown();
                    Runnable causalPublication = pool.wrapCausalHandoff(() ->
                            rescuedAccepted.set(pool.publishExternalRoot(permit -> {
                                rescuedPermitCancelled.set(permit.cancelBeforeRun());
                                rescuedPublicationVisible.countDown();
                            })));
                    CompletableFuture<Void> external = CompletableFuture.runAsync(() -> {
                        await(requestObserved);
                        causalPublication.run();
                    });
                    external.join();
                    await(rescuedPublicationVisible);
                    workerReleased.countDown();
                }));
                pool.wakeAll();
                assertTrue(workerEntered.await(1, TimeUnit.SECONDS));
                assertEquals(1L, rescuedPublicationVisible.getCount(),
                        "external-root callback became visible before the exact request");

                pool.pauseExactTurnAndAwait(requestObserved::countDown);
                assertTrue(rescuedAccepted.get(),
                        "wrapped generic-executor publication was rejected at the request cut");
                assertTrue(rescuedPermitCancelled.get(),
                        "causal external publication did not own one exact permit");
                assertEquals(0L, rescuedPublicationVisible.getCount(),
                        "the live causal token did not publish its external root");
                assertEquals(0L, workerReleased.getCount(),
                        "the worker remained cyclically blocked on its generic-executor child");

                resumeAccepted.set(CompletableFuture.supplyAsync(() ->
                        pool.publishExternalRoot(permit -> {
                            resumePermitCancelled.set(permit.cancelBeforeRun());
                            resumePublicationVisible.countDown();
                        })).join());
                assertTrue(resumeAccepted.get());
                assertEquals(1L, resumePublicationVisible.getCount(),
                        "a post-pause external callback escaped before resume replay");

                pool.resumeExactTurn();
                assertEquals(0L, resumePublicationVisible.getCount(),
                        "resume did not replay the accepted external-root callback");
                assertTrue(resumePermitCancelled.get(),
                        "resume replay did not publish exactly one tracked permit");
            } finally {
                requestObserved.countDown();
                if (pool.ownsPausedExactTurn(Thread.currentThread())) {
                    pool.resumeExactTurn();
                }
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void admittedCausalWrapperKeepsFreshAuthorityAfterCapturedParentRetires() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch wrapperEntered = new CountDownLatch(1);
            CountDownLatch releaseNestedWork = new CountDownLatch(1);
            CountDownLatch childRan = new CountDownLatch(1);
            CountDownLatch wrapperLaunched = new CountDownLatch(1);
            AtomicInteger nestedBodies = new AtomicInteger();
            AtomicReference<CompletableFuture<Void>> foreign = new AtomicReference<>();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    Runnable handoff = pool.wrapCausalHandoff(() -> {
                        wrapperEntered.countDown();
                        await(releaseNestedWork);
                        pool.runSuccessBodyIfHealthy(nestedBodies::incrementAndGet);
                        if (!pool.enqueueGlobal(childRan::countDown)) {
                            throw new IllegalStateException(
                                    "nested causal child was rejected");
                        }
                        pool.wakeAll();
                        await(childRan);
                    });
                    foreign.set(CompletableFuture.runAsync(handoff));
                    wrapperLaunched.countDown();
                }));
                pool.wakeAll();
                assertTrue(wrapperLaunched.await(1, TimeUnit.SECONDS));
                assertTrue(wrapperEntered.await(1, TimeUnit.SECONDS));
                awaitNoActiveSteps(pool);

                // The captured worker token is now expired. REQUESTED must still
                // wait the admitted wrapper's own permit and allow its nested causal
                // stages to reach a real fixed point before PAUSED is established.
                pool.pauseExactTurnAndAwait(releaseNestedWork::countDown);
                foreign.get().join();
                assertEquals(1, nestedBodies.get());
                assertEquals(0L, childRan.getCount());
                assertNull(pool.fatalFailureOrNull());
                pool.resumeExactTurn();
            } finally {
                releaseNestedWork.countDown();
                if (pool.ownsPausedExactTurn(Thread.currentThread())) {
                    pool.resumeExactTurn();
                }
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void wrappedForeignMainSafeChildUsesLiveCausalityOrAnOrdinaryOpenRoot() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch parentEntered = new CountDownLatch(1);
            CountDownLatch wrappersCaptured = new CountDownLatch(1);
            CountDownLatch requestObserved = new CountDownLatch(1);
            CountDownLatch parentJoinedLiveChild = new CountDownLatch(1);
            CountDownLatch liveChildRan = new CountDownLatch(1);
            CountDownLatch delayedChildRan = new CountDownLatch(1);
            AtomicBoolean liveAccepted = new AtomicBoolean();
            AtomicBoolean delayedAccepted = new AtomicBoolean();
            AtomicReference<Runnable> delayedWrapper = new AtomicReference<>();
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    parentEntered.countDown();
                    Runnable liveWrapper = pool.wrapCausalHandoff(() ->
                            liveAccepted.set(pool.enqueueGlobalMainSafe(liveChildRan::countDown)));
                    delayedWrapper.set(pool.wrapCausalHandoff(() ->
                            delayedAccepted.set(pool.enqueueGlobalMainSafe(
                                    delayedChildRan::countDown))));
                    wrappersCaptured.countDown();

                    CompletableFuture<Void> foreign = CompletableFuture.runAsync(() -> {
                        await(requestObserved);
                        liveWrapper.run();
                    });
                    foreign.join();
                    parentJoinedLiveChild.countDown();
                }));
                pool.wakeAll();
                assertTrue(parentEntered.await(1, TimeUnit.SECONDS));
                assertTrue(wrappersCaptured.await(1, TimeUnit.SECONDS));

                pool.pauseExactTurnAndAwait(requestObserved::countDown);
                assertTrue(liveAccepted.get(),
                        "a wrapped foreign child lost the live parent-step causality");
                assertEquals(0L, parentJoinedLiveChild.getCount(),
                        "the worker remained blocked joining its wrapped foreign child");
                assertEquals(0L, liveChildRan.getCount(),
                        "pause returned before the accepted causal main-safe child ran");

                pool.resumeExactTurn();
                CompletableFuture.runAsync(delayedWrapper.get()).join();
                assertTrue(delayedAccepted.get(),
                        "the expired wrapper was not admitted as an ordinary open root");
                pool.wakeAll();
                assertTrue(delayedChildRan.await(1, TimeUnit.SECONDS),
                        "the ordinary post-turn child did not execute exactly once");
            } finally {
                requestObserved.countDown();
                if (pool.ownsPausedExactTurn(Thread.currentThread())) {
                    pool.resumeExactTurn();
                }
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void expiredCausalWrapperAtExactPauseFailsBeforeBodyAndNeverReplays() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<Runnable> delayedWrapper = new AtomicReference<>();
            AtomicInteger bodyRuns = new AtomicInteger();
            Throwable winner = null;
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    delayedWrapper.set(pool.wrapCausalHandoff(bodyRuns::incrementAndGet));
                    captured.countDown();
                }));
                pool.wakeAll();
                assertTrue(captured.await(1, TimeUnit.SECONDS));

                // The pause itself waits for the capturing parent step to close,
                // so the stored token is definitely expired at invocation time.
                pool.pauseExactTurnAndAwait(() -> {});
                winner = assertThrows(
                        IllegalStateException.class, delayedWrapper.get()::run);
                assertSame(winner, pool.fatalFailureOrNull());
                assertEquals(0, bodyRuns.get(),
                        "physical exact-owner authority revived an expired wrapper");

                pool.resumeExactTurn();
                IllegalStateException successor = assertThrows(
                        IllegalStateException.class, delayedWrapper.get()::run);
                assertSame(winner, successor,
                        "a later invocation did not observe the exact selected W");
                assertEquals(0, bodyRuns.get(),
                        "the refused wrapper was replayed after exact-turn release");
                assertSame(winner, assertThrows(IllegalStateException.class, pool::stop));
            } finally {
                if (pool.ownsPausedExactTurn(Thread.currentThread())) {
                    try {
                        pool.resumeExactTurn();
                    } catch (Throwable ignored) {
                    }
                }
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable stopped) {
                        if (winner != null) assertSame(winner, stopped);
                    }
                }
            }
        });
    }

    @Test
    void expiredCausalWrapperCannotUseExactRequesterAuthority() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<Runnable> delayedWrapper = new AtomicReference<>();
            AtomicInteger bodyRuns = new AtomicInteger();
            RegionWorkerPool.ExternalPermit blocker = null;
            Throwable winner = null;
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    delayedWrapper.set(pool.wrapCausalHandoff(bodyRuns::incrementAndGet));
                    captured.countDown();
                }));
                pool.wakeAll();
                assertTrue(captured.await(1, TimeUnit.SECONDS));
                awaitNoActiveSteps(pool);

                // Keep the pause in REQUESTED while its physical owner invokes
                // the stale wrapper from the progress callback.
                blocker = pool.reserveExternalPermit();
                winner = assertThrows(IllegalStateException.class,
                        () -> pool.pauseExactTurnAndAwait(delayedWrapper.get()));
                assertSame(winner, pool.fatalFailureOrNull());
                assertEquals(0, bodyRuns.get(),
                        "exact requester authority revived an expired wrapper");
                assertFalse(pool.ownsPausedExactTurn(Thread.currentThread()));
                assertTrue(blocker.cancelBeforeRun());
                blocker = null;
                assertSame(winner, assertThrows(IllegalStateException.class, pool::stop));
            } finally {
                if (blocker != null) blocker.cancelBeforeRun();
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable stopped) {
                        if (winner != null) assertSame(winner, stopped);
                    }
                }
            }
        });
    }

    @Test
    void expiredCausalWrapperCannotUseDrainOwnerAuthority() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<Runnable> delayedWrapper = new AtomicReference<>();
            AtomicInteger bodyRuns = new AtomicInteger();
            Throwable winner = null;
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    delayedWrapper.set(pool.wrapCausalHandoff(bodyRuns::incrementAndGet));
                    captured.countDown();
                }));
                pool.wakeAll();
                assertTrue(captured.await(1, TimeUnit.SECONDS));
                awaitNoActiveSteps(pool);

                pool.beginDraining(Thread.currentThread());
                winner = assertThrows(
                        IllegalStateException.class, delayedWrapper.get()::run);
                assertSame(winner, pool.fatalFailureOrNull());
                assertEquals(0, bodyRuns.get(),
                        "drain-owner authority revived an expired wrapper");
                assertSame(winner, assertThrows(IllegalStateException.class, pool::stop));
            } finally {
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable stopped) {
                        if (winner != null) assertSame(winner, stopped);
                    }
                }
            }
        });
    }

    @Test
    void expiredCausalWrapperRefusesInsideReplayClosingWithoutWaitingOrReplay() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch captured = new CountDownLatch(1);
            CountDownLatch admissionHeld = new CountDownLatch(1);
            CountDownLatch releaseAdmission = new CountDownLatch(1);
            CountDownLatch wrapperReturned = new CountDownLatch(1);
            AtomicReference<Runnable> delayedWrapper = new AtomicReference<>();
            AtomicReference<Throwable> wrapperFailure = new AtomicReference<>();
            AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
            AtomicBoolean returnedBeforeRelease = new AtomicBoolean();
            AtomicInteger bodyRuns = new AtomicInteger();
            Thread holder = null;
            Thread controller = null;
            Throwable winner = null;
            pool.start();
            try {
                assertTrue(pool.enqueueGlobal(() -> {
                    delayedWrapper.set(pool.wrapCausalHandoff(bodyRuns::incrementAndGet));
                    captured.countDown();
                }));
                pool.wakeAll();
                assertTrue(captured.await(1, TimeUnit.SECONDS));
                awaitNoActiveSteps(pool);
                pool.pauseExactTurnAndAwait(() -> {});

                holder = Thread.ofPlatform().name("causal-replay-admission-holder").start(() -> {
                    boolean entered = false;
                    try {
                        enterRootAdmission(pool);
                        entered = true;
                        admissionHeld.countDown();
                        await(releaseAdmission);
                    } catch (Throwable failure) {
                        backgroundFailure.compareAndSet(null, failure);
                    } finally {
                        if (entered) leaveRootAdmission(pool);
                    }
                });
                assertTrue(admissionHeld.await(1, TimeUnit.SECONDS));

                controller = Thread.ofPlatform().name("causal-replay-wrapper-controller")
                        .start(() -> {
                            try {
                                long deadline = System.nanoTime()
                                        + TimeUnit.SECONDS.toNanos(1);
                                while (!booleanFieldUnderLifecycleLock(
                                        pool, "exactReplayClosing")) {
                                    if (System.nanoTime() >= deadline) {
                                        throw new AssertionError(
                                                "resume never entered replay-closing");
                                    }
                                    Thread.onSpinWait();
                                }
                                Thread invoker = Thread.ofPlatform()
                                        .name("expired-causal-replay-invoker")
                                        .start(() -> {
                                            try {
                                                delayedWrapper.get().run();
                                            } catch (Throwable failure) {
                                                wrapperFailure.set(failure);
                                            } finally {
                                                wrapperReturned.countDown();
                                            }
                                        });
                                returnedBeforeRelease.set(
                                        wrapperReturned.await(200, TimeUnit.MILLISECONDS));
                                releaseAdmission.countDown();
                                invoker.join();
                            } catch (Throwable failure) {
                                backgroundFailure.compareAndSet(null, failure);
                                releaseAdmission.countDown();
                            }
                        });

                pool.resumeExactTurn();
                holder.join();
                controller.join();
                winner = wrapperFailure.get();
                assertTrue(winner instanceof IllegalStateException);
                assertSame(winner, pool.fatalFailureOrNull());
                assertTrue(returnedBeforeRelease.get(),
                        "expired wrapper waited for replay admission to reopen");
                assertEquals(0, bodyRuns.get(),
                        "expired wrapper was replayed after the closing cut");
                assertNull(backgroundFailure.get());
                assertSame(winner, assertThrows(IllegalStateException.class, pool::stop));
            } finally {
                releaseAdmission.countDown();
                if (pool.ownsPausedExactTurn(Thread.currentThread())) {
                    try {
                        pool.resumeExactTurn();
                    } catch (Throwable ignored) {
                    }
                }
                if (holder != null) holder.join(Duration.ofSeconds(1));
                if (controller != null) controller.join(Duration.ofSeconds(1));
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable stopped) {
                        if (winner != null) assertSame(winner, stopped);
                    }
                }
            }
        });
    }

    @Test
    void finalPausedProgressRawPublicationForcesAnotherCompleteDrain() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AtomicInteger progressPasses = new AtomicInteger();
            AtomicBoolean published = new AtomicBoolean();
            AtomicBoolean accepted = new AtomicBoolean();
            CountDownLatch rawBodyRan = new CountDownLatch(1);
            pool.start();
            try {
                pool.pauseExactTurnAndAwait(() -> {
                    progressPasses.incrementAndGet();
                    if (pool.ownsPausedExactTurn(Thread.currentThread())
                            && published.compareAndSet(false, true)) {
                        accepted.set(pool.enqueueGlobal(rawBodyRan::countDown));
                    }
                });

                assertTrue(published.get(),
                        "the test never reached the final progress pass under PAUSED");
                assertTrue(accepted.get(),
                        "the exact owner could not publish its raw causal root");
                assertEquals(0L, rawBodyRan.getCount(),
                        "pause returned without draining work published by final progress");
                assertTrue(progressPasses.get() >= 2,
                        "raw publication did not force a second fixed-point validation");
                assertTrue(pool.ownsPausedExactTurn(Thread.currentThread()),
                        "pause returned without re-establishing the owner fence");
                pool.resumeExactTurn();
            } finally {
                if (pool.ownsPausedExactTurn(Thread.currentThread())) {
                    pool.resumeExactTurn();
                }
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void exactReplayClosingDrainsAdmittedTailAndBlocksEveryNewRootUntilReopen() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            CountDownLatch admittedBeforeClose = new CountDownLatch(1);
            CountDownLatch allowTailPublication = new CountDownLatch(1);
            CountDownLatch closingObserved = new CountDownLatch(1);
            CountDownLatch lateAdmissionEntered = new CountDownLatch(1);
            AtomicBoolean lateBlockedByClosing = new AtomicBoolean();
            AtomicBoolean deferred = new AtomicBoolean();
            AtomicInteger tailBodies = new AtomicInteger();
            AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
            AtomicReference<Thread> lateThread = new AtomicReference<>();
            pool.start();
            Thread admitted = null;
            Thread controller = null;
            try {
                pool.pauseExactTurnAndAwait(() -> {});

                admitted = Thread.ofPlatform().name("exact-admitted-root").start(() -> {
                    boolean entered = false;
                    try {
                        enterRootAdmission(pool);
                        entered = true;
                        admittedBeforeClose.countDown();
                        await(allowTailPublication);
                        Object lock = lifecycleLock(pool);
                        synchronized (lock) {
                            deferred.set(deferExactRootLocked(pool,
                                    tailBodies::incrementAndGet));
                        }
                    } catch (Throwable failure) {
                        backgroundFailure.compareAndSet(null, failure);
                    } finally {
                        if (entered) leaveRootAdmission(pool);
                    }
                });
                assertTrue(admittedBeforeClose.await(1, TimeUnit.SECONDS));

                controller = Thread.ofPlatform().name("exact-closing-controller").start(() -> {
                    try {
                        long closingDeadline = System.nanoTime()
                                + TimeUnit.SECONDS.toNanos(1);
                        while (!booleanFieldUnderLifecycleLock(pool, "exactReplayClosing")) {
                            if (System.nanoTime() >= closingDeadline) {
                                throw new AssertionError(
                                        "resume did not close exact-root replay admission");
                            }
                            Thread.onSpinWait();
                        }
                        closingObserved.countDown();
                        Thread late = Thread.ofPlatform().name("exact-late-root").start(() -> {
                            boolean entered = false;
                            try {
                                enterRootAdmission(pool);
                                entered = true;
                                lateAdmissionEntered.countDown();
                            } catch (Throwable failure) {
                                backgroundFailure.compareAndSet(null, failure);
                            } finally {
                                if (entered) leaveRootAdmission(pool);
                            }
                        });
                        lateThread.set(late);
                        lateBlockedByClosing.set(
                                !lateAdmissionEntered.await(100, TimeUnit.MILLISECONDS));
                        allowTailPublication.countDown();
                        late.join();
                    } catch (Throwable failure) {
                        backgroundFailure.compareAndSet(null, failure);
                        allowTailPublication.countDown();
                    }
                });

                pool.resumeExactTurn();
                admitted.join();
                controller.join();
                Thread late = lateThread.get();
                if (late != null) late.join();

                assertEquals(0L, closingObserved.getCount(),
                        "resume never published its closing admission cut");
                assertTrue(lateBlockedByClosing.get(),
                        "a post-close root joined the tail that was already closing");
                assertTrue(deferred.get(),
                        "the root admitted before close was not accepted into the tail");
                assertEquals(1, tailBodies.get(),
                        "the admitted tail root was lost or replayed more than once");
                assertEquals(0L, lateAdmissionEntered.getCount(),
                        "post-close admission did not reopen after exact-turn release");
                assertNull(backgroundFailure.get());
            } finally {
                allowTailPublication.countDown();
                if (pool.ownsPausedExactTurn(Thread.currentThread())) {
                    pool.resumeExactTurn();
                }
                if (admitted != null) admitted.join(Duration.ofSeconds(1));
                if (controller != null) controller.join(Duration.ofSeconds(1));
                Thread late = lateThread.get();
                if (late != null) late.join(Duration.ofSeconds(1));
                if (!pool.isTerminated()) pool.stop();
            }
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

    private static void awaitFatal(RegionWorkerPool pool, Throwable expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            try {
                pool.throwIfFailed();
            } catch (Throwable failure) {
                assertSame(expected, failure);
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("pool did not publish its fatal failure");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Queue<Runnable> rawMainSafeQueue(RegionWorkerPool pool) {
        try {
            var field = RegionWorkerPool.class.getDeclaredField("mainSafeTasks");
            field.setAccessible(true);
            return (java.util.Queue<Runnable>) field.get(pool);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Queue<Object> rawMainSyncQueue() {
        try {
            var field = RegionScheduler.class.getDeclaredField("MAIN_SYNC");
            field.setAccessible(true);
            return (java.util.Queue<Object>) field.get(null);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static Object newMainSyncTask(RegionWorkerPool.ExternalPermit permit,
                                          AtomicInteger admissionRuns,
                                          AtomicInteger completionRuns) {
        try {
            Class<?> type = Class.forName(RegionScheduler.class.getName() + "$MainSyncTask");
            var constructor = type.getDeclaredConstructor(
                    net.minecraft.world.entity.Entity.class,
                    RegionWorkerPool.ExternalPermit.class,
                    java.util.function.BooleanSupplier.class,
                    Runnable.class,
                    boolean.class,
                    boolean.class);
            constructor.setAccessible(true);
            java.util.function.BooleanSupplier admission = () -> {
                admissionRuns.incrementAndGet();
                return true;
            };
            return constructor.newInstance(null, permit, admission,
                    (Runnable) completionRuns::incrementAndGet, false, false);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static long longField(RegionWorkerPool pool, String name) {
        Object lock = lifecycleLock(pool);
        synchronized (lock) {
            try {
                var field = RegionWorkerPool.class.getDeclaredField(name);
                field.setAccessible(true);
                return field.getLong(pool);
            } catch (ReflectiveOperationException reflectionFailure) {
                throw new AssertionError(reflectionFailure);
            }
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

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Runnable reserveExternalTask(RegionWorkerPool pool, Runnable body) {
        try {
            var reserve = RegionWorkerPool.class.getDeclaredMethod("reserveExternalTask", Runnable.class);
            reserve.setAccessible(true);
            Runnable task = (Runnable) reserve.invoke(pool, body);
            if (task == null) throw new AssertionError("running pool rejected an external task reservation");
            return task;
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static void drainOrphanHelp(RegionWorkerPool pool, Region region) {
        try {
            var drain = RegionWorkerPool.class.getDeclaredMethod("drainOrphanHelp", Region.class);
            drain.setAccessible(true);
            drain.invoke(pool, region);
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

    private static boolean booleanFieldUnderLifecycleLock(RegionWorkerPool pool,
                                                           String name) {
        Object lock = lifecycleLock(pool);
        synchronized (lock) {
            try {
                var field = RegionWorkerPool.class.getDeclaredField(name);
                field.setAccessible(true);
                return field.getBoolean(pool);
            } catch (ReflectiveOperationException reflectionFailure) {
                throw new AssertionError(reflectionFailure);
            }
        }
    }

    private static void awaitNoActiveSteps(RegionWorkerPool pool) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (activeStepsUnderLifecycleLock(pool) != 0) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("worker step did not retire");
            }
            Thread.onSpinWait();
        }
    }

    private static int activeStepsUnderLifecycleLock(RegionWorkerPool pool) {
        Object lock = lifecycleLock(pool);
        synchronized (lock) {
            try {
                var field = RegionWorkerPool.class.getDeclaredField("activeSteps");
                field.setAccessible(true);
                return ((AtomicInteger) field.get(pool)).get();
            } catch (ReflectiveOperationException reflectionFailure) {
                throw new AssertionError(reflectionFailure);
            }
        }
    }

    private static void enterRootAdmission(RegionWorkerPool pool) {
        invokePrivate(pool, "enterRootAdmission", new Class<?>[0]);
    }

    private static void leaveRootAdmission(RegionWorkerPool pool) {
        invokePrivate(pool, "leaveRootAdmission", new Class<?>[0]);
    }

    private static boolean deferExactRootLocked(RegionWorkerPool pool, Runnable root) {
        return (boolean) invokePrivate(pool, "deferExactRootLocked",
                new Class<?>[]{Runnable.class}, root);
    }

    private static Object invokePrivate(RegionWorkerPool pool, String name,
                                        Class<?>[] parameterTypes, Object... arguments) {
        try {
            var method = RegionWorkerPool.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(pool, arguments);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }
}
