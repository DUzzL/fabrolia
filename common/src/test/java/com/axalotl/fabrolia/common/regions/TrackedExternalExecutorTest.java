package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TrackedExternalExecutorTest {
    @Test
    void directAndCallerRunsCompleteAfterPublicationWithoutWaitingOnThemselves() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            ThreadPoolExecutor rejectionContext = new ThreadPoolExecutor(0, 1, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.SynchronousQueue<>());
            Executor callerRuns = body -> new ThreadPoolExecutor.CallerRunsPolicy()
                    .rejectedExecution(body, rejectionContext);
            for (Executor executor : List.<Executor>of(Runnable::run, callerRuns)) {
                RegionWorkerPool pool = new RegionWorkerPool(1);
                pool.start();
                try {
                    ArrayList<String> order = new ArrayList<>();
                    Thread submitting = Thread.currentThread();
                    pool.executeTrackedExternal(executor, () -> {
                        assertSame(submitting, Thread.currentThread());
                        assertTrue(RegionWorkerPool.currentThreadHasLiveCausalStep());
                        pool.runSuccessBodyIfHealthy(() -> order.add("body"));
                    }, () -> order.add("marker"));
                    order.add("returned");
                    assertEquals(List.of("marker", "body", "returned"), order);
                } finally {
                    pool.stop();
                }
            }
            rejectionContext.shutdownNow();
        });
    }

    @Test
    void delayedCallbackOnItsOriginalSubmittingThreadIsNotLost() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AtomicReference<Runnable> queued = new AtomicReference<>();
            AtomicInteger bodies = new AtomicInteger();
            pool.start();
            try {
                pool.executeTrackedExternal(queued::set, bodies::incrementAndGet);
                assertEquals(0, bodies.get());
                queued.get().run();
                assertEquals(1, bodies.get());
            } finally {
                pool.stop();
            }
        });
    }

    @Test
    void fatalCutCancelsDeferredMainControlBeforeNativePublication() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            RuntimeException failure = new RuntimeException("failure before native control replay");
            AtomicReference<Runnable> queued = new AtomicReference<>();
            AtomicInteger bodies = new AtomicInteger();
            AtomicInteger cancellations = new AtomicInteger();
            class Control implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
                @Override public void run() { bodies.incrementAndGet(); }
                @Override public void cancelQueuedBeforeStart() { cancellations.incrementAndGet(); }
            }
            pool.start();
            try {
                pool.pauseExactTurnAndAwait(() -> {});
                CompletableFuture.runAsync(() ->
                        pool.executeTrackedMainControl(queued::set, new Control())).join();
                assertNull(queued.get());
                pool.publishFatalFailure(failure);
                assertSame(failure, assertThrows(RuntimeException.class, pool::resumeExactTurn));
                assertNull(queued.get(), "cancelled roots cannot leave stale native queue carriers");
                assertEquals(0, bodies.get());
                assertEquals(1, cancellations.get());
            } finally {
                assertSame(failure, assertThrows(RuntimeException.class, pool::stop));
            }
            assertEquals(1, cancellations.get(), "stop must not cancel the retired root twice");
        });
    }

    @Test
    void inlineInvocationFollowedByExecutorFailureNeverEntersTheBody() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            RuntimeException failure = new RuntimeException("executor failed publication");
            AtomicInteger bodies = new AtomicInteger();
            pool.start();
            try {
                assertSame(failure, assertThrows(RuntimeException.class, () ->
                        pool.executeTrackedExternal(task -> {
                            task.run();
                            throw failure;
                        }, bodies::incrementAndGet)));
                assertEquals(0, bodies.get());
                assertSame(failure, pool.fatalFailureOrNull());
            } finally {
                assertSame(failure, assertThrows(RuntimeException.class, pool::stop));
            }
        });
    }
}
