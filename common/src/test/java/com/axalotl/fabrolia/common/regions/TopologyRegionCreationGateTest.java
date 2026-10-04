package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void islandCutsOfDifferentDomainsDoNotWaitForEachOther() throws Exception {
        EpochDomain a = new EpochDomain();
        EpochDomain b = new EpochDomain();
        long keyA = 41L;
        long keyB = 42L;
        ThreadedRegionizer regionizer = islandRegionizer(Map.of(keyA, a, keyB, b));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch otherSideDone = new CountDownLatch(1);
        AtomicReference<Region> createdForB = new AtomicReference<>();

        regionizer.lockDomainCreationCut(a);
        try {
            Thread other = Thread.ofPlatform().name("other-island").start(() -> {
                try {
                    // B's creator and B's own cut are not members of A's cut.
                    createdForB.set(regionizer.regionForGridKey(keyB));
                    regionizer.lockDomainCreationCut(b);
                    regionizer.unlockDomainCreationCut(b);
                    otherSideDone.countDown();
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
            assertTrue(otherSideDone.await(2, TimeUnit.SECONDS),
                    "another island's creator or cut waited for this island's cut");
            other.join(Duration.ofSeconds(3));
        } finally {
            regionizer.unlockDomainCreationCut(a);
        }
        assertNull(failure.get());
        assertSame(b, createdForB.get().domain);
        assertNull(regionizer.peekByGridKey(keyA));
    }

    @Test
    void domainCutLinearizesItsOwnCreatorsAtExactlyOneSide() throws Exception {
        EpochDomain a = new EpochDomain();
        a.epoch = 9L;
        a.entityDispatchCutEpoch = 8L;
        long early = 51L;
        long late = 52L;
        ThreadedRegionizer regionizer = islandRegionizer(Map.of(early, a, late, a));
        CountDownLatch creatorAdmitted = new CountDownLatch(1);
        CountDownLatch cutQueued = new CountDownLatch(1);
        CountDownLatch cutDone = new CountDownLatch(1);
        AtomicReference<Region> earlyRegion = new AtomicReference<>();
        AtomicReference<List<Region>> members = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // An already-admitted creator (holding the domain READ) publishes
        // before the cut; its reentrant creation is not blocked by the queued cut.
        Thread creator = Thread.ofPlatform().name("admitted-creator").start(() -> {
            a.regionCreationGate.readLock().lock();
            try {
                creatorAdmitted.countDown();
                assertTrue(cutQueued.await(2, TimeUnit.SECONDS));
                earlyRegion.set(regionizer.regionForGridKey(early));
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            } finally {
                a.regionCreationGate.readLock().unlock();
            }
        });
        assertTrue(creatorAdmitted.await(2, TimeUnit.SECONDS));
        Thread cut = Thread.ofPlatform().name("island-cut").start(() -> {
            try {
                cutQueued.countDown();
                regionizer.lockDomainCreationCut(a);
                try {
                    Region r = regionizer.peekByGridKey(early);
                    members.set(r == null ? List.of() : List.of(r));
                    a.entityDispatchCutEpoch = a.epoch;
                } finally {
                    regionizer.unlockDomainCreationCut(a);
                }
                cutDone.countDown();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        assertTrue(cutDone.await(3, TimeUnit.SECONDS));
        creator.join(Duration.ofSeconds(3));
        cut.join(Duration.ofSeconds(3));
        assertNull(failure.get());

        assertEquals(List.of(earlyRegion.get()), members.get(),
                "the admitted creator's region must be inside the cut");
        assertEquals(8L, earlyRegion.get().doneEpoch, "pre-cut creation owes epoch E");
        Region lateRegion = regionizer.regionForGridKey(late);
        assertEquals(9L, lateRegion.doneEpoch, "post-cut creation cannot owe the armed barrier");
        assertSame(a, lateRegion.domain);
    }

    @Test
    void queuedDomainCutBlocksNewCreatorsOfThatDomainOnly() throws Exception {
        EpochDomain a = new EpochDomain();
        long keyA = 61L;
        long gapKey = 62L;
        ThreadedRegionizer regionizer = islandRegionizer(Map.of(keyA, a));
        CountDownLatch creatorDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        regionizer.lockDomainCreationCut(a);
        Thread creator;
        try {
            // A gap cell resolves to the global domain: never behind an island cut.
            Region gap = regionizer.regionForGridKey(gapKey);
            assertSame(regionizer.owner.globalDomain, gap.domain);
            creator = Thread.ofPlatform().name("late-creator").start(() -> {
                try {
                    regionizer.regionForGridKey(keyA);
                    creatorDone.countDown();
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
            assertFalse(creatorDone.await(100, TimeUnit.MILLISECONDS),
                    "a creator of the cut's own domain crossed the held cut");
            assertNull(regionizer.peekByGridKey(keyA));
        } finally {
            regionizer.unlockDomainCreationCut(a);
        }
        assertTrue(creatorDone.await(2, TimeUnit.SECONDS));
        creator.join(Duration.ofSeconds(3));
        assertNull(failure.get());
        assertSame(a, regionizer.peekByGridKey(keyA).domain);
    }

    private static ThreadedRegionizer islandRegionizer(Map<Long, EpochDomain> cells) throws Exception {
        java.lang.reflect.Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        RegionizedServer runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<EpochDomain> snapshot =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        cells.forEach(snapshot::put);
        set(runtime, "globalDomain", new EpochDomain());
        set(runtime, "cellDomainSnapshot", snapshot);
        runtime.asyncActive = true;
        ThreadedRegionizer regionizer = regionizer();
        regionizer.owner = runtime;
        return regionizer;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = RegionizedServer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
