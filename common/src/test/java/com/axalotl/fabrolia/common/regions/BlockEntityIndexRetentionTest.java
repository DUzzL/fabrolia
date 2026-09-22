package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityIndexRetentionTest {


    @Test
    void coldBacklogHasAHardRetentionBound() {
        BoundedPendingQueue<Object> pending = new BoundedPendingQueue<>(4);
        boolean overflowed = false;

        for (int i = 0; i < 10_000; i++) {
            overflowed |= pending.offer(new Object());
            assertTrue(pending.size() <= 4);
        }

        assertTrue(overflowed, "the owner must be told to invalidate its derived index");
        assertEquals(4, pending.size());
    }

    @Test
    void overflowDropsOldReferencesButPreservesTheNewestRegistration() {
        BoundedPendingQueue<Object> pending = new BoundedPendingQueue<>(2);
        Object oldA = new Object();
        Object oldB = new Object();
        Object newest = new Object();

        assertFalse(pending.offer(oldA));
        assertFalse(pending.offer(oldB));
        assertTrue(pending.offer(newest));

        var drained = pending.drain();
        assertEquals(1, drained.size());
        assertSame(newest, drained.get(0));
        assertEquals(0, pending.size());
    }

    @Test
    void vanillaFallbackReleaseClearsEveryDerivedStrongReference() {
        Object ticker = new Object();
        Long2ObjectConcurrentHashMap<ArrayList<Object>> cells =
                new Long2ObjectConcurrentHashMap<>();
        cells.put(7L, new ArrayList<>(java.util.List.of(ticker)));
        ConcurrentHashMap<Object, Long> sequences = new ConcurrentHashMap<>();
        sequences.put(ticker, 1L);
        Reference2LongOpenHashMap<Object> cellByTicker =
                new Reference2LongOpenHashMap<>();
        cellByTicker.put(ticker, 7L);
        HashSet<Object> pendingSweep = new HashSet<>();
        pendingSweep.add(ticker);
        ConcurrentLinkedQueue<Object> unitRemoved = new ConcurrentLinkedQueue<>();
        unitRemoved.add(ticker);

        BlockEntityIndexRetention.clear(
                cells, sequences, cellByTicker, pendingSweep, unitRemoved);

        assertTrue(cells.isEmpty());
        assertTrue(sequences.isEmpty());
        assertTrue(cellByTicker.isEmpty());
        assertTrue(pendingSweep.isEmpty());
        assertTrue(unitRemoved.isEmpty());
    }

}
