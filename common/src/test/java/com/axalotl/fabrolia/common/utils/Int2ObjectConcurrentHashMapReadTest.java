package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class Int2ObjectConcurrentHashMapReadTest {
    private static final long TIMEOUT_SECONDS = 10;

    @TestFactory
    Stream<DynamicTest> readersCannotTraversePartiallyPublishedRehash() {
        return Arrays.stream(ReadOperation.values()).map(operation ->
                DynamicTest.dynamicTest(operation.name(), () -> readDuringRehash(operation)));
    }

    private static void readDuringRehash(ReadOperation operation) throws Exception {
        Int2ObjectConcurrentHashMap<String> map = new Int2ObjectConcurrentHashMap<>(16, 16);
        PausedRehashMap<String> delegate = new PausedRehashMap<>();
        installFirstStripe(map, delegate);
        int entriesBeforeGrowth = delegate.entriesBeforeGrowth();
        // These nonzero keys all select stripe zero, without relying on table hash collisions.
        for (int i = 1; i <= entriesBeforeGrowth; i++) {
            map.put(i * 16, "resident");
        }
        int probe = delegate.outOfBoundsProbeAfterGrowth();
        int insertedKey = (entriesBeforeGrowth + 1) * 16;
        delegate.pauseGrowth = true;
        FutureTask<String> writer = new FutureTask<>(() -> map.put(insertedKey, "inserted"));
        Thread writerThread = daemonThread("int-map-rehash-writer", writer);
        FutureTask<Object> reader = new FutureTask<>(() -> operation.read(map, probe));
        Thread readerThread = daemonThread("int-map-rehash-reader", reader);
        writerThread.start();
        try {
            await(delegate.maskPublished);
            readerThread.start();
            // A fixed reader parks on the real stripe lock; a broken reader completes
            // exceptionally inside fastutil's unmodified get/containsKey/getOrDefault.
            // Do not release the writer before one of those outcomes is observable.
            awaitParkedOrCompleted(readerThread, reader);
        } finally {
            delegate.finishPublication.countDown();
            writerThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            readerThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
        writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(operation.expected, reader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("inserted", map.get(insertedKey));
        assertEquals("resident", map.get(16));
        assertEquals(entriesBeforeGrowth + 1 + (operation.computes ? 1 : 0), map.size());
        assertEquals(operation.computes, map.containsKey(probe));
    }

    @Test
    void boxedAndPrimitiveReadsPreserveDefaultAndNullPresence() {
        Int2ObjectConcurrentHashMap<Object> map = new Int2ObjectConcurrentHashMap<>(16, 16);
        Object missing = new Object();
        Object fallback = new Object();
        Object present = new Object();
        map.defaultReturnValue(missing);
        map.put(0, null);
        map.put(16, missing);
        map.put(32, present);

        assertSame(missing, map.get(48));
        assertNull(map.get((Object) 48));
        assertSame(fallback, map.getOrDefault(48, fallback));
        assertSame(fallback, map.getOrDefault((Object) 48, fallback));
        assertFalse(map.containsKey(48));
        assertFalse(map.containsKey((Object) 48));
        assertNull(map.get(0));
        assertNull(map.get((Object) 0));
        assertNull(map.getOrDefault(0, fallback));
        assertNull(map.getOrDefault((Object) 0, fallback));
        assertTrue(map.containsKey(0));
        assertTrue(map.containsKey((Object) 0));
        assertSame(missing, map.getOrDefault(16, fallback));
        assertTrue(map.containsKey(16));
        assertSame(present, map.get(32));
        assertSame(present, map.get((Object) 32));
        assertNull(map.get("not-an-int"));
        assertSame(fallback, map.getOrDefault(null, fallback));
        assertFalse(map.containsKey("not-an-int"));
    }

    @Test
    void computeOverloadsDistinguishAbsentNullAndDefaultValuedMappings() {
        Int2ObjectConcurrentHashMap<Object> map = new Int2ObjectConcurrentHashMap<>(16, 16);
        Object missing = new Object();
        Object computed = new Object();
        map.defaultReturnValue(missing);
        map.put(0, null);
        map.put(16, missing);
        IntFunction<Object> unexpected = key -> fail("existing mapping was recomputed");
        Int2ObjectFunction<Object> unexpectedPrimitive = key -> fail("existing mapping was recomputed");

        assertNull(map.computeIfAbsent(0, unexpected));
        assertNull(map.computeIfAbsent(0, unexpectedPrimitive));
        assertSame(missing, map.computeIfAbsent(16, unexpected));
        assertSame(missing, map.computeIfAbsent(16, unexpectedPrimitive));
        assertSame(computed, map.computeIfAbsent(32, (IntFunction<Object>) key -> computed));
        assertSame(computed, map.computeIfAbsent(48, (Int2ObjectFunction<Object>) key -> computed));
        assertSame(computed, map.get(32));
        assertSame(computed, map.get(48));
        assertNull(map.computeIfAbsent(64, (IntFunction<Object>) key -> null));
        assertNull(map.computeIfAbsent(80, (Int2ObjectFunction<Object>) key -> null));
        assertTrue(map.containsKey(64));
        assertTrue(map.containsKey(80));
        assertEquals(6, map.size());
    }

    @TestFactory
    Stream<DynamicTest> computeRechecksMappingsPublishedBeforeWriteAcquisition() {
        return Stream.of(false, true).flatMap(primitive -> Stream.of(false, true).map(nullValue ->
                DynamicTest.dynamicTest("primitive=" + primitive + ", null=" + nullValue,
                        () -> computeAfterCompetingPut(primitive, nullValue))));
    }

    private static void computeAfterCompetingPut(boolean primitive, boolean nullValue) throws Exception {
        Int2ObjectConcurrentHashMap<Object> map = new Int2ObjectConcurrentHashMap<>(16, 16);
        Object missing = new Object();
        Object published = nullValue ? null : missing;
        map.defaultReturnValue(missing);
        PausedWriteLock lock = new PausedWriteLock();
        // Stripes are created on first write: materialize stripe 0 (no mapping)
        // before swapping in the pausing lock, ahead of any thread start.
        map.remove(0);
        Field locks = Int2ObjectConcurrentHashMap.class.getDeclaredField("locks");
        locks.setAccessible(true);
        ((StampedLock[]) locks.get(map))[0] = lock;
        IntFunction<Object> unexpected = key -> fail("concurrently published mapping was recomputed");
        Int2ObjectFunction<Object> unexpectedPrimitive = key -> fail("concurrently published mapping was recomputed");
        FutureTask<Object> reader = new FutureTask<>(() -> primitive
                ? map.computeIfAbsent(0, unexpectedPrimitive)
                : map.computeIfAbsent(0, unexpected));
        Thread readerThread = daemonThread("int-map-compute-reader", reader);
        lock.pausedThread = readerThread;
        FutureTask<Object> writer = new FutureTask<>(() -> map.put(0, published));
        Thread writerThread = daemonThread("int-map-competing-writer", writer);
        readerThread.start();
        try {
            await(lock.beforeWriteAcquisition);
            // The computing thread has released its read lock but not acquired the
            // write lock. A competing put must win without invoking its callback.
            writerThread.start();
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            lock.continueWriteAcquisition.countDown();
            readerThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            writerThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
        assertSame(published, reader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertSame(published, map.getOrDefault(0, new Object()));
        assertTrue(map.containsKey(0));
        assertEquals(1, map.size());
    }

    private static final class PausedWriteLock extends StampedLock {
        private final CountDownLatch beforeWriteAcquisition = new CountDownLatch(1);
        private final CountDownLatch continueWriteAcquisition = new CountDownLatch(1);
        private Thread pausedThread;

        @Override
        public long writeLock() {
            if (Thread.currentThread() == pausedThread) {
                beforeWriteAcquisition.countDown();
                await(continueWriteAcquisition);
            }
            return super.writeLock();
        }
    }

    private enum ReadOperation {
        GET(null, false), BOXED_GET(null, false),
        GET_OR_DEFAULT("fallback", false), BOXED_GET_OR_DEFAULT("fallback", false),
        CONTAINS(false, false), BOXED_CONTAINS(false, false),
        COMPUTE("computed", true), PRIMITIVE_COMPUTE("computed", true);

        private final Object expected;
        private final boolean computes;

        ReadOperation(Object expected, boolean computes) {
            this.expected = expected;
            this.computes = computes;
        }

        Object read(Int2ObjectConcurrentHashMap<String> map, int key) {
            return switch (this) {
                case GET -> map.get(key);
                case BOXED_GET -> map.get((Object) key);
                case GET_OR_DEFAULT -> map.getOrDefault(key, "fallback");
                case BOXED_GET_OR_DEFAULT -> map.getOrDefault((Object) key, "fallback");
                case CONTAINS -> map.containsKey(key);
                case BOXED_CONTAINS -> map.containsKey((Object) key);
                case COMPUTE -> map.computeIfAbsent(key, (IntFunction<String>) k -> "computed");
                case PRIMITIVE_COMPUTE -> map.computeIfAbsent(key, (Int2ObjectFunction<String>) k -> "computed");
            };
        }
    }

    private static void installFirstStripe(Int2ObjectConcurrentHashMap<String> map,
                                           PausedRehashMap<String> delegate) throws ReflectiveOperationException {
        Field field = Int2ObjectConcurrentHashMap.class.getDeclaredField("segments");
        field.setAccessible(true);
        // Only replace the empty delegate, before any threads start. The production
        // stripe selection, locks, put path, reader methods and size accounting run unchanged.
        ((Object[]) field.get(map))[0] = delegate;
    }

    private static Thread daemonThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitParkedOrCompleted(Thread thread, FutureTask<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!task.isDone() && thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() >= deadline) fail("reader did not reach the paused rehash");
            Thread.sleep(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) fail("rehash rendezvous timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("rehash rendezvous interrupted", interrupted);
        }
    }

    /**
     * Runs the dependency's real rehash, then holds its new mask with the old arrays
     * until publication is released. All lookups are inherited fastutil methods.
     * This reconstructs the observable publication boundary; it does not instrument
     * the dependency's individual assignments or copy its rehash algorithm.
     */
    private static final class PausedRehashMap<V> extends Int2ObjectOpenHashMap<V> {
        private final CountDownLatch maskPublished = new CountDownLatch(1);
        private final CountDownLatch finishPublication = new CountDownLatch(1);
        private boolean pauseGrowth;

        PausedRehashMap() {
            super(16, 0.75f);
        }

        int entriesBeforeGrowth() {
            return maxFill;
        }

        int outOfBoundsProbeAfterGrowth() {
            for (int probe = 16_384; probe < 65_536; probe += 16) {
                if ((HashCommon.mix(probe) & (2 * n - 1)) > n) return probe;
            }
            throw new AssertionError("no stripe-zero probe beyond the old key array");
        }

        @Override
        protected void rehash(int newN) {
            if (!pauseGrowth) {
                super.rehash(newN);
                return;
            }
            pauseGrowth = false;
            int[] oldKeys = key;
            V[] oldValues = value;
            int oldMaxFill = maxFill;
            super.rehash(newN);
            int[] newKeys = key;
            V[] newValues = value;
            int newMaxFill = maxFill;
            key = oldKeys;
            value = oldValues;
            maxFill = oldMaxFill;
            try {
                maskPublished.countDown();
                await(finishPublication);
            } finally {
                maxFill = newMaxFill;
                key = newKeys;
                value = newValues;
            }
        }
    }
}
