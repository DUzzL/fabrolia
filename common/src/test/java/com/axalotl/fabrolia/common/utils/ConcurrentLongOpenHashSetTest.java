package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Contract coverage for the segmented open-addressing set behind PoiManager.loadedChunks. */
class ConcurrentLongOpenHashSetTest {

    @Test
    void randomizedOperationsMatchAReferenceSet() {
        ConcurrentLongOpenHashSet set = new ConcurrentLongOpenHashSet(4);
        Set<Long> reference = new HashSet<>();
        Random random = new Random(20261011L);
        for (int op = 0; op < 200_000; op++) {
            // A narrow key range forces tombstone reuse, same-capacity rehash
            // (tombstones > size) and growth in every segment.
            long key = random.nextInt(4096) - 2048L;
            switch (random.nextInt(3)) {
                case 0 -> assertEquals(reference.add(key), set.add(key), "add " + key);
                case 1 -> assertEquals(reference.remove(key), set.remove(key), "remove " + key);
                default -> assertEquals(reference.contains(key), set.contains(key), "contains " + key);
            }
            if (op % 10_000 == 0) assertEquals(reference.size(), set.size());
        }
        assertEquals(reference.size(), set.size());
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long key : set.toLongArray()) {
            assertTrue(seen.add(key), "snapshot holds no duplicates");
            assertTrue(reference.contains(key));
        }
        assertEquals(reference.size(), seen.size());
    }

    @Test
    void packedChunkPositionsAtTheWorldBorderAreRegularKeys() {
        ConcurrentLongOpenHashSet set = new ConcurrentLongOpenHashSet();
        long[] keys = {pack(0, 0), pack(-1, -1), pack(1_875_000, -1_875_000),
                pack(-1_875_000, 1_875_000), pack(Integer.MAX_VALUE, Integer.MAX_VALUE)};
        for (long key : keys) assertTrue(set.add(key));
        for (long key : keys) assertTrue(set.contains(key));
        assertFalse(set.contains(Long.MIN_VALUE), "reserved keys are never members");
        assertThrows(IllegalArgumentException.class, () -> set.add(Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> set.add(Long.MIN_VALUE + 1));
    }

    @Test
    void iteratorRemoveAndClearUpdateTheLiveSet() {
        ConcurrentLongOpenHashSet set = new ConcurrentLongOpenHashSet();
        for (long key = 0; key < 1000; key++) set.add(key);
        for (LongIterator it = set.iterator(); it.hasNext(); ) {
            if (it.nextLong() % 2 == 0) it.remove();
        }
        assertEquals(500, set.size());
        assertFalse(set.contains(10));
        assertTrue(set.contains(11));
        set.clear();
        assertTrue(set.isEmpty());
        assertFalse(set.contains(11));
        assertTrue(set.add(11), "a cleared set accepts its old keys again");
    }

    @Test
    void lockFreeReadersAlwaysSeeStableMembersWhileWritersResize() throws Exception {
        ConcurrentLongOpenHashSet set = new ConcurrentLongOpenHashSet(4);
        int stable = 2_000;
        for (long key = 0; key < stable; key++) set.add(key);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] writers = new Thread[4];
        for (int w = 0; w < writers.length; w++) {
            long base = 1_000_000L * (w + 1);
            writers[w] = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 20; round++) {
                        for (long key = base; key < base + 5_000; key++) set.add(key);
                        for (long key = base; key < base + 5_000; key++) set.remove(key);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            writers[w].start();
        }
        Thread reader = new Thread(() -> {
            try {
                start.await();
                Random random = new Random(7);
                while (anyAlive(writers)) {
                    long key = random.nextInt(stable);
                    if (!set.contains(key)) {
                        throw new AssertionError("stable member " + key + " missed during resize");
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        reader.start();
        start.countDown();
        for (Thread writer : writers) writer.join(60_000);
        reader.join(60_000);
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(stable, set.size(), "every transient key was removed again");
    }

    private static boolean anyAlive(Thread[] threads) {
        for (Thread thread : threads) if (thread.isAlive()) return true;
        return false;
    }

    private static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }
}
