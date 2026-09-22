package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.Long2ByteConcurrentHashMap;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.LongToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

final class Long2ByteConcurrentHashMapComputeTest {
    @Test
    void computeDistinguishesAbsenceFromStoredDefault() {
        var map = new Long2ByteConcurrentHashMap();
        map.defaultReturnValue((byte) -7);
        assertEquals((byte) -7, map.compute(1L, (key, old) -> {
            assertNull(old);
            return (byte) -7;
        }));
        assertTrue(map.containsKey(1L));
        assertEquals((byte) 4, map.compute(1L, (key, old) -> {
            assertEquals(Byte.valueOf((byte) -7), old);
            return (byte) 4;
        }));
        assertEquals((byte) -7, map.compute(1L, (key, old) -> null));
        assertFalse(map.containsKey(1L));
    }

    @Test
    void conditionalRemappersTreatStoredDefaultAsPresent() {
        var map = new Long2ByteConcurrentHashMap();
        map.defaultReturnValue((byte) -7);
        map.put(1L, (byte) -7);
        assertEquals((byte) -7, map.computeIfAbsent(1L,
                (LongToIntFunction) key -> { throw new AssertionError("present key was remapped"); }));
        assertEquals((byte) 3, map.computeIfPresent(1L, (key, old) -> {
            assertEquals(Byte.valueOf((byte) -7), old);
            return (byte) 3;
        }));
        map.put(1L, (byte) -7);
        assertEquals((byte) 2, map.merge(1L, (byte) 9, (old, value) -> (byte) (old + value)));
        assertEquals((byte) -7, map.computeIfPresent(2L,
                (key, old) -> { throw new AssertionError("absent key was remapped"); }));
        assertEquals((byte) 9, map.merge(2L, (byte) 9,
                (old, value) -> { throw new AssertionError("absent key was merged"); }));
        map.put(1L, (byte) -7);
        assertEquals((byte) -7, map.computeIfPresent(1L, (key, old) -> null));
        assertFalse(map.containsKey(1L));
        map.put(1L, (byte) -7);
        assertEquals((byte) -7, map.merge(1L, (byte) 9, (old, value) -> null));
        assertFalse(map.containsKey(1L));
    }

    @Test
    void computeIfAbsentRejectsOutOfRangeWithoutPublishingTruncatedValue() {
        var map = new Long2ByteConcurrentHashMap();
        assertThrows(IllegalArgumentException.class,
                () -> map.computeIfAbsent(1L, (LongToIntFunction) key -> 128));
        assertFalse(map.containsKey(1L));
        assertEquals(Byte.MIN_VALUE,
                map.computeIfAbsent(1L, (LongToIntFunction) key -> Byte.MIN_VALUE));
        assertTrue(map.containsValue(Byte.MIN_VALUE));
    }

    @Test
    void nullComputeIfPresentCannotDeleteCompetingReplacement() throws Exception {
        nullRemapCannotDeleteCompetingReplacement(false);
    }

    @Test
    void nullMergeCannotDeleteCompetingReplacement() throws Exception {
        nullRemapCannotDeleteCompetingReplacement(true);
    }

    private static void nullRemapCannotDeleteCompetingReplacement(boolean merge) throws Exception {
        var map = new Long2ByteConcurrentHashMap();
        map.put(1L, (byte) 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        java.util.function.BiFunction<Byte, Byte, Byte> remapper = (old, value) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("remapper not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return null;
        };
        var removal = new FutureTask<Byte>(() -> merge
                ? map.merge(1L, (byte) 2, remapper)
                : map.computeIfPresent(1L, (key, old) -> remapper.apply(old, (byte) 2)));
        var replacement = new FutureTask<Byte>(() -> map.put(1L, (byte) 9));
        Thread remover = new Thread(removal, "byte-map-remap");
        Thread writer = new Thread(replacement, "byte-map-put");
        remover.setDaemon(true);
        writer.setDaemon(true);
        remover.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            writer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!replacement.isDone() && writer.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadline) Thread.yield();
            assertTrue(replacement.isDone() || writer.getState() == Thread.State.BLOCKED,
                    "competing writer did not reach the mutation");
            release.countDown();
            assertEquals(Byte.valueOf((byte) 0), removal.get(5, TimeUnit.SECONDS));
            replacement.get(5, TimeUnit.SECONDS);
            assertEquals((byte) 9, map.get(1L), "null remap deleted the later replacement");
        } finally {
            release.countDown();
            remover.join(5000);
            writer.join(5000);
            assertFalse(remover.isAlive());
            assertFalse(writer.isAlive());
        }
    }
}
