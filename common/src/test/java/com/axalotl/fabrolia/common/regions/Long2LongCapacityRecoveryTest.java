package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2LongConcurrentHashMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression pin for scheduled-tick index high-water recovery. */
final class Long2LongCapacityRecoveryTest {

    @Test
    void removalsAndClearReleaseTransientBackingCapacity() throws Exception {
        Long2LongConcurrentHashMap map = new Long2LongConcurrentHashMap();
        map.defaultReturnValue(Long.MAX_VALUE);
        int baselineCapacity = backingCapacity(map);

        final int entries = 65_536;
        for (long key = 1; key <= entries; key++) {
            map.put(key, key * 17L);
        }
        int grownCapacity = backingCapacity(map);
        assertTrue(grownCapacity > baselineCapacity * 16,
                "fixture did not establish a meaningful high-water table");

        for (long key = 1; key <= entries - 64L; key++) {
            assertEquals(key * 17L, map.remove(key));
        }
        assertEquals(64, map.size());
        for (long key = entries - 63L; key <= entries; key++) {
            assertEquals(key * 17L, map.get(key),
                    "a live entry was lost while a segment shrank");
        }
        int recoveredCapacity = backingCapacity(map);
        assertTrue(recoveredCapacity < grownCapacity / 8,
                "backing capacity remained coupled to the generation high-water mark");

        map.clear();
        assertEquals(0, map.size());
        assertEquals(baselineCapacity, backingCapacity(map),
                "clear must restore each segment's initial table");
        map.put(7L, 119L);
        assertEquals(119L, map.get(7L));
    }

    private static int backingCapacity(Long2LongConcurrentHashMap map) throws Exception {
        Field segmentsField = Long2LongConcurrentHashMap.class.getDeclaredField("segments");
        segmentsField.setAccessible(true);
        Object[] segments = (Object[]) segmentsField.get(map);
        int total = 0;
        for (Object segment : segments) {
            Field stateField = segment.getClass().getDeclaredField("state");
            stateField.setAccessible(true);
            Object state = stateField.get(segment);
            Field keysField = state.getClass().getDeclaredField("keys");
            keysField.setAccessible(true);
            total += ((long[]) keysField.get(state)).length;
        }
        return total;
    }
}
