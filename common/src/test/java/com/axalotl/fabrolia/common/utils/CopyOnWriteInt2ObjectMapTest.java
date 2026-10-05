package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
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
