package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongLinkedOpenHashSet;
import com.axalotl.fabrolia.api.fastutil.ConcurrentLongSortedSet;
import com.axalotl.fabrolia.api.fastutil.FastUtilHackUtil;
import com.axalotl.fabrolia.api.fastutil.Reference2ByteConcurrentHashMap;
import com.axalotl.fabrolia.api.fastutil.Reference2ReferenceConcurrentHashMap;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
    void sortedRangeAliasesInvalidateEachOthersSnapshots() {
        var root = new ConcurrentLongSortedSet(List.of(1L, 2L, 4L, 5L));
        var middle = root.subSet(2, 5);
        var head = root.headSet(4);
        var tail = root.tailSet(3);
        root.toLongArray();
        middle.toLongArray();
        head.toLongArray();
        tail.toLongArray();

        root.add(3);
        assertArrayEquals(new long[]{2, 3, 4}, middle.toLongArray());
        assertArrayEquals(new long[]{1, 2, 3}, head.toLongArray());
        assertArrayEquals(new long[]{3, 4, 5}, tail.toLongArray());
        middle.remove(2);
        assertArrayEquals(new long[]{1, 3, 4, 5}, root.toLongArray());

        var nested = middle.tailSet(3);
        var iterator = nested.iterator(0);
        assertEquals(3, iterator.nextLong());
        iterator.remove();
        assertArrayEquals(new long[]{1, 4, 5}, root.toLongArray());
        assertArrayEquals(new long[]{4, 5}, tail.toLongArray());
        assertFalse(nested.iterator(99).hasNext());
        nested.clear();
        assertArrayEquals(new long[]{1, 5}, root.toLongArray());
    }

    @Test
    void sortedViewBoundsSurvivePartialBulkFailureAndCopiesStayIndependent() {
        var root = new ConcurrentLongSortedSet(List.of(1L, 5L));
        var view = root.subSet(2, 5);
        var copy = new ConcurrentLongSortedSet(root);
        root.toLongArray();
        view.toLongArray();
        assertThrows(IllegalArgumentException.class, () -> view.addAll(List.of(3L, 5L)));
        assertArrayEquals(new long[]{1, 3, 5}, root.toLongArray());
        assertArrayEquals(new long[]{3}, view.toLongArray());
        assertArrayEquals(new long[]{1, 5}, copy.toLongArray());
        assertThrows(IllegalArgumentException.class, () -> root.subSet(5, 2));
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
    void primitiveArrayFactoriesReuseDestinationsWithoutTouchingSuffix() {
        var bytes = FastUtilHackUtil.wrapBytes(List.of((byte) 3, (byte) 4));
        assertArrayEquals(new byte[]{3, 4}, bytes.toByteArray());
        byte[] byteDestination = {9, 9, 7};
        assertSame(byteDestination, bytes.toArray(byteDestination));
        assertArrayEquals(new byte[]{3, 4, 7}, byteDestination);
        assertArrayEquals(new byte[]{3, 4}, bytes.toArray((byte[]) null));
        assertArrayEquals(new byte[]{3, 4}, bytes.toArray(new byte[0]));

        var ints = new FastUtilHackUtil.WrappingIntSet(new LinkedHashSet<>(List.of(3, 4)));
        int[] intDestination = {9, 9, 7};
        assertSame(intDestination, ints.toArray(intDestination));
        assertArrayEquals(new int[]{3, 4, 7}, intDestination);
        assertArrayEquals(new int[]{3, 4}, ints.toArray((int[]) null));
        assertArrayEquals(new int[]{3, 4}, ints.toArray(new int[0]));

        var longs = new FastUtilHackUtil.WrappingLongSet(new LinkedHashSet<>(List.of(3L, 4L)));
        long[] longDestination = {9, 9, 7};
        assertSame(longDestination, longs.toArray(longDestination));
        assertArrayEquals(new long[]{3, 4, 7}, longDestination);
        assertArrayEquals(new long[]{3, 4}, longs.toArray((long[]) null));
        assertArrayEquals(new long[]{3, 4}, longs.toArray(new long[0]));
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

    @Test
    void byteReferenceSnapshotsRetainSupportedNullKey() {
        var map = new Reference2ByteConcurrentHashMap<Object>();
        map.put(null, (byte) 7);
        var entry = map.reference2ByteEntrySet().iterator().next();
        assertNull(entry.getKey());
        assertEquals(7, entry.getByteValue());
        AtomicInteger seen = new AtomicInteger();
        map.forEach((key, value) -> {
            assertNull(key);
            assertEquals((byte) 7, value);
            seen.incrementAndGet();
        });
        assertEquals(1, seen.get());
    }
}
