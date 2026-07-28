package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    /** The production wedge: load=false + a future that never completes must
     *  answer the vanilla-legal null within the bounded budget — the pre-fix
     *  loop parked here until the watchdog killed the server. */
    @Test
    @Timeout(30)
    void loadFalseStrandedFutureGivesVanillaNullWithinBudget() {
        CompletableFuture<Object> stranded = new CompletableFuture<>();
        long t0 = System.nanoTime();
        var out = ChunkBridgeWait.await(stranded,
                () -> { throw new AssertionError("load=false must never resubmit"); },
                () -> null, false, POS);
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
        var out = ChunkBridgeWait.await(fut, () -> fut, () -> null, false, POS);
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
        ChunkBridgeWait.Result<Object, String> out = ChunkBridgeWait.await(stranded, () -> stranded,
                () -> calls.incrementAndGet() >= 3 ? "probed-chunk" : null,
                true, POS);
        assertInstanceOf(ChunkBridgeWait.Result.Ready.class, out);
        assertEquals("probed-chunk",
                ((ChunkBridgeWait.Result.Ready<Object, String>) out).chunk());
    }

    /** load=true self-heal: a stranded first request is re-submitted after
     *  the interval and the FRESH future's completion wins — a load the
     *  chunk system dropped no longer pins the worker forever. Runs with a
     *  test-shortened interval via the race structure: we simulate the
     *  interval passing by completing only the resubmitted future. */
    @Test
    @Timeout(60)
    void loadTrueResubmitsAndFreshFutureWins() throws Exception {
        CompletableFuture<Object> stranded = new CompletableFuture<>();
        CompletableFuture<Object> fresh = new CompletableFuture<>();
        AtomicInteger resubmits = new AtomicInteger();
        // Result captured for MAIN-thread assertion — an assertion inside the
        // spawned thread would be swallowed by JUnit (review finding).
        AtomicReference<ChunkBridgeWait.Result<Object, Object>> outcome = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread t = new Thread(() -> {
            try {
                outcome.set(ChunkBridgeWait.await(stranded, () -> {
                    resubmits.incrementAndGet();
                    return fresh;
                }, () -> null, true, POS));
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "bridge-waiter");
        t.setDaemon(true);
        t.start();

        // Wait out the real 5s resubmit interval once (bounded, not flaky:
        // the loop re-checks monotonic time each iteration).
        long deadline = System.nanoTime() + ChunkBridgeWait.RESUBMIT_INTERVAL_NS + 10_000_000_000L;
        while (resubmits.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(resubmits.get() >= 1, "load=true must re-submit after the interval");
        fresh.complete("healed");
        t.join(10_000);
        assertTrue(!t.isAlive(), "fresh completion must release the waiter");
        if (thrown.get() != null) throw new AssertionError(thrown.get());
        assertInstanceOf(ChunkBridgeWait.Result.Done.class, outcome.get());
        assertEquals("healed",
                ((ChunkBridgeWait.Result.Done<Object, Object>) outcome.get()).future().join());
    }
}
