package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-JDK replay of the 2026-08-25 ChunkMap/holder deadlock graph. */
class ChunkViewerLockOrderRegressionTest {

    @Test
    void holderOwnerCanSnapshotComponentViewWhileMainWaitsWithChunkMapHeld() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            Object chunkMap = new Object();
            Object holder = new Object();
            ViewerIndex<String> playerMap = new ViewerIndex<>();
            playerMap.add("viewer");

            CountDownLatch workerOwnsHolder = new CountDownLatch(1);
            CountDownLatch mainOwnsChunkMap = new CountDownLatch(1);
            CountDownLatch bothFinished = new CountDownLatch(2);
            AtomicReference<List<String>> observed = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread worker = daemon("holder-publication", () -> {
                try {
                    synchronized (holder) {
                        workerOwnsHolder.countDown();
                        await(mainOwnsChunkMap);
                        // Fixed production shape: the viewer snapshot takes only the
                        // component PlayerMap monitor. Re-entering chunkMap here would
                        // close holder -> ChunkMap against Main's ChunkMap -> holder.
                        observed.set(playerMap.snapshot());
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    bothFinished.countDown();
                }
            });

            Thread main = daemon("distance-update", () -> {
                try {
                    await(workerOwnsHolder);
                    synchronized (chunkMap) {
                        mainOwnsChunkMap.countDown();
                        synchronized (holder) {
                            // Exact C2ME edge: status update enters a holder while
                            // Fabrolia's ticket/write transaction owns ChunkMap.
                        }
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    bothFinished.countDown();
                }
            });

            worker.start();
            main.start();

            assertTrue(bothFinished.await(2, TimeUnit.SECONDS),
                    "holder publication reintroduced the holder -> ChunkMap edge");
            assertNull(failure.get());
            assertEquals(List.of("viewer"), observed.get());
        });
    }

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lock-graph rendezvous timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * PlayerMap's fixed ownership rule without any Minecraft classes: writers and the
     * detached live-view copy share only this component's intrinsic monitor.
     */
    private static final class ViewerIndex<T> {
        private final List<T> viewers = new ArrayList<>();

        synchronized void add(T viewer) {
            viewers.add(viewer);
        }

        synchronized List<T> snapshot() {
            return List.copyOf(viewers);
        }
    }
}
