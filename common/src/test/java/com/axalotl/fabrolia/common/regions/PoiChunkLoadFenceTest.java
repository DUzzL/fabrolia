package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.utils.PoiChunkLoadFence;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #38, review D4 — deterministic repros for the {@code getOrLoad} ISE
 * race. Vanilla's loser of the {@code loadedChunks.add} race returns while the
 * winner is still parked in the disk join, then reads a null section and
 * throws. The {@link PoiChunkLoadFence} handshake is the fix: losers join a
 * per-chunk future the winner completes only after the fan-out. These tests
 * drive the production fence directly (the mixin that wires it into
 * {@code SectionStorage.unpackChunk} is pinned in
 * {@link PoiBlanketSyncRemovalTest} — mixins do not apply in unit tests).
 */
@Timeout(30)
class PoiChunkLoadFenceTest {

    private static final long KEY = 42L;

    /**
     * The exact production interleave: winner claims the chunk and parks
     * "in the disk join" (latch); the loser arrives, and pre-fix would have
     * returned immediately into a null section. Post-fix it must stay parked
     * until the winner's fan-out completes.
     */
    @Test
    void loserBlocksUntilWinnersFanoutCompletes() throws Exception {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        LongOpenHashSet loaded = new LongOpenHashSet();

        CountDownLatch winnerInside = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        AtomicBoolean fanoutDone = new AtomicBoolean();
        AtomicBoolean loserSawCompletedFanout = new AtomicBoolean();

        Thread winner = new Thread(() -> fence.runExclusively(loadLock, KEY, loaded::contains, () -> {
            synchronized (loadLock) {
                loaded.add(KEY); // vanilla adds loadedChunks inside its first loadLock block
            }
            winnerInside.countDown();
            try {
                releaseWinner.await(); // parked "in the disk join"
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            fanoutDone.set(true); // "sections are now in storage"
        }), "fence-winner");
        winner.start();
        assertTrue(winnerInside.await(5, TimeUnit.SECONDS));

        Thread loser = new Thread(() -> {
            fence.runExclusively(loadLock, KEY, loaded::contains, () -> {
                throw new AssertionError("loser must never run the unpack");
            });
            // The whole point: by the time a loser proceeds, the fan-out is done.
            loserSawCompletedFanout.set(fanoutDone.get());
        }, "fence-loser");
        loser.start();

        // The loser must be parked while the winner is mid-"disk join" — this
        // is the window where vanilla returned early and threw the ISE.
        loser.join(300);
        assertTrue(loser.isAlive(), "loser proceeded while the winner was still unpacking — the D4 window is open");

        releaseWinner.countDown();
        winner.join(5000);
        loser.join(5000);
        assertFalse(winner.isAlive());
        assertFalse(loser.isAlive());
        assertTrue(loserSawCompletedFanout.get(), "loser observed loadedChunks-present but sections-absent");
    }

    /** N racers, exactly one runs the unpack; everyone returns after it completes. */
    @Test
    void exactlyOneWinnerUnderContention() throws Exception {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        LongOpenHashSet loaded = new LongOpenHashSet();
        AtomicInteger unpacks = new AtomicInteger();
        int threads = 12;
        CyclicBarrier start = new CyclicBarrier(threads);

        Thread[] racers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            racers[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                fence.runExclusively(loadLock, KEY, loaded::contains, () -> {
                    synchronized (loadLock) {
                        loaded.add(KEY);
                    }
                    unpacks.incrementAndGet();
                });
            }, "fence-racer-" + i);
            racers[i].start();
        }
        for (Thread t : racers) {
            t.join(10_000);
            assertFalse(t.isAlive(), "racer hung");
        }
        assertEquals(1, unpacks.get(), "the unpack must run exactly once");
        // Late arrival after completion: nothing to do, no wait.
        assertFalse(fence.runExclusively(loadLock, KEY, loaded::contains,
                () -> unpacks.incrementAndGet()));
        assertEquals(1, unpacks.get());
    }

    /**
     * A winner that dies in the unpack (disk failure shape) must still release
     * its losers — a hung loser here is a new production deadlock, strictly
     * worse than the ISE this fence fixes.
     */
    @Test
    void winnerExceptionStillReleasesLosers() throws Exception {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        LongOpenHashSet loaded = new LongOpenHashSet();

        CountDownLatch winnerInside = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        Thread winner = new Thread(() -> assertThrows(IllegalStateException.class,
                () -> fence.runExclusively(loadLock, KEY, loaded::contains, () -> {
                    winnerInside.countDown();
                    try {
                        releaseWinner.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    throw new IllegalStateException("disk exploded");
                })), "fence-failing-winner");
        winner.start();
        assertTrue(winnerInside.await(5, TimeUnit.SECONDS));

        AtomicBoolean loserReturned = new AtomicBoolean();
        Thread loser = new Thread(() -> {
            fence.runExclusively(loadLock, KEY, loaded::contains,
                    () -> new AssertionError("loser must not unpack"));
            loserReturned.set(true);
        }, "fence-orphaned-loser");
        loser.start();
        loser.join(300);
        assertTrue(loser.isAlive(), "loser should be parked on the in-flight winner");

        releaseWinner.countDown();
        winner.join(5000);
        loser.join(5000);
        assertFalse(loser.isAlive(), "winner failure stranded its loser");
        assertTrue(loserReturned.get());
        // And the chunk is claimable again (loadedChunks was never marked here,
        // matching a pre-add failure): a retry becomes the new winner.
        AtomicInteger retries = new AtomicInteger();
        assertTrue(fence.runExclusively(loadLock, KEY, loaded::contains, retries::incrementAndGet));
        assertEquals(1, retries.get());
    }

    /** Already-loaded chunks short-circuit without touching the unpack. */
    @Test
    void alreadyLoadedShortCircuits() {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        assertFalse(fence.runExclusively(loadLock, KEY, k -> true,
                () -> { throw new AssertionError("must not unpack a loaded chunk"); }));
        synchronized (loadLock) {
            assertFalse(fence.isInFlight(KEY));
        }
    }
}
