package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongLinkedOpenHashSet;
import com.axalotl.fabrolia.api.fastutil.Reference2ByteConcurrentHashMap;
import com.axalotl.fabrolia.api.fastutil.Reference2ReferenceConcurrentHashMap;
import com.axalotl.fabrolia.api.utils.IteratorSafeOrderedReferenceSet;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;

import static org.junit.jupiter.api.Assertions.*;

final class ApiCollectionRaceTest {
    @Test
    void linkedContainsCannotTraverseAnInFlightResize() throws Exception {
        var set = new ConcurrentLongLinkedOpenHashSet(2);
        set.add(1);
        Field lockField = ConcurrentLongLinkedOpenHashSet.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        StampedLock lock = (StampedLock) lockField.get(set);
        Field maskField = LongLinkedOpenHashSet.class.getDeclaredField("mask");
        maskField.setAccessible(true);
        int oldMask = maskField.getInt(set);
        int growingMask = ((oldMask + 1) << 1) - 1;
        long probe = 2;
        while ((HashCommon.mix(probe) & growingMask) <= oldMask + 1) probe++;
        final long absent = probe;
        FutureTask<Boolean> lookup = new FutureTask<>(() -> set.contains(absent));
        Thread reader = new Thread(lookup, "linked-resize-reader");
        reader.setDaemon(true);
        long stamp = lock.writeLock();
        try {
            // Reproduce fastutil's rehash publication boundary: new mask, old array.
            // Assertions use only the public membership contract after publication.
            maskField.setInt(set, growingMask);
            reader.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!lookup.isDone() && reader.getState() != Thread.State.WAITING) {
                if (System.nanoTime() >= deadline) fail("lookup did not reach resize boundary");
                Thread.sleep(1);
            }
        } finally {
            maskField.setInt(set, oldMask);
            lock.unlockWrite(stamp);
        }
        assertFalse(lookup.get(5, TimeUnit.SECONDS));
        assertTrue(set.contains(1));
        assertEquals(1, set.size());
    }

    @Test
    void seeAdditionsIteratorSurvivesConcurrentGrowthAndSeesPublishedTail() throws Exception {
        for (int round = 0; round < 50; round++) {
            var set = new IteratorSafeOrderedReferenceSet<Object>(2, 0.75f, 1, 0.2, Object.class, false);
            Object[] values = new Object[4096];
            for (int i = 0; i < values.length; i++) values[i] = new Object();
            set.add(values[0]);
            var iterator = set.iterator(IteratorSafeOrderedReferenceSet.ITERATOR_FLAG_SEE_ADDITIONS);
            FutureTask<Void> append = new FutureTask<>(() -> {
                for (int i = 1; i < values.length; i++) set.add(values[i]);
                return null;
            });
            Thread writer = new Thread(append, "ordered-set-grower");
            writer.setDaemon(true);
            writer.start();
            int observed = 0;
            try {
                while (!append.isDone()) {
                    while (iterator.hasNext()) assertSame(values[observed++], iterator.next());
                    Thread.yield();
                }
                append.get(5, TimeUnit.SECONDS);
                while (iterator.hasNext()) assertSame(values[observed++], iterator.next());
                assertEquals(values.length, observed);
            } finally {
                iterator.finishedIterating();
            }
        }
    }

    @Test
    void zeroCapacityOrderedSetAcceptsFirstAddition() {
        var set = new IteratorSafeOrderedReferenceSet<Object>(2, 0.75f, 0, 0.2, Object.class, false);
        Object value = new Object();
        assertTrue(set.add(value));
        assertTrue(set.contains(value));
        var iterator = set.iterator();
        try { assertSame(value, iterator.next()); assertFalse(iterator.hasNext()); }
        finally { iterator.finishedIterating(); }
    }

    @Test
    void simultaneousFirstWritesPreserveBothReferenceMaps() throws Exception {
        AtomicReference<Reference2ReferenceConcurrentHashMap<Object, Object>> refs = new AtomicReference<>();
        AtomicReference<Reference2ByteConcurrentHashMap<Object>> bytes = new AtomicReference<>();
        Object[] keys = {new Object(), new Object(), new Object(), new Object()};
        CyclicBarrier start = new CyclicBarrier(keys.length + 1);
        CyclicBarrier done = new CyclicBarrier(keys.length + 1);
        FutureTask<?>[] workers = new FutureTask<?>[keys.length];
        for (int i = 0; i < keys.length; i++) {
            Object key = keys[i];
            workers[i] = new FutureTask<>(() -> {
                for (int round = 0; round < 2000; round++) {
                    start.await(10, TimeUnit.SECONDS);
                    refs.get().put(key, key);
                    bytes.get().put(key, (byte) 7);
                    done.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            Thread thread = new Thread(workers[i], "reference-first-write-" + i);
            thread.setDaemon(true);
            thread.start();
        }
        for (int round = 0; round < 2000; round++) {
            refs.set(new Reference2ReferenceConcurrentHashMap<>());
            bytes.set(new Reference2ByteConcurrentHashMap<>());
            start.await(10, TimeUnit.SECONDS);
            done.await(10, TimeUnit.SECONDS);
            assertEquals(keys.length, refs.get().size());
            assertEquals(keys.length, bytes.get().size());
            for (Object key : keys) {
                assertSame(key, refs.get().get(key));
                assertEquals(7, bytes.get().getByte(key));
            }
        }
        for (FutureTask<?> worker : workers) worker.get(10, TimeUnit.SECONDS);
    }
}
