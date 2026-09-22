package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.utils.LockFreeAppendList;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LockFreeAppendListTest {

    @Test
    void growsPastFormerCapacityWithoutLosingInsertionOrder() {
        LockFreeAppendList<Integer> list = new LockFreeAppendList<>();
        int count = 65_537;
        for (int i = 0; i < count; i++) {
            list.add(i);
        }

        assertEquals(count, list.size());
        Iterator<Integer> iterator = list.iterator();
        for (int i = 0; i < count; i++) {
            assertEquals(i, list.get(i).intValue());
            assertEquals(i, iterator.next().intValue());
        }
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        int[] index = {0};
        list.forEach(value -> assertEquals(index[0]++, value.intValue()));
        assertEquals(count, index[0]);
    }

    @Test
    void traversalsKeepTheirInitialBoundAndCallbacksCanAppend() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            LockFreeAppendList<Integer> list = new LockFreeAppendList<>();
            for (int i = 0; i < 256; i++) list.add(i);
            Iterator<Integer> beforeAppend = list.iterator();
            list.add(256);
            for (int i = 0; i < 256; i++) {
                assertEquals(i, beforeAppend.next().intValue());
            }
            assertFalse(beforeAppend.hasNext());
            assertThrows(NoSuchElementException.class, beforeAppend::next);

            int[] visited = {0};
            list.forEach(value -> {
                assertEquals(visited[0]++, value.intValue());
                list.add(value);
            });
            assertEquals(257, visited[0]);
            assertEquals(514, list.size());
            for (int i = 0; i < 257; i++) {
                assertEquals(i, list.get(i + 257).intValue());
            }
        });
    }

    @Test
    void rejectedNullDoesNotReserveAnIndexAndClearAllowsReuse() {
        LockFreeAppendList<Integer> list = new LockFreeAppendList<>();
        list.add(1);
        assertThrows(NullPointerException.class, () -> list.add(null));
        assertEquals(List.of(1), list);
        // AbstractList's append-only bulk operation preserves its successful prefix.
        assertThrows(NullPointerException.class, () -> list.addAll(Arrays.asList(2, null, 3)));
        list.add(4);
        assertEquals(List.of(1, 2, 4), list);
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(3));
        assertThrows(UnsupportedOperationException.class, () -> list.add(0, 5));
        assertThrows(UnsupportedOperationException.class, () -> list.set(0, 5));
        Iterator<Integer> iterator = list.iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);

        list.clear();
        assertTrue(list.isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(0));
        list.add(5);
        assertEquals(List.of(5), list);
    }

    @Test
    void concurrentAppendsPublishCompleteOrderedPrefixesToEveryReadPath() throws Exception {
        int writers = 4;
        int readers = 3;
        int phases = 48;
        int perWriterPerPhase = 64;
        LockFreeAppendList<PublishedEntry> list = new LockFreeAppendList<>();
        CyclicBarrier phase = new CyclicBarrier(writers + readers);
        ExecutorService threads = Executors.newFixedThreadPool(writers + readers, runnable -> {
            Thread thread = new Thread(runnable, "append-publication-test");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<Void> completed = new ExecutorCompletionService<>(threads);
        try {
            for (int writer = 0; writer < writers; writer++) {
                int owner = writer;
                completed.submit(() -> {
                    for (int round = 0; round < phases; round++) {
                        phase.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < perWriterPerPhase; i++) {
                            list.add(new PublishedEntry(owner, round * perWriterPerPhase + i));
                        }
                        phase.await(10, TimeUnit.SECONDS);
                    }
                    return null;
                });
            }
            for (int reader = 0; reader < readers; reader++) {
                int readPath = reader;
                completed.submit(() -> {
                    for (int round = 0; round < phases; round++) {
                        // Reopen the append/read race at every former 256-slot boundary.
                        phase.await(10, TimeUnit.SECONDS);
                        int seen = verifyPublishedPrefix(list, writers, readPath);
                        int completedPrefix = round * writers * perWriterPerPhase;
                        assertTrue(seen >= completedPrefix);
                        assertTrue(seen <= completedPrefix + writers * perWriterPerPhase);
                        phase.await(10, TimeUnit.SECONDS);
                    }
                    return null;
                });
            }
            for (int task = 0; task < writers + readers; task++) {
                Future<Void> result = completed.poll(30, TimeUnit.SECONDS);
                assertNotNull(result, "append/read task did not complete");
                result.get();
            }
        } finally {
            threads.shutdownNow();
            phase.reset();
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(writers * phases * perWriterPerPhase, list.size());
        int[] expectedSequence = new int[writers];
        for (PublishedEntry entry : list) {
            assertEquals(expectedSequence[entry.writer]++, entry.sequence);
        }
        for (int sequence : expectedSequence) {
            assertEquals(phases * perWriterPerPhase, sequence);
        }
    }

    private static int verifyPublishedPrefix(LockFreeAppendList<PublishedEntry> list,
                                             int writers, int readPath) {
        int[] nextSequence = new int[writers];
        int[] index = {0};
        Consumer<PublishedEntry> check = entry -> {
            assertNotNull(entry);
            assertTrue(entry.writer >= 0 && entry.writer < writers);
            assertEquals(nextSequence[entry.writer]++, entry.sequence);
            assertEquals(signature(entry.writer, entry.sequence), entry.signature);
            assertSame(entry, list.get(index[0]++));
        };
        switch (readPath) {
            case 0 -> {
                int bound = list.size();
                for (int i = 0; i < bound; i++) check.accept(list.get(i));
            }
            case 1 -> {
                Iterator<PublishedEntry> iterator = list.iterator();
                while (iterator.hasNext()) check.accept(iterator.next());
                assertThrows(NoSuchElementException.class, iterator::next);
            }
            case 2 -> list.forEach(check);
            default -> throw new AssertionError("unknown read path");
        }
        return index[0];
    }

    private static long signature(int writer, int sequence) {
        return 0x5a5a000000000001L ^ ((long) writer << 32) ^ sequence;
    }

    private static final class PublishedEntry {
        // Non-final fields make the test check publication, not final-field semantics.
        private int writer;
        private int sequence;
        private long signature;

        private PublishedEntry(int writer, int sequence) {
            this.writer = writer;
            this.sequence = sequence;
            this.signature = signature(writer, sequence);
        }
    }
}
