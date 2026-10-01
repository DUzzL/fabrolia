package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #35 pins — the c2me FLAG_BROKEN fail-stop detector seam.
 */
class C2meStrandHealTest {

    /** Non-c2me holders (vanilla, tests, other rewrites) must be a no-op —
     *  the probe never throws and never touches foreign objects. */
    @Test
    void foreignHolderIsNotApplicable() {
        assertEquals(C2meStrandHeal.Probe.NOT_APPLICABLE,
                C2meStrandHeal.inspectAndMaybeHeal(new Object(), new Object(), 7L));
        assertEquals(C2meStrandHeal.Probe.NOT_APPLICABLE,
                C2meStrandHeal.inspectAndMaybeHeal(null, new Object(), 7L));
    }

    /** A load=true wait stays attached to its one entered request. The wait API
     * has no retry or mutation hook from which a successor could be started. */
    @Test
    void awaitNeverRunsLegacyStallHookOrSuccessorSubmission() throws Exception {
        CompletableFuture<Object> original = new CompletableFuture<>();
        Thread completer = new Thread(() -> {
            try { Thread.sleep(30); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            original.complete("done");
        }, "pin-completer");
        completer.start();
        var out = ChunkBridgeWait.await(original, () -> null, true, () -> "[pin]", () -> { });
        completer.join();
        assertTrue(out instanceof ChunkBridgeWait.Result.Done<?, ?>);
    }

}
