package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail-stop behavior for entered world-tail work and its rejected successors. */
class WorldTailFailStopWiringTest {


    


    @Test
    void syntheticPortalLeaseRadiiPreserveOnlyTheRequiredTickingFootprint() {
        // 26.2 TicketStorage uses FULL(33) - radius; entity/block ticking
        // thresholds are 31/32. Keep this pure: loading ChunkLevel itself
        // initializes the complete bootstrap registry, unavailable in unit tests.
        int fullLevel = 33;
        int destination = fullLevel - CrossDimArrivals.DESTINATION_LEASE_RADIUS;
        int activeEntity = fullLevel - CrossDimArrivals.PORTAL_ACTIVE_LEASE_RADIUS;
        int pendingSource = fullLevel - CrossDimArrivals.PENDING_SOURCE_LEASE_RADIUS;

        assertTrue(destination <= 31);
        assertTrue(destination + 1 <= 31,
                "vanilla-equivalent destination tickets retain their radius-3 footprint");
        assertTrue(activeEntity <= 31,
                "the exact live portal-loader chunk must keep ticking its entity");
        assertFalse(activeEntity + 1 <= 31,
                "a synthetic loader lease must not entity-tick adjacent stasis machinery");
        assertTrue(pendingSource <= 32);
        assertFalse(pendingSource <= 31,
                "a frozen pending source owes loading, never entity simulation");
        assertTrue(pendingSource + 1 <= 33);
        assertFalse(pendingSource + 1 <= 32);
    }




    @Test
    void peerWinnerCannotBisectAlreadyEnteredBlockEventWave() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError winner = new AssertionError("block-event-wave-W");
            CountDownLatch enteredEvent = new CountDownLatch(1);
            CountDownLatch releaseEvent = new CountDownLatch(1);
            AtomicInteger eventBodies = new AtomicInteger();
            AtomicInteger broadcasts = new AtomicInteger();
            AtomicInteger laterWaveBodies = new AtomicInteger();
            AtomicReference<Throwable> surfaced = new AtomicReference<>();
            pool.start();
            try {
                Thread eventThread = new Thread(() -> {
                    try {
                        pool.runSuccessBodyIfHealthy(() -> {
                            eventBodies.incrementAndGet();
                            enteredEvent.countDown();
                            await(releaseEvent);
                            broadcasts.incrementAndGet();
                            laterWaveBodies.incrementAndGet();
                        });
                    } catch (Throwable thrown) {
                        surfaced.set(thrown);
                    }
                }, "fabrolia-block-event-wave-race");
                eventThread.start();
                assertTrue(enteredEvent.await(1, TimeUnit.SECONDS));
                pool.publishFatalFailure(winner);
                releaseEvent.countDown();
                eventThread.join(1_000L);

                assertFalse(eventThread.isAlive());
                assertSame(winner, surfaced.get());
                assertEquals(1, eventBodies.get());
                assertEquals(1, broadcasts.get());
                assertEquals(1, laterWaveBodies.get());
            } finally {
                releaseEvent.countDown();
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
    void ownWinnerRetiresEnteredBlockEventWaveWithoutReplay() {
        RegionWorkerPool pool = new RegionWorkerPool(1);
        AssertionError winner = new AssertionError("own-block-event-W");
        AtomicInteger firstBody = new AtomicInteger();
        AtomicInteger detachedSuffix = new AtomicInteger();
        AtomicInteger replayAttempts = new AtomicInteger();
        List<Runnable> detachedWave = List.of(
                () -> {
                    firstBody.incrementAndGet();
                    throw winner;
                },
                detachedSuffix::incrementAndGet);
        pool.start();
        try {
            AssertionError surfaced = assertThrows(AssertionError.class,
                    () -> pool.runSuccessBodyIfHealthy(() -> {
                        for (Runnable event : detachedWave) event.run();
                    }));

            assertSame(winner, surfaced);
            assertEquals(1, firstBody.get());
            assertEquals(0, detachedSuffix.get());
            assertSame(winner, assertThrows(AssertionError.class,
                    () -> pool.runSuccessBodyIfHealthy(
                            replayAttempts::incrementAndGet)));
            assertEquals(0, detachedSuffix.get());
            assertEquals(0, replayAttempts.get());
        } finally {
            if (!pool.isTerminated()) {
                try {
                    pool.stop();
                } catch (Throwable ignored) {
                }
            }
        }
    }








    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for race release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
