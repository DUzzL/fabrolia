package com.axalotl.fabrolia.api.fastutil;

import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceCollection;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Drop-in concurrent replacement for
 * {@link Reference2ReferenceOpenHashMap} that protects the parent's
 * internal arrays with a {@link ReentrantReadWriteLock}.
 * <p>
 * Extension (not composition) keeps {@code instanceof
 * Reference2ReferenceOpenHashMap} checks valid and lets the fabrolia mod
 * transparently swap instances via bytecode rewriting — callers holding a
 * reference typed as the parent class keep working.
 * <p>
 * <b>Why reentrant rather than {@link java.util.concurrent.locks.StampedLock}</b>:
 * fastutil's parent methods freely call {@code this.X} virtually. For example
 * {@code Reference2ReferenceOpenHashMap.putAll} calls {@code this.size()} and
 * then {@code super.putAll(m)} which in turn loops {@code put(k, v)} through
 * {@code AbstractReference2ReferenceMap.putAll}. Both size and put dispatch
 * into this subclass's overrides. A non-reentrant lock parks the thread on
 * the first such re-entry. ReentrantReadWriteLock handles this natively,
 * including the write-lock-holding-thread-acquires-read-lock downgrade path.
 * <p>
 * The lock is lazy-initialized because fastutil's
 * {@code Reference2ReferenceOpenHashMap(Map)} calls {@code this.putAll(m)}
 * from inside the superclass constructor — before subclass field initializers
 * have run.
 */
@SuppressWarnings("unchecked")
public final class Reference2ReferenceConcurrentHashMap<K, V> extends Reference2ReferenceOpenHashMap<K, V> {

    private volatile ReentrantReadWriteLock lockField;

    private ReentrantReadWriteLock lock() {
        ReentrantReadWriteLock l = lockField;
        if (l != null) return l;
        synchronized (this) {
            l = lockField;
            if (l == null) lockField = l = new ReentrantReadWriteLock();
            return l;
        }
    }

    public Reference2ReferenceConcurrentHashMap() {
        super();
    }

    public Reference2ReferenceConcurrentHashMap(int expected) {
        super(expected);
    }

    public Reference2ReferenceConcurrentHashMap(int expected, float f) {
        super(expected, f);
    }

    public Reference2ReferenceConcurrentHashMap(Map<? extends K, ? extends V> m) {
        super(m);
    }

    public Reference2ReferenceConcurrentHashMap(Reference2ReferenceMap<K, V> m) {
        super(m);
    }

    @Override
    public V get(Object k) {
        return getLocked(k);
    }

    private V getLocked(Object k) {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.get(k); } finally { l.readLock().unlock(); }
    }

    @Override
    public V getOrDefault(Object k, V defaultValue) {
        return getOrDefaultLocked(k, defaultValue);
    }

    private V getOrDefaultLocked(Object k, V defaultValue) {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.getOrDefault(k, defaultValue); } finally { l.readLock().unlock(); }
    }

    @Override
    public boolean containsKey(Object k) {
        return containsKeyLocked(k);
    }

    private boolean containsKeyLocked(Object k) {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.containsKey(k); } finally { l.readLock().unlock(); }
    }

    @Override
    public boolean containsValue(Object v) {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.containsValue(v); } finally { l.readLock().unlock(); }
    }

    @Override
    public int size() {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.size(); } finally { l.readLock().unlock(); }
    }

    @Override
    public boolean isEmpty() {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.isEmpty(); } finally { l.readLock().unlock(); }
    }

    /** Runs {@code op} under the reentrant write lock. */
    private <R> R doWrite(java.util.function.Supplier<R> op) {
        ReentrantReadWriteLock l = lock();
        l.writeLock().lock();
        try {
            return op.get();
        } finally { l.writeLock().unlock(); }
    }

    private void doWriteVoid(Runnable op) {
        ReentrantReadWriteLock l = lock();
        l.writeLock().lock();
        try {
            op.run();
        } finally { l.writeLock().unlock(); }
    }

    @Override
    public V put(K k, V v) { return doWrite(() -> super.put(k, v)); }

    @Override
    public V putIfAbsent(K k, V v) { return doWrite(() -> super.putIfAbsent(k, v)); }

    @Override
    public V remove(Object k) { return doWrite(() -> super.remove(k)); }

    @Override
    public boolean remove(Object k, Object v) { return doWrite(() -> super.remove(k, v)); }

    @Override
    public V replace(K k, V v) { return doWrite(() -> super.replace(k, v)); }

    @Override
    public boolean replace(K k, V oldV, V newV) { return doWrite(() -> super.replace(k, oldV, newV)); }

    @Override
    public V computeIfAbsent(K k, Function<? super K, ? extends V> f) { return doWrite(() -> super.computeIfAbsent(k, f)); }

    @Override
    public V computeIfPresent(K k, BiFunction<? super K, ? super V, ? extends V> f) { return doWrite(() -> super.computeIfPresent(k, f)); }

    @Override
    public V compute(K k, BiFunction<? super K, ? super V, ? extends V> f) { return doWrite(() -> super.compute(k, f)); }

    @Override
    public V merge(K k, V v, BiFunction<? super V, ? super V, ? extends V> f) { return doWrite(() -> super.merge(k, v, f)); }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) { doWriteVoid(() -> super.putAll(m)); }

    @Override
    public void clear() { doWriteVoid(super::clear); }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        List<Map.Entry<K, V>> snap = snapshotEntries();
        for (Map.Entry<K, V> e : snap) action.accept(e.getKey(), e.getValue());
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> f) { doWriteVoid(() -> super.replaceAll(f)); }

    @Override
    public ReferenceSet<K> keySet() {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return new ReferenceOpenHashSet<>(super.keySet()); } finally { l.readLock().unlock(); }
    }

    @Override
    public ReferenceCollection<V> values() {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return new ReferenceArrayList<>(super.values()); } finally { l.readLock().unlock(); }
    }

    @Override
    public Reference2ReferenceMap.FastEntrySet<K, V> reference2ReferenceEntrySet() {
        List<Map.Entry<K, V>> snap = snapshotEntries();
        Reference2ReferenceOpenHashMap<K, V> copy = new Reference2ReferenceOpenHashMap<>(snap.size());
        for (Map.Entry<K, V> e : snap) copy.put(e.getKey(), e.getValue());
        return copy.reference2ReferenceEntrySet();
    }

    private List<Map.Entry<K, V>> snapshotEntries() {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try {
            List<Map.Entry<K, V>> out = new ArrayList<>(super.size());
            ObjectIterator<Reference2ReferenceMap.Entry<K, V>> it = super.reference2ReferenceEntrySet().iterator();
            while (it.hasNext()) {
                Reference2ReferenceMap.Entry<K, V> e = it.next();
                out.add(new ImmutableRefEntry<>(e.getKey(), e.getValue()));
            }
            return out;
        } finally { l.readLock().unlock(); }
    }

    @Override
    public int hashCode() {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.hashCode(); } finally { l.readLock().unlock(); }
    }

    @Override
    public boolean equals(Object o) {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.equals(o); } finally { l.readLock().unlock(); }
    }

    @Override
    public String toString() {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.toString(); } finally { l.readLock().unlock(); }
    }

    @Override
    public Reference2ReferenceOpenHashMap<K, V> clone() {
        ReentrantReadWriteLock l = lock();
        l.readLock().lock();
        try { return super.clone(); } finally { l.readLock().unlock(); }
    }

    @Override
    public boolean trim() { return doWrite(super::trim); }

    @Override
    public boolean trim(int n) { return doWrite(() -> super.trim(n)); }

    private record ImmutableRefEntry<K, V>(K key, V value) implements Map.Entry<K, V> {
        @Override public K getKey() { return key; }
        @Override public V getValue() { return value; }
        @Override public V setValue(V value) { throw new UnsupportedOperationException(); }
    }
}
