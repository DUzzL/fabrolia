package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2LongConcurrentHashMap;
import it.unimi.dsi.fastutil.longs.AbstractLong2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class Long2LongPrimitiveSnapshotTest {

    @Test
    void copiesPrimitivePairsAndOverwritesReusableBuffers() {
        Long2LongConcurrentHashMap map = new Long2LongConcurrentHashMap();
        map.defaultReturnValue(Long.MAX_VALUE);
        for (long key = 1; key <= 512; key++) {
            map.put(key, valueFor(key));
        }

        LongArrayList keys = new LongArrayList();
        LongArrayList values = new LongArrayList();
        keys.add(-101L);
        values.add(-202L);
        map.copyPrimitiveEntries(keys, values);

        assertEquals(keys.size(), values.size(), "key/value indexes must stay aligned");
        assertEquals(512, keys.size());
        assertSnapshotPairs(keys, values);
        assertTrue(!keys.contains(-101L) && !values.contains(-202L),
                "copy must clear reusable buffers before filling them");

        map.clear();
        map.put(9001L, valueFor(9001L));
        map.copyPrimitiveEntries(keys, values);
        assertEquals(1, keys.size());
        assertEquals(1, values.size());
        assertEquals(9001L, keys.getLong(0));
        assertEquals(valueFor(9001L), values.getLong(0));
        assertThrows(IllegalArgumentException.class,
                () -> map.copyPrimitiveEntries(keys, keys));
    }

    @Test
    void entrySetContainsMappingWhoseValueEqualsDefaultReturnValue() {
        Long2LongConcurrentHashMap map = new Long2LongConcurrentHashMap();
        map.defaultReturnValue(0L);
        map.put(41L, 0L);

        assertTrue(map.long2LongEntrySet().contains(
                new AbstractLong2LongMap.BasicEntry(41L, 0L)));
        assertFalse(map.long2LongEntrySet().contains(
                new AbstractLong2LongMap.BasicEntry(41L, 1L)));

        map.remove(41L);
        assertFalse(map.long2LongEntrySet().contains(
                new AbstractLong2LongMap.BasicEntry(41L, 0L)));
    }

    @Test
    void snapshotRemainsFrozenWhileMapIsMutated() {
        Long2LongConcurrentHashMap map = new Long2LongConcurrentHashMap();
        map.defaultReturnValue(Long.MAX_VALUE);
        map.put(7L, valueFor(7L));
        LongArrayList keys = new LongArrayList();
        LongArrayList values = new LongArrayList();

        map.copyPrimitiveEntries(keys, values);
        map.remove(7L);
        map.put(8L, valueFor(8L));

        assertEquals(1, keys.size());
        assertEquals(7L, keys.getLong(0));
        assertEquals(valueFor(7L), values.getLong(0));
        assertNotEquals(map.get(7L), values.getLong(0));
    }

    @Test
    void concurrentRelocationNeverPublishesDuplicateKeysTornPairsOrLengths() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            int expectedSize = 4096;
            Long2LongConcurrentHashMap map = new Long2LongConcurrentHashMap(expectedSize);
            map.defaultReturnValue(Long.MAX_VALUE);
            long[] collision = collidingKeysForInitialTable(expectedSize);
            long moving = collision[0];
            long blocker = collision[1];
            long filler = collision[2];
            map.put(moving, valueFor(moving));
            map.put(blocker, valueFor(blocker));
            for (long key = 1; key <= 2048; key++) {
                map.put(key, valueFor(key));
            }

            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        // Move one key from its home slot to the far side of
                        // the same probe cluster and back. A lock-free table
                        // scan can otherwise observe both positions.
                        map.remove(moving);
                        map.put(filler, valueFor(filler));
                        map.put(moving, valueFor(moving));
                        map.remove(filler);
                        map.remove(moving);
                        map.put(moving, valueFor(moving));
                    }
                } catch (Throwable t) {
                    writerFailure.set(t);
                }
            }, "primitive-snapshot-writer");
            writer.start();

            LongArrayList keys = new LongArrayList();
            LongArrayList values = new LongArrayList();
            start.countDown();
            try {
                for (int iteration = 0; iteration < 2_000; iteration++) {
                    map.copyPrimitiveEntries(keys, values);
                    assertEquals(keys.size(), values.size(),
                            "concurrent snapshot returned mismatched buffer lengths");
                    assertSnapshotPairs(keys, values);
                }
            } finally {
                stop.set(true);
                writer.join(5_000L);
            }
            if (writer.isAlive()) fail("writer did not stop");
            if (writerFailure.get() != null) fail("writer failed", writerFailure.get());
        });
    }

    private static long[] collidingKeysForInitialTable(int expectedSize) {
        int requestedSegments = Math.max(8,
                Math.min(64, Runtime.getRuntime().availableProcessors() * 2));
        int segmentCount = Integer.highestOneBit(requestedSegments - 1) << 1;
        int segmentBits = Integer.numberOfTrailingZeros(segmentCount);
        int perSegmentEntries = Math.max(8, expectedSize / segmentCount);
        int capacity = 16;
        while (capacity < perSegmentEntries * 2) {
            capacity <<= 1;
        }
        int tableMask = capacity - 1;
        Map<Integer, long[]> buckets = new HashMap<>();
        for (long key = 10_000L; ; key++) {
            int hash = mapHash(key);
            int home = hash & tableMask;
            if (home > tableMask - 8) {
                continue;
            }
            int segment = (hash >>> (32 - segmentBits)) & (segmentCount - 1);
            int signature = (segment << 16) | home;
            long[] bucket = buckets.computeIfAbsent(signature, ignored -> new long[3]);
            if (bucket[0] == 0L) {
                bucket[0] = key;
            } else if (bucket[1] == 0L) {
                bucket[1] = key;
            } else {
                bucket[2] = key;
                return bucket;
            }
        }
    }

    private static int mapHash(long value) {
        value *= 0xC6BC279692B5C323L;
        return (int) (value ^ (value >>> 32));
    }

    private static void assertSnapshotPairs(LongArrayList keys, LongArrayList values) {
        Map<Long, Long> firstValues = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            long key = keys.getLong(i);
            long value = values.getLong(i);
            assertEquals(valueFor(key), value,
                    "snapshot exposed a key paired with another slot's value");
            assertNull(firstValues.putIfAbsent(key, value),
                    "snapshot must not contain duplicate key " + key);
        }
    }

    /** Vanilla LevelTicks.sortContainersToTick re-indexes through Entry.setValue. */
    @Test
    void fastIteratorEntrySetValueWritesThrough() {
        Long2LongConcurrentHashMap map = new Long2LongConcurrentHashMap();
        map.defaultReturnValue(Long.MAX_VALUE);
        map.put(7L, 10L);
        var iterator = it.unimi.dsi.fastutil.longs.Long2LongMaps.fastIterator(map);
        var entry = iterator.next();
        assertEquals(10L, entry.setValue(25L));
        assertEquals(25L, entry.getLongValue());
        assertEquals(25L, map.get(7L));
        iterator.remove();
        assertFalse(map.containsKey(7L), "removal after setValue must match the new mapping");
    }

    /** The cell index behind scheduled-tick discovery: after any sequence of
     *  every mutating entry point, each group holds exactly the map's keys of
     *  that group. */
    @Test
    void keyGroupsFollowEveryKeyTransition() {
        Long2LongConcurrentHashMap map = new Long2LongConcurrentHashMap(16, key -> key >> 4);
        map.defaultReturnValue(Long.MAX_VALUE);
        Map<Long, Long> reference = new HashMap<>();
        java.util.Random random = new java.util.Random(20261025L);
        for (int step = 0; step < 50_000; step++) {
            long key = random.nextInt(256);
            long value = random.nextInt(4);
            switch (random.nextInt(10)) {
                case 0 -> { map.put(key, value); reference.put(key, value); }
                case 1 -> { map.remove(key); reference.remove(key); }
                case 2 -> {
                    if (map.remove(key, value)) reference.remove(key);
                }
                case 3 -> {
                    map.compute(key, (k, old) -> value == 0 ? null : (Long) value);
                    if (value == 0) reference.remove(key); else reference.put(key, value);
                }
                case 4 -> {
                    map.computeIfPresent(key, (k, old) -> value == 0 ? null : (Long) value);
                    if (reference.containsKey(key)) {
                        if (value == 0) reference.remove(key); else reference.put(key, value);
                    }
                }
                case 5 -> {
                    map.merge(key, value, (old, given) -> given == 0 ? null : given);
                    if (!reference.containsKey(key)) reference.put(key, value);
                    else if (value == 0) reference.remove(key);
                    else reference.put(key, value);
                }
                case 6 -> { map.computeIfAbsent(key, k -> value); reference.putIfAbsent(key, value); }
                case 7 -> { map.putIfAbsent(key, value); reference.putIfAbsent(key, value); }
                case 8 -> {
                    if (map.keySet().remove(key)) reference.remove(key);
                }
                default -> {
                    if (random.nextInt(500) == 0) { map.clear(); reference.clear(); }
                    else { map.replace(key, value); reference.computeIfPresent(key, (k, v) -> value); }
                }
            }
        }
        assertGroupsMatch(map, reference.keySet(), 16, 4);
    }

    @Test
    void concurrentWritersOfOneGroupKeepItExact() throws Exception {
        Long2LongConcurrentHashMap map = new Long2LongConcurrentHashMap(16, key -> key >> 6);
        map.defaultReturnValue(Long.MAX_VALUE);
        int writers = 4;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[writers];
        for (int w = 0; w < writers; w++) {
            long base = w;
            threads[w] = new Thread(() -> {
                try {
                    start.await();
                    java.util.Random random = new java.util.Random(base);
                    for (int i = 0; i < 100_000; i++) {
                        long key = base + writers * (long) random.nextInt(32);
                        if (random.nextBoolean()) map.put(key, i); else map.remove(key);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            threads[w].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertNull(failure.get());
        java.util.Set<Long> keys = new java.util.HashSet<>();
        map.keySet().forEach((long key) -> keys.add(key));
        assertGroupsMatch(map, keys, 4, 6);
    }

    private static void assertGroupsMatch(Long2LongConcurrentHashMap map,
                                          java.util.Set<Long> expectedKeys, int groups, int shift) {
        for (long group = 0; group < groups; group++) {
            LongArrayList collected = new LongArrayList();
            map.collectKeysInGroup(group, collected);
            java.util.Set<Long> actual = new java.util.HashSet<>(collected);
            assertEquals(collected.size(), actual.size(), "group " + group + " lists a key twice");
            java.util.Set<Long> expected = new java.util.HashSet<>();
            for (long key : expectedKeys) if ((key >> shift) == group) expected.add(key);
            assertEquals(expected, actual, "group " + group);
        }
    }

    private static long valueFor(long key) {
        return Long.rotateLeft(key * 0x9E3779B97F4A7C15L, 17) ^ 0x5A5A5A5A5A5A5A5AL;
    }

}
