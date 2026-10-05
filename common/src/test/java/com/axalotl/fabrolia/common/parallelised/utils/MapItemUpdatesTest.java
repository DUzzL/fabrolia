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

    /**
     * The island-local venue is sound only if the ownership rectangle covers
     * every chunk vanilla's MapItem.update pixel loop reads (26.3 bytecode:
     * columns [px-range+1, px+range) ∩ [0,128), rows [pz-range-1, pz+range) ∩
     * [-1,128), each pixel a scale×scale block square). Tight, so a holder at
     * an island edge is not needlessly sent to the fence.
     */
    @Test
    void pixelChunkBoundsAreTheBoundingBoxOfVanillaReads() {
        java.util.Random random = new java.util.Random(26_3L);
        for (int scaleShift = 0; scaleShift <= 4; scaleShift++) {
            for (boolean ceiling : new boolean[] {false, true}) {
                for (int trial = 0; trial < 400; trial++) {
                    int scale = 1 << scaleShift;
                    int centerX = random.nextInt(64_001) - 32_000;
                    int centerZ = random.nextInt(64_001) - 32_000;
                    // Holders inside, at the edge of and far outside the map.
                    double x = centerX + (random.nextDouble() * 2 - 1) * 200 * scale;
                    double z = centerZ + (random.nextDouble() * 2 - 1) * 200 * scale;
                    int[] expected = vanillaReadBounds(scaleShift, centerX, centerZ, x, z, ceiling);
                    int[] actual = MapItemUpdates.pixelChunkBounds(
                            scaleShift, centerX, centerZ, x, z, ceiling);
                    if (expected == null) {
                        assertEquals(null, actual, "no pixel read at " + x + "," + z);
                    } else {
                        assertTrue(java.util.Arrays.equals(expected, actual),
                                "scale " + scaleShift + " ceiling " + ceiling + " at " + x + "," + z
                                        + ": vanilla " + java.util.Arrays.toString(expected)
                                        + " vs " + java.util.Arrays.toString(actual));
                    }
                }
            }
        }
    }

    private static int[] vanillaReadBounds(int scaleShift, int centerX, int centerZ,
                                            double x, double z, boolean ceiling) {
        int scale = 1 << scaleShift;
        int px = net.minecraft.util.Mth.floor(x - centerX) / scale + 64;
        int pz = net.minecraft.util.Mth.floor(z - centerZ) / scale + 64;
        int range = 128 / scale;
        if (ceiling) range /= 2;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int pixelX = px - range + 1; pixelX < px + range; pixelX++) {
            for (int pixelZ = pz - range - 1; pixelZ < pz + range; pixelZ++) {
                if (pixelX < 0 || pixelZ < -1 || pixelX >= 128 || pixelZ >= 128) continue;
                int worldX = (centerX / scale + pixelX - 64) * scale;
                int worldZ = (centerZ / scale + pixelZ - 64) * scale;
                for (int dx = 0; dx < scale; dx++) {
                    for (int dz = 0; dz < scale; dz++) {
                        minX = Math.min(minX, (worldX + dx) >> 4);
                        maxX = Math.max(maxX, (worldX + dx) >> 4);
                        minZ = Math.min(minZ, (worldZ + dz) >> 4);
                        maxZ = Math.max(maxZ, (worldZ + dz) >> 4);
                    }
                }
            }
        }
        return minX == Integer.MAX_VALUE ? null : new int[] {minX, minZ, maxX, maxZ};
    }

    /**
     * While an owned pixel pass runs (map monitor held), a chunk-cache miss in
     * its rectangle is answered from the capture instead of waiting on Main;
     * outside the rectangle, another level, or after the pass, nothing is
     * answered.
     */
    @Test
    void ownedPassAnswersOnlyItsOwnCapturedChunksWhileItRuns() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var level = (net.minecraft.server.level.ServerLevel)
                unsafe.allocateInstance(net.minecraft.server.level.ServerLevel.class);
        var other = (net.minecraft.server.level.ServerLevel)
                unsafe.allocateInstance(net.minecraft.server.level.ServerLevel.class);
        var data = (net.minecraft.world.level.saveddata.maps.MapItemSavedData)
                unsafe.allocateInstance(net.minecraft.world.level.saveddata.maps.MapItemSavedData.class);
        // Rectangle x in [-2, 0], z in [5, 6], row-major.
        var chunks = new net.minecraft.world.level.chunk.LevelChunk[6];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = (net.minecraft.world.level.chunk.LevelChunk)
                    unsafe.allocateInstance(net.minecraft.world.level.chunk.LevelChunk.class);
        }
        var pass = new MapItemUpdates.OwnedPass(level, data, new int[] {-2, 5, 0, 6}, chunks);
        assertEquals(null, MapItemUpdates.ownedPassChunk(level, -2, 5));
        AtomicInteger ran = new AtomicInteger();
        MapItemUpdates.runOwned(pass, () -> {
            assertTrue(Thread.holdsLock(data));
            assertSame(chunks[0], MapItemUpdates.ownedPassChunk(level, -2, 5));
            assertSame(chunks[2], MapItemUpdates.ownedPassChunk(level, 0, 5));
            assertSame(chunks[3], MapItemUpdates.ownedPassChunk(level, -2, 6));
            assertSame(chunks[5], MapItemUpdates.ownedPassChunk(level, 0, 6));
            assertEquals(null, MapItemUpdates.ownedPassChunk(level, 1, 5));
            assertEquals(null, MapItemUpdates.ownedPassChunk(level, -2, 7));
            assertEquals(null, MapItemUpdates.ownedPassChunk(other, -2, 5));
            ran.incrementAndGet();
        });
        assertEquals(1, ran.get());
        assertEquals(null, MapItemUpdates.ownedPassChunk(level, -2, 5));
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
