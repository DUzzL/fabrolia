package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.ints.AbstractInt2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Small thread-safe {@link Int2ObjectMap} for per-object tables that hold a
 * handful of entries and are read far more often than written (the per-chunk
 * game-event listener registries: at most one entry per section).
 *
 * <p>Reads, including a {@code computeIfAbsent} that finds its key, are
 * lock-free on an immutable snapshot published through a volatile field.
 * Every mutation, including every compound one ({@code computeIfAbsent},
 * {@code compute}, {@code merge}, … in both the primitive and the boxed
 * {@link Map} form), runs under this map's monitor and publishes a fresh copy,
 * so a compound operation is atomic against every other mutation. Iteration
 * walks one snapshot through immutable entries. An empty map costs one object
 * and one shared empty table — the segmented concurrent map it replaces
 * allocated ~38 KB per chunk.
 */
public final class CopyOnWriteInt2ObjectMap<V> extends AbstractInt2ObjectMap<V> {

    private static final Int2ObjectOpenHashMap<?> EMPTY = new Int2ObjectOpenHashMap<>(0);

    @SuppressWarnings("unchecked")
    private volatile Int2ObjectOpenHashMap<V> snapshot = (Int2ObjectOpenHashMap<V>) EMPTY;

    @Override
    public V get(int key) {
        Int2ObjectOpenHashMap<V> current = snapshot;
        V value = current.get(key);
        return value != null || current.containsKey(key) ? value : this.defRetValue;
    }

    @Override
    public V getOrDefault(int key, V defaultValue) {
        Int2ObjectOpenHashMap<V> current = snapshot;
        V value = current.get(key);
        return value != null || current.containsKey(key) ? value : defaultValue;
    }

    @Override
    public boolean containsKey(int key) {
        return snapshot.containsKey(key);
    }

    @Override
    public int size() {
        return snapshot.size();
    }

    @Override
    public boolean isEmpty() {
        return snapshot.isEmpty();
    }

    /** One snapshot, as immutable entries: a published table is never written. */
    @Override
    @SuppressWarnings("unchecked")
    public ObjectSet<Int2ObjectMap.Entry<V>> int2ObjectEntrySet() {
        Int2ObjectOpenHashMap<V> current = snapshot;
        Int2ObjectMap.Entry<V>[] entries = new Int2ObjectMap.Entry[current.size()];
        int i = 0;
        for (Int2ObjectMap.Entry<V> e : current.int2ObjectEntrySet()) {
            entries[i++] = new AbstractInt2ObjectMap.BasicEntry<>(e.getIntKey(), e.getValue());
        }
        return ObjectSets.unmodifiable(new ObjectArraySet<>(entries));
    }

    @Override
    public synchronized V put(int key, V value) {
        Int2ObjectOpenHashMap<V> next = new Int2ObjectOpenHashMap<>(snapshot);
        boolean had = next.containsKey(key);
        V previous = next.put(key, value);
        snapshot = next;
        return had ? previous : this.defRetValue;
    }

    @Override
    public synchronized V remove(int key) {
        Int2ObjectOpenHashMap<V> current = snapshot;
        if (!current.containsKey(key)) return this.defRetValue;
        Int2ObjectOpenHashMap<V> next = new Int2ObjectOpenHashMap<>(current);
        V previous = next.remove(key);
        snapshot = next;
        return previous;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void clear() {
        snapshot = (Int2ObjectOpenHashMap<V>) EMPTY;
    }

    @Override
    public synchronized void putAll(Map<? extends Integer, ? extends V> m) {
        Int2ObjectOpenHashMap<V> next = new Int2ObjectOpenHashMap<>(snapshot);
        next.putAll(m);
        snapshot = next;
    }

    @Override
    public synchronized void replaceAll(BiFunction<? super Integer, ? super V, ? extends V> function) {
        Int2ObjectOpenHashMap<V> next = new Int2ObjectOpenHashMap<>(snapshot);
        next.replaceAll(function);
        snapshot = next;
    }

    // Compound operations: the interface defaults over get/put/remove, made
    // atomic by holding the monitor across the whole read-modify-write.

    @Override
    public V computeIfAbsent(int key, IntFunction<? extends V> mappingFunction) {
        V present = snapshot.get(key);
        if (present != null) return present;
        synchronized (this) {
            return super.computeIfAbsent(key, mappingFunction);
        }
    }

    @Override
    public V computeIfAbsent(int key, Int2ObjectFunction<? extends V> mappingFunction) {
        V present = snapshot.get(key);
        if (present != null) return present;
        synchronized (this) {
            return super.computeIfAbsent(key, mappingFunction);
        }
    }

    @Override
    @Deprecated
    public synchronized V computeIfAbsentPartial(int key, Int2ObjectFunction<? extends V> mappingFunction) {
        return super.computeIfAbsentPartial(key, mappingFunction);
    }

    @Override
    public synchronized V putIfAbsent(int key, V value) {
        return super.putIfAbsent(key, value);
    }

    @Override
    public synchronized boolean remove(int key, Object value) {
        return super.remove(key, value);
    }

    @Override
    public synchronized boolean replace(int key, V oldValue, V newValue) {
        return super.replace(key, oldValue, newValue);
    }

    @Override
    public synchronized V replace(int key, V value) {
        return super.replace(key, value);
    }

    @Override
    public synchronized V computeIfPresent(int key,
            BiFunction<? super Integer, ? super V, ? extends V> remappingFunction) {
        return super.computeIfPresent(key, remappingFunction);
    }

    @Override
    public synchronized V compute(int key,
            BiFunction<? super Integer, ? super V, ? extends V> remappingFunction) {
        return super.compute(key, remappingFunction);
    }

    @Override
    public synchronized V merge(int key, V value,
            BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        return super.merge(key, value, remappingFunction);
    }

    // Boxed java.util.Map compound forms: same atomicity.

    @Override
    public synchronized V computeIfAbsent(Integer key, Function<? super Integer, ? extends V> mappingFunction) {
        return super.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public synchronized V computeIfPresent(Integer key,
            BiFunction<? super Integer, ? super V, ? extends V> remappingFunction) {
        return super.computeIfPresent(key, remappingFunction);
    }

    @Override
    public synchronized V compute(Integer key,
            BiFunction<? super Integer, ? super V, ? extends V> remappingFunction) {
        return super.compute(key, remappingFunction);
    }

    @Override
    public synchronized V merge(Integer key, V value,
            BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        return super.merge(key, value, remappingFunction);
    }

    @Override
    public synchronized V putIfAbsent(Integer key, V value) {
        return super.putIfAbsent(key, value);
    }

    @Override
    public synchronized boolean remove(Object key, Object value) {
        return super.remove(key, value);
    }

    @Override
    public synchronized boolean replace(Integer key, V oldValue, V newValue) {
        return super.replace(key, oldValue, newValue);
    }

    @Override
    public synchronized V replace(Integer key, V value) {
        return super.replace(key, value);
    }
}
