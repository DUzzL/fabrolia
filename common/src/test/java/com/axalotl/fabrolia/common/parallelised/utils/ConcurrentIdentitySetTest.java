package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for ServerPlayer's cross-thread, cross-dimension pearl registry. */
class ConcurrentIdentitySetTest {

    @Test
    void mutableEntityStyleHashCannotStrandAnEntry() {
        ConcurrentIdentitySet<MutableId> set = new ConcurrentIdentitySet<>();
        MutableId pearl = new MutableId(41);

        assertTrue(set.add(pearl));
        pearl.id = 9001; // staged cross-dimension publication assigns a new entity id

        assertTrue(set.contains(pearl));
        assertTrue(set.remove(pearl));
        assertTrue(set.isEmpty());
    }

    @Test
    void equalRuntimeIdsDoNotMergeSourceAndDestinationObjects() {
        ConcurrentIdentitySet<MutableId> set = new ConcurrentIdentitySet<>();
        MutableId source = new MutableId(77);
        MutableId destination = new MutableId(77);

        assertTrue(set.add(source));
        assertTrue(set.add(destination));
        assertEquals(2, set.size(),
                "cross-dimension source and destination overlap as distinct lifecycle objects");

        assertTrue(set.remove(source));
        assertFalse(set.contains(source));
        assertTrue(set.contains(destination));
    }

    @Test
    void iteratorRemovalStillUsesIdentityAfterIdMutation() {
        ConcurrentIdentitySet<MutableId> set = new ConcurrentIdentitySet<>();
        MutableId pearl = new MutableId(5);
        set.add(pearl);

        Iterator<MutableId> iterator = set.iterator();
        assertEquals(pearl, iterator.next());
        pearl.id = 6;
        iterator.remove();

        assertTrue(set.isEmpty());
    }

    /** Mirrors Minecraft Entity's equals/hashCode dependence on a mutable id. */
    private static final class MutableId {
        private int id;

        private MutableId(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MutableId value && value.id == id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }
}
