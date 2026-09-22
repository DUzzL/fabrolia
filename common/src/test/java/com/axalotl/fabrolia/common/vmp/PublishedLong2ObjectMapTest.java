package com.axalotl.fabrolia.common.vmp;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedLong2ObjectMapTest {

    @Test
    void supportsAllLongKeysAcrossGrowthReplacementRemovalAndShrink() {
        PublishedLong2ObjectMap<Value> map = new PublishedLong2ObjectMap<>();
        Map<Long, Value> expected = new HashMap<>();
        long[] special = {0L, -1L, Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE};
        for (long key : special) {
            Value value = new Value(key, 1L);
            map.put(key, value);
            expected.put(key, value);
        }
        for (int index = 0; index < 20_000; index++) {
            long key = key(index);
            Value value = new Value(key, index);
            map.put(key, value);
            expected.put(key, value);
        }

        assertEquals(expected.size(), map.size());
        assertTrue(map.capacity() > 64, "the exercise must cross multiple table generations");
        expected.forEach((key, value) -> assertSame(value, map.get(key)));

        int replacementIndex = 0;
        for (long key : expected.keySet().stream().mapToLong(Long::longValue).toArray()) {
            if ((replacementIndex++ & 3) == 0) {
                Value replacement = new Value(key, -replacementIndex);
                map.put(key, replacement);
                expected.put(key, replacement);
            }
        }
        expected.forEach((key, value) -> assertSame(value, map.get(key)));

        for (long key : expected.keySet().stream().mapToLong(Long::longValue).toArray()) {
            if (expected.size() > 24) {
                map.remove(key);
                expected.remove(key);
                assertNull(map.get(key));
            }
        }
        assertEquals(expected.size(), map.size());
        assertEquals(64, map.capacity(), "sparse tables must release travelled-world capacity");
        expected.forEach((key, value) -> assertSame(value, map.get(key)));
    }

    @Test
    void concurrentReadersSeeOnlyWholeValuesForTheirRequestedKey() throws Exception {
        PublishedLong2ObjectMap<Payload> map = new PublishedLong2ObjectMap<>();
        int keyCount = 4_096;
        for (int index = 0; index < keyCount; index++) {
            long key = key(index);
            map.put(key, payload(key, 0));
        }

        int readers = 6;
        ExecutorService executor = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Future<?> writer = executor.submit(() -> {
            await(start);
            try {
                for (long version = 1; version <= 250_000; version++) {
                    int index = (int) (version & (keyCount - 1));
                    long key = key(index);
                    if ((version & 7L) == 0L) {
                        map.remove(key);
                    }
                    map.put(key, payload(key, version));

                    // Exercise structural publication while readers retain
                    // arbitrary old table and bucket heads.
                    if ((version & 1_023L) == 0L) {
                        long transientKey = key(keyCount + (int) version);
                        map.put(transientKey, payload(transientKey, version));
                        map.remove(transientKey);
                    }
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            } finally {
                done.set(true);
            }
        });

        Future<?>[] readerFutures = new Future<?>[readers];
        for (int reader = 0; reader < readers; reader++) {
            final int offset = reader;
            readerFutures[reader] = executor.submit(() -> {
                await(start);
                long iteration = offset;
                try {
                    while (!done.get()) {
                        long requestedKey = key((int) iteration & (keyCount - 1));
                        Payload value = map.get(requestedKey);
                        if (value != null && (value.key != requestedKey
                                || value.checksum != checksum(value.key, value.version))) {
                            throw new AssertionError("torn or wrong-key publication for "
                                    + requestedKey + ": " + value);
                        }
                        iteration += 17;
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                    done.set(true);
                }
            });
        }

        start.countDown();
        writer.get(20, TimeUnit.SECONDS);
        for (Future<?> reader : readerFutures) {
            reader.get(20, TimeUnit.SECONDS);
        }
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertNull(failure.get(), () -> "concurrent publication failed: " + failure.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static long key(int index) {
        return Long.rotateLeft(0x9e3779b97f4a7c15L * index, 17);
    }

    private static Payload payload(long key, long version) {
        Payload payload = new Payload();
        payload.key = key;
        payload.version = version;
        payload.checksum = checksum(key, version);
        return payload;
    }

    private static long checksum(long key, long version) {
        return Long.rotateLeft(key, 23) ^ (version * 0xd6e8feb86659fd93L);
    }

    private record Value(long key, long version) {}

    /** Deliberately non-final fields exercise volatile value safe publication. */
    private static final class Payload {
        long key;
        long version;
        long checksum;

        @Override
        public String toString() {
            return "Payload[key=" + key + ", version=" + version
                    + ", checksum=" + checksum + ']';
        }
    }
}
