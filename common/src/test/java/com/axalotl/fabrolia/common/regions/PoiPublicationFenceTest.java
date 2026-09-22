package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PoiPublicationFenceTest {
    @Test
    void executorAcceptanceDoesNotMakeCreatedPortalVisible() {
        PoiPublicationFence fence = new PoiPublicationFence(failure -> failure);
        ArrayDeque<Runnable> executor = new ArrayDeque<>();
        AtomicInteger portalBlocks = new AtomicInteger();
        fence.submit(portalBlocks::incrementAndGet, executor::add);
        assertFalse(fence.isPublished());
        assertEquals(0, portalBlocks.get());
        executor.remove().run();
        assertTrue(fence.isPublished());
        assertEquals(1, portalBlocks.get());
    }

    @Test
    void queuedChildrenRemainOwedAfterTheirParentCallbackReturns() {
        PoiPublicationFence fence = new PoiPublicationFence(failure -> failure);
        ArrayDeque<Runnable> executor = new ArrayDeque<>();
        AtomicInteger childEffects = new AtomicInteger();
        fence.submit(() -> fence.submit(childEffects::incrementAndGet, executor::add), executor::add);
        executor.remove().run();
        assertFalse(fence.isPublished());
        executor.remove().run();
        assertTrue(fence.isPublished());
        assertEquals(1, childEffects.get());
    }

    @Test
    void offerThenThrowCancelsOnlyTheUnenteredCallback() {
        PoiPublicationFence fence = new PoiPublicationFence(failure -> failure);
        AtomicReference<Runnable> accepted = new AtomicReference<>();
        AtomicInteger effects = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("executor publication failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fence.submit(effects::incrementAndGet, callback -> {
                    accepted.set(callback);
                    throw failure;
                })));
        accepted.get().run();
        assertEquals(0, effects.get());
        assertSame(failure, assertThrows(IllegalStateException.class, fence::isPublished));
    }

    @Test
    void executorFailureAfterInlineEntryDoesNotReplayOrDoubleTerminalize() {
        PoiPublicationFence fence = new PoiPublicationFence(failure -> failure);
        AtomicReference<Runnable> accepted = new AtomicReference<>();
        AtomicInteger effects = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("after inline execution");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fence.submit(effects::incrementAndGet, callback -> {
                    accepted.set(callback);
                    callback.run();
                    throw failure;
                })));
        accepted.get().run();
        assertEquals(1, effects.get());
        assertSame(failure, assertThrows(IllegalStateException.class, fence::isPublished));
    }

    @Test
    void failedCallbackPoisonsReadinessAndRefusesQueuedSuccessors() {
        AtomicReference<PoiPublicationFence> reference = new AtomicReference<>();
        AtomicInteger failureSelections = new AtomicInteger();
        PoiPublicationFence fence = new PoiPublicationFence(failure -> {
            failureSelections.incrementAndGet();
            assertFalse(reference.get().isPublished());
            return failure;
        });
        reference.set(fence);
        ArrayDeque<Runnable> executor = new ArrayDeque<>();
        AtomicInteger successorEffects = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("POI add failed");
        fence.submit(() -> { throw failure; }, executor::add);
        fence.submit(successorEffects::incrementAndGet, executor::add);
        assertSame(failure, assertThrows(IllegalStateException.class, executor.remove()::run));
        assertSame(failure, assertThrows(IllegalStateException.class, fence::isPublished));
        assertSame(failure, assertThrows(IllegalStateException.class, executor.remove()::run));
        assertEquals(0, successorEffects.get());
        assertTrue(failureSelections.get() >= 1);
    }
}
