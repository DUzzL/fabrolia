package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongLinkedOpenHashSet;
import com.axalotl.fabrolia.api.fastutil.ConcurrentLongSortedSet;
import com.axalotl.fabrolia.api.fastutil.Reference2ReferenceConcurrentHashMap;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ApiCollectionSnapshotTest {
    @Test
    void sortedSnapshotsAreCallerOwnedAndReflectIteratorRemoval() {
        ConcurrentLongSortedSet set = new ConcurrentLongSortedSet(List.of(1L, 2L));
        long[] first = set.toLongArray();
        first[0] = 99;
        assertArrayEquals(new long[]{1, 2}, set.toLongArray());
        long[] cached = set.toLongArray();
        cached[1] = 99;
        assertArrayEquals(new long[]{1, 2}, set.toLongArray());
        var iterator = set.iterator();
        assertEquals(1, iterator.nextLong());
        iterator.remove();
        assertArrayEquals(new long[]{2}, set.toLongArray());
        long[] destination = {9, 8, 7};
        assertSame(destination, set.toArray(destination));
        assertArrayEquals(new long[]{2, 8, 7}, destination);
        assertArrayEquals(new long[]{2}, set.toArray((long[]) null));
    }

    @Test
    void linkedArrayFactoryPreservesDestinationSuffix() {
        var set = new ConcurrentLongLinkedOpenHashSet(List.of(2L, 1L));
        long[] destination = {9, 8, 7};
        assertSame(destination, set.toArray(destination));
        assertArrayEquals(new long[]{2, 1, 7}, destination);
        assertArrayEquals(new long[]{2, 1}, set.toArray((long[]) null));
    }

    @Test
    void sortedSnapshotInvalidatesAfterPartiallyFailingBulkAdd() {
        ConcurrentLongSortedSet set = new ConcurrentLongSortedSet(List.of(1L));
        set.toLongArray();
        assertThrows(NullPointerException.class, () -> set.addAll(Arrays.asList(2L, null)));
        assertTrue(set.contains(2));
        assertArrayEquals(new long[]{1, 2}, set.toLongArray());
    }

    @Test
    void referenceValueSnapshotsKeepMultiplicityAndNullValues() {
        var map = new Reference2ReferenceConcurrentHashMap<Object, Object>();
        Object value = new Object();
        map.put(new Object(), value);
        map.put(new Object(), value);
        map.put(null, null);
        Object[] values = map.values().toArray();
        assertEquals(3, values.length);
        assertEquals(2, Arrays.stream(values).filter(v -> v == value).count());
        assertEquals(1, Arrays.stream(values).filter(v -> v == null).count());
    }
}
