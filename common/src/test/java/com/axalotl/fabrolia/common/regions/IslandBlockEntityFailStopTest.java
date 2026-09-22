package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IslandBlockEntityFailStopTest {



    @Test
    void peerWinnerDuringTickerPreventsTheNextTickerEntry() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AssertionError winner = new AssertionError("block-entity-W");
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger nextTickerEntries = new AtomicInteger();
            AtomicReference<Throwable> surfaced = new AtomicReference<>();
            pool.start();
            try {
                Thread ticker = new Thread(() -> {
                    try {
                        pool.runSuccessBodyIfHealthy(() -> {
                            firstEntered.countDown();
                            await(releaseFirst);
                        });
                        pool.runSuccessBodyIfHealthy(nextTickerEntries::incrementAndGet);
                    } catch (Throwable thrown) {
                        surfaced.set(thrown);
                    }
                }, "fabrolia-block-entity-fail-stop-race");
                ticker.start();
                assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
                pool.publishFatalFailure(winner);
                releaseFirst.countDown();
                ticker.join(1_000L);

                assertFalse(ticker.isAlive());
                assertSame(winner, surfaced.get());
                assertEquals(0, nextTickerEntries.get());
            } finally {
                releaseFirst.countDown();
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }


    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for ticker release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
