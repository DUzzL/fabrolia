package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #34 regression pins — the 2026-07-28 06:56 production watchdog
 * (classic-pool zombie tick → {@code getChunkForCollisions} (load=false) →
 * Tier-3 bridge parked forever on a chunk future the c2me pipeline abandoned;
 * every c2me worker idle, main pumping, entity barrier never completing) —
 * proven against the REAL {@link ChunkBridgeWait#await} loop.
 */
class ChunkBridgeWaitTest {

    private static final java.util.function.Supplier<String> POS = () -> "[test]";
    private static final Runnable NO_STALL_INSPECTOR = () -> { };

    /** The production wedge: load=false + a future that never completes must
     *  answer the vanilla-legal null within the bounded budget — the pre-fix
     *  loop parked here until the watchdog killed the server. */
    @Test
    @Timeout(30)
    void loadFalseStrandedFutureGivesVanillaNullWithinBudget() {
        CompletableFuture<Object> stranded = new CompletableFuture<>();
        long t0 = System.nanoTime();
        var out = ChunkBridgeWait.await(stranded, () -> null, false, POS, NO_STALL_INSPECTOR);
        long elapsed = System.nanoTime() - t0;
        assertInstanceOf(ChunkBridgeWait.Result.GiveUpNull.class, out,
                "stranded load=false future must resolve to the vanilla null");
        assertTrue(elapsed >= ChunkBridgeWait.SOFT_NULL_BUDGET_NS,
                "the budget must be honored (legitimate promotions get their grace)");
        assertTrue(elapsed < ChunkBridgeWait.SOFT_NULL_BUDGET_NS * 20,
                "give-up must be prompt, not watchdog-scale");
    }

    /** A future that completes within the budget is returned as Done —
     *  vanilla wait semantics preserved for the healthy path. */
    @Test
    @Timeout(30)
    void loadFalseCompletingFutureIsReturned() {
        CompletableFuture<Object> fut = new CompletableFuture<>();
        Thread completer = new Thread(() -> {
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            fut.complete("chunk-result");
        });
        completer.start();
        var out = ChunkBridgeWait.await(fut, () -> null, false, POS, NO_STALL_INSPECTOR);
        assertInstanceOf(ChunkBridgeWait.Result.Done.class, out);
        assertEquals("chunk-result",
                ((ChunkBridgeWait.Result.Done<Object, Object>) out).future().join());
    }

    /** The lock-free probe short-circuits the whole wait — a chunk that
     *  becomes visible mid-wait wins over the (possibly stranded) future. */
    @Test
    @Timeout(30)
    void probeShortCircuitsStrandedFuture() {
        CompletableFuture<Object> stranded = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        ChunkBridgeWait.Result<Object, String> out = ChunkBridgeWait.await(stranded,
                () -> calls.incrementAndGet() >= 3 ? "probed-chunk" : null,
                true, POS, NO_STALL_INSPECTOR);
        assertInstanceOf(ChunkBridgeWait.Result.Ready.class, out);
        assertEquals("probed-chunk",
                ((ChunkBridgeWait.Result.Ready<Object, String>) out).chunk());
    }

    /** load=true keeps one exact upgrade obligation and returns that same
     * future; the API exposes no successor-submission hook. */
    @Test
    @Timeout(30)
    void loadTrueNeverSubmitsSuccessorAndReturnsOriginalFuture() throws Exception {
        CompletableFuture<Object> original = new CompletableFuture<>();
        Thread completer = new Thread(() -> {
            try { Thread.sleep(30); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            original.complete("done");
        });
        completer.start();
        var outcome = ChunkBridgeWait.await(original, () -> null, true, POS, NO_STALL_INSPECTOR);
        completer.join();
        assertInstanceOf(ChunkBridgeWait.Result.Done.class, outcome);
        assertEquals(original,
                ((ChunkBridgeWait.Result.Done<Object, Object>) outcome).future());
    }

    /** A RUNNING ray can already be parked in this bridge when peer W wins.
     * It must unwind with that exact object so Main can passively quiesce the
     * ray fan-in without polling a level-wide chunk successor after W. */
    @Test
    @Timeout(3)
    void physicalWinnerTerminatesAStrandedAcceptedBodyExactly() throws Exception {
        var poolField = RegionScheduler.class.getDeclaredField("pool");
        var fatalField = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        poolField.setAccessible(true);
        fatalField.setAccessible(true);
        Object previousPool = poolField.get(null);
        Object previousFatal = fatalField.get(null);
        AssertionError winner = new AssertionError("ray chunk-bridge W");
        AtomicInteger probes = new AtomicInteger();
        try {
            poolField.set(null, null);
            fatalField.set(null, winner);
            AssertionError surfaced = assertThrows(AssertionError.class,
                    () -> ChunkBridgeWait.await(
                            new CompletableFuture<>(),
                            () -> {
                                probes.incrementAndGet();
                                return null;
                            },
                            true, POS, NO_STALL_INSPECTOR));
            assertSame(winner, surfaced);
            assertEquals(0, probes.get(),
                    "W must stop the accepted wait before another chunk probe body");
        } finally {
            poolField.set(null, previousPool);
            fatalField.set(null, previousFatal);
        }
    }
}
