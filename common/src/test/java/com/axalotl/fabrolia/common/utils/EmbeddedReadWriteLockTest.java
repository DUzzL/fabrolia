package com.axalotl.fabrolia.common.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedReadWriteLockTest {

    /** Stand-in for the container the mixin adds these fields to. */
    private static final class Guarded implements EmbeddedReadWriteLock.State {
        int state;
        Thread writer;

        @Override public int fabrolia$lockState() { return state; }
        @Override public void fabrolia$lockState(int s) { state = s; }
        @Override public Thread fabrolia$lockWriter() { return writer; }
        @Override public void fabrolia$lockWriter(Thread w) { writer = w; }
    }

    private static Thread start(Runnable body) {
        Thread t = new Thread(body);
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void writerWaitsForEveryReaderAndReadersShare() throws Exception {
        Guarded g = new Guarded();
        EmbeddedReadWriteLock.readLock(g);
        CountDownLatch otherReading = new CountDownLatch(1);
        CountDownLatch releaseOther = new CountDownLatch(1);
        Thread reader = start(() -> {
            EmbeddedReadWriteLock.readLock(g);
            otherReading.countDown();
            try { releaseOther.await(); } catch (InterruptedException ignored) { }
            EmbeddedReadWriteLock.readUnlock(g);
        });
        assertTrue(otherReading.await(5, TimeUnit.SECONDS), "a second reader must not wait for the first");

        AtomicBoolean written = new AtomicBoolean();
        Thread writer = start(() -> {
            EmbeddedReadWriteLock.writeLock(g);
            written.set(true);
            EmbeddedReadWriteLock.writeUnlock(g);
        });
        Thread.sleep(50);
        EmbeddedReadWriteLock.readUnlock(g);
        Thread.sleep(50);
        assertFalse(written.get(), "writer entered while a reader still held the lock");
        releaseOther.countDown();
        writer.join(5000);
        reader.join(5000);
        assertTrue(written.get());
        assertEquals(0, g.state);
        assertNull(g.writer);
    }

    @Test
    void readerWaitsForAHoldingWriter() throws Exception {
        Guarded g = new Guarded();
        EmbeddedReadWriteLock.writeLock(g);
        AtomicBoolean read = new AtomicBoolean();
        Thread reader = start(() -> {
            EmbeddedReadWriteLock.readLock(g);
            read.set(true);
            EmbeddedReadWriteLock.readUnlock(g);
        });
        Thread.sleep(50);
        assertFalse(read.get(), "reader entered while the writer held the lock");
        EmbeddedReadWriteLock.writeUnlock(g);
        reader.join(5000);
        assertTrue(read.get());
    }

    @Test
    void nestedReadSucceedsWhileAWriterWaits() throws Exception {
        // Chunk-packet serialization: read-lock every section, then each
        // section's write/getSerializedSize read-locks again.
        Guarded g = new Guarded();
        EmbeddedReadWriteLock.readLock(g);
        Thread writer = start(() -> {
            EmbeddedReadWriteLock.writeLock(g);
            EmbeddedReadWriteLock.writeUnlock(g);
        });
        Thread.sleep(50);
        assertTrue(writer.isAlive(), "the writer must be waiting for the outer read");
        EmbeddedReadWriteLock.readLock(g);   // would park forever behind the writer if reads queued
        EmbeddedReadWriteLock.readUnlock(g);
        assertTrue(writer.isAlive(), "the writer must still wait for the outer read");
        EmbeddedReadWriteLock.readUnlock(g);
        writer.join(5000);
        assertFalse(writer.isAlive());
    }

    @Test
    void writeIsReentrantAndItsHolderMayRead() throws Exception {
        Guarded g = new Guarded();
        EmbeddedReadWriteLock.writeLock(g);
        EmbeddedReadWriteLock.writeLock(g);
        EmbeddedReadWriteLock.readLock(g);
        EmbeddedReadWriteLock.writeUnlock(g);
        EmbeddedReadWriteLock.writeUnlock(g);

        AtomicBoolean read = new AtomicBoolean();
        Thread reader = start(() -> {
            EmbeddedReadWriteLock.readLock(g);
            read.set(true);
            EmbeddedReadWriteLock.readUnlock(g);
        });
        Thread.sleep(50);
        assertFalse(read.get(), "a downgraded read keeps the write hold until released");
        EmbeddedReadWriteLock.readUnlock(g);
        reader.join(5000);
        assertTrue(read.get());
        assertNull(g.writer);
        assertEquals(0, g.state);
    }

    @Test
    void unlockWithoutHoldIsRejected() {
        Guarded g = new Guarded();
        assertThrows(IllegalMonitorStateException.class, () -> EmbeddedReadWriteLock.readUnlock(g));
        assertThrows(IllegalMonitorStateException.class, () -> EmbeddedReadWriteLock.writeUnlock(g));
    }

    @Test
    void interruptWhileWaitingIsRestoredAfterAcquiring() throws Exception {
        Guarded g = new Guarded();
        EmbeddedReadWriteLock.writeLock(g);
        AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
        Thread reader = start(() -> {
            EmbeddedReadWriteLock.readLock(g);
            interruptedAfter.set(Thread.currentThread().isInterrupted());
            EmbeddedReadWriteLock.readUnlock(g);
        });
        Thread.sleep(50);
        reader.interrupt();
        Thread.sleep(50);
        assertNull(interruptedAfter.get(), "an interrupt must not abort the acquisition");
        EmbeddedReadWriteLock.writeUnlock(g);
        reader.join(5000);
        assertEquals(Boolean.TRUE, interruptedAfter.get());
    }

    @Test
    void writersAreMutuallyExclusiveUnderContention() throws Exception {
        Guarded g = new Guarded();
        int threads = 8, rounds = 20_000;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        int[] counter = {0};
        CountDownLatch go = new CountDownLatch(1);
        Thread[] all = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            boolean writerThread = (t & 1) == 0;
            all[t] = start(() -> {
                try { go.await(); } catch (InterruptedException ignored) { }
                for (int i = 0; i < rounds; i++) {
                    if (writerThread) {
                        EmbeddedReadWriteLock.writeLock(g);
                        if (inside.incrementAndGet() != 1) violations.incrementAndGet();
                        counter[0]++;
                        inside.decrementAndGet();
                        EmbeddedReadWriteLock.writeUnlock(g);
                    } else {
                        EmbeddedReadWriteLock.readLock(g);
                        if (inside.get() != 0) violations.incrementAndGet();
                        EmbeddedReadWriteLock.readUnlock(g);
                    }
                }
            });
        }
        go.countDown();
        for (Thread t : all) t.join(60_000);
        assertEquals(0, violations.get());
        assertEquals(threads / 2 * rounds, counter[0]);
        assertEquals(0, g.state);
        assertNull(g.writer);
    }
}
