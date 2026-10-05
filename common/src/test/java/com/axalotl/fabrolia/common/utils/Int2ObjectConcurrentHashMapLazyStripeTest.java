package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stripes are created on first write; creation must not lose racing first writes. */
final class Int2ObjectConcurrentHashMapLazyStripeTest {

    @Test
    void racingFirstWritesIntoFreshStripesAreAllRetained() throws Exception {
        int threads = 8;
        int perThread = 64;
        for (int round = 0; round < 200; round++) {
            Int2ObjectConcurrentHashMap<Integer> map = new Int2ObjectConcurrentHashMap<>(16, 16);
            CountDownLatch start = new CountDownLatch(1);
            List<FutureTask<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t;
                FutureTask<Void> task = new FutureTask<>(() -> {
                    start.await();
                    // Every thread touches every stripe: key = base + threads * i.
                    for (int i = 0; i < perThread; i++) {
                        int key = base + threads * i;
                        if ((i & 1) == 0) map.put(key, Integer.valueOf(key));
                        else map.computeIfAbsent(key, (java.util.function.IntFunction<Integer>) Integer::valueOf);
                    }
                    return null;
                });
                tasks.add(task);
                Thread thread = new Thread(task, "lazy-stripe-writer-" + t);
                thread.setDaemon(true);
                thread.start();
            }
            start.countDown();
            for (FutureTask<Void> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(threads * perThread, map.size());
            for (int key = 0; key < threads * perThread; key++) {
                assertEquals(key, map.get(key));
            }
        }
    }

    @Test
    void neverWrittenStripesReadAsAbsentWithTheCurrentDefault() {
        Int2ObjectConcurrentHashMap<Object> map = new Int2ObjectConcurrentHashMap<>(16, 16);
        Object missing = new Object();
        Object fallback = new Object();
        assertNull(map.get(5));
        assertTrue(map.isEmpty());
        assertFalse(map.values().iterator().hasNext());
        assertTrue(map.keySet().isEmpty());
        assertTrue(map.int2ObjectEntrySet().isEmpty());
        map.clear();

        map.put(0, "zero");
        map.defaultReturnValue(missing);
        // Stripe 0 existed before the default changed; stripe 1 never did; stripe 2 is created after.
        assertSame(missing, map.get(16));
        assertSame(missing, map.get(1));
        assertNull(map.get((Object) 1));
        assertSame(fallback, map.getOrDefault(1, fallback));
        assertFalse(map.containsKey(1));
        assertFalse(map.containsValue(missing));
        map.put(2, "two");
        assertSame(missing, map.get(18));
        assertSame(missing, map.remove(3));
        assertEquals(2, map.size());
    }
}
