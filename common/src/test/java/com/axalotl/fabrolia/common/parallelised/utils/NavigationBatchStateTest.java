package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the production membership/batch sidecar, not a copied concurrency model. */
class NavigationBatchStateTest {
    /** Navigation overrides must not gain a callback edge beneath either leaf lock. */
    private static final class Navigation {
        @Override
        public boolean equals(Object other) {
            throw new AssertionError("navigation equality callback");
        }

        @Override
        public int hashCode() {
            throw new AssertionError("navigation hash callback");
        }
    }

    @Test
    void queriesObserveCompletedMembershipChangesAndKeepTheirOwnImmutableCut() {
        NavigationBatchState<Navigation> state = new NavigationBatchState<>();
        Navigation first = new Navigation();
        Navigation second = new Navigation();
        state.activate(first);
        List<Navigation> before = state.activeSnapshot();
        state.activate(second);
        List<Navigation> during = state.activeSnapshot();
        state.deactivateAll(List.of(first));
        List<Navigation> after = state.activeSnapshot();

        assertEquals(1, before.size());
        assertSame(first, before.getFirst());
        assertEquals(2, during.size());
        assertTrue(during.stream().anyMatch(nav -> nav == first));
        assertTrue(during.stream().anyMatch(nav -> nav == second));
        assertEquals(1, after.size());
        assertSame(second, after.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> before.add(second));
    }

    @Test
    void competingWritersLeaveEveryCompletedMembershipChangePublished() throws Exception {
        NavigationBatchState<Navigation> state = new NavigationBatchState<>();
        Navigation retired = new Navigation();
        Navigation first = new Navigation();
        Navigation second = new Navigation();
        state.activate(retired);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var writerA = workers.submit(() -> {
                await(start);
                state.activate(first);
                state.deactivate(retired);
            });
            var writerB = workers.submit(() -> {
                await(start);
                state.activate(second);
            });
            start.countDown();
            writerA.get(5, TimeUnit.SECONDS);
            writerB.get(5, TimeUnit.SECONDS);
        }
        List<Navigation> published = state.activeSnapshot();
        assertEquals(2, published.size());
        assertTrue(published.stream().anyMatch(nav -> nav == first));
        assertTrue(published.stream().anyMatch(nav -> nav == second));
    }

    @Test
    void producerDelayedPastCloseIsReturnedToNormalRouting() throws Exception {
        NavigationBatchState<Navigation> state = new NavigationBatchState<>();
        Navigation existing = new Navigation();
        Navigation late = new Navigation();
        state.beginBatch();
        assertTrue(state.defer(existing));
        CountDownLatch producerAtDecision = new CountDownLatch(1);
        CountDownLatch resumeProducer = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var producer = workers.submit(() -> {
                producerAtDecision.countDown();
                await(resumeProducer);
                return state.defer(late);
            });
            try {
                await(producerAtDecision);
                state.endBatch();
                Set<Navigation> detached = state.detachReady();
                assertEquals(1, detached.size());
                assertSame(existing, detached.iterator().next());
            } finally {
                resumeProducer.countDown();
            }
            assertFalse(producer.get(5, TimeUnit.SECONDS),
                    "a delayed qualifying notification must use the ordinary recompute path");
        }
        assertTrue(state.detachReady().isEmpty());
    }

    @Test
    void consumingDetachedBatchCannotEraseSameIdentityAcceptedByItsSuccessor() throws Exception {
        NavigationBatchState<Navigation> state = new NavigationBatchState<>();
        Navigation navigation = new Navigation();
        state.beginBatch();
        assertTrue(state.defer(navigation));
        state.endBatch();
        Set<Navigation> detached = state.detachReady();
        CountDownLatch consuming = new CountDownLatch(1);
        CountDownLatch finishConsumption = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var consumer = workers.submit(() -> {
                for (Navigation nav : detached) {
                    assertSame(navigation, nav);
                    consuming.countDown();
                    await(finishConsumption);
                }
                detached.clear();
            });
            try {
                await(consuming);
                state.beginBatch();
                assertTrue(state.defer(navigation));
            } finally {
                finishConsumption.countDown();
            }
            consumer.get(5, TimeUnit.SECONDS);
        }
        state.endBatch();
        Set<Navigation> successor = state.detachReady();
        assertEquals(1, successor.size());
        assertSame(navigation, successor.iterator().next());
        assertTrue(state.detachReady().isEmpty());
    }

    @Test
    void nestedCleanupDoesNotDrainOrEndTheOuterBatch() {
        NavigationBatchState<Navigation> state = new NavigationBatchState<>();
        Navigation outer = new Navigation();
        Navigation inner = new Navigation();
        state.beginBatch();
        assertTrue(state.defer(outer));
        state.beginBatch();
        assertTrue(state.defer(inner));
        state.endBatch();
        assertTrue(state.detachReady().isEmpty());
        assertTrue(state.defer(outer));
        state.endBatch();
        // endBatch is also the failure cleanup operation: it must retain the
        // obligation without dispatching or clearing it. Only a success tail detaches.
        assertEquals(2, state.pendingCount());
        Set<Navigation> detached = state.detachReady();
        assertEquals(2, detached.size());
        assertTrue(detached.contains(outer));
        assertTrue(detached.contains(inner));
        assertTrue(state.detachReady().isEmpty());
    }

    @Test
    void overlappingWorkerCloseRetainsNotificationsUntilTheLastBodyCloses() throws Exception {
        NavigationBatchState<Navigation> state = new NavigationBatchState<>();
        Navigation first = new Navigation();
        Navigation second = new Navigation();
        state.beginBatch();
        assertTrue(state.defer(first));
        CountDownLatch overlapping = new CountDownLatch(1);
        CountDownLatch finishSecond = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var secondBody = workers.submit(() -> {
                state.beginBatch();
                try {
                    assertTrue(state.defer(second));
                    overlapping.countDown();
                    await(finishSecond);
                } finally {
                    state.endBatch();
                }
            });
            try {
                await(overlapping);
                state.endBatch();
                assertTrue(state.detachReady().isEmpty());
                assertEquals(2, state.pendingCount());
            } finally {
                finishSecond.countDown();
            }
            secondBody.get(5, TimeUnit.SECONDS);
        }
        Set<Navigation> detached = state.detachReady();
        assertEquals(2, detached.size());
        assertTrue(detached.contains(first));
        assertTrue(detached.contains(second));
        assertFalse(state.defer(new Navigation()));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "navigation test rendezvous timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
