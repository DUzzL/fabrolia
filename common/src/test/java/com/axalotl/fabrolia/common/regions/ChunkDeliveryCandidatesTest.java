package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDeliveryCandidatesTest {
    private enum State { TRACKED, REQUESTED, READY, RESERVED, ADMITTED, ENQUEUED,
        ACKNOWLEDGED, CANCELLED, REPLACED }

    private static final class Record {
        final long key;
        volatile State state = State.TRACKED;

        Record(int x, int z) {
            key = pack(x, z);
        }

        boolean terminal() {
            return state == State.ACKNOWLEDGED || state == State.CANCELLED
                    || state == State.REPLACED;
        }
    }

    private static ChunkDeliveryCandidates<Record> candidates(Collection<Record> records) {
        return new ChunkDeliveryCandidates<>(records, r -> r.key, Record::terminal);
    }

    @Test
    void movementAndTiesMatchStableFullMapSortWithoutTerminalRecords() {
        ConcurrentHashMap<Long, Record> records = new ConcurrentHashMap<>();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                Record r = new Record(x, z);
                if ((x + z) % 3 == 0) r.state = State.ACKNOWLEDGED;
                records.put(r.key, r);
            }
        }
        ChunkDeliveryCandidates<Record> candidates = candidates(records.values());
        for (long position : new long[]{pack(0, 0), pack(3, -2), pack(-4, 3), pack(0, 0)}) {
            assertEquals(oldCandidates(records.values(), position), candidates.prepare(position));
        }
        Record replacement = new Record(1, 1);
        records.get(replacement.key).state = State.REPLACED;
        records.put(replacement.key, replacement);
        candidates.changed();
        assertEquals(oldCandidates(records.values(), pack(0, 0)), candidates.prepare(pack(0, 0)));
    }

    @Test
    void reentrantPreparationCannotChangeTheEnteredSendersCandidateOrder() {
        Record first = new Record(0, 0);
        Record second = new Record(1, 0);
        Record third = new Record(2, 0);
        ArrayList<Record> records = new ArrayList<>(List.of(first, second, third));
        ChunkDeliveryCandidates<Record> candidates = candidates(records);
        List<Record> visited = new ArrayList<>();
        for (Record record : candidates.prepare(0)) {
            visited.add(record);
            if (record == first) {
                second.state = State.ACKNOWLEDGED;
                Record added = new Record(10, 0);
                records.add(added);
                candidates.changed();
                assertEquals(List.of(added, third, first), candidates.prepare(pack(10, 0)));
            }
        }
        assertEquals(List.of(first, second, third), visited,
                "a nested sender may not mutate an already-entered candidate snapshot");
    }

    @Test
    void onlyIrreversibleStatesArePrunedAndEligibleRevivalNeedsNoNotification() {
        Record requested = new Record(0, 0);
        Record reserved = new Record(1, 0);
        Record admitted = new Record(2, 0);
        Record enqueued = new Record(3, 0);
        Record acked = new Record(4, 0);
        Record cancelled = new Record(5, 0);
        Record replaced = new Record(6, 0);
        requested.state = State.REQUESTED;
        reserved.state = State.RESERVED;
        admitted.state = State.ADMITTED;
        enqueued.state = State.ENQUEUED;
        acked.state = State.ACKNOWLEDGED;
        cancelled.state = State.CANCELLED;
        replaced.state = State.REPLACED;
        ChunkDeliveryCandidates<Record> candidates = candidates(List.of(
                requested, reserved, admitted, enqueued, acked, cancelled, replaced));
        List<Record> live = List.of(requested, reserved, admitted, enqueued);
        assertEquals(live, candidates.prepare(0));
        requested.state = State.TRACKED; // REQUESTED timeout must keep making progress.
        reserved.state = State.READY; // A failed reservation can return to eligibility.
        assertEquals(live, candidates.prepare(0));
        requested.state = State.READY;
        reserved.state = State.TRACKED; // A stale seal retries without structural mutation.
        enqueued.state = State.ACKNOWLEDGED;
        assertEquals(List.of(requested, reserved, admitted), candidates.prepare(0));
    }

    @Test
    void replacementAfterTransientPublicationGapCannotLoseTheNewIdentity() {
        ConcurrentHashMap<Long, Record> records = new ConcurrentHashMap<>();
        Record old = new Record(0, 0);
        records.put(old.key, old);
        ChunkDeliveryCandidates<Record> candidates = candidates(records.values());
        assertEquals(List.of(old), candidates.prepare(0));
        old.state = State.REPLACED;
        assertEquals(List.of(), candidates.prepare(0));
        Record replacement = new Record(0, 0);
        records.put(replacement.key, replacement);
        candidates.changed(); // Publication notification follows the map transaction.
        assertEquals(List.of(replacement), candidates.prepare(0));
        replacement.state = State.CANCELLED;
        records.clear();
        candidates.changed();
        assertEquals(List.of(), candidates.prepare(0));
        Record newWorld = new Record(0, 0);
        records.put(newWorld.key, newWorld);
        candidates.changed();
        assertEquals(List.of(newWorld), candidates.prepare(0));
    }

    @Test
    void concurrentPublicationCannotBeConsumedByAnOlderPreparationGeneration() throws Exception {
        ConcurrentHashMap<Long, Record> records = new ConcurrentHashMap<>();
        Record old = new Record(0, 0);
        records.put(old.key, old);
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Collection<Record> pausedSnapshot = new AbstractCollection<>() {
            @Override
            public Iterator<Record> iterator() {
                Iterator<Record> snapshot = new ArrayList<>(records.values()).iterator();
                captured.countDown();
                await(resume);
                return snapshot;
            }

            @Override
            public int size() {
                return records.size();
            }
        };
        ChunkDeliveryCandidates<Record> candidates = candidates(pausedSnapshot);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> new ArrayList<>(candidates.prepare(0)));
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            Record added = new Record(1, 0);
            records.put(added.key, added);
            candidates.changed();
            resume.countDown();
            assertEquals(List.of(old), first.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(old, added), candidates.prepare(0));
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    private static ArrayList<Record> oldCandidates(Collection<Record> records, long position) {
        ArrayList<Record> result = new ArrayList<>(records);
        result.sort((a, b) -> Long.compare(distance(a.key, position), distance(b.key, position)));
        result.removeIf(Record::terminal);
        return result;
    }

    private static long pack(int x, int z) {
        return (x & 0xffffffffL) | ((long) z << 32);
    }

    private static long distance(long key, long position) {
        long dx = (int) key - (int) position;
        long dz = (int) (key >>> 32) - (int) (position >>> 32);
        return dx * dx + dz * dz;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("preparation timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
