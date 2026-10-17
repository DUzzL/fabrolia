package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyOnWriteInt2ObjectMapTest {

    @Test
    void firstCleanupAfterAReadOnlyPrefixKeepsTheNativeIteratorPosition() {
        Int2ObjectMap<Integer> vanilla = new Int2ObjectOpenHashMap<>();
        CopyOnWriteInt2ObjectMap<Integer> published = new CopyOnWriteInt2ObjectMap<>(16);
        for (int key = 0; key < 200; key++) {
            vanilla.put(key, Integer.valueOf(key));
            published.put(key, Integer.valueOf(key));
        }
        var nativeCursor = vanilla.values().iterator();
        var cursor = published.mutableValuesIterator();
        int visited = 0;
        while (nativeCursor.hasNext()) {
            assertEquals(nativeCursor.next(), cursor.next());
            if (++visited > 63 && visited % 3 == 0) {
                nativeCursor.remove();
                cursor.remove();
            }
            assertEquals(new ArrayList<>(vanilla.values()), new ArrayList<>(published.values()));
        }
        assertFalse(cursor.hasNext());
    }

    @Test
    void simultaneousOwnerCleanupNeverOverwritesAnotherOwnersNewRegistrations() throws Exception {
        CopyOnWriteInt2ObjectMap<Integer> map = new CopyOnWriteInt2ObjectMap<>(16);
        for (int id = 0; id < 400; id++) map.put(id, Integer.valueOf(id));
        var admittedValues = map.values();
        var captured = new java.util.concurrent.CyclicBarrier(4);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<?>> results = new ArrayList<>();
            for (int owner = 0; owner < 4; owner++) {
                int lane = owner;
                results.add(workers.submit(() -> {
                    var cursor = map.mutableValuesIterator();
                    captured.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    while (cursor.hasNext()) {
                        int value = cursor.next();
                        if (value % 4 != lane) continue;
                        cursor.remove();
                        map.put(value + 1000, Integer.valueOf(value + 1000));
                    }
                    return null;
                }));
            }
            for (var result : results) result.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(400, admittedValues.size(), "published reader snapshots stay immutable");
        assertTrue(admittedValues.contains(0));
        assertEquals(400, map.size());
        for (int id = 0; id < 400; id++) {
            assertFalse(map.containsKey(id));
            assertEquals(id + 1000, map.get(id + 1000));
        }
    }

    @Test
    void lifecycleCursorPreservesOtherIslandsRegistrationsAndReplacements() {
        CopyOnWriteInt2ObjectMap<Object> map = new CopyOnWriteInt2ObjectMap<>(16);
        Object first = new Object();
        Object second = new Object();
        map.put(1, first);
        map.put(2, second);
        var cursor = map.mutableValuesIterator();
        Object admitted = cursor.next();
        int key = admitted == first ? 1 : 2;
        Object replacement = new Object();
        Object foreign = new Object();
        map.put(key, replacement);
        map.put(99, foreign);
        cursor.remove();
        assertSame(replacement, map.get(key), "cleanup must not remove a replacement registration");
        assertSame(foreign, map.get(99));
        Object remaining = cursor.next();
        cursor.remove();
        assertFalse(map.containsValue(remaining));
        assertEquals(2, map.size(), "publishing a later removal must retain both outside writes");
        assertSame(replacement, map.get(key));
        assertSame(foreign, map.get(99));
        assertFalse(cursor.hasNext(), "a phase does not admit newly registered values midway");
    }

    @Test
    void stoppedRaidIteratorRemovalsRetainNativeOrderAndTableCapacity() {
        Int2ObjectMap<Object> vanilla = new Int2ObjectOpenHashMap<>();
        CopyOnWriteInt2ObjectMap<Object> published = new CopyOnWriteInt2ObjectMap<>(16);
        for (int key = 0; key < 200; key++) {
            Object raid = new Object();
            vanilla.put(key, raid);
            published.put(key, raid);
        }
        var iterator = vanilla.values().iterator();
        var publishedIterator = published.mutableValuesIterator();
        while (iterator.hasNext()) {
            Object raid = iterator.next();
            assertSame(raid, publishedIterator.next());
            iterator.remove();
            publishedIterator.remove();
            assertFalse(published.removeValueIdentity(raid));
            assertEquals(new ArrayList<>(vanilla.values()), new ArrayList<>(published.values()));
        }
        // Iterator cleanup does not shrink like keyed removal. Future inserts
        // must still encounter the same slots, even after an empty registry.
        for (int key = 2531; key < 2600; key++) {
            Object raid = new Object();
            vanilla.put(key, raid);
            published.put(key, raid);
            assertEquals(new ArrayList<>(vanilla.values()), new ArrayList<>(published.values()));
        }
    }

    @Test
    void encounterOrderMatchesTheVanillaTableAcrossResizesAndRemovals() {
        Int2ObjectMap<Integer> vanilla = new Int2ObjectOpenHashMap<>();
        Int2ObjectMap<Integer> published = new CopyOnWriteInt2ObjectMap<>(16);
        // A raid tie keeps the FIRST encountered active raid; identical key/value
        // contents alone do not preserve village AI's raid/pre_raid selection.
        for (int key = 2531; key < 2660; key++) {
            vanilla.put(key, Integer.valueOf(key));
            published.put(key, Integer.valueOf(key));
            assertEquals(new ArrayList<>(vanilla.values()), new ArrayList<>(published.values()), "insert " + key);
        }
        for (int key = 2531; key < 2660; key += 2) {
            vanilla.remove(key);
            published.remove(key);
            assertEquals(new ArrayList<>(vanilla.values()), new ArrayList<>(published.values()), "remove " + key);
        }
        vanilla.clear();
        published.clear();
        for (int key = 2560; key < 2600; key++) {
            vanilla.put(key, Integer.valueOf(key));
            published.put(key, Integer.valueOf(key));
            assertEquals(new ArrayList<>(vanilla.values()), new ArrayList<>(published.values()), "after clear " + key);
        }
    }

    @Test
    void mapOperationsFollowTheInt2ObjectMapContract() {
        Int2ObjectMap<String> map = new CopyOnWriteInt2ObjectMap<>();
        assertNull(map.get(3));
        assertNull(map.put(3, "a"));
        assertEquals("a", map.put(3, "b"));
        assertEquals("b", map.get(3));
        assertEquals("b", map.computeIfAbsent(3, k -> "c"));
        assertEquals("x", map.computeIfAbsent(-4, k -> "x"));
        assertEquals(2, map.size());
        assertEquals("b", map.remove(3));
        assertNull(map.remove(3));
        assertFalse(map.containsKey(3));
        map.defaultReturnValue("none");
        assertEquals("none", map.get(99));
        assertEquals("none", map.remove(99));
        map.put(7, null);
        assertTrue(map.containsKey(7));
        assertNull(map.get(7), "a stored null is not the default return value");
        map.clear();
        assertTrue(map.isEmpty());
    }

    @Test
    void iterationIsASnapshotAndReadOnly() {
        Int2ObjectMap<String> map = new CopyOnWriteInt2ObjectMap<>();
        map.put(1, "a");
        map.put(2, "b");
        Iterator<Int2ObjectMap.Entry<String>> it = map.int2ObjectEntrySet().iterator();
        map.put(3, "c");
        map.remove(1);
        int seen = 0;
        while (it.hasNext()) {
            it.next();
            seen++;
        }
        assertEquals(2, seen, "an iterator walks the snapshot it started on");
        Iterator<Int2ObjectMap.Entry<String>> live = map.int2ObjectEntrySet().iterator();
        Int2ObjectMap.Entry<String> entry = live.next();
        assertThrows(UnsupportedOperationException.class, live::remove);
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue("z"),
                "a published snapshot must never be written through an entry");
        map.replaceAll((k, v) -> v + "!");
        assertEquals("b!", map.get(2));
    }

    @Test
    void racingComputeIfAbsentCreatesOneValuePerKey() throws Exception {
        for (int round = 0; round < 200; round++) {
            Int2ObjectMap<Object> map = new CopyOnWriteInt2ObjectMap<>();
            int threads = 8;
            AtomicInteger created = new AtomicInteger();
            Object[][] results = new Object[threads][4];
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> all = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int slot = t;
                Thread th = new Thread(() -> {
                    try { go.await(); } catch (InterruptedException ignored) { }
                    for (int key = 0; key < 4; key++) {
                        results[slot][key] = map.computeIfAbsent(key, k -> {
                            created.incrementAndGet();
                            return new Object();
                        });
                    }
                });
                th.start();
                all.add(th);
            }
            go.countDown();
            for (Thread th : all) th.join(10_000);
            assertEquals(4, created.get(), "each key's value is created exactly once");
            for (int key = 0; key < 4; key++) {
                for (int t = 1; t < threads; t++) assertSame(results[0][key], results[t][key]);
            }
        }
    }

    @Test
    void removalRacingCreationNeverLosesAWrite() throws Exception {
        CopyOnWriteInt2ObjectMap<Integer> map = new CopyOnWriteInt2ObjectMap<>();
        ConcurrentHashMap<Integer, Boolean> expected = new ConcurrentHashMap<>();
        CountDownLatch go = new CountDownLatch(1);
        Thread adder = new Thread(() -> {
            try { go.await(); } catch (InterruptedException ignored) { }
            for (int i = 0; i < 2000; i += 2) {
                map.put(i, Integer.valueOf(i));
                expected.put(i, true);
            }
        });
        Thread remover = new Thread(() -> {
            try { go.await(); } catch (InterruptedException ignored) { }
            for (int i = 1; i < 2000; i += 2) {
                map.put(i, Integer.valueOf(i));
                map.remove(i);
            }
        });
        adder.start();
        remover.start();
        go.countDown();
        adder.join(10_000);
        remover.join(10_000);
        assertEquals(expected.size(), map.size());
        for (int key : expected.keySet()) assertEquals(key, map.get(key));
    }
}
