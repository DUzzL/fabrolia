package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The crash report of a fail-stop is requested exactly once per selected winner:
 * once per pool generation, however many threads publish concurrently, and once
 * for the orphan slot. The report is diagnostic only and never alters W.
 */
class FailStopCrashReportsTest {

    private final List<Throwable> reported = new CopyOnWriteArrayList<>();
    /** Only throwables created by this test count; stray publishers are ignored. */
    private final Set<Throwable> ours = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));
    private Consumer<Throwable> previousSink;

    private Field poolField;
    private Field transitionPoolField;
    private Field orphanFatalField;
    private Object previousPool;
    private Object previousTransitionPool;
    private Object previousOrphanFatal;

    @BeforeEach
    void captureReports() throws Exception {
        previousSink = FailStopCrashReports.sink;
        FailStopCrashReports.sink = winner -> {
            if (ours.contains(winner)) reported.add(winner);
        };
        poolField = field("pool");
        transitionPoolField = field("transitionPool");
        orphanFatalField = field("orphanFatalFailure");
        previousPool = poolField.get(null);
        previousTransitionPool = transitionPoolField.get(null);
        previousOrphanFatal = orphanFatalField.get(null);
        poolField.set(null, null);
        transitionPoolField.set(null, null);
        orphanFatalField.set(null, null);
    }

    @AfterEach
    void restore() throws Exception {
        FailStopCrashReports.sink = previousSink;
        poolField.set(null, previousPool);
        transitionPoolField.set(null, previousTransitionPool);
        orphanFatalField.set(null, previousOrphanFatal);
    }

    @Test
    void concurrentPublishersReportOneWinnerPerPoolGeneration() throws Exception {
        RegionWorkerPool pool = new RegionWorkerPool(1);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> racers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            RuntimeException candidate = mine(new RuntimeException("candidate " + i));
            int mode = i % 3;
            Thread racer = Thread.ofPlatform().name("fatal-racer-" + i).unstarted(() -> {
                try {
                    start.await();
                    switch (mode) {
                        case 0 -> pool.publishFatalFailure(candidate);
                        case 1 -> pool.publishAndSelectFatal(candidate);
                        default -> pool.commitSuccessIfHealthy(() -> {
                            throw candidate;
                        });
                    }
                } catch (Throwable ignored) {
                    // commitSuccessIfHealthy rethrows W by design.
                }
            });
            racers.add(racer);
            racer.start();
        }
        start.countDown();
        for (Thread racer : racers) racer.join(10_000);

        Throwable winner = pool.fatalFailureOrNull();
        assertEquals(List.of(winner), reported,
                "exactly one report, for the generation's exact winner");

        RegionWorkerPool next = new RegionWorkerPool(1);
        RuntimeException nextWinner = mine(new RuntimeException("next generation"));
        next.publishFatalFailure(nextWinner);
        next.publishFatalFailure(mine(new RuntimeException("late loser")));
        assertEquals(List.of(winner, nextWinner), reported);
    }

    @Test
    void throwingReportNeverChangesTheWinnerOrEscapes() {
        FailStopCrashReports.sink = winner -> {
            throw new OutOfMemoryError("cannot start the writer");
        };
        RegionWorkerPool pool = new RegionWorkerPool(1);
        RuntimeException winner = new RuntimeException("W");

        pool.publishFatalFailure(winner);

        assertSame(winner, pool.fatalFailureOrNull());
        assertSame(winner, pool.publishAndSelectFatal(new RuntimeException("loser")));
    }

    @Test
    void orphanSlotReportsOnlyItsFirstWinner() throws Exception {
        RuntimeException first = mine(new RuntimeException("orphan W"));
        RuntimeException second = mine(new RuntimeException("orphan loser"));

        RegionScheduler.publishFatalFailure(first);
        RegionScheduler.publishFatalFailure(second);
        Throwable selected = RegionScheduler.publishFatalAndSelectWinner(
                mine(new RuntimeException("orphan select")));

        assertSame(first, orphanFatalField.get(null));
        assertSame(first, selected);
        assertEquals(List.of(first), reported);
    }

    @Test
    void orphanCommitFailureIsReportedOnce() throws Exception {
        RuntimeException failure = mine(new RuntimeException("orphan commit W"));

        assertThrows(RuntimeException.class,
                () -> RegionScheduler.commitSuccessIfHealthy(() -> {
                    throw failure;
                }));
        assertThrows(RuntimeException.class,
                () -> RegionScheduler.commitSuccessIfHealthy(() -> {}));

        assertSame(failure, orphanFatalField.get(null));
        assertEquals(List.of(failure), reported);
    }

    @Test
    void defaultWriterSkipsWithoutARunningServer() {
        assumeTrue(RegionScheduler.getServer() == null
                && com.axalotl.fabrolia.common.ParallelProcessor.getServer() == null);
        FailStopCrashReports.sink = previousSink;
        // No server directory: the production writer must return without a thread
        // or a crash-reports directory, and without throwing.
        FailStopCrashReports.winnerSelected(new RuntimeException("no server"));
    }

    @Test
    void reportUsesVanillaCrashReportLocation() {
        Path server = Path.of("srv");
        assertEquals(Path.of("srv", "crash-reports", "crash-2026-10-28_12.00.00-server.txt"),
                FailStopCrashReports.reportPath(server, "2026-10-28_12.00.00"));
    }

    private <T extends Throwable> T mine(T throwable) {
        ours.add(throwable);
        return throwable;
    }

    private static Field field(String name) throws Exception {
        Field field = RegionScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
