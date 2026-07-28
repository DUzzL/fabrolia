package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #35 pins — the c2me FLAG_BROKEN probe/heal seam.
 */
class C2meStrandHealTest {

    @AfterEach
    void reset() {
        C2meStrandHeal.resetForTest();
    }

    /** The per-chunk budget: a chunk whose upgrade genuinely cannot succeed
     *  re-latches and must stop being retried after MAX_HEALS_PER_POS. */
    @Test
    void healBudgetIsPerChunkAndBounded() {
        long a = 42L, b = 43L;
        for (int i = 0; i < C2meStrandHeal.MAX_HEALS_PER_POS; i++) {
            assertTrue(C2meStrandHeal.tryConsumeAttempt(a), "attempt " + (i + 1) + " must pass");
        }
        assertFalse(C2meStrandHeal.tryConsumeAttempt(a), "budget must exhaust");
        assertTrue(C2meStrandHeal.tryConsumeAttempt(b), "budget is per-chunk");
    }

    /** Non-c2me holders (vanilla, tests, other rewrites) must be a no-op —
     *  the probe never throws and never touches foreign objects. */
    @Test
    void foreignHolderIsNotApplicable() {
        assertEquals(C2meStrandHeal.Probe.NOT_APPLICABLE,
                C2meStrandHeal.inspectAndMaybeHeal(new Object(), new Object(), 7L));
        assertEquals(C2meStrandHeal.Probe.NOT_APPLICABLE,
                C2meStrandHeal.inspectAndMaybeHeal(null, new Object(), 7L));
    }

    /** Task #35 wiring: the in-loop stall hook fires before each load=true
     *  resubmission — the latch produces a NEVER-completing future, so this
     *  hook is the only place the probe can catch it. */
    @Test
    void awaitFiresOnStallHookBeforeResubmission() throws Exception {
        CompletableFuture<Object> stranded = new CompletableFuture<>();
        CompletableFuture<Object> fresh = new CompletableFuture<>();
        AtomicInteger stalls = new AtomicInteger();
        Thread t = new Thread(() -> ChunkBridgeWait.await(stranded, () -> fresh, () -> null,
                true, () -> "[pin]", stalls::incrementAndGet), "pin-waiter");
        t.setDaemon(true);
        t.start();
        long deadline = System.nanoTime() + ChunkBridgeWait.RESUBMIT_INTERVAL_NS + 10_000_000_000L;
        while (stalls.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(stalls.get() >= 1, "onStall must fire at the resubmission point");
        fresh.complete("done");
        t.join(10_000);
        assertFalse(t.isAlive());
    }

    /** A failing onStall hook must not kill the wait loop. */
    @Test
    void onStallFailureIsContained() throws Exception {
        CompletableFuture<Object> fut = new CompletableFuture<>();
        Thread completer = new Thread(() -> {
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            fut.complete("ok");
        });
        completer.start();
        var out = ChunkBridgeWait.await(fut, () -> fut, () -> null, true, () -> "[pin]",
                () -> { throw new IllegalStateException("hook boom"); });
        assertInstanceOf(ChunkBridgeWait.Result.Done.class, out);
    }
}
