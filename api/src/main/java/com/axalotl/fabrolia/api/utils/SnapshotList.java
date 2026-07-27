package com.axalotl.fabrolia.api.utils;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Read-optimized concurrent {@link java.util.List}: reads are completely lock-free
 * (volatile array snapshot), writes synchronize and rebuild the snapshot array.
 *
 * <p>Reads use a single {@code volatile} array load. {@link #get(int)}, {@link #size()},
 * {@link #iterator()}, {@link #forEach(Consumer)} are zero-allocation and zero-locking.
 *
 * <p>Writes ({@link #add}, {@link #remove}, {@link #set}, etc.) take an internal monitor
 * and allocate a fresh array of the new size, publishing it via the volatile field.
 * Each mutation is therefore O(N) and produces N references of garbage. This trade-off
 * is correct when reads dominate writes by a large factor — e.g. {@code ServerLevel.players}
 * (iterated every tick for packet broadcast, mutated only on login/logout).
 *
 * <p>Insertion order is preserved. Iteration is weakly consistent — an in-flight iterator
 * sees a frozen snapshot taken at {@code iterator()} time and is immune to concurrent
 * mutation.
 *
 * <p>Versus {@link java.util.concurrent.CopyOnWriteArrayList}: this is essentially the
 * same algorithm but typed to avoid a few CoWAL warts (no internal {@code ReentrantLock}
 * allocation, simpler).
 *
 * @param <E> element type (nulls not permitted)
 */
public final class SnapshotList<E> extends AbstractList<E> {

    private static final Object[] EMPTY = new Object[0];

    private volatile Object[] snapshot;
    private final Object lock = new Object();

    public SnapshotList() {
        this.snapshot = EMPTY;
    }

    public SnapshotList(Collection<? extends E> c) {
        Object[] src = c.toArray();
        int valid = 0;
        for (Object e : src) {
            if (e != null) src[valid++] = e;
        }
        this.snapshot = valid == 0 ? EMPTY : Arrays.copyOf(src, valid);
    }

    @Override
    public int size() {
        return snapshot.length;
    }

    @Override
    public boolean isEmpty() {
        return snapshot.length == 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E get(int index) {
        Object[] s = snapshot;
        if (index < 0 || index >= s.length)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + s.length);
        return (E) s[index];
    }

    @Override
    public boolean contains(Object o) {
        if (o == null) return false;
        for (Object e : snapshot) {
            if (o.equals(e)) return true;
        }
        return false;
    }

    @Override
    public int indexOf(Object o) {
        if (o == null) return -1;
        Object[] s = snapshot;
        for (int i = 0; i < s.length; i++) {
            if (o.equals(s[i])) return i;
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        if (o == null) return -1;
        Object[] s = snapshot;
        for (int i = s.length - 1; i >= 0; i--) {
            if (o.equals(s[i])) return i;
        }
        return -1;
    }

    @Override
    public Iterator<E> iterator() {
        Object[] s = snapshot;
        if (s.length == 0) return Collections.emptyIterator();
        return new SnapIter(s);
    }

    @Override
    public boolean add(E element) {
        Objects.requireNonNull(element);
        synchronized (lock) {
            Object[] s = snapshot;
            Object[] n = Arrays.copyOf(s, s.length + 1);
            n[s.length] = element;
            snapshot = n;
            return true;
        }
    }

    @Override
    public void add(int index, E element) {
        Objects.requireNonNull(element);
        synchronized (lock) {
            Object[] s = snapshot;
            if (index < 0 || index > s.length)
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + s.length);
            Object[] n = new Object[s.length + 1];
            System.arraycopy(s, 0, n, 0, index);
            n[index] = element;
            if (index < s.length) {
                System.arraycopy(s, index, n, index + 1, s.length - index);
            }
            snapshot = n;
        }
    }

    @Override
    public boolean remove(Object o) {
        if (o == null) return false;
        synchronized (lock) {
            Object[] s = snapshot;
            int idx = -1;
            for (int i = 0; i < s.length; i++) {
                if (o.equals(s[i])) { idx = i; break; }
            }
            if (idx < 0) return false;
            removeAtUnlocked(s, idx);
            return true;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public E remove(int index) {
        synchronized (lock) {
            Object[] s = snapshot;
            if (index < 0 || index >= s.length)
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + s.length);
            E old = (E) s[index];
            removeAtUnlocked(s, index);
            return old;
        }
    }

    private void removeAtUnlocked(Object[] s, int idx) {
        if (s.length == 1) {
            snapshot = EMPTY;
            return;
        }
        Object[] n = new Object[s.length - 1];
        System.arraycopy(s, 0, n, 0, idx);
        System.arraycopy(s, idx + 1, n, idx, s.length - 1 - idx);
        snapshot = n;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        Objects.requireNonNull(element);
        synchronized (lock) {
            Object[] s = snapshot;
            if (index < 0 || index >= s.length)
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + s.length);
            E old = (E) s[index];
            Object[] n = s.clone();
            n[index] = element;
            snapshot = n;
            return old;
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            snapshot = EMPTY;
        }
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (c.isEmpty()) return false;
        synchronized (lock) {
            Object[] s = snapshot;
            Object[] toAdd = c.toArray();
            int valid = 0;
            for (Object e : toAdd) {
                if (e != null) toAdd[valid++] = e;
            }
            if (valid == 0) return false;
            Object[] n = Arrays.copyOf(s, s.length + valid);
            System.arraycopy(toAdd, 0, n, s.length, valid);
            snapshot = n;
            return true;
        }
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        synchronized (lock) {
            Object[] s = snapshot;
            if (index < 0 || index > s.length)
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + s.length);
            Object[] toAdd = c.toArray();
            int valid = 0;
            for (Object e : toAdd) {
                if (e != null) toAdd[valid++] = e;
            }
            if (valid == 0) return false;
            Object[] n = new Object[s.length + valid];
            System.arraycopy(s, 0, n, 0, index);
            System.arraycopy(toAdd, 0, n, index, valid);
            System.arraycopy(s, index, n, index + valid, s.length - index);
            snapshot = n;
            return true;
        }
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        synchronized (lock) {
            Object[] s = snapshot;
            if (s.length == 0) return false;
            ArrayList<Object> survivors = new ArrayList<>(s.length);
            for (Object e : s) {
                @SuppressWarnings("unchecked") E typed = (E) e;
                if (!filter.test(typed)) survivors.add(e);
            }
            if (survivors.size() == s.length) return false;
            snapshot = survivors.isEmpty() ? EMPTY : survivors.toArray();
            return true;
        }
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return removeIf(c::contains);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return removeIf(e -> !c.contains(e));
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        Object[] s = snapshot;
        outer:
        for (Object e : c) {
            for (Object x : s) {
                if (Objects.equals(e, x)) continue outer;
            }
            return false;
        }
        return true;
    }

    @Override
    public Object[] toArray() {
        Object[] s = snapshot;
        return s.length == 0 ? EMPTY : s.clone();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        Object[] s = snapshot;
        if (a.length < s.length) return (T[]) Arrays.copyOf(s, s.length, a.getClass());
        System.arraycopy(s, 0, a, 0, s.length);
        if (a.length > s.length) a[s.length] = null;
        return a;
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        Object[] s = snapshot;
        for (Object e : s) {
            @SuppressWarnings("unchecked") E typed = (E) e;
            action.accept(typed);
        }
    }

    private final class SnapIter implements Iterator<E> {
        private final Object[] snap;
        private int cursor;

        SnapIter(Object[] snap) { this.snap = snap; }

        @Override public boolean hasNext() { return cursor < snap.length; }

        @Override
        @SuppressWarnings("unchecked")
        public E next() {
            if (cursor >= snap.length) throw new NoSuchElementException();
            return (E) snap[cursor++];
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("SnapshotList iterator is read-only");
        }

        @Override
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            while (cursor < snap.length) {
                @SuppressWarnings("unchecked") E e = (E) snap[cursor++];
                action.accept(e);
            }
        }
    }
}
