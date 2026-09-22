package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapItemUpdatesTest {
    @Test
    void workerPublishesWhileForeignFrameOwnsMapAndColdLoadRunsOnlyOnMain() throws Exception {
        Object map = new Object();
        Thread main = Thread.currentThread();
        CompletableFuture<Runnable> queued = new CompletableFuture<>();
        CompletableFuture<Void> workerResult = new CompletableFuture<>();
        AtomicInteger holdingStep = new AtomicInteger();
        AtomicInteger pixels = new AtomicInteger();
        Thread worker = new Thread(() -> {
            try {
                MapItemUpdates.enqueue(queued::complete, () -> {
                    // Cold SavedDataStorage lookup is Main-owned too, not only
                    // the chunk reads inside the eventual pixel transaction.
                    assertSame(main, Thread.currentThread());
                    return map;
                }, () -> {
                    holdingStep.incrementAndGet();
                    // A cold chunk request must not need this Main thread while
                    // its item-frame transaction is waiting for the map monitor.
                    assertSame(main, Thread.currentThread());
                    pixels.set(17);
                });
                workerResult.complete(null);
            } catch (Throwable failure) {
                workerResult.completeExceptionally(failure);
            }
        }, "map-inventory-owner");
        try {
            synchronized (map) {
                worker.start();
                workerResult.get(5, TimeUnit.SECONDS);
                assertEquals(0, holdingStep.get());
                assertEquals(0, pixels.get());
            }
            queued.get(5, TimeUnit.SECONDS).run();
            assertEquals(1, holdingStep.get());
            assertEquals(17, pixels.get());
        } finally {
            worker.join(Duration.ofSeconds(5));
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void frameCannotObservePartialPixelTransaction() throws Exception {
        Object map = new Object();
        int[] pixels = {0, 0};
        CountDownLatch firstPixel = new CountDownLatch(1);
        CountDownLatch finishUpdate = new CountDownLatch(1);
        CountDownLatch frameAttempt = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<int[]> framePixels = new CompletableFuture<>();
        Thread updater = new Thread(() -> {
            try {
                MapItemUpdates.serialized(map, () -> {
                    pixels[0] = 7;
                    firstPixel.countDown();
                    await(finishUpdate);
                    pixels[1] = 9;
                });
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "map-pixel-transaction");
        Thread frame = new Thread(() -> {
            frameAttempt.countDown();
            synchronized (map) {
                framePixels.complete(pixels.clone());
            }
        }, "foreign-island-frame");
        try {
            updater.start();
            assertTrue(firstPixel.await(5, TimeUnit.SECONDS));
            frame.start();
            assertTrue(frameAttempt.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (frame.getState() != Thread.State.BLOCKED
                    && !framePixels.isDone() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(framePixels.isDone(), "frame observed an incomplete pixel transaction");
            assertEquals(Thread.State.BLOCKED, frame.getState());
            finishUpdate.countDown();
            int[] snapshot = framePixels.get(5, TimeUnit.SECONDS);
            assertEquals(7, snapshot[0]);
            assertEquals(9, snapshot[1]);
        } finally {
            finishUpdate.countDown();
            updater.join(Duration.ofSeconds(5));
            frame.join(Duration.ofSeconds(5));
            assertFalse(updater.isAlive());
            assertFalse(frame.isAlive());
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test
    void rejectedPublicationNeverStartsMapMutation() {
        RuntimeException rejection = new RuntimeException("queue rejected");
        AtomicInteger mutations = new AtomicInteger();
        assertSame(rejection, assertThrows(RuntimeException.class, () ->
                MapItemUpdates.enqueue(task -> { throw rejection; }, Object::new,
                        mutations::incrementAndGet)));
        assertEquals(0, mutations.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
