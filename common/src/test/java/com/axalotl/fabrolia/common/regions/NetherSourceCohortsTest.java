package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NetherSourceCohortsTest {
    @Test
    void laterCommitCannotOvertakeAnOlderOfferingReceipt() {
        NetherSourceCohorts<String> source = new NetherSourceCohorts<>((item, failure) -> fail(item));
        source.stage("first");
        source.stage("second");
        NetherSourceCohorts.Prepared<String> older = source.prepare();
        assertFalse(older.isEmpty());
        assertNull(source.tryAcquireHead());

        source.stage("third");
        NetherSourceCohorts.Prepared<String> newer = source.prepare();
        newer.commit();
        assertNull(source.tryAcquireHead());
        older.commit();

        try (NetherSourceCohorts.Cohort<String> head = source.tryAcquireHead()) {
            assertNotNull(head);
            assertEquals(List.of("first", "second"), head.items());
            assertNull(source.tryAcquireHead());
            head.complete();
        }
        try (NetherSourceCohorts.Cohort<String> head = source.tryAcquireHead()) {
            assertNotNull(head);
            assertEquals(List.of("third"), head.items());
            head.complete();
        }
        assertNull(source.tryAcquireHead());
    }

    @Test
    void releasedAttemptPreservesHeadAndCannotConsumeItsReplacement() {
        NetherSourceCohorts<String> source = new NetherSourceCohorts<>((item, failure) -> fail(item));
        source.stage("older");
        source.prepare().commit();
        source.stage("newer");
        source.prepare().commit();

        NetherSourceCohorts.Cohort<String> abandoned = source.tryAcquireHead();
        assertNotNull(abandoned);
        assertThrows(UnsupportedOperationException.class, () -> abandoned.items().add("intruder"));
        abandoned.close();
        try (NetherSourceCohorts.Cohort<String> retry = source.tryAcquireHead()) {
            assertNotNull(retry);
            assertEquals(List.of("older"), retry.items());
            abandoned.close();
            abandoned.complete();
            assertNull(source.tryAcquireHead());
            retry.complete();
            retry.complete();
        }
        try (NetherSourceCohorts.Cohort<String> next = source.tryAcquireHead()) {
            assertNotNull(next);
            assertEquals(List.of("newer"), next.items());
            abandoned.complete();
            assertNull(source.tryAcquireHead());
            next.complete();
        }
        assertNull(source.tryAcquireHead());
    }

    @Test
    void publicationFailureCancelsEveryDetachedItemOnceDespiteCleanupFailures() {
        RuntimeException publicationFailure = new RuntimeException("source publication failed after offer");
        RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
        List<String> cancelled = new ArrayList<>();
        NetherSourceCohorts<String> source = new NetherSourceCohorts<>((item, failure) -> {
            assertSame(publicationFailure, failure);
            cancelled.add(item);
            if (item.equals("first")) throw cleanupFailure;
            if (item.equals("second")) throw publicationFailure;
        });
        source.stage("first");
        source.stage("second");
        source.stage("third");
        NetherSourceCohorts.Prepared<String> offered = source.prepare();
        source.stage("next");
        source.prepare().commit();
        assertNull(source.tryAcquireHead());

        assertSame(publicationFailure, offered.cancel(publicationFailure));
        assertSame(publicationFailure, offered.cancel(publicationFailure));
        offered.commit();
        assertEquals(List.of("first", "second", "third"), cancelled);
        assertArrayEquals(new Throwable[]{cleanupFailure}, publicationFailure.getSuppressed());
        try (NetherSourceCohorts.Cohort<String> head = source.tryAcquireHead()) {
            assertNotNull(head);
            assertEquals(List.of("next"), head.items());
            head.complete();
        }
        assertNull(source.tryAcquireHead());
    }

    @Test
    void cancellingOpenStageDoesNotCancelDetachedOrFutureStages() {
        RuntimeException failure = new RuntimeException("source failed");
        RuntimeException cleanup = new RuntimeException("cleanup failed");
        List<String> cancelled = new ArrayList<>();
        NetherSourceCohorts<String> source = new NetherSourceCohorts<>((item, cause) -> {
            assertSame(failure, cause);
            cancelled.add(item);
            if (item.equals("open first")) throw cleanup;
        });
        source.stage("detached");
        NetherSourceCohorts.Prepared<String> prepared = source.prepare();
        source.stage("open first");
        source.stage("open second");
        source.cancelOpen(failure);
        source.cancelOpen(failure);
        assertEquals(List.of("open first", "open second"), cancelled);
        assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
        assertNull(source.tryAcquireHead());

        prepared.commit();
        assertSame(failure, prepared.cancel(failure));
        source.stage("future");
        source.prepare().commit();
        try (NetherSourceCohorts.Cohort<String> head = source.tryAcquireHead()) {
            assertNotNull(head);
            assertEquals(List.of("detached"), head.items());
            head.complete();
        }
        try (NetherSourceCohorts.Cohort<String> head = source.tryAcquireHead()) {
            assertNotNull(head);
            assertEquals(List.of("future"), head.items());
            head.complete();
        }
        assertEquals(List.of("open first", "open second"), cancelled);
        assertNull(source.tryAcquireHead());
    }

    @Test
    void cancellationCallbacksCanUseTheSameSourceFromAnotherThread() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        List<String> cancelled = new ArrayList<>();
        List<Future<?>> callbacks = new ArrayList<>();
        List<NetherSourceCohorts<String>> sources = new ArrayList<>();
        RuntimeException failure = new RuntimeException("cancelled");
        NetherSourceCohorts<String> source = new NetherSourceCohorts<>((item, cause) -> {
            Future<?> callback = worker.submit(() -> {
                sources.get(0).stage("replacement for " + item);
                cancelled.add(item);
            });
            callbacks.add(callback);
            try {
                callback.get(5, TimeUnit.SECONDS);
            } catch (Exception blocked) {
                throw new AssertionError("Cancellation held the source monitor", blocked);
            }
        });
        sources.add(source);
        try {
            source.stage("detached");
            source.prepare().cancel(failure);
            source.cancelOpen(failure);
            for (Future<?> callback : callbacks) callback.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new Throwable[0], failure.getSuppressed());
            assertEquals(List.of("detached", "replacement for detached"), cancelled);
            source.prepare().commit();
            try (NetherSourceCohorts.Cohort<String> head = source.tryAcquireHead()) {
                assertNotNull(head);
                assertEquals(List.of("replacement for replacement for detached"), head.items());
                head.complete();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void oppositeSourcesBackOutAndReacquireWithoutHoldingEachOther() throws Exception {
        NetherSourceCohorts<String> overworld = new NetherSourceCohorts<>((item, failure) -> fail(item));
        NetherSourceCohorts<String> nether = new NetherSourceCohorts<>((item, failure) -> fail(item));
        overworld.stage("outbound");
        overworld.prepare().commit();
        nether.stage("returning");
        nether.prepare().commit();
        CyclicBarrier phases = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> outbound = workers.submit(() -> {
                backOutAndRetry(overworld, nether, "outbound", phases);
                return null;
            });
            Future<?> returning = workers.submit(() -> {
                backOutAndRetry(nether, overworld, "returning", phases);
                return null;
            });
            outbound.get(5, TimeUnit.SECONDS);
            returning.get(5, TimeUnit.SECONDS);
            assertNull(overworld.tryAcquireHead());
            assertNull(nether.tryAcquireHead());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentSourceWritersAreAllIncludedWhenTheirPhaseCloses() throws Exception {
        NetherSourceCohorts<Integer> source = new NetherSourceCohorts<>((item, failure) -> fail(item.toString()));
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService writers = Executors.newFixedThreadPool(2);
        try {
            Future<?> even = writers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int value = 0; value < 100; value += 2) source.stage(value);
                return null;
            });
            Future<?> odd = writers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int value = 1; value < 100; value += 2) source.stage(value);
                return null;
            });
            even.get(5, TimeUnit.SECONDS);
            odd.get(5, TimeUnit.SECONDS);
            source.prepare().commit();
            try (NetherSourceCohorts.Cohort<Integer> head = source.tryAcquireHead()) {
                assertNotNull(head);
                HashSet<Integer> expected = new HashSet<>();
                for (int value = 0; value < 100; value++) expected.add(value);
                assertEquals(expected, new HashSet<>(head.items()));
                assertEquals(expected.size(), head.items().size());
                int lastEven = -2;
                int lastOdd = -1;
                for (int value : head.items()) {
                    if (value % 2 == 0) {
                        assertEquals(lastEven + 2, value);
                        lastEven = value;
                    } else {
                        assertEquals(lastOdd + 2, value);
                        lastOdd = value;
                    }
                }
                head.complete();
            }
            assertNull(source.tryAcquireHead());
        } finally {
            writers.shutdownNow();
        }
    }

    @Test
    void emptyPreparationDoesNotBlockLaterAcceptedItems() {
        NetherSourceCohorts<String> source = new NetherSourceCohorts<>((item, failure) -> fail(item));
        NetherSourceCohorts.Prepared<String> empty = source.prepare();
        assertTrue(empty.isEmpty());
        source.stage("later");
        source.prepare().commit();
        try (NetherSourceCohorts.Cohort<String> head = source.tryAcquireHead()) {
            assertNotNull(head);
            assertEquals(List.of("later"), head.items());
            head.complete();
        }
        empty.commit();
        assertNull(source.tryAcquireHead());
    }

    private static void backOutAndRetry(NetherSourceCohorts<String> own,
                                       NetherSourceCohorts<String> opposite,
                                       String item, CyclicBarrier phases) throws Exception {
        try (NetherSourceCohorts.Cohort<String> attempt = own.tryAcquireHead()) {
            assertNotNull(attempt);
            assertEquals(List.of(item), attempt.items());
            phases.await(5, TimeUnit.SECONDS);
            assertNull(opposite.tryAcquireHead());
            phases.await(5, TimeUnit.SECONDS);
        }
        phases.await(5, TimeUnit.SECONDS);
        try (NetherSourceCohorts.Cohort<String> retry = own.tryAcquireHead()) {
            assertNotNull(retry);
            assertEquals(List.of(item), retry.items());
            retry.complete();
        }
    }
}
