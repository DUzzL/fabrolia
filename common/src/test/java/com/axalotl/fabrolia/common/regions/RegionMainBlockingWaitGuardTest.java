package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RegionMainBlockingWaitGuardTest {

    @Test
    void everyCausalWitnessIsSufficientAndNoWitnessIsSafe() {
        assertFalse(RegionMainBlockingWaitGuard.hasParticipationWitness(
                false, false, false));
        assertTrue(RegionMainBlockingWaitGuard.hasParticipationWitness(
                true, false, false), "currentRegion/currentDomain worker surface");
        assertTrue(RegionMainBlockingWaitGuard.hasParticipationWitness(
                false, true, false), "propagated live causal step");
        assertTrue(RegionMainBlockingWaitGuard.hasParticipationWitness(
                false, false, true), "exact live unitClaim holder");
        assertTrue(RegionMainBlockingWaitGuard.hasParticipationWitness(
                true, true, true));
    }

    @Test
    void denseHelperCurrentRegionAndCoordinatorDomainAreBothParticipants()
            throws Exception {
        AtomicReference<Boolean> dense = new AtomicReference<>();
        RegionWorkerThread denseHelper = new RegionWorkerThread(
                () -> dense.set(RegionMainBlockingWaitGuard.currentThreadParticipates()),
                "fabrolia-test-dense-helper");
        denseHelper.currentRegion = new Region(null, 7L);
        denseHelper.start();
        denseHelper.join(5_000L);
        assertFalse(denseHelper.isAlive());
        assertEquals(Boolean.TRUE, dense.get());

        AtomicReference<Boolean> coordinator = new AtomicReference<>();
        RegionWorkerThread unitCoordinator = new RegionWorkerThread(
                () -> coordinator.set(
                        RegionMainBlockingWaitGuard.currentThreadParticipates()),
                "fabrolia-test-unit-coordinator");
        unitCoordinator.currentDomain = new EpochDomain();
        unitCoordinator.start();
        unitCoordinator.join(5_000L);
        assertFalse(unitCoordinator.isAlive());
        assertEquals(Boolean.TRUE, coordinator.get());
    }

    @Test
    void liveCausalTokenIncludesForeignThreadAndExpiresExactly() throws Exception {
        Field causalField = RegionWorkerPool.class.getDeclaredField("CAUSAL_STEP");
        causalField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Object> causal = (ThreadLocal<Object>) causalField.get(null);

        Class<?> tokenType = Class.forName(
                RegionWorkerPool.class.getName() + "$StepToken");
        var constructor = tokenType.getDeclaredConstructor(RegionWorkerPool.class);
        constructor.setAccessible(true);
        Object token = constructor.newInstance(new RegionWorkerPool(1));
        Field active = tokenType.getDeclaredField("active");
        active.setAccessible(true);

        try {
            active.setBoolean(token, true);
            causal.set(token);
            assertTrue(RegionWorkerPool.currentThreadHasLiveCausalStep());
            assertTrue(RegionMainBlockingWaitGuard.currentThreadParticipates());

            active.setBoolean(token, false);
            assertFalse(RegionWorkerPool.currentThreadHasLiveCausalStep());
            assertFalse(RegionMainBlockingWaitGuard.currentThreadParticipates());
        } finally {
            causal.remove();
        }
    }

    @Test
    void participantPublishesAndThrowsExactExistingWinnerBeforeContinuation()
            throws Exception {
        Field activeField = RegionScheduler.class.getDeclaredField("active");
        Field failureField = RegionScheduler.class.getDeclaredField(
                "orphanFatalFailure");
        activeField.setAccessible(true);
        failureField.setAccessible(true);
        boolean previousActive = activeField.getBoolean(null);
        Object previousFailure = failureField.get(null);

        AssertionError exactWinner = new AssertionError("preselected-W");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicInteger continuationEntries = new AtomicInteger();
        try {
            activeField.setBoolean(null, true);
            failureField.set(null, exactWinner);
            RegionWorkerThread participant = new RegionWorkerThread(() -> {
                try {
                    RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates(
                            "test blocking edge");
                    continuationEntries.incrementAndGet();
                } catch (Throwable thrown) {
                    observed.set(thrown);
                }
            }, "fabrolia-test-blocking-participant");
            participant.currentRegion = new Region(null, 11L);
            participant.start();
            participant.join(5_000L);
            assertFalse(participant.isAlive());
        } finally {
            activeField.setBoolean(null, previousActive);
            failureField.set(null, previousFailure);
        }

        assertSame(exactWinner, observed.get());
        assertEquals(0, continuationEntries.get(),
                "the blocking continuation must remain unentered");
    }

    @Test
    void inactiveAndActiveNonParticipantCallersRemainUntouched() throws Exception {
        Field activeField = RegionScheduler.class.getDeclaredField("active");
        activeField.setAccessible(true);
        boolean previousActive = activeField.getBoolean(null);
        AtomicInteger continuationEntries = new AtomicInteger();
        AtomicReference<Throwable> inactiveFailure = new AtomicReference<>();
        try {
            activeField.setBoolean(null, false);
            RegionWorkerThread inactiveParticipant = new RegionWorkerThread(() -> {
                try {
                    RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates(
                            "inactive test edge");
                    continuationEntries.incrementAndGet();
                } catch (Throwable thrown) {
                    inactiveFailure.set(thrown);
                }
            }, "fabrolia-test-inactive-participant");
            inactiveParticipant.currentDomain = new EpochDomain();
            inactiveParticipant.start();
            inactiveParticipant.join(5_000L);
            assertFalse(inactiveParticipant.isAlive());

            activeField.setBoolean(null, true);
            RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates(
                    "non-participant test edge");
            continuationEntries.incrementAndGet();
        } finally {
            activeField.setBoolean(null, previousActive);
        }

        assertNull(inactiveFailure.get());
        assertEquals(2, continuationEntries.get());
    }
}
