package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exactly-once pins for the autonomous island pacer's pure ticket authority. */
class AutonomousPacerTicketStateTest {

    @Test
    void nonBodyAttemptsUseTheClaimRetryCadence() {
        assertEquals(2_000_000L, TemporalControl.Pacer.retryDelayNanos(
                IslandTickUnit.AttemptOutcome.NO_BODY));
        assertEquals(2_000_000L, TemporalControl.Pacer.retryDelayNanos(
                IslandTickUnit.AttemptOutcome.RETIRED));
        assertEquals(50_000_000L, TemporalControl.Pacer.retryDelayNanos(
                IslandTickUnit.AttemptOutcome.HOLD),
                "a readiness hold retries at vanilla's 20 Hz tick cadence");
    }

    @Test
    void terminalReceiptUnparksItsRegisteredWatcher() throws Exception {
        var constructor = IslandTickUnit.AttemptReceipt.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        IslandTickUnit.AttemptReceipt receipt = constructor.newInstance();
        java.util.concurrent.CountDownLatch registered = new java.util.concurrent.CountDownLatch(1);
        // The pacer's shape: register, then park until terminal. A lost wake-up
        // leaves it parked for the whole (here: one minute) park bound.
        Thread watcher = new Thread(() -> {
            receipt.wakeOnTerminal(Thread.currentThread());
            registered.countDown();
            while (!receipt.isDone()) {
                java.util.concurrent.locks.LockSupport.parkNanos(60_000_000_000L);
            }
        }, "receipt-watcher");
        watcher.setDaemon(true);
        watcher.start();
        assertTrue(registered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        receipt.commitOutcome(IslandTickUnit.AttemptOutcome.ADVANCED, 4L, 5L);
        receipt.finishSuccessfully();
        watcher.join(5_000L);
        assertFalse(watcher.isAlive(), "the terminal edge must wake the watching pacer");
    }

    @Test
    void duplicateOrLaterArmCannotCreateASecondQueuedAuthority() {
        TemporalControl.Pacer.TicketState state = new TemporalControl.Pacer.TicketState();
        var first = state.arm(7, 1_000, 1_000, false);

        assertNotNull(first);
        assertNull(state.arm(7, 1_000, 1_000, false));
        assertNull(state.arm(7, 1_000, 1_100, false));

        // Main's watchdog cannot preempt the exact queued frontier.
        var earlier = state.arm(7, 1_000, 900, false);
        assertNull(earlier);
        assertTrue(state.begin(first));
        assertNull(state.arm(7, 1_000, 800, false),
                "same-generation watchdogs cannot overlap an in-flight attempt");
    }

    @Test
    void generationTransitionInvalidatesAnInFlightAttempt() {
        TemporalControl.Pacer.TicketState state = new TemporalControl.Pacer.TicketState();
        var oldRate = state.arm(11, 2_000, 2_000, false);
        assertTrue(state.begin(oldRate));

        var newRate = state.arm(12, 2_000, 2_000, false);
        assertNotNull(newRate, "a new pacing generation must reseed immediately");
        assertFalse(state.finish(oldRate.id()),
                "the old attempt receipt cannot re-arm over the transition");
        assertTrue(state.begin(newRate));
        assertTrue(state.finish(newRate.id()));
    }

    @Test
    void unitBoundaryReplacesAttemptBeforeItsReceiptIsPolled() {
        TemporalControl.Pacer.TicketState state = new TemporalControl.Pacer.TicketState();
        var attempt = state.arm(21, 3_000, 3_000, false);
        assertTrue(state.begin(attempt));

        var successor = state.arm(21, 4_000, 4_000, true);
        assertNotNull(successor);
        assertFalse(state.finish(attempt.id()),
                "completion publication, not the stale receipt, owns the successor");
        assertTrue(state.begin(successor));
    }

    @Test
    void refusalFinishHandsAuthorityBackAndCancelMakesEveryTicketStale() {
        TemporalControl.Pacer.TicketState state = new TemporalControl.Pacer.TicketState();
        var refused = state.arm(31, 5_000, 5_000, false);
        assertTrue(state.begin(refused));
        assertTrue(state.finish(refused.id()));

        var retry = state.arm(31, 5_000, 5_100, false);
        assertNotNull(retry);
        state.cancel();
        assertFalse(state.begin(retry));
        assertFalse(state.finish(retry.id()));
    }

    @Test
    void normalStopRetiresTheExactDaemonAndAllowsAFreshOwner() throws Exception {
        TemporalControl.Pacer pacer = new TemporalControl.Pacer();
        Thread first = null;
        Thread second = null;
        try {
            pacer.ensureStarted();
            first = pacer.ownerThreadForTest();
            assertNotNull(first);
            assertTrue(first.isAlive());

            Thread.currentThread().interrupt();
            pacer.shutdownNormally();
            assertTrue(Thread.currentThread().isInterrupted(),
                    "joining the old daemon must preserve Main's interrupt status");
            Thread.interrupted(); // do not leak the deliberate flag into JUnit
            first.join(2_000L);
            assertFalse(first.isAlive());
            assertNull(pacer.ownerThreadForTest(),
                    "a terminal daemon identity must not suppress the next start");

            pacer.ensureStarted();
            second = pacer.ownerThreadForTest();
            assertNotNull(second);
            assertNotSame(first, second);
            assertTrue(second.isAlive());
        } finally {
            Thread.interrupted();
            pacer.shutdownNormally();
            Thread owner = pacer.ownerThreadForTest();
            if (owner != null) owner.join(2_000L);
            if (second != null) second.join(2_000L);
        }
    }
}
