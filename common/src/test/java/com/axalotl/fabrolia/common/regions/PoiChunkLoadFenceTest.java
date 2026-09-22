package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.utils.PoiChunkLoadFence;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #38, review D4 — deterministic repros for the {@code getOrLoad} ISE
 * race. Vanilla's loser of the {@code loadedChunks.add} race returns while the
 * winner is still parked in the disk join, then reads a null section and
 * throws. The {@link PoiChunkLoadFence} handshake makes losers join the
 * winner. These tests drive the production fence directly, including the
 * result-acquisition lease that outlives the unpack handshake.
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
    void winnerExceptionPoisonsLosersWithExactFailureAndNeverRetries() throws Exception {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        LongOpenHashSet loaded = new LongOpenHashSet();

        CountDownLatch winnerInside = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        IllegalStateException primary = new IllegalStateException("disk exploded");
        AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();
        AtomicInteger bodyEntries = new AtomicInteger();

        Thread winner = new Thread(() -> {
            try {
                fence.runExclusively(loadLock, KEY, loaded::contains, () -> {
                    bodyEntries.incrementAndGet();
                    winnerInside.countDown();
                    try {
                        releaseWinner.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    throw primary;
                });
            } catch (Throwable thrown) {
                winnerFailure.set(thrown);
            }
        }, "fence-failing-winner");
        winner.start();
        assertTrue(winnerInside.await(5, TimeUnit.SECONDS));

        Thread loser = new Thread(() -> {
            try {
                fence.runExclusively(loadLock, KEY, loaded::contains,
                        () -> { throw new AssertionError("loser must not unpack"); });
            } catch (Throwable thrown) {
                loserFailure.set(thrown);
            }
        }, "fence-orphaned-loser");
        loser.start();
        loser.join(300);
        assertTrue(loser.isAlive(), "loser should be parked on the in-flight winner");

        releaseWinner.countDown();
        winner.join(5000);
        loser.join(5000);
        assertFalse(loser.isAlive(), "winner failure stranded its loser");
        assertSame(primary, winnerFailure.get());
        assertSame(primary, loserFailure.get());
        assertEquals(1, bodyEntries.get());

        Throwable lateFailure = assertThrows(Throwable.class,
                () -> fence.runExclusively(loadLock, KEY, loaded::contains,
                        bodyEntries::incrementAndGet));
        assertSame(primary, lateFailure);
        assertEquals(1, bodyEntries.get(), "a failed vanilla unpack must never be retried");
    }

    @Test
    void peerWinnerBetweenVanillaBodyAndReceiptPoisonsTheExactAttempt()
            throws Exception {
        Field pool = RegionScheduler.class.getDeclaredField("pool");
        Field orphanFatal = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        pool.setAccessible(true);
        orphanFatal.setAccessible(true);
        Object previousPool = pool.get(null);
        Object previousFatal = orphanFatal.get(null);
        AssertionError winner = new AssertionError("peer generation W");
        AtomicInteger bodies = new AtomicInteger();
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        try {
            pool.set(null, null);
            orphanFatal.set(null, null);
            Throwable escaped = assertThrows(Throwable.class,
                    () -> fence.runExclusively(loadLock, KEY, key -> false, () -> {
                        bodies.incrementAndGet();
                        assertSame(winner,
                                RegionScheduler.publishFatalAndSelectWinner(winner));
                    }));
            assertSame(winner, escaped,
                    "peer W must beat POI token removal and normal waiter notification");

            Throwable late = assertThrows(Throwable.class,
                    () -> fence.runExclusively(loadLock, KEY, key -> false,
                            bodies::incrementAndGet));
            assertSame(winner, late);
            assertEquals(1, bodies.get(), "the poisoned vanilla load must not be retried");
        } finally {
            pool.set(null, previousPool);
            orphanFatal.set(null, previousFatal);
        }
    }

    @Test
    void successReceiptTakesLoadLockBeforeLifecycleCut() throws Exception {
        Field pool = RegionScheduler.class.getDeclaredField("pool");
        Field orphanFatal = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        Field lifecycle = RegionScheduler.class.getDeclaredField("LIFECYCLE_LOCK");
        pool.setAccessible(true);
        orphanFatal.setAccessible(true);
        lifecycle.setAccessible(true);
        Object previousPool = pool.get(null);
        Object previousFatal = orphanFatal.get(null);
        Object lifecycleLock = lifecycle.get(null);

        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        CountDownLatch bodyReturned = new CountDownLatch(1);
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch probeAcquiredLoadLock = new CountDownLatch(1);
        AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
        Thread winner = null;
        Thread probe = null;
        try {
            pool.set(null, null);
            orphanFatal.set(null, null);
            synchronized (lifecycleLock) {
                winner = new Thread(() -> {
                    try {
                        fence.runExclusively(loadLock, KEY, key -> false,
                                bodyReturned::countDown);
                    } catch (Throwable thrown) {
                        winnerFailure.set(thrown);
                    }
                }, "poi-lock-order-winner");
                winner.start();
                assertTrue(bodyReturned.await(5, TimeUnit.SECONDS));

                // Both the correct and the old implementation now block on the
                // lifecycle monitor held by this test.  Only the correct order
                // has already acquired loadLock before reaching that cut.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (winner.isAlive() && winner.getState() != Thread.State.BLOCKED
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(Thread.State.BLOCKED, winner.getState(),
                        "POI winner did not reach the held lifecycle cut");

                probe = new Thread(() -> {
                    probeStarted.countDown();
                    synchronized (loadLock) {
                        probeAcquiredLoadLock.countDown();
                    }
                }, "poi-lock-order-probe");
                probe.start();
                assertTrue(probeStarted.await(5, TimeUnit.SECONDS));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (probeAcquiredLoadLock.getCount() != 0
                        && probe.getState() != Thread.State.BLOCKED
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(Thread.State.BLOCKED, probe.getState(),
                        "lifecycle cut was entered before acquiring loadLock");
                assertEquals(1L, probeAcquiredLoadLock.getCount());
            }

            winner.join(5_000);
            probe.join(5_000);
            assertFalse(winner.isAlive(), "POI winner remained blocked after lifecycle release");
            assertFalse(probe.isAlive(), "loadLock probe remained blocked after success commit");
            assertTrue(probeAcquiredLoadLock.await(1, TimeUnit.SECONDS));
            assertNull(winnerFailure.get());
        } finally {
            if (winner != null) winner.join(5_000);
            if (probe != null) probe.join(5_000);
            pool.set(null, previousPool);
            orphanFatal.set(null, previousFatal);
        }
    }

    @Test
    void coldResultRemainsPresentAfterUnpackUntilFinalGetter() {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        AtomicReference<Optional<String>> section = new AtomicReference<>();
        AtomicInteger unloads = new AtomicInteger();

        Optional<String> result = fence.acquire(loadLock, KEY, () -> {
            fence.runExclusively(loadLock, KEY, ignored -> false,
                    () -> section.set(Optional.of("loaded POI")));
            // Exact race: unpack's completion receipt has ended, but getOrLoad
            // has not executed the final required storage getter.
            fence.unload(loadLock, KEY, ignored -> false, () -> {
                unloads.incrementAndGet();
                section.set(null);
            });
            fence.drainUnloads(loadLock, ignored -> false);
            return java.util.Objects.requireNonNull(section.get(), "successful section disappeared");
        });

        assertEquals(Optional.of("loaded POI"), result);
        assertEquals(0, unloads.get());
        fence.drainUnloads(loadLock, ignored -> false);
        assertNull(section.get());
        assertEquals(1, unloads.get(), "a one-shot downgrade must not be discarded");
        fence.drainUnloads(loadLock, ignored -> false);
        assertEquals(1, unloads.get(), "unload must execute exactly once");
    }

    @Test
    void hotNestedQueryKeepsMapperAndLazyCaptureAliveWithoutBlockingMain() throws Exception {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        AtomicReference<Optional<String>> section = new AtomicReference<>(Optional.of("live POI"));
        CountDownLatch readFlags = new CountDownLatch(1);
        CountDownLatch mainReturned = new CountDownLatch(1);
        AtomicReference<Throwable> mainFailure = new AtomicReference<>();
        Thread main = new Thread(() -> {
            try {
                assertTrue(readFlags.await(5, TimeUnit.SECONDS));
                fence.unload(loadLock, KEY, ignored -> false, () -> section.set(null));
                fence.drainUnloads(loadLock, ignored -> false);
            } catch (Throwable failure) {
                mainFailure.set(failure);
            } finally {
                mainReturned.countDown();
            }
        });
        main.start();
        try {
            List<String> captured = fence.acquire(loadLock, KEY, () -> {
                // The nested getNonEmptyPOISections lease ends before the
                // outer first-in-range mapper / eager iterable capture.
                fence.acquire(loadLock, KEY, () -> {
                    readFlags.countDown();
                    return true;
                });
                try {
                    assertTrue(mainReturned.await(5, TimeUnit.SECONDS),
                            "Main cannot wait for a query that may itself need Main");
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
                assertNull(mainFailure.get());
                String mapped = section.get().orElseThrow().toUpperCase(java.util.Locale.ROOT);
                return List.of(mapped);
            });
            fence.drainUnloads(loadLock, ignored -> false);
            assertNull(section.get());
            assertEquals(List.of("LIVE POI"), captured,
                    "iteration after lease release must use captured references");
        } finally {
            readFlags.countDown();
            main.join(5000);
        }
    }

    @Test
    void cancelledAndFailingAcquisitionsReleaseEveryLease() {
        for (RuntimeException failure : List.of(
                new CancellationException("cancelled read"),
                new IllegalStateException("mapper failed"))) {
            PoiChunkLoadFence fence = new PoiChunkLoadFence();
            Object loadLock = new Object();
            AtomicInteger unloads = new AtomicInteger();
            assertSame(failure, assertThrows(RuntimeException.class, () ->
                    fence.acquire(loadLock, KEY, () -> {
                        fence.unload(loadLock, KEY, ignored -> false, unloads::incrementAndGet);
                        throw failure;
                    })));
            fence.drainUnloads(loadLock, ignored -> false);
            assertEquals(1, unloads.get(), "exceptional exit retained a column lease");
        }
    }

    @Test
    void cancelledUnpackTerminalizesDeferredUnloadWithoutFlushingPartialData() {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        CancellationException failure = new CancellationException("disk read cancelled");
        AtomicInteger unloads = new AtomicInteger();
        assertSame(failure, assertThrows(CancellationException.class, () ->
                fence.acquire(loadLock, KEY, () -> {
                    fence.runExclusively(loadLock, KEY, ignored -> false, () -> {
                        fence.unload(loadLock, KEY, ignored -> false, unloads::incrementAndGet);
                        throw failure;
                    });
                    throw new AssertionError("failed unpack returned successfully");
                })));
        assertSame(failure, assertThrows(CancellationException.class,
                () -> fence.drainUnloads(loadLock, ignored -> false)));
        fence.drainUnloads(loadLock, ignored -> false);
        assertEquals(0, unloads.get(), "a failed partial load must never be flushed by deferred unload");
        assertSame(failure, assertThrows(CancellationException.class, () ->
                fence.acquire(loadLock, KEY, () -> {
                    throw new AssertionError("poisoned partial body was accepted");
                })));
    }

    @Test
    void newerResidencyCancelsOldDeferredUnloadButCompletedPendingReadDoesNotLoseIt() {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        AtomicBoolean pending = new AtomicBoolean(true);
        AtomicInteger oldUnloads = new AtomicInteger();
        AtomicInteger currentUnloads = new AtomicInteger();
        fence.unload(loadLock, KEY, ignored -> pending.get(), oldUnloads::incrementAndGet);
        fence.cancelUnload(loadLock, KEY);
        pending.set(false);
        fence.drainUnloads(loadLock, ignored -> pending.get());
        assertEquals(0, oldUnloads.get(), "old residency evicted a newer C2ME upgrade");

        pending.set(true);
        fence.unload(loadLock, KEY, ignored -> pending.get(), currentUnloads::incrementAndGet);
        fence.drainUnloads(loadLock, ignored -> pending.get());
        assertEquals(0, currentUnloads.get());
        pending.set(false);
        fence.drainUnloads(loadLock, ignored -> pending.get());
        assertEquals(1, currentUnloads.get(), "completed prefetch stranded one-shot unload");
    }

    @Test
    void emptyLookupDoesNotReleaseCreationLeaseBeforePublication() {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        AtomicBoolean loaded = new AtomicBoolean(true);
        AtomicReference<Optional<String>> section = new AtomicReference<>(Optional.empty());
        String created = fence.acquire(loadLock, KEY, () -> {
            Optional<String> empty = fence.acquire(loadLock, KEY, section::get);
            assertTrue(empty.isEmpty());
            fence.unload(loadLock, KEY, ignored -> false, () -> {
                section.set(null);
                loaded.set(false);
            });
            fence.drainUnloads(loadLock, ignored -> false);
            section.set(Optional.of("created POI"));
            assertTrue(loaded.get(), "creation published into an unloaded column");
            return section.get().orElseThrow();
        });
        assertEquals("created POI", created);
        fence.drainUnloads(loadLock, ignored -> false);
        assertNull(section.get());
        assertFalse(loaded.get());
    }

    @Test
    void closingStorageDiscardsCallbacksThatWouldReenterClosedIo() {
        PoiChunkLoadFence fence = new PoiChunkLoadFence();
        Object loadLock = new Object();
        AtomicInteger unloads = new AtomicInteger();
        fence.unload(loadLock, KEY, ignored -> true, unloads::incrementAndGet);
        fence.discardUnloads(loadLock);
        fence.drainUnloads(loadLock, ignored -> false);
        assertEquals(0, unloads.get(), "closed storage retained a delayed IO callback");
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
