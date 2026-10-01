package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EagerSaveChunkSetTest {

    private static LongArrayList drain(EagerSaveChunkSet set) {
        LongArrayList seen = new LongArrayList();
        for (LongIterator it = set.iterator(); it.hasNext(); ) seen.add(it.nextLong());
        return seen;
    }

    @Test
    void iteratesInFirstAddOrderAndReAddKeepsPosition() {
        EagerSaveChunkSet set = new EagerSaveChunkSet();
        assertTrue(set.add(3));
        assertTrue(set.add(1));
        assertFalse(set.add(3));
        assertTrue(set.add(2));
        assertEquals(LongArrayList.of(3, 1, 2), drain(set));
        assertEquals(3, set.size());
    }

    @Test
    void iteratorRemoveDropsOnlyEntriesNotReDirtiedSinceTheyWereReturned() {
        EagerSaveChunkSet set = new EagerSaveChunkSet();
        set.add(10);
        set.add(20);
        LongIterator it = set.iterator();
        assertEquals(10, it.nextLong());
        set.add(10);            // chunk re-dirtied while Main was saving it
        it.remove();
        assertEquals(20, it.nextLong());
        it.remove();
        assertTrue(set.contains(10), "a re-dirtied chunk keeps its eager-save entry");
        assertFalse(set.contains(20));
        assertEquals(LongArrayList.of(10), drain(set));
    }

    @Test
    void reDirtiedEntryMovesToTheTailLikeVanillaRemoveThenAdd() {
        EagerSaveChunkSet set = new EagerSaveChunkSet();
        set.add(1);
        set.add(2);
        set.add(3);
        LongIterator it = set.iterator();
        assertEquals(1, it.nextLong());
        set.add(1);
        it.remove();
        assertEquals(LongArrayList.of(2, 3, 1), drain(set));
    }

    @Test
    void removeAfterALookaheadPastOnlyDeadNodesStillRemovesTheReturnedEntry() {
        EagerSaveChunkSet set = new EagerSaveChunkSet();
        set.add(1);
        set.add(2);
        LongIterator it = set.iterator();
        assertEquals(1, it.nextLong());
        set.remove(2);
        assertFalse(it.hasNext());
        it.remove();
        assertFalse(set.contains(1));
        assertEquals(0, set.size());
        assertEquals(LongArrayList.of(), drain(set));
    }

    @Test
    void directRemoveThenReAddIsQueuedOnceAtTheTail() {
        EagerSaveChunkSet set = new EagerSaveChunkSet();
        set.add(1);
        set.add(2);
        assertTrue(set.remove(1));
        assertTrue(set.add(1));
        assertEquals(LongArrayList.of(2, 1), drain(set));
    }

    @Test
    void mainIterationRemovingWhileWorkersAddNeverCorruptsTheSet() throws Exception {
        EagerSaveChunkSet set = new EagerSaveChunkSet();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[4];
        for (int w = 0; w < workers.length; w++) {
            final long base = (long) w << 32;
            workers[w] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200_000; i++) set.add(base | (i & 4095));
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            workers[w].start();
        }
        start.countDown();
        boolean alive = true;
        while (alive) {
            for (LongIterator it = set.iterator(); it.hasNext(); ) {
                it.nextLong();
                it.remove();
            }
            alive = false;
            for (Thread worker : workers) alive |= worker.isAlive();
        }
        for (Thread worker : workers) worker.join();
        assertNull(failure.get());
        LongArrayList rest = drain(set);
        assertEquals(set.size(), rest.size(), "iteration and size agree after the race");
        for (LongIterator it = set.iterator(); it.hasNext(); ) {
            it.nextLong();
            it.remove();
        }
        assertEquals(0, set.size());
    }
}
