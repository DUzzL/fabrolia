package com.axalotl.fabrolia.common.parallelised.utils;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.RandomAccess;

/**
 * Immutable, insertion-ordered member list of one Lithium entity class group
 * inside one {@code ClassInstanceMultiMap}.
 *
 * <p>Vanilla iterates a section's {@code allInstances} in insertion order and
 * Lithium's own class-group cache ({@code ReferenceLinkedOpenHashSet}) keeps
 * that order, so push/collision candidate order is observable vanilla state.
 * Writers replace the published snapshot copy-on-write under the owning map's
 * monitor; readers iterate a stable array without locking. {@link #with} and
 * {@link #without} mirror {@code List.add}/{@code List.remove(Object)} of
 * {@code allInstances} exactly: duplicates kept, first {@code equals} match
 * removed ({@code Entity.equals} compares entity ids).
 */
public final class OrderedGroupSnapshot<T> extends AbstractList<T> implements RandomAccess {

    private static final OrderedGroupSnapshot<?> EMPTY = new OrderedGroupSnapshot<>(new Object[0]);

    private final Object[] members;

    private OrderedGroupSnapshot(Object[] members) {
        this.members = members;
    }

    @SuppressWarnings("unchecked")
    public static <T> OrderedGroupSnapshot<T> empty() {
        return (OrderedGroupSnapshot<T>) EMPTY;
    }

    /** Snapshot of {@code members[0, size)}; the array must not be reused by the caller. */
    public static <T> OrderedGroupSnapshot<T> ofOwned(Object[] members, int size) {
        if (size == 0) return empty();
        return new OrderedGroupSnapshot<>(size == members.length ? members : Arrays.copyOf(members, size));
    }

    public OrderedGroupSnapshot<T> with(T member) {
        Object[] next = Arrays.copyOf(this.members, this.members.length + 1);
        next[this.members.length] = member;
        return new OrderedGroupSnapshot<>(next);
    }

    public OrderedGroupSnapshot<T> without(Object member) {
        Object[] current = this.members;
        for (int i = 0; i < current.length; i++) {
            if (java.util.Objects.equals(current[i], member)) {
                if (current.length == 1) return empty();
                Object[] next = new Object[current.length - 1];
                System.arraycopy(current, 0, next, 0, i);
                System.arraycopy(current, i + 1, next, i, current.length - i - 1);
                return new OrderedGroupSnapshot<>(next);
            }
        }
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(int index) {
        return (T) this.members[index];
    }

    @Override
    public int size() {
        return this.members.length;
    }

    @Override
    public boolean isEmpty() {
        return this.members.length == 0;
    }

    @Override
    public Iterator<T> iterator() {
        Object[] snapshot = this.members;
        return new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return this.next < snapshot.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public T next() {
                if (this.next >= snapshot.length) throw new NoSuchElementException();
                return (T) snapshot[this.next++];
            }
        };
    }
}
