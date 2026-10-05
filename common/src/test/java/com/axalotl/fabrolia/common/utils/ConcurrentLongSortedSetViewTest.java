package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongSortedSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Range views follow fastutil's sorted-set contract: half-open, live, write-through. */
final class ConcurrentLongSortedSetViewTest {

    @Test
    void rangeViewsAreHalfOpenAndLive() {
        ConcurrentLongSortedSet set = new ConcurrentLongSortedSet();
        for (long v = 0; v < 10; v++) set.add(v * 10);
        LongSortedSet sub = set.subSet(20, 50);
        LongSortedSet head = set.headSet(30);
        LongSortedSet tail = set.tailSet(70);
        assertArrayEquals(new long[]{20, 30, 40}, sub.toLongArray());
        assertArrayEquals(new long[]{0, 10, 20}, head.toLongArray());
        assertArrayEquals(new long[]{70, 80, 90}, tail.toLongArray());

        set.add(25);
        set.remove(40);
        assertArrayEquals(new long[]{20, 25, 30}, sub.toLongArray());
        assertArrayEquals(new long[]{0, 10, 20, 25}, head.toLongArray());
    }

    @Test
    void mutationThroughAViewInvalidatesTheParentSnapshot() {
        ConcurrentLongSortedSet set = new ConcurrentLongSortedSet();
        set.add(1);
        set.add(5);
        assertArrayEquals(new long[]{1, 5}, set.toLongArray());
        LongSortedSet sub = set.subSet(0, 4);
        assertTrue(sub.add(3));
        assertArrayEquals(new long[]{1, 3, 5}, set.toLongArray());
        assertTrue(sub.remove(1));
        assertFalse(set.contains(1));
        assertArrayEquals(new long[]{3, 5}, set.toLongArray());
    }
}
