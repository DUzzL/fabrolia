package com.axalotl.fabrolia.common.save;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class AsyncSaveWriterTest {

    /** Holds submitted drains until the test runs them (or never). */
    private static final class ManualExecutor implements Executor {
        final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
    }

    private static Path file(String name) {
        return Path.of("/save-writer-test", name);
    }

    @Test
    void jobsOfOneFileRunInSubmissionOrderAndNeverOverlap() {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            AsyncSaveWriter writer = new AsyncSaveWriter(4, 10_000, failure -> {}, pool);
            int files = 4;
            int perFile = 300;
            List<List<Integer>> order = new ArrayList<>();
            List<AtomicInteger> inFlight = new ArrayList<>();
            AtomicInteger overlaps = new AtomicInteger();
            for (int f = 0; f < files; f++) {
                order.add(Collections.synchronizedList(new ArrayList<>()));
                inFlight.add(new AtomicInteger());
            }
            for (int i = 0; i < perFile; i++) {
                for (int f = 0; f < files; f++) {
                    int file = f;
                    int seq = i;
                    writer.submit(file("f" + f), state -> {
                        if (inFlight.get(file).incrementAndGet() != 1) overlaps.incrementAndGet();
                        LockSupport.parkNanos((seq * 7919L % 50) * 1_000L);
                        order.get(file).add(seq);
                        inFlight.get(file).decrementAndGet();
                    });
                }
            }
            writer.awaitAll();
            assertEquals(0, overlaps.get());
            for (int f = 0; f < files; f++) {
                List<Integer> seen = order.get(f);
                assertEquals(perFile, seen.size());
                for (int i = 0; i < perFile; i++) assertEquals(i, seen.get(i));
            }
            assertEquals(0, writer.pendingCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentFilesAreWrittenInParallel() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AsyncSaveWriter writer = new AsyncSaveWriter(2, 100, failure -> {}, pool);
            CountDownLatch bothRunning = new CountDownLatch(2);
            AtomicReference<Boolean> overlapped = new AtomicReference<>(true);
            for (String name : List.of("a", "b")) {
                writer.submit(file(name), state -> {
                    bothRunning.countDown();
                    if (!bothRunning.await(10, TimeUnit.SECONDS)) overlapped.set(false);
                });
            }
            writer.awaitAll();
            assertTrue(overlapped.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void waiterWritesItsOwnFileInsteadOfQueueingBehindAnotherFile() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        try {
            AsyncSaveWriter writer = new AsyncSaveWriter(1, 100, failure -> {}, pool);
            AsyncSaveWriter.Ticket blocker = writer.submit(file("autosave-backlog"), state -> {
                blockerStarted.countDown();
                release.await();
            });
            assertTrue(blockerStarted.await(10, TimeUnit.SECONDS));
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            AsyncSaveWriter.Ticket logout = writer.submit(file("leaving-player"),
                    state -> ranOn.set(Thread.currentThread()));
            writer.await(logout);
            assertSame(Thread.currentThread(), ranOn.get());
            release.countDown();
            writer.await(blocker);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void awaitCoversEarlierJobsOfTheSameFile() {
        ManualExecutor never = new ManualExecutor();
        AsyncSaveWriter writer = new AsyncSaveWriter(1, 100, failure -> {}, never);
        List<String> written = new ArrayList<>();
        writer.submit(file("p"), state -> written.add("autosave"));
        writer.submit(file("other"), state -> written.add("other"));
        AsyncSaveWriter.Ticket logout = writer.submit(file("p"), state -> written.add("logout"));
        writer.await(logout);
        assertEquals(List.of("autosave", "logout"), written);
        writer.awaitAll();
        assertEquals(List.of("autosave", "logout", "other"), written);
        assertEquals(0, writer.pendingCount());
        // The queued pool drains find nothing left to do.
        never.runAll();
        assertEquals(3, written.size());
    }

    @Test
    void fullQueueFailsLoudlyAndRecoversAfterDraining() {
        ManualExecutor never = new ManualExecutor();
        AsyncSaveWriter writer = new AsyncSaveWriter(1, 3, failure -> {}, never);
        for (int i = 0; i < 3; i++) writer.submit(file("f" + i), state -> {});
        IllegalStateException full = assertThrows(IllegalStateException.class,
                () -> writer.submit(file("f3"), state -> {}));
        assertTrue(full.getMessage().contains("queue is full"));
        writer.awaitAll();
        writer.submit(file("f3"), state -> {});
        assertEquals(1, writer.pendingCount());
    }

    @Test
    void escapedJobFailureIsFatalAndDoesNotStopLaterWrites() {
        ManualExecutor never = new ManualExecutor();
        List<Throwable> fatal = new ArrayList<>();
        AsyncSaveWriter writer = new AsyncSaveWriter(1, 100, fatal::add, never);
        IllegalStateException boom = new IllegalStateException("encode failed");
        AsyncSaveWriter.Ticket failed = writer.submit(file("p"), state -> { throw boom; });
        List<String> written = new ArrayList<>();
        AsyncSaveWriter.Ticket next = writer.submit(file("p"), state -> written.add("next"));
        assertSame(boom, assertThrows(IllegalStateException.class, () -> writer.await(failed)));
        assertEquals(List.of(boom), fatal);
        writer.await(next);
        assertEquals(List.of("next"), written);
        assertFalse(fatal.size() > 1);
    }

    @Test
    void awaitAllReportsAFailureOfAnyAwaitedFile() {
        ManualExecutor never = new ManualExecutor();
        AsyncSaveWriter writer = new AsyncSaveWriter(1, 100, failure -> {}, never);
        writer.submit(file("ok"), state -> {});
        writer.submit(file("bad"), state -> { throw new IllegalArgumentException("bad"); });
        assertThrows(IllegalArgumentException.class, writer::awaitAll);
        assertEquals(0, writer.pendingCount());
    }

    @Test
    void durabilityScopes() {
        ManualExecutor never = new ManualExecutor();
        AsyncSaveWriter writer = new AsyncSaveWriter(1, 100, failure -> {}, never);
        List<String> written = new ArrayList<>();

        // Outside any scope a save is on disk before afterSubmit returns (vanilla).
        SaveDurability.afterSubmit(writer, writer.submit(file("p"), state -> written.add("plain")));
        assertEquals(List.of("plain"), written);

        // saveAll/autosave: returns after the snapshot; the flush barrier writes it.
        SaveDurability.runDeferred(() -> SaveDurability.afterSubmit(writer,
                writer.submit(file("p"), state -> written.add("autosave"))));
        assertEquals(List.of("plain"), written);
        writer.awaitAll();
        assertEquals(List.of("plain", "autosave"), written);

        // A logout batch writes when awaited (after the owner claim is released).
        SaveDurability.Batch logout = SaveDurability.openBatch(true);
        try {
            SaveDurability.afterSubmit(writer, writer.submit(file("p"), state -> written.add("logout")));
            assertEquals(2, written.size());
        } finally {
            logout.close();
        }
        logout.awaitDurable();
        assertEquals(List.of("plain", "autosave", "logout"), written);

        // A batch opened inside saveAll does not wait.
        SaveDurability.runDeferred(() -> {
            SaveDurability.Batch inSaveAll = SaveDurability.openBatch(false);
            try {
                SaveDurability.afterSubmit(writer, writer.submit(file("p"), state -> written.add("saveAll")));
            } finally {
                inSaveAll.close();
            }
            inSaveAll.awaitDurable();
        });
        assertEquals(3, written.size());
        writer.awaitAll();
        assertEquals(List.of("plain", "autosave", "logout", "saveAll"), written);
    }
}
