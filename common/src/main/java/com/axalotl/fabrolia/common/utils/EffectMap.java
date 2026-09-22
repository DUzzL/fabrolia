package com.axalotl.fabrolia.common.utils;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Effect mutations share the entity tick's monitor without locking remote readers.
 * Monitor-owned traversal stops before its next element after structural reentry,
 * but an insertion does not cancel expiration of the still-current effect.
 * Unowned readers retain ConcurrentHashMap's weak traversal.
 * Values are live references, not snapshots of mutable effect-instance fields.
 */
public final class EffectMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    private final ConcurrentHashMap<K, V> backing = new ConcurrentHashMap<>();
    // Only writers and monitor-owned iterators access this field.
    private long structuralRevision;
    private long removalRevision;
    private volatile Set<K> keys;
    private volatile Collection<V> values;
    private volatile Set<Entry<K, V>> entries;

    @Override public V get(Object key) { return backing.get(key); }
    @Override public V getOrDefault(Object key, V fallback) { return backing.getOrDefault(key, fallback); }
    @Override public boolean containsKey(Object key) { return backing.containsKey(key); }
    @Override public boolean containsValue(Object value) { return backing.containsValue(value); }
    @Override public int size() { return backing.size(); }
    @Override public boolean isEmpty() { return backing.isEmpty(); }
    @Override public void forEach(BiConsumer<? super K, ? super V> action) { backing.forEach(action); }

    @Override
    public synchronized V put(K key, V value) {
        V previous = backing.put(key, value);
        if (previous == null) structuralRevision++;
        return previous;
    }

    @Override
    public synchronized V putIfAbsent(K key, V value) {
        V previous = backing.putIfAbsent(key, value);
        if (previous == null) structuralRevision++;
        return previous;
    }

    @Override
    public synchronized V remove(Object key) {
        V previous = backing.remove(key);
        if (previous != null) {
            structuralRevision++;
            removalRevision++;
        }
        return previous;
    }

    @Override
    public synchronized boolean remove(Object key, Object value) {
        boolean removed = backing.remove(key, value);
        if (removed) {
            structuralRevision++;
            removalRevision++;
        }
        return removed;
    }

    @Override
    public synchronized void clear() {
        if (!backing.isEmpty()) {
            backing.clear();
            structuralRevision++;
            removalRevision++;
        }
    }

    @Override
    public synchronized void putAll(Map<? extends K, ? extends V> source) {
        for (Entry<? extends K, ? extends V> entry : source.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override public synchronized V replace(K key, V value) { return backing.replace(key, value); }
    @Override public synchronized boolean replace(K key, V oldValue, V newValue) {
        return backing.replace(key, oldValue, newValue);
    }

    @Override
    public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        boolean present = backing.containsKey(key);
        try {
            return backing.computeIfAbsent(key, function);
        } finally {
            recordPresenceChange(present, backing.containsKey(key));
        }
    }

    @Override
    public synchronized V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        boolean present = backing.containsKey(key);
        try {
            return backing.computeIfPresent(key, function);
        } finally {
            recordPresenceChange(present, backing.containsKey(key));
        }
    }

    @Override
    public synchronized V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        boolean present = backing.containsKey(key);
        try {
            return backing.compute(key, function);
        } finally {
            recordPresenceChange(present, backing.containsKey(key));
        }
    }

    @Override
    public synchronized V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        boolean present = backing.containsKey(key);
        try {
            return backing.merge(key, value, function);
        } finally {
            recordPresenceChange(present, backing.containsKey(key));
        }
    }

    private void recordPresenceChange(boolean before, boolean after) {
        if (before != after) {
            structuralRevision++;
            if (before) removalRevision++;
        }
    }

    @Override
    public synchronized void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        backing.replaceAll(function);
    }

    @Override public Set<K> keySet() {
        Set<K> view = keys;
        if (view == null) keys = view = new KeyView();
        return view;
    }
    @Override public Collection<V> values() {
        Collection<V> view = values;
        if (view == null) values = view = new ValueView();
        return view;
    }
    @Override public Set<Entry<K, V>> entrySet() {
        Set<Entry<K, V>> view = entries;
        if (view == null) entries = view = new EntryView();
        return view;
    }

    private abstract class GuardedIterator<T> implements Iterator<T> {
        final boolean owned = Thread.holdsLock(EffectMap.this);
        long expectedRevision = owned ? structuralRevision : 0;
        long expectedRemovalRevision = owned ? removalRevision : 0;
        K lastKey;
        V lastValue;
        boolean removable;

        V removalValue() { return lastValue; }

        final void checkRevision() {
            if (owned && expectedRevision != structuralRevision) throw new ConcurrentModificationException();
        }

        @Override public final void remove() {
            synchronized (EffectMap.this) {
                if (!removable) throw new IllegalStateException();
                boolean reentered = owned && expectedRevision != structuralRevision;
                // An insertion during tickServer must not retain the expiring old
                // effect. A removal may have removed/reinserted even the SAME value:
                // that is a new registration, not this cursor's expiration.
                if (owned && (expectedRemovalRevision != removalRevision
                        || backing.get(lastKey) != removalValue())) {
                    throw new ConcurrentModificationException();
                }
                EffectMap.this.remove(lastKey);
                if (owned) {
                    expectedRemovalRevision = removalRevision;
                    // Still abort before another element after reentry: an effect
                    // installed by this tick starts counting down on the next tick.
                    if (!reentered) expectedRevision = structuralRevision;
                }
                removable = false;
            }
        }
    }

    // Only the owned cursor needs the current registration identity.
    private final class KeyIterator extends GuardedIterator<K> {
        private final Iterator<K> iterator = backing.keySet().iterator();
        @Override public boolean hasNext() { return iterator.hasNext(); }
        @Override public K next() {
            checkRevision();
            lastKey = iterator.next();
            if (owned) lastValue = backing.get(lastKey);
            removable = true;
            return lastKey;
        }
    }

    private abstract class ProjectedIterator<T> extends GuardedIterator<T> {
        private final Iterator<K> iterator = backing.keySet().iterator();
        private K nextKey;
        private V nextValue;
        ProjectedIterator() { advance(); }
        private void advance() {
            nextKey = null;
            nextValue = null;
            while (iterator.hasNext()) {
                K key = iterator.next();
                if (owned) {
                    nextKey = key;
                    return;
                }
                V value = backing.get(key);
                if (value != null) {
                    nextKey = key;
                    nextValue = value;
                    return;
                }
            }
        }
        @Override public boolean hasNext() { return nextKey != null; }
        @Override public T next() {
            checkRevision();
            if (nextKey == null) throw new NoSuchElementException();
            K key = nextKey;
            // Owned traversal observes nonstructural value replacements, like HashMap.
            V value = owned ? backing.get(key) : nextValue;
            lastKey = key;
            lastValue = value;
            removable = true;
            advance();
            return project(key, value);
        }
        abstract T project(K key, V value);
    }

    private final class ValueIterator extends ProjectedIterator<V> {
        @Override V project(K key, V value) { return value; }
    }
    private final class EntryIterator extends ProjectedIterator<Entry<K, V>> {
        private WriteThroughEntry lastEntry;
        @Override Entry<K, V> project(K key, V value) {
            return lastEntry = new WriteThroughEntry(key, value);
        }
        @Override V removalValue() { return lastEntry.value; }
    }
    private final class WriteThroughEntry implements Entry<K, V> {
        private final K key;
        private V value;
        WriteThroughEntry(K key, V value) { this.key = key; this.value = value; }
        @Override public K getKey() { return key; }
        @Override public V getValue() { return value; }
        @Override public V setValue(V replacement) {
            synchronized (EffectMap.this) {
                EffectMap.this.put(key, replacement);
                V previous = value;
                value = replacement;
                return previous;
            }
        }
        @Override public boolean equals(Object other) {
            return other instanceof Entry<?, ?> entry
                    && key.equals(entry.getKey()) && value.equals(entry.getValue());
        }
        @Override public int hashCode() { return key.hashCode() ^ value.hashCode(); }
        @Override public String toString() { return key + "=" + value; }
    }

    private final class KeyView extends AbstractSet<K> {
        @Override public int size() { return EffectMap.this.size(); }
        @Override public boolean isEmpty() { return EffectMap.this.isEmpty(); }
        @Override public boolean contains(Object key) { return containsKey(key); }
        @Override public Iterator<K> iterator() { return new KeyIterator(); }
        @Override public boolean remove(Object key) { return EffectMap.this.remove(key) != null; }
        @Override public void clear() { EffectMap.this.clear(); }
        @Override public boolean add(K key) { throw new UnsupportedOperationException(); }
        @Override public boolean addAll(Collection<? extends K> source) { throw new UnsupportedOperationException(); }
        @Override public boolean removeAll(Collection<?> source) {
            synchronized (EffectMap.this) { return super.removeAll(source); }
        }
        @Override public boolean retainAll(Collection<?> source) {
            synchronized (EffectMap.this) { return super.retainAll(source); }
        }
        @Override public boolean removeIf(Predicate<? super K> filter) {
            synchronized (EffectMap.this) { return super.removeIf(filter); }
        }
        @Override public Spliterator<K> spliterator() {
            return Thread.holdsLock(EffectMap.this)
                    ? Spliterators.spliteratorUnknownSize(iterator(), Spliterator.DISTINCT | Spliterator.NONNULL)
                    : backing.keySet().spliterator();
        }
    }

    private final class ValueView extends AbstractCollection<V> {
        @Override public int size() { return EffectMap.this.size(); }
        @Override public boolean isEmpty() { return EffectMap.this.isEmpty(); }
        @Override public boolean contains(Object value) { return containsValue(value); }
        @Override public Iterator<V> iterator() { return new ValueIterator(); }
        @Override public void clear() { EffectMap.this.clear(); }
        @Override public boolean add(V value) { throw new UnsupportedOperationException(); }
        @Override public boolean addAll(Collection<? extends V> source) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(Object value) {
            if (value == null) return false;
            synchronized (EffectMap.this) { return super.remove(value); }
        }
        @Override public boolean removeAll(Collection<?> source) {
            synchronized (EffectMap.this) { return super.removeAll(source); }
        }
        @Override public boolean retainAll(Collection<?> source) {
            synchronized (EffectMap.this) { return super.retainAll(source); }
        }
        @Override public boolean removeIf(Predicate<? super V> filter) {
            Objects.requireNonNull(filter);
            synchronized (EffectMap.this) {
                boolean changed = false;
                for (K key : backing.keySet()) {
                    V value = backing.get(key);
                    if (value != null && filter.test(value) && EffectMap.this.remove(key, value)) changed = true;
                }
                return changed;
            }
        }
        @Override public Spliterator<V> spliterator() {
            return Thread.holdsLock(EffectMap.this)
                    ? Spliterators.spliteratorUnknownSize(iterator(), Spliterator.NONNULL)
                    : backing.values().spliterator();
        }
    }

    private final class EntryView extends AbstractSet<Entry<K, V>> {
        @Override public int size() { return EffectMap.this.size(); }
        @Override public boolean isEmpty() { return EffectMap.this.isEmpty(); }
        @Override public Iterator<Entry<K, V>> iterator() { return new EntryIterator(); }
        @Override public void clear() { EffectMap.this.clear(); }
        @Override public boolean contains(Object value) {
            if (!(value instanceof Entry<?, ?> entry) || entry.getKey() == null || entry.getValue() == null) return false;
            V current = backing.get(entry.getKey());
            return current != null && entry.getValue().equals(current);
        }
        @Override public boolean remove(Object value) {
            if (!(value instanceof Entry<?, ?> entry) || entry.getKey() == null) return false;
            return EffectMap.this.remove(entry.getKey(), entry.getValue());
        }
        @Override public boolean add(Entry<K, V> entry) {
            return EffectMap.this.put(entry.getKey(), entry.getValue()) == null;
        }
        @Override public boolean addAll(Collection<? extends Entry<K, V>> source) {
            synchronized (EffectMap.this) {
                boolean changed = false;
                for (Entry<K, V> entry : source) changed |= add(entry);
                return changed;
            }
        }
        @Override public boolean removeAll(Collection<?> source) {
            synchronized (EffectMap.this) { return super.removeAll(source); }
        }
        @Override public boolean retainAll(Collection<?> source) {
            synchronized (EffectMap.this) { return super.retainAll(source); }
        }
        @Override public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
            Objects.requireNonNull(filter);
            synchronized (EffectMap.this) {
                boolean changed = false;
                for (K key : backing.keySet()) {
                    V value = backing.get(key);
                    if (value != null && filter.test(new WriteThroughEntry(key, value))
                            && EffectMap.this.remove(key, value)) changed = true;
                }
                return changed;
            }
        }
        @Override public Spliterator<Entry<K, V>> spliterator() {
            int flags = Spliterator.DISTINCT | Spliterator.NONNULL;
            if (!Thread.holdsLock(EffectMap.this)) flags |= Spliterator.CONCURRENT;
            return Spliterators.spliteratorUnknownSize(iterator(), flags);
        }
    }
}
