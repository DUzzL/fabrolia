package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact reader-drain and writer-admission tests for tracker topology changes. */
final class TrackingTopologyAdmissionGateTest {

    @Test
    void writerDrainsOldReadersAndKeepsNewReadersBehindPublishedIntent()
            throws Exception {
        TrackingTopologyAdmissionGate gate = new TrackingTopologyAdmissionGate();
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch lateReaderEntered = new CountDownLatch(1);
        Semaphore releaseWriter = new Semaphore(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean outerReadHeld = new AtomicBoolean(true);

        gate.readLock();
        Thread writer = Thread.ofPlatform().name("topology-writer").start(() -> {
            try {
                gate.writeLock();
                try {
                    writerEntered.countDown();
                    releaseWriter.acquireUninterruptibly();
                } finally {
                    gate.writeUnlock();
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        Thread lateReader = null;
        try {
            awaitWriterIntent(gate);
            assertFalse(writerEntered.await(100, TimeUnit.MILLISECONDS),
                    "writer crossed an admitted old reader");

            lateReader = Thread.ofPlatform().name("topology-late-reader").start(() -> {
                try {
                    gate.readLock();
                    try {
                        lateReaderEntered.countDown();
                    } finally {
                        gate.readUnlock();
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
            assertFalse(lateReaderEntered.await(100, TimeUnit.MILLISECONDS),
                    "reader entered after writer intent was published");

            gate.readUnlock();
            outerReadHeld.set(false);
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS),
                    "writer did not enter after the old reader drained");
            assertFalse(lateReaderEntered.await(100, TimeUnit.MILLISECONDS),
                    "new reader overlapped the held writer");

            releaseWriter.release();
            assertTrue(lateReaderEntered.await(2, TimeUnit.SECONDS),
                    "reader was not reopened by the exact writer release");
        } finally {
            if (outerReadHeld.getAndSet(false)) gate.readUnlock();
            releaseWriter.release();
            writer.join(Duration.ofSeconds(3));
            if (lateReader != null) lateReader.join(Duration.ofSeconds(3));
        }

        assertFalse(writer.isAlive());
        assertFalse(lateReader != null && lateReader.isAlive());
        assertNull(failure.get());
    }

    @Test
    void nestedReaderAlreadyInsideTheOldPhaseCannotSelfDeadlockOnWriterIntent()
            throws Exception {
        TrackingTopologyAdmissionGate gate = new TrackingTopologyAdmissionGate();
        CountDownLatch writerEntered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean readHeld = new AtomicBoolean(true);

        gate.readLock();
        Thread writer = Thread.ofPlatform().name("topology-reentrant-writer").start(() -> {
            try {
                gate.writeLock();
                try {
                    writerEntered.countDown();
                } finally {
                    gate.writeUnlock();
                }
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        try {
            awaitWriterIntent(gate);

            gate.readLock();
            assertEquals(2, gate.getReadHoldCount(),
                    "nested admission must reuse the already-counted stripe");
            gate.readUnlock();
            assertEquals(1, gate.getReadHoldCount());
            assertFalse(writerEntered.await(100, TimeUnit.MILLISECONDS),
                    "nested unlock released the outer reader stripe");

            gate.readUnlock();
            readHeld.set(false);
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS));
        } finally {
            if (readHeld.getAndSet(false)) gate.readUnlock();
            writer.join(Duration.ofSeconds(3));
        }

        assertFalse(writer.isAlive());
        assertNull(failure.get());
    }

    @Test
    void writerWaitsForTheLastOfManyIndependentReaderStripes() throws Exception {
        TrackingTopologyAdmissionGate gate = new TrackingTopologyAdmissionGate();
        int readerCount = 24;
        CountDownLatch readersEntered = new CountDownLatch(readerCount);
        List<Semaphore> releases = new ArrayList<>(readerCount);
        List<Thread> readers = new ArrayList<>(readerCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int index = 0; index < readerCount; index++) {
            Semaphore release = new Semaphore(0);
            releases.add(release);
            readers.add(Thread.ofPlatform().name("topology-stripe-" + index).start(() -> {
                try {
                    gate.readLock();
                    try {
                        readersEntered.countDown();
                        release.acquireUninterruptibly();
                    } finally {
                        gate.readUnlock();
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            }));
        }
        assertTrue(readersEntered.await(3, TimeUnit.SECONDS),
                "not every independent reader entered the open phase");

        CountDownLatch writerEntered = new CountDownLatch(1);
        Thread writer = Thread.ofPlatform().name("topology-striped-writer").start(() -> {
            try {
                gate.writeLock();
                try {
                    writerEntered.countDown();
                } finally {
                    gate.writeUnlock();
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        try {
            awaitWriterIntent(gate);
            for (int index = 0; index < readerCount - 1; index++) {
                releases.get(index).release();
            }
            for (int index = 0; index < readerCount - 1; index++) {
                readers.get(index).join(Duration.ofSeconds(3));
            }
            assertFalse(writerEntered.await(100, TimeUnit.MILLISECONDS),
                    "writer ignored the final non-zero reader stripe");

            releases.getLast().release();
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS),
                    "writer did not observe the final stripe drain");
        } finally {
            for (Semaphore release : releases) release.release();
            for (Thread reader : readers) reader.join(Duration.ofSeconds(3));
            writer.join(Duration.ofSeconds(3));
        }

        assertFalse(writer.isAlive());
        assertTrue(readers.stream().noneMatch(Thread::isAlive));
        assertNull(failure.get());
    }

    @Test
    void parkedReaderKeepsItsWakeRegistrationAcrossBackToBackWriters()
            throws Exception {
        TrackingTopologyAdmissionGate gate = new TrackingTopologyAdmissionGate();
        CountDownLatch firstWriterEntered = new CountDownLatch(1);
        CountDownLatch readerEntered = new CountDownLatch(1);
        Semaphore runWriterBurst = new Semaphore(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = Thread.ofPlatform().name("topology-writer-burst").start(() -> {
            try {
                gate.writeLock();
                firstWriterEntered.countDown();
                runWriterBurst.acquireUninterruptibly();
                for (int cycle = 0; cycle < 512; cycle++) {
                    gate.writeUnlock();
                    gate.writeLock();
                }
                gate.writeUnlock();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        assertTrue(firstWriterEntered.await(2, TimeUnit.SECONDS));

        Thread reader = Thread.ofPlatform().name("topology-burst-reader").start(() -> {
            try {
                gate.readLock();
                try {
                    readerEntered.countDown();
                } finally {
                    gate.readUnlock();
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        try {
            awaitThreadWaiting(reader);
            runWriterBurst.release();
            assertTrue(readerEntered.await(3, TimeUnit.SECONDS),
                    "reader lost its wake when a new writer closed the transient open phase");
        } finally {
            runWriterBurst.release();
            writer.join(Duration.ofSeconds(3));
            reader.join(Duration.ofSeconds(3));
        }

        assertFalse(writer.isAlive());
        assertFalse(reader.isAlive());
        assertNull(failure.get());
    }

    @Test
    void unmatchedOwnershipAndReadToWriteUpgradeFailClosed() {
        TrackingTopologyAdmissionGate gate = new TrackingTopologyAdmissionGate();

        assertThrows(IllegalStateException.class, gate::readUnlock);
        assertThrows(IllegalStateException.class, gate::writeUnlock);

        gate.readLock();
        assertThrows(IllegalStateException.class, gate::writeLock);
        assertEquals(1, gate.getReadHoldCount(),
                "a refused upgrade must retain the exact outer read ownership");
        gate.readUnlock();

        gate.writeLock();
        gate.writeLock();
        assertTrue(gate.writerIntentPublished());
        gate.writeUnlock();
        assertTrue(gate.writerIntentPublished(),
                "nested writer release must not reopen readers");
        gate.writeUnlock();
        assertFalse(gate.writerIntentPublished());
    }

    private static void awaitWriterIntent(TrackingTopologyAdmissionGate gate)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!gate.writerIntentPublished() && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue(gate.writerIntentPublished(),
                "writer did not publish its closed admission phase");
    }

    private static void awaitThreadWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.getState() != Thread.State.WAITING
                && thread.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(Thread.State.WAITING, thread.getState(),
                "reader did not register and park behind writer intent");
    }
}
