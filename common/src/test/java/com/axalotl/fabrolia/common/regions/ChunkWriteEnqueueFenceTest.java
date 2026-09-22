package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The full-chunk enqueue/write ordering model using the production fence. */
class ChunkWriteEnqueueFenceTest {

    @Test
    void committedOldSnapshotAlwaysPrecedesTheNewerCorrection() throws Exception {
        ChunkWriteEnqueueFence fence = new ChunkWriteEnqueueFence();
        List<String> transport = Collections.synchronizedList(new ArrayList<>());
        ArrayDeque<Runnable> eventLoop = new ArrayDeque<>();
        CountDownLatch writerAttempted = new CountDownLatch(1);
        AtomicBoolean mutationEntered = new AtomicBoolean();

        assertTrue(fence.tryReserveEnqueue(0L));
        Thread writer = new Thread(() -> {
            writerAttempted.countDown();
            fence.markWriteStart();
            mutationEntered.set(true);
            synchronized (eventLoop) {
                eventLoop.addLast(() -> transport.add("AIR-correction"));
            }
        }, "chunk-fence-model-writer");
        writer.start();

        assertTrue(writerAttempted.await(5, TimeUnit.SECONDS));
        assertFalse(mutationEntered.get(),
                "a newer mutation must not overtake an admitted old snapshot enqueue");
        synchronized (eventLoop) {
            eventLoop.addLast(() -> transport.add("old-full-chunk"));
        }
        fence.releaseEnqueue(0L);

        writer.join(5_000L);
        assertFalse(writer.isAlive());
        for (;;) {
            Runnable task;
            synchronized (eventLoop) {
                task = eventLoop.pollFirst();
            }
            if (task == null) break;
            task.run();
        }
        assertEquals(List.of("old-full-chunk", "AIR-correction"), transport);
        assertEquals(1L, fence.generation());
    }

    @Test
    void markedWriteRejectsTheOldGeneration() {
        ChunkWriteEnqueueFence fence = new ChunkWriteEnqueueFence();
        fence.markWriteStart();

        assertFalse(fence.tryReserveEnqueue(0L));
        assertTrue(fence.tryReserveEnqueue(1L));
        fence.releaseEnqueue(1L);
    }

    @Test
    void writerNeverRetainsFenceAcrossItsArbitraryBody() throws Exception {
        ChunkWriteEnqueueFence fence = new ChunkWriteEnqueueFence();
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            fence.markWriteStart();
            bodyEntered.countDown();
            try {
                releaseBody.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "chunk-fence-model-arbitrary-body");
        writer.start();
        assertTrue(bodyEntered.await(5, TimeUnit.SECONDS));

        boolean admitted;
        try {
            admitted = fence.tryReserveEnqueue(1L);
            if (admitted) fence.releaseEnqueue(1L);
        } finally {
            releaseBody.countDown();
        }
        assertTrue(admitted,
                "block callbacks/body must execute after the writer released its fence");

        writer.join(5_000L);
        assertFalse(writer.isAlive());
    }

    @Test
    void throwingBodyStillLeavesOldSnapshotInvalidated() {
        ChunkWriteEnqueueFence fence = new ChunkWriteEnqueueFence();
        fence.markWriteStart();
        // The modeled body throws after the mark. There is deliberately no
        // writer-side rollback: a partial mutation must invalidate old data.
        assertEquals(1L, fence.generation());
        assertFalse(fence.tryReserveEnqueue(0L));
    }
}
