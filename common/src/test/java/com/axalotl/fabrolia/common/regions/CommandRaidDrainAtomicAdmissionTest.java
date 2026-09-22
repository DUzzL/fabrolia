package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Behavioral race coverage for multi-entry fail-stop drains. */
final class CommandRaidDrainAtomicAdmissionTest {



    @Test
    void peerWinnerDuringDetachedEntryStopsBeforeTheNextDetachment() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError winner = new AssertionError("command-drain-peer-W");
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger detachments = new AtomicInteger();
            AtomicInteger successorEntries = new AtomicInteger();
            AtomicReference<Throwable> surfaced = new AtomicReference<>();
            ArrayDeque<Runnable> queue = new ArrayDeque<>();
            queue.add(() -> {
                firstEntered.countDown();
                await(releaseFirst);
            });
            queue.add(successorEntries::incrementAndGet);

            pool.start();
            try {
                Thread drain = new Thread(() -> {
                    try {
                        while (pool.callSuccessBooleanIfHealthy(() -> {
                            Runnable entry = queue.poll();
                            if (entry == null) return false;
                            detachments.incrementAndGet();
                            entry.run();
                            return true;
                        })) {
                            // The terminal receipt, not a raw pre-check, owns succession.
                        }
                    } catch (Throwable thrown) {
                        surfaced.set(thrown);
                    }
                }, "fabrolia-command-drain-W-race");
                drain.start();
                assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

                pool.publishFatalFailure(winner);
                releaseFirst.countDown();
                drain.join(1_000L);

                assertFalse(drain.isAlive());
                assertSame(winner, surfaced.get(),
                        "the entered body's receipt must surface the exact peer W");
                assertEquals(1, detachments.get(),
                        "W must stop the drain before it detaches a successor obligation");
                assertEquals(0, successorEntries.get());
                assertEquals(1, queue.size(),
                        "the unentered successor remains owned by its queue, not skipped");
            } finally {
                releaseFirst.countDown();
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable expectedWinner) {
                        assertSame(winner, expectedWinner);
                    }
                }
            }
            AtomicInteger healthyBodies = new AtomicInteger();
            RegionScheduler.runSuccessBodyIfHealthy(healthyBodies::incrementAndGet);
            assertEquals(1, healthyBodies.get(),
                    "stopping an unregistered generation must not poison scheduler admission");
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out awaiting test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

}
