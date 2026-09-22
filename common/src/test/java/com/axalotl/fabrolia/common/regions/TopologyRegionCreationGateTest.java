package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic map-generation races for topology/domain rebuilds. */
final class TopologyRegionCreationGateTest {

    @Test
    void creatorAlreadyInsideReadPublishesBeforeWriterSnapshot() throws Exception {
        ThreadedRegionizer regionizer = regionizer();
        EpochDomain domain = new EpochDomain();
        domain.epoch = 8L;
        long key = 0x0000_0007_0000_0003L;
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch writerEntered = new CountDownLatch(1);
        AtomicReference<List<Region>> captured = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // Pause the creator after admission. regionForGridKey's nested READ is
        // reentrant and its map publication remains in this old generation.
        regionizer.lockRegionCreationRead();
        Thread writer = Thread.ofPlatform().name("topology-create-cut").start(() -> {
            try {
                writerStarted.countDown();
                captured.set(regionizer.snapshotAtEntityDispatchCut(domain));
                writerEntered.countDown();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        try {
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS));
            assertFalse(writerEntered.await(100, TimeUnit.MILLISECONDS),
                    "topology writer crossed a creator admitted to the old map generation");

            Region created = regionizer.regionForGridKey(key);
            assertSame(created, regionizer.peekByGridKey(key));
        } finally {
            regionizer.unlockRegionCreationRead();
            writer.join(Duration.ofSeconds(3));
        }

        assertFalse(writer.isAlive());
        assertNull(failure.get());
        assertTrue(writerEntered.await(100, TimeUnit.MILLISECONDS));
        assertEquals(1, captured.get().size());
        assertEquals(key, captured.get().getFirst().gridKey);
        assertEquals(8L, domain.entityDispatchCutEpoch);
    }

    @Test
    void creatorQueuedBehindStableWriterStartsInSuccessorGeneration()
            throws Exception {
        ThreadedRegionizer regionizer = regionizer();
        long oldKey = 11L;
        long successorKey = 12L;
        regionizer.regionForGridKey(oldKey);

        CountDownLatch writerEntered = new CountDownLatch(1);
        Semaphore publishSuccessor = new Semaphore(0);
        CountDownLatch creatorStarted = new CountDownLatch(1);
        CountDownLatch creatorPublished = new CountDownLatch(1);
        AtomicReference<List<Region>> stableSnapshot = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = Thread.ofPlatform().name("topology-stable-writer").start(() -> {
            try {
                regionizer.lockTopologyCreationWrite();
                try {
                    stableSnapshot.set(regionizer.snapshot());
                    writerEntered.countDown();
                    publishSuccessor.acquireUninterruptibly();
                } finally {
                    regionizer.unlockTopologyCreationWrite();
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        assertTrue(writerEntered.await(2, TimeUnit.SECONDS));

        Thread creator = Thread.ofPlatform().name("topology-successor-creator").start(() -> {
            try {
                creatorStarted.countDown();
                regionizer.regionForGridKey(successorKey);
                creatorPublished.countDown();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        try {
            assertTrue(creatorStarted.await(2, TimeUnit.SECONDS));
            assertFalse(creatorPublished.await(100, TimeUnit.MILLISECONDS),
                    "late creator crossed the stable cut before successor publication");
            assertEquals(1, stableSnapshot.get().size());
            assertNull(regionizer.peekByGridKey(successorKey));

            publishSuccessor.release();
            assertTrue(creatorPublished.await(2, TimeUnit.SECONDS));
        } finally {
            publishSuccessor.release();
            writer.join(Duration.ofSeconds(3));
            creator.join(Duration.ofSeconds(3));
        }

        assertFalse(writer.isAlive());
        assertFalse(creator.isAlive());
        assertNull(failure.get());
        assertEquals(2, regionizer.snapshot().size());
    }

    @Test
    void callbackCreatedRegionIsCapturedByNextPassExactlyOnce() {
        ThreadedRegionizer regionizer = regionizer();
        long originalKey = 21L;
        long callbackKey = 22L;
        Region original = regionizer.regionForGridKey(originalKey);
        Set<Region> captured = Collections.newSetFromMap(new IdentityHashMap<>());

        assertEquals(1, captureNewMapMembers(regionizer, captured));
        assertTrue(captured.contains(original));

        AtomicInteger callbackBodies = new AtomicInteger();
        // Models the finite pre-cut FIFO drain. It must execute outside WRITE,
        // otherwise this lazy destination lookup self-deadlocks.
        callbackBodies.incrementAndGet();
        Region bornFromCallback = regionizer.regionForGridKey(callbackKey);

        assertEquals(1, captureNewMapMembers(regionizer, captured));
        assertTrue(captured.contains(bornFromCallback));
        assertEquals(0, captureNewMapMembers(regionizer, captured));
        assertEquals(1, callbackBodies.get(),
                "fixed-point rescan replayed a previously settled callback");
    }

    @Test
    void postReleaseTailCreationCompletesBeforeNextWriterCapture()
            throws Exception {
        ThreadedRegionizer regionizer = regionizer();
        Region claimed = regionizer.regionForGridKey(31L);
        EpochDomain oldDomain = new EpochDomain();
        oldDomain.epoch = 7L;
        claimed.domain = oldDomain;
        assertTrue(claimed.tryClaim(
                Thread.currentThread(), oldDomain, oldDomain.epoch));

        // Initial topology pass closes the exact claim transaction, then drops
        // WRITE before waiting for its release-successor tail.
        regionizer.lockTopologyCreationWrite();
        try {
            synchronized (claimed.admissionLock) {
                assertTrue(claimed.tryCloseClaimTransactionsForDomainRebase());
            }
        } finally {
            regionizer.unlockTopologyCreationWrite();
        }

        CountDownLatch waiterEntered = new CountDownLatch(1);
        CountDownLatch waiterDone = new CountDownLatch(1);
        CountDownLatch tailCreatedRegion = new CountDownLatch(1);
        AtomicReference<Region> bornInTail = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().name("topology-tail-waiter").start(() -> {
            try {
                waiterEntered.countDown();
                RegionizedServer.awaitClaimTransactionsForDomainRebase(claimed);
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            } finally {
                waiterDone.countDown();
            }
        });
        assertTrue(waiterEntered.await(2, TimeUnit.SECONDS));

        // RegionWorkerPool releases currentWorker before its done/barrier/ready
        // receipt tail. Null is not the topology terminal: the transaction is.
        claimed.release(Thread.currentThread());
        assertNull(claimed.currentWorker());
        assertEquals(1, claimed.activeClaimTransactions());
        assertFalse(waiterDone.await(100, TimeUnit.MILLISECONDS),
                "topology await overtook the post-release completion tail");

        Thread tail = Thread.ofPlatform().name("topology-release-tail").start(() -> {
            try {
                bornInTail.set(regionizer.regionForGridKey(32L));
                tailCreatedRegion.countDown();
                claimed.finishClaimTransaction();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        try {
            assertTrue(tailCreatedRegion.await(2, TimeUnit.SECONDS),
                    "await retained WRITE and deadlocked tail-side Region creation");
            assertTrue(waiterDone.await(2, TimeUnit.SECONDS));
        } finally {
            tail.join(Duration.ofSeconds(3));
            waiter.join(Duration.ofSeconds(3));
        }

        Set<Region> captured = Collections.newSetFromMap(new IdentityHashMap<>());
        captured.add(claimed);
        assertEquals(1, captureNewMapMembers(regionizer, captured));
        assertTrue(captured.contains(bornInTail.get()),
                "next fixed-point writer missed the Region published by the old tail");
        synchronized (claimed.admissionLock) {
            claimed.reopenClaimTransactionsAfterDomainRebase();
        }
        assertFalse(tail.isAlive());
        assertFalse(waiter.isAlive());
        assertNull(failure.get());
    }

    private static int captureNewMapMembers(
            ThreadedRegionizer regionizer, Set<Region> captured) {
        int before = captured.size();
        regionizer.lockTopologyCreationWrite();
        try {
            captured.addAll(regionizer.snapshot());
        } finally {
            regionizer.unlockTopologyCreationWrite();
        }
        return captured.size() - before;
    }

    private static ThreadedRegionizer regionizer() {
        return new ThreadedRegionizer(null, new RegionGrid(RegionGrid.DEFAULT_SHIFT));
    }
}
