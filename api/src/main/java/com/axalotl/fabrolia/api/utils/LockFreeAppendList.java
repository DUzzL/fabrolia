package com.axalotl.fabrolia.api.utils;

import org.jspecify.annotations.NonNull;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Concurrent, growable append-only list with insertion-ordered, bounded traversal.
 * The historical class name is retained for its public constructor/API; appends
 * now use {@link ConcurrentList}'s write lock instead of reserving unpublished slots.
 *
 * <p>Iterators and {@link #forEach(Consumer)} capture their upper bound when created
 * or invoked. Later appends do not extend that traversal. Each element is read
 * through the delegate's publication-safe get, without copying the entire list
 * for each potential-energy query or holding a lock while invoking user code.
 *
 * <p>{@link #clear()} resets the list for reuse. As with any sequence of individual
 * list operations, callers must exclude clear while consuming an existing traversal
 * or using a previously observed size as an index bound. Indexed insertion,
 * replacement and removal are unsupported.
 */
public final class LockFreeAppendList<E> extends AbstractList<E> {

    private final ConcurrentList<E> elements = new ConcurrentList<>();

    public LockFreeAppendList() {
    }

    @Override
    public boolean add(E e) {
        return elements.add(e);
    }


    @Override
    public int size() {
        return elements.size();
    }

    @Override
    public E get(int index) {
        return elements.get(index);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        int max = size();
        for (int i = 0; i < max; i++) {
            action.accept(get(i));
        }
    }

    @Override
    public @NonNull Iterator<E> iterator() {
        return new Iterator<>() {
            private final int max = size();
            private int cursor;

            @Override
            public boolean hasNext() {
                return cursor < max;
            }

            @Override
            public E next() {
                if (cursor >= max) throw new NoSuchElementException();
                return get(cursor++);
            }
        };
    }

    @Override
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public void clear() {
        elements.clear();
    }
}
