package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class Int2ObjectConcurrentHashMapContractTest {
    @Test
    void defaultValuedAbsenceDoesNotLookLikeAnExistingEntry() {
        var map = new Int2ObjectConcurrentHashMap<String>(16, 16);
        map.defaultReturnValue("missing");
        assertEquals("missing", map.putIfAbsent(0, "inserted"));
        assertEquals("inserted", map.get(0));
        assertFalse(map.remove(16, "missing"));
        assertFalse(map.replace(16, "missing", "replacement"));
        assertEquals("missing", map.computeIfPresent(16, (key, old) -> fail("absent callback")));
        assertEquals("created", map.compute(16, (key, old) -> {
            assertNull(old);
            return "created";
        }));
        assertEquals("merged", map.merge(32, "merged", (old, added) -> fail("absent callback")));
        assertEquals(3, map.size());
    }

    @Test
    void nullMappingsRemainPresentWithoutInflatingOrLosingSize() {
        var map = new Int2ObjectConcurrentHashMap<String>(16, 16);
        map.defaultReturnValue("missing");
        map.put(0, null);
        assertNull(map.putIfAbsent(0, "replacement"));
        assertNull(map.get(0));
        assertEquals(1, map.size());
        assertTrue(map.replace(0, null, "replaced"));
        assertEquals("replaced", map.put(0, null));
        assertEquals("missing", map.computeIfPresent(0, (key, old) -> fail("null mapping is not remapped")));
        assertNull(map.get(0));
        assertTrue(map.remove(0, null));
        assertTrue(map.isEmpty());
        map.put(0, null);
        assertEquals("missing", map.compute(0, (key, old) -> null));
        assertFalse(map.containsKey(0));
        assertEquals(0, map.size());
    }

    @Test
    void partialPrimitiveMappingFunctionDoesNotInsertAbsentDomainKey() {
        var map = new Int2ObjectConcurrentHashMap<String>(16, 16);
        map.defaultReturnValue("missing");
        Int2ObjectFunction<String> partial = new Int2ObjectFunction<>() {
            @Override public boolean containsKey(int key) { return key == 16; }
            @Override public String get(int key) { return key == 16 ? null : fail("outside function domain"); }
        };
        assertEquals("missing", map.computeIfAbsent(0, partial));
        assertFalse(map.containsKey(0));
        assertNull(map.computeIfAbsent(16, partial));
        assertTrue(map.containsKey(16));
        assertEquals(1, map.size());
    }

    @Test
    void clearDoesNotEraseCountOfInsertionIntoAlreadyClearedStripe() throws Exception {
        var map = new Int2ObjectConcurrentHashMap<String>(16, 16);
        PausedClearMap<String> pause = new PausedClearMap<>();
        // Stripes are created on first write: materialize stripe 15 before swapping its map.
        map.remove(15);
        Field segments = Int2ObjectConcurrentHashMap.class.getDeclaredField("segments");
        segments.setAccessible(true);
        ((Object[]) segments.get(map))[15] = pause;
        map.put(0, "old");
        FutureTask<Void> clear = new FutureTask<>(() -> { map.clear(); return null; });
        Thread thread = new Thread(clear, "int-map-clear-race");
        thread.setDaemon(true);
        thread.start();
        try {
            assertTrue(pause.entered.await(5, TimeUnit.SECONDS));
            map.put(0, "survives");
        } finally {
            pause.resume.countDown();
        }
        clear.get(5, TimeUnit.SECONDS);
        assertEquals("survives", map.get(0));
        assertEquals(1, map.size());
        assertFalse(map.isEmpty());
        assertEquals("survives", map.remove(0));
        assertTrue(map.isEmpty());
    }

    private static final class PausedClearMap<V> extends Int2ObjectOpenHashMap<V> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        @Override public void clear() {
            entered.countDown();
            try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            super.clear();
        }
    }
}
