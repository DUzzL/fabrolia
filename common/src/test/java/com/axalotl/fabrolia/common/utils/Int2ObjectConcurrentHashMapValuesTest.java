package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Int2ObjectConcurrentHashMapValuesTest {

    @Test
    void iteratorRemovalMutatesBackingMapWithoutSkippingSnapshotEntries() {
        Int2ObjectConcurrentHashMap<Object> map = new Int2ObjectConcurrentHashMap<>(4_096, 16);
        for (int key = 0; key < 4_096; key++) {
            map.put(key, new Object());
        }

        int removed = 0;
        Iterator<Object> iterator = map.values().iterator();
        while (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
            removed++;
        }

        assertEquals(4_096, removed);
        assertTrue(map.isEmpty());
    }

    @Test
    void staleIteratorRemovalDoesNotDeleteAReplacementValue() {
        Int2ObjectConcurrentHashMap<String> map = new Int2ObjectConcurrentHashMap<>(16, 16);
        String original = new String("equal-value");
        String replacement = new String("equal-value");
        map.put(7, original);

        Iterator<String> iterator = map.values().iterator();
        assertSame(original, iterator.next());
        map.put(7, replacement);
        iterator.remove();

        assertSame(replacement, map.get(7));
        assertEquals(1, map.size());
    }

    @Test
    void valuesClearAppliesToMappingsAddedAfterViewCreation() {
        Int2ObjectConcurrentHashMap<Object> map = new Int2ObjectConcurrentHashMap<>(16, 16);
        ObjectCollection<Object> values = map.values();
        map.put(1, new Object());
        map.put(2, new Object());

        values.clear();

        assertTrue(map.isEmpty());
    }
}
