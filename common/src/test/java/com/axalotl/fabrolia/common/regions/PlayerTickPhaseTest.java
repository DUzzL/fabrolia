package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PlayerTickPhaseTest {

    @Test
    void exceptionalAbortTerminalizesReservedAndPublishedFallbackWithoutReplay() {
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        Object reservedObject = new Object();
        Object fallbackObject = new Object();
        Object session = new Object();
        PlayerTickPhase.Claim<Object, Object> reserved = phase.reserve(reservedObject, session);
        PlayerTickPhase.Claim<Object, Object> fallback = phase.reserve(fallbackObject, session);
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(fallback, fallbackObject, session, true));
        assertTrue(phase.cancelUnsubmittedWorkerToMain(fallback, () -> {}));

        phase.abortUnstarted();

        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(reservedObject));
        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(fallbackObject));
        assertNull(phase.verifyTerminal());
    }

    private record UuidEqualPlayer(UUID id, int generation) {
        @Override public boolean equals(Object other) {
            return other instanceof UuidEqualPlayer p && id.equals(p.id);
        }
        @Override public int hashCode() { return id.hashCode(); }
    }

    @Test
    void gateIsAnImmutablePerPhaseSnapshot() {
        PlayerTickPhase<Object, Object> off = new PlayerTickPhase<>(false);
        PlayerTickPhase<Object, Object> on = new PlayerTickPhase<>(true);
        assertFalse(off.enabledSnapshot());
        assertTrue(on.enabledSnapshot());
    }

    @Test
    void identityNotUuidControlsExactlyOnceAndNewPhaseTicksAgain() {
        UUID uuid = UUID.randomUUID();
        UuidEqualPlayer oldObject = new UuidEqualPlayer(uuid, 1);
        UuidEqualPlayer newObject = new UuidEqualPlayer(uuid, 2);
        assertEquals(oldObject, newObject, "fixture must be equals/UUID-keyed adversarially");

        PlayerTickPhase<UuidEqualPlayer, Object> phase = new PlayerTickPhase<>(true);
        Object oldSession = new Object();
        Object newSession = new Object();
        runWorker(phase, phase.reserve(oldObject, oldSession), oldObject, oldSession);
        runWorker(phase, phase.reserve(newObject, newSession), newObject, newSession);
        phase.sealAndAwait();
        assertEquals(2, phase.claimCount());
        assertNull(phase.firstFailure());

        PlayerTickPhase<UuidEqualPlayer, Object> next = new PlayerTickPhase<>(true);
        Object nextSession = new Object();
        runWorker(next, next.reserve(oldObject, nextSession), oldObject, nextSession);
        next.sealAndAwait();
        assertNull(next.firstFailure(), "a new wall phase must not inherit an old tick stamp");
    }

    @Test
    void duplicateSameObjectFailsInsteadOfDoubleTicking() {
        Object player = new Object();
        Object session = new Object();
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var first = phase.reserve(player, session);
        assertNull(phase.reserve(player, new Object()));
        runWorker(phase, first, player, session);
        phase.sealAndAwait();
        assertTrue(phase.firstFailure() instanceof IllegalStateException);
        assertEquals(1, phase.claimCount());
    }

    @Test
    void objectOrSessionAbaIsRejectedBeforeWorkerStart() {
        Object player = new Object();
        Object replacement = new Object();
        Object oldSession = new Object();
        Object newSession = new Object();

        PlayerTickPhase<Object, Object> sessionPhase = new PlayerTickPhase<>(true);
        var sessionClaim = sessionPhase.reserve(player, oldSession);
        assertEquals(PlayerTickPhase.Admission.REJECTED,
                sessionPhase.startWorker(sessionClaim, player, newSession, true));
        assertEquals(PlayerTickPhase.State.FAILED, sessionPhase.stateOf(player));

        PlayerTickPhase<Object, Object> objectPhase = new PlayerTickPhase<>(true);
        var objectClaim = objectPhase.reserve(player, oldSession);
        assertEquals(PlayerTickPhase.Admission.REJECTED,
                objectPhase.startWorker(objectClaim, replacement, oldSession, true));
        assertEquals(PlayerTickPhase.State.FAILED, objectPhase.stateOf(player));
    }

    @Test
    void postReservationEligibilityFlipIsRejectedWithoutFallback() {
        Object player = new Object();
        Object session = new Object();
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var claim = phase.reserve(player, session);
        assertEquals(PlayerTickPhase.Admission.REJECTED,
                phase.startWorker(claim, player, session, false));
        phase.sealAndAwait();
        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(player));
        assertTrue(phase.firstFailure() instanceof IllegalStateException);
    }

    @Test
    void unsubmittedWorkerPublishesMainFallbackBeforeOpeningBarrier() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Object player = new Object();
            Object session = new Object();
            PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
            var claim = phase.reserve(player, session);
            assertEquals(PlayerTickPhase.Admission.WORKER,
                    phase.startWorker(claim, player, session, true));
            AtomicBoolean visible = new AtomicBoolean();
            CountDownLatch publishEntered = new CountDownLatch(1);
            CountDownLatch allowPublish = new CountDownLatch(1);
            CompletableFuture<Boolean> cancel = CompletableFuture.supplyAsync(() ->
                    phase.cancelUnsubmittedWorkerToMain(claim, () -> {
                        publishEntered.countDown();
                        await(allowPublish);
                        visible.set(true);
                    }));
            assertTrue(publishEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> waiter = CompletableFuture.runAsync(phase::sealAndAwait);
            assertFalse(waiter.isDone(), "barrier opened before fallback publication");
            allowPublish.countDown();
            assertTrue(cancel.join());
            waiter.join();
            assertTrue(visible.get());
            assertTrue(phase.startMainFallback(claim, player, session, true));
            phase.markVanillaConsumerEntered(claim);
            phase.complete(claim, null);
            assertEquals(PlayerTickPhase.State.DONE, phase.stateOf(player));
            assertNull(phase.firstFailure());
        });
    }

    @Test
    void fallbackPublicationFailureIsTerminalAndCannotHangTheBarrier() {
        Object player = new Object();
        Object session = new Object();
        RuntimeException exact = new RuntimeException("fallback publication failed");
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var claim = phase.reserve(player, session);
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(claim, player, session, true));

        assertFalse(phase.cancelUnsubmittedWorkerToMain(claim, () -> {
            throw exact;
        }));
        phase.sealAndAwait();
        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(player));
        assertSame(exact, phase.firstFailure());
    }

    @Test
    void preBodyFailureReleasesAdmissionWithoutRetryOrFallback() {
        Object player = new Object();
        Object session = new Object();
        RuntimeException exact = new RuntimeException("lane failed before body");
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var claim = phase.reserve(player, session);
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(claim, player, session, true));

        phase.failBeforeBody(claim, exact);
        phase.sealAndAwait();
        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(player));
        assertSame(exact, phase.firstFailure());
        assertEquals(PlayerTickPhase.Admission.REJECTED,
                phase.startWorker(claim, player, session, true));
    }

    @Test
    void realCancellationFallbackThrowableIsPreservedAndNeverRetried() {
        Object player = new Object();
        Object session = new Object();
        RuntimeException exact = new RuntimeException("main fallback body failed");
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var claim = phase.reserve(player, session);
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(claim, player, session, true));
        assertTrue(phase.cancelUnsubmittedWorkerToMain(claim, () -> {}));
        assertTrue(phase.startMainFallback(claim, player, session, true));
        phase.markVanillaConsumerEntered(claim);
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> phase.complete(claim, exact));
        assertSame(exact, thrown,
                "completion must rethrow the exact entered-body failure after terminalization");
        phase.sealAndAwait();

        assertSame(exact, phase.firstFailure());
        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(player));
        assertEquals(PlayerTickPhase.Admission.REJECTED,
                phase.startWorker(claim, player, session, true));
        assertFalse(phase.startMainFallback(claim, player, session, true));
    }

    @Test
    void failedProgressIterationIsNotRetriedAfterPartialEntry() {
        Object player = new Object();
        Object session = new Object();
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var claim = phase.reserve(player, session);
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(claim, player, session, true));
        phase.markVanillaConsumerEntered(claim);
        AtomicInteger pumps = new AtomicInteger();
        RuntimeException exact = new RuntimeException("pump entered then failed");
        Throwable failure = PlayerTickPhase.sealAndAwaitAll(List.of(phase), () -> {
            pumps.incrementAndGet();
            phase.complete(claim, null);
            throw exact;
        });
        assertSame(exact, failure);
        assertEquals(1, pumps.get(),
                "an entered progress body must not be replayed after it throws");
        assertEquals(PlayerTickPhase.State.DONE, phase.stateOf(player));
    }

    @Test
    void mainFallbackCannotReportDoneWithoutEnteringVanillaConsumer() {
        Object player = new Object();
        Object session = new Object();
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var claim = phase.reserve(player, session);
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(claim, player, session, true));
        assertTrue(phase.cancelUnsubmittedWorkerToMain(claim, () -> {}));
        phase.seal();
        assertTrue(phase.startMainFallback(claim, player, session, true));
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> phase.complete(claim, null));
        phase.sealAndAwait();
        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(player));
        assertSame(thrown, phase.firstFailure(),
                "the missing vanilla-entry invariant must be the exact terminal failure");
    }

    @Test
    void mainFallbackRejectsAReplacedSessionBeforeInvocation() {
        Object player = new Object();
        Object oldSession = new Object();
        Object newSession = new Object();
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var claim = phase.reserve(player, oldSession);
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(claim, player, oldSession, true));
        assertTrue(phase.cancelUnsubmittedWorkerToMain(claim, () -> {}));
        phase.seal();
        assertFalse(phase.startMainFallback(claim, player, newSession, true));
        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(player));
        assertTrue(phase.firstFailure() instanceof IllegalStateException);
    }

    @Test
    void terminalVerificationRejectsAnUnfulfilledReservedObligation() {
        Object player = new Object();
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        phase.reserve(player, new Object());
        phase.sealAndAwait();
        Throwable failure = phase.verifyTerminal();
        assertTrue(failure instanceof IllegalStateException);
        assertEquals(PlayerTickPhase.State.RESERVED, phase.stateOf(player));
    }

    @Test
    void duplicateConsumerEntryFailsButRunningBodyCanStillReleaseBarrier() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Object player = new Object();
            Object session = new Object();
            PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
            var claim = phase.reserve(player, session);
            assertEquals(PlayerTickPhase.Admission.WORKER,
                    phase.startWorker(claim, player, session, true));
            phase.markVanillaConsumerEntered(claim);
            assertThrows(IllegalStateException.class,
                    () -> phase.markVanillaConsumerEntered(claim));
            assertEquals(PlayerTickPhase.Admission.REJECTED,
                    phase.startWorker(claim, player, session, true));
            assertEquals(PlayerTickPhase.State.RUNNING, phase.stateOf(player),
                    "an invariant report must not steal the live body's completion ownership");
            phase.complete(claim, null);
            phase.sealAndAwait();
            assertTrue(phase.firstFailure() instanceof IllegalStateException);
        });
    }

    @Test
    void barrierCannotOpenUntilLastStartedTickAndPublishesPlainWrite() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Object player = new Object();
            Object session = new Object();
            PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
            var claim = phase.reserve(player, session);
            assertEquals(PlayerTickPhase.Admission.WORKER,
                    phase.startWorker(claim, player, session, true));
            phase.markVanillaConsumerEntered(claim);

            int[] plainState = {0};
            CountDownLatch sealerEntered = new CountDownLatch(1);
            CompletableFuture<Void> sealed = CompletableFuture.runAsync(() -> {
                sealerEntered.countDown();
                phase.sealAndAwait();
                assertEquals(41, plainState[0]);
            });
            assertTrue(sealerEntered.await(1, TimeUnit.SECONDS));
            assertFalse(sealed.isDone(), "seal force-opened while the worker was still RUNNING");
            plainState[0] = 41;
            phase.complete(claim, null);
            sealed.join();
        });
    }

    @Test
    void acceptedReceiptDoesNotOpenBarrierUntilTerminalIsExplicitlySignalled() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Object player = new Object();
            Object session = new Object();
            PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
            var claim = phase.reserve(player, session);
            assertEquals(PlayerTickPhase.Admission.WORKER,
                    phase.startWorker(claim, player, session, true));
            assertTrue(phase.bindExecutor(claim));
            phase.markVanillaConsumerEntered(claim);

            PlayerTickPhase.CompletionReceipt<Object, Object> receipt =
                    phase.offerCompletion(claim);
            receipt.acceptSuccess();
            assertNull(phase.consumeAcceptedCompletion(receipt));
            assertEquals(PlayerTickPhase.State.DONE, phase.stateOf(player));

            CountDownLatch waiterEntered = new CountDownLatch(1);
            CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
                waiterEntered.countDown();
                phase.sealAndAwait();
            });
            assertTrue(waiterEntered.await(1, TimeUnit.SECONDS));
            assertFalse(waiter.isDone(),
                    "accepted/consumed receipt opened the barrier before owner retirement");

            phase.signalCompletion(receipt);
            waiter.join();
            assertNull(phase.firstFailure());
        });
    }

    @Test
    void refusedReceiptCarriesExactSelectedWAndStillNeedsOneSignal() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Object player = new Object();
            Object session = new Object();
            PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
            var claim = phase.reserve(player, session);
            assertEquals(PlayerTickPhase.Admission.WORKER,
                    phase.startWorker(claim, player, session, true));
            assertTrue(phase.bindExecutor(claim));
            phase.markVanillaConsumerEntered(claim);

            PlayerTickPhase.CompletionReceipt<Object, Object> receipt =
                    phase.offerCompletion(claim);
            AssertionError exact = new AssertionError("peer W won before receipt acceptance");
            assertSame(exact, phase.poisonCompletion(receipt, exact));
            assertSame(exact, phase.firstFailure());
            assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(player));

            CountDownLatch waiterEntered = new CountDownLatch(1);
            CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
                waiterEntered.countDown();
                phase.sealAndAwait();
            });
            assertTrue(waiterEntered.await(1, TimeUnit.SECONDS));
            assertFalse(waiter.isDone(),
                    "FAILED receipt opened the barrier before cleanup signalled it");

            phase.signalCompletion(receipt);
            waiter.join();
            assertSame(exact, phase.verifyTerminal());
            assertThrows(IllegalStateException.class,
                    () -> phase.signalCompletion(receipt));
        });
    }

    @Test
    void provisionalCompletionInvariantCannotReplaceLaterSelectedGlobalW() {
        Object player = new Object();
        Object session = new Object();
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var claim = phase.reserve(player, session);
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(claim, player, session, true));
        assertTrue(phase.bindExecutor(claim));
        phase.markVanillaConsumerEntered(claim);

        PlayerTickPhase.CompletionReceipt<Object, Object> receipt =
                phase.offerCompletion(claim);
        receipt.acceptSuccess();
        Throwable provisional = CompletableFuture.supplyAsync(
                () -> phase.consumeAcceptedCompletion(receipt)).join();
        assertTrue(provisional instanceof IllegalStateException,
                "the foreign completion thread must be diagnosed");
        assertNull(phase.firstFailure(),
                "a provisional invariant may not publish before global W is selected");

        AssertionError exact = new AssertionError("generation W won the global slot");
        assertSame(exact, phase.poisonCompletion(receipt, exact));
        assertSame(exact, phase.firstFailure());
        assertEquals(1, exact.getSuppressed().length);
        assertSame(provisional, exact.getSuppressed()[0]);
        phase.signalCompletion(receipt);
        phase.sealAndAwait();
        assertSame(exact, phase.verifyTerminal());
    }

    @Test
    void workerThrowableIsPreservedByIdentityAndBodyIsNotRetried() {
        Object player = new Object();
        Object session = new Object();
        RuntimeException exact = new RuntimeException("exact worker failure");
        AtomicInteger attempts = new AtomicInteger();
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var claim = phase.reserve(player, session);
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(claim, player, session, true));
        phase.markVanillaConsumerEntered(claim);
        attempts.incrementAndGet();
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> phase.complete(claim, exact));
        assertSame(exact, thrown,
                "completion must preserve the exact worker Throwable at its escape edge");
        phase.sealAndAwait();
        assertSame(exact, phase.firstFailure());
        assertEquals(1, attempts.get());
        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(player));
    }

    @Test
    void sealRejectsLateReserveAndReservedButNotStartedWorker() {
        Object player = new Object();
        Object session = new Object();
        PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
        var reserved = phase.reserve(player, session);
        phase.sealAndAwait();
        assertEquals(PlayerTickPhase.Admission.REJECTED,
                phase.startWorker(reserved, player, session, true));
        assertNull(phase.reserve(new Object(), new Object()));
        assertEquals(PlayerTickPhase.State.FAILED, phase.stateOf(player));
        assertTrue(phase.firstFailure() instanceof IllegalStateException);
    }

    @Test
    void exceptionalBarrierKeepsWaitingAfterProgressPumpThrows() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Object player = new Object();
            Object session = new Object();
            PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
            var claim = phase.reserve(player, session);
            assertEquals(PlayerTickPhase.Admission.WORKER,
                    phase.startWorker(claim, player, session, true));

            CountDownLatch workerReady = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            Thread worker = Thread.ofPlatform().start(() -> {
                assertTrue(phase.bindExecutor(claim));
                phase.markVanillaConsumerEntered(claim);
                workerReady.countDown();
                await(releaseWorker);
                phase.complete(claim, null);
            });
            await(workerReady);

            RuntimeException exactPumpFailure = new RuntimeException("pump failed once");
            AtomicInteger pumps = new AtomicInteger();
            CountDownLatch pumpFailed = new CountDownLatch(1);
            CompletableFuture<Throwable> waiter = CompletableFuture.supplyAsync(() ->
                    PlayerTickPhase.sealAndAwaitAll(List.of(phase), () -> {
                        pumps.incrementAndGet();
                        pumpFailed.countDown();
                        throw exactPumpFailure;
                    }));

            assertTrue(pumpFailed.await(1, TimeUnit.SECONDS));
            assertFalse(waiter.isDone(), "progress failure force-opened the running barrier");
            releaseWorker.countDown();
            assertSame(exactPumpFailure, waiter.join());
            worker.join();
            assertEquals(1, pumps.get(),
                    "the throwing progress body must not be retried as recovery");
            assertEquals(PlayerTickPhase.State.DONE, phase.stateOf(player));
        });
    }

    @Test
    void interruptedExceptionalWaitStillQuiescesAndRestoresInterruptFlag() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Object player = new Object();
            Object session = new Object();
            PlayerTickPhase<Object, Object> phase = new PlayerTickPhase<>(true);
            var claim = phase.reserve(player, session);
            assertEquals(PlayerTickPhase.Admission.WORKER,
                    phase.startWorker(claim, player, session, true));
            CountDownLatch workerReady = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            Thread worker = Thread.ofPlatform().start(() -> {
                assertTrue(phase.bindExecutor(claim));
                phase.markVanillaConsumerEntered(claim);
                workerReady.countDown();
                await(releaseWorker);
                phase.complete(claim, null);
            });
            await(workerReady);
            AtomicBoolean restored = new AtomicBoolean();
            CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
                Thread.currentThread().interrupt();
                PlayerTickPhase.sealAndAwaitAll(List.of(phase), releaseWorker::countDown);
                restored.set(Thread.currentThread().isInterrupted());
                Thread.interrupted();
            });
            waiter.join();
            worker.join();
            assertTrue(restored.get());
            assertEquals(PlayerTickPhase.State.DONE, phase.stateOf(player));
        });
    }

    private static <P, S> void runWorker(PlayerTickPhase<P, S> phase,
                                         PlayerTickPhase.Claim<P, S> claim,
                                         P player, S session) {
        assertEquals(PlayerTickPhase.Admission.WORKER,
                phase.startWorker(claim, player, session, true));
        phase.markVanillaConsumerEntered(claim);
        phase.complete(claim, null);
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
