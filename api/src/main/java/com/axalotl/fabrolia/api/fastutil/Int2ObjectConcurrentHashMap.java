package com.axalotl.fabrolia.api.fastutil;

import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.AbstractObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectArrays;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.jspecify.annotations.NonNull;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * 32+-stripe concurrent {@link Int2ObjectOpenHashMap}.
 * <p>
 * <b>Extends</b> {@code Int2ObjectOpenHashMap} (not just {@code AbstractInt2ObjectMap})
 * so this class is a drop-in replacement for fastutil fields declared with
 * the concrete type — VerifyError-safe for {@code PUTFIELD} and
 * {@code instanceof} checks. The parent's internal arrays stay empty; all
 * state is held by the stripe array. Every public method of the parent is
 * overridden to route through the stripes, otherwise inherited methods would
 * read the unused parent arrays and silently return empty/default values.
 * <p>
 * Reads take the per-stripe {@link StampedLock} read lock: fastutil publishes
 * its mask and backing arrays separately during rehash, so even a subsequently
 * invalidated optimistic traversal can throw. Writes take the stripe's write
 * lock. {@code size()} is an amortized {@link LongAdder} sum, so updates don't
 * contend across stripes.
 * <p>
 * Stripes (backing map + lock) are created on their first write: an empty map
 * costs two reference arrays, not {@code segmentCount} hash tables and locks.
 * A stripe's map is installed before its lock is published (release), and
 * every access to a stripe's map follows an acquire read of its lock, so a
 * reader that sees no lock sees an absent key — the write that would create
 * the stripe has not inserted anything yet.
 */
public final class Int2ObjectConcurrentHashMap<V> extends Int2ObjectOpenHashMap<V> {

    private static final int DEFAULT_SEGMENTS = defaultSegmentCount();
    /** fastutil's own default expected size, per stripe. */
    private static final int DEFAULT_STRIPE_SIZE = 16;
    private static final VarHandle LOCKS = MethodHandles.arrayElementVarHandle(StampedLock[].class);

    private final int segmentCount;
    private final int segmentMask;
    private final int perSegment;
    /** Written only under the {@code segments} monitor, before the stripe's lock is published. */
    private final Int2ObjectOpenHashMap<V>[] segments;
    /** Published with release under the {@code segments} monitor; read with acquire. */
    private final StampedLock[] locks;
    private final LongAdder totalSize = new LongAdder();

    public Int2ObjectConcurrentHashMap() { this(DEFAULT_STRIPE_SIZE * DEFAULT_SEGMENTS, DEFAULT_SEGMENTS); }
    public Int2ObjectConcurrentHashMap(int expectedSize) { this(expectedSize, DEFAULT_SEGMENTS); }
    public Int2ObjectConcurrentHashMap(int expected, float f) { this(expected, DEFAULT_SEGMENTS); }
    public Int2ObjectConcurrentHashMap(Map<? extends Integer, ? extends V> m) {
        this(Math.max(16, m.size()), DEFAULT_SEGMENTS);
        putAll(m);
    }
    public Int2ObjectConcurrentHashMap(Int2ObjectMap<V> m) {
        this(Math.max(16, m.size()), DEFAULT_SEGMENTS);
        for (Int2ObjectMap.Entry<V> e : m.int2ObjectEntrySet()) put(e.getIntKey(), e.getValue());
    }

    @SuppressWarnings("unchecked")
    public Int2ObjectConcurrentHashMap(int expectedSize, int concurrencyLevel) {
        // The parent's own tables are never used: keep them minimal.
        super(0, DEFAULT_LOAD_FACTOR);
        this.segmentCount = nextPowerOf2(Math.max(16, concurrencyLevel));
        this.segmentMask = segmentCount - 1;
        this.perSegment = Math.max(DEFAULT_STRIPE_SIZE, expectedSize / segmentCount);
        segments = new Int2ObjectOpenHashMap[segmentCount];
        locks = new StampedLock[segmentCount];
    }

    /** The stripe's lock, or null while the stripe was never created (no mapping there). */
    private StampedLock existingLock(int seg) {
        return (StampedLock) LOCKS.getAcquire(locks, seg);
    }

    /** The stripe's lock, creating the stripe on first use (writes). */
    private StampedLock lock(int seg) {
        StampedLock lock = existingLock(seg);
        return lock != null ? lock : createStripe(seg);
    }

    private StampedLock createStripe(int seg) {
        synchronized (segments) {
            if (segments[seg] == null) {
                Int2ObjectOpenHashMap<V> segment = new Int2ObjectOpenHashMap<>(perSegment, DEFAULT_LOAD_FACTOR);
                segment.defaultReturnValue(defRetValue);
                segments[seg] = segment;
            }
            StampedLock lock = locks[seg];
            if (lock == null) {
                lock = new StampedLock();
                LOCKS.setRelease(locks, seg, lock);
            }
            return lock;
        }
    }

    /** Lock of a stripe for a whole-map pass, or null when the stripe holds no map. */
    private StampedLock traversalLock(int seg) {
        StampedLock lock = existingLock(seg);
        if (lock != null) return lock;
        synchronized (segments) {
            if (segments[seg] == null) return null;
        }
        return createStripe(seg);
    }

    static int defaultSegmentCount() {
        return nextPowerOf2(Math.max(16, Runtime.getRuntime().availableProcessors()));
    }

    private static int nextPowerOf2(int v) {
        return Integer.highestOneBit(Math.max(1, v - 1)) << 1;
    }

    private static int spread(int key) {
        return key ^ (key >>> 16);
    }

    private int segmentFor(int key) { return spread(key) & segmentMask; }

    // ============================== READS ==============================

    @Override
    public V get(int key) {
        int seg = segmentFor(key);
        StampedLock lock = existingLock(seg);
        if (lock == null) return defRetValue;
        long stamp = lock.readLock();
        try {
            return segments[seg].get(key);
        } finally { lock.unlockRead(stamp); }
    }

    @Override public V get(Object k) {
        if (!(k instanceof Integer key)) return null;
        int seg = segmentFor(key);
        StampedLock lock = existingLock(seg);
        if (lock == null) return null;
        long stamp = lock.readLock();
        try { return segments[seg].get(k); }
        finally { lock.unlockRead(stamp); }
    }

    @Override
    public V getOrDefault(int key, V defaultValue) {
        int seg = segmentFor(key);
        StampedLock lock = existingLock(seg);
        if (lock == null) return defaultValue;
        long stamp = lock.readLock();
        try { return segments[seg].getOrDefault(key, defaultValue); }
        finally { lock.unlockRead(stamp); }
    }

    @Override public V getOrDefault(Object k, V d) { return k instanceof Integer i ? getOrDefault(i.intValue(), d) : d; }

    @Override
    public boolean containsKey(int key) {
        int seg = segmentFor(key);
        StampedLock lock = existingLock(seg);
        if (lock == null) return false;
        long stamp = lock.readLock();
        try { return segments[seg].containsKey(key); }
        finally { lock.unlockRead(stamp); }
    }

    @Override public boolean containsKey(Object k) { return k instanceof Integer i && containsKey(i.intValue()); }

    @Override
    public boolean containsValue(Object value) {
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.readLock();
            try { if (segments[i].containsValue(value)) return true; }
            finally { lock.unlockRead(stamp); }
        }
        return false;
    }

    @Override public int size() { return (int) Math.clamp(totalSize.sum(), 0L, (long) Integer.MAX_VALUE); }
    @Override public boolean isEmpty() { return totalSize.sum() == 0; }

    // ============================== WRITES ==============================

    @Override
    public V put(int key, V value) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            int sb = segments[seg].size();
            V prev = segments[seg].put(key, value);
            if (segments[seg].size() > sb) totalSize.increment();
            return prev;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override public V put(Integer k, V v) {
        int seg = segmentFor(k);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            boolean present = segments[seg].containsKey(k.intValue());
            V previous = segments[seg].put(k.intValue(), v);
            if (!present) totalSize.increment();
            return present ? previous : null;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public V putIfAbsent(int key, V value) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            int before = segments[seg].size();
            V previous = segments[seg].putIfAbsent(key, value);
            totalSize.add(segments[seg].size() - before);
            return previous;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public V remove(int key) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            int sb = segments[seg].size();
            V prev = segments[seg].remove(key);
            if (segments[seg].size() < sb) totalSize.decrement();
            return prev;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override public V remove(Object k) {
        if (!(k instanceof Integer key)) return null;
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            int before = segments[seg].size();
            V previous = segments[seg].remove(k);
            totalSize.add(segments[seg].size() - before);
            return previous;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public boolean remove(int key, Object value) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            boolean removed = segments[seg].remove(key, value);
            if (removed) totalSize.decrement();
            return removed;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public V replace(int key, V value) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            if (segments[seg].containsKey(key)) return segments[seg].put(key, value);
            return defaultReturnValue();
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public boolean replace(int key, V oldValue, V newValue) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            return segments[seg].replace(key, oldValue, newValue);
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public V computeIfAbsent(int key, IntFunction<? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.readLock();
        try {
            if (segments[seg].containsKey(key)) return segments[seg].get(key);
        } finally { lock.unlockRead(stamp); }
        stamp = lock.writeLock();
        try {
            // Recheck presence after releasing the read lock, including null mappings.
            if (segments[seg].containsKey(key)) return segments[seg].get(key);
            int before = segments[seg].size();
            V computed = segments[seg].computeIfAbsent(key, mappingFunction);
            totalSize.add(segments[seg].size() - before);
            return computed;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public V computeIfAbsent(int key, Int2ObjectFunction<? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.readLock();
        try {
            if (segments[seg].containsKey(key)) return segments[seg].get(key);
        } finally { lock.unlockRead(stamp); }
        stamp = lock.writeLock();
        try {
            int before = segments[seg].size();
            V computed = segments[seg].computeIfAbsent(key, mappingFunction);
            totalSize.add(segments[seg].size() - before);
            return computed;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public V computeIfPresent(int key, BiFunction<? super Integer, ? super V, ? extends V> remappingFunction) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            int before = segments[seg].size();
            V result = segments[seg].computeIfPresent(key, remappingFunction);
            totalSize.add(segments[seg].size() - before);
            return result;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public V compute(int key, BiFunction<? super Integer, ? super V, ? extends V> remappingFunction) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            int before = segments[seg].size();
            V result = segments[seg].compute(key, remappingFunction);
            totalSize.add(segments[seg].size() - before);
            return result;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public V merge(int key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            int before = segments[seg].size();
            V result = segments[seg].merge(key, value, remappingFunction);
            totalSize.add(segments[seg].size() - before);
            return result;
        } finally { lock.unlockWrite(stamp); }
    }

    @Override
    public void putAll(Map<? extends Integer, ? extends V> m) {
        for (Map.Entry<? extends Integer, ? extends V> e : m.entrySet()) put(e.getKey().intValue(), e.getValue());
    }

    @Override
    public void clear() {
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.writeLock();
            try {
                int removed = segments[i].size();
                segments[i].clear();
                totalSize.add(-removed);
            }
            finally { lock.unlockWrite(stamp); }
        }
    }

    @Override
    public void defaultReturnValue(V rv) {
        // Stripes created from now on copy the new value under the monitor;
        // existing ones are updated under their own write locks, never while
        // holding the monitor (a compute callback may create a stripe).
        synchronized (segments) {
            super.defaultReturnValue(rv);
        }
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.writeLock();
            try { segments[i].defaultReturnValue(rv); }
            finally { lock.unlockWrite(stamp); }
        }
    }

    // ============================== VIEWS ==============================

    @Override public Int2ObjectMap.FastEntrySet<V> int2ObjectEntrySet() {
        Int2ObjectOpenHashMap<V> snap = new Int2ObjectOpenHashMap<>(size());
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.readLock();
            try {
                for (Int2ObjectMap.Entry<V> e : segments[i].int2ObjectEntrySet())
                    snap.put(e.getIntKey(), e.getValue());
            } finally { lock.unlockRead(stamp); }
        }
        return snap.int2ObjectEntrySet();
    }

    @Override
    public @NonNull IntSet keySet() {
        IntOpenHashSet keys = new IntOpenHashSet(size());
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.readLock();
            try {
                IntIterator it = segments[i].keySet().iterator();
                while (it.hasNext()) keys.add(it.nextInt());
            } finally { lock.unlockRead(stamp); }
        }
        return keys;
    }

    /**
     * Returns a live view whose iterators traverse per-iterator snapshots.
     * Iterator removal deletes only the identical captured backing mapping;
     * concurrent additions are visible to the next iterator.
     */
    @Override
    public @NonNull ObjectCollection<V> values() {
        if (values == null) {
            values = new AbstractObjectCollection<>() {
                @Override
                public ObjectIterator<V> iterator() {
                    return new ValuesIterator();
                }

                @Override public int size() {
                    return Int2ObjectConcurrentHashMap.this.size();
                }

                @Override public boolean isEmpty() {
                    return Int2ObjectConcurrentHashMap.this.isEmpty();
                }

                @Override public boolean contains(Object value) {
                    return Int2ObjectConcurrentHashMap.this.containsValue(value);
                }

                @Override public void clear() {
                    Int2ObjectConcurrentHashMap.this.clear();
                }
            };
        }
        return values;
    }

    /**
     * Weakly-consistent snapshot traversal with a live backing-map removal edge.
     * Capturing keys lets vanilla {@link java.util.Iterator#remove()} mutate this
     * map without shifting the snapshot or deleting a concurrently replaced value.
     */
    private final class ValuesIterator implements ObjectIterator<V> {
        private int[] snapshotKeys;
        private V[] snapshotValues;
        private int snapshotSize;
        private int cursor;
        private int lastReturned = -1;

        @SuppressWarnings("unchecked")
        private ValuesIterator() {
            int initialSize = Math.max(1, Int2ObjectConcurrentHashMap.this.size());
            snapshotKeys = new int[initialSize];
            snapshotValues = (V[]) new Object[initialSize];
            for (int i = 0; i < segmentCount; i++) {
                StampedLock lock = traversalLock(i);
                if (lock == null) continue;
                long stamp = lock.readLock();
                try {
                    for (Int2ObjectMap.Entry<V> entry : segments[i].int2ObjectEntrySet()) {
                        int requiredSize = snapshotSize + 1;
                        if (requiredSize > snapshotKeys.length) {
                            snapshotKeys = IntArrays.grow(snapshotKeys, requiredSize);
                            snapshotValues = ObjectArrays.grow(snapshotValues, requiredSize);
                        }
                        snapshotKeys[snapshotSize] = entry.getIntKey();
                        snapshotValues[snapshotSize] = entry.getValue();
                        snapshotSize = requiredSize;
                    }
                } finally {
                    lock.unlockRead(stamp);
                }
            }
        }

        @Override
        public boolean hasNext() {
            return cursor < snapshotSize;
        }

        @Override
        public V next() {
            if (!hasNext()) throw new NoSuchElementException();
            lastReturned = cursor;
            return snapshotValues[cursor++];
        }

        @Override
        public void remove() {
            if (lastReturned < 0) throw new IllegalStateException();
            removeSnapshotMapping(
                    snapshotKeys[lastReturned], snapshotValues[lastReturned]);
            lastReturned = -1;
        }
    }

    private boolean removeSnapshotMapping(int key, V expectedValue) {
        int seg = segmentFor(key);
        StampedLock lock = lock(seg);
        long stamp = lock.writeLock();
        try {
            Int2ObjectOpenHashMap<V> segment = segments[seg];
            if (!segment.containsKey(key) || segment.get(key) != expectedValue) {
                return false;
            }
            segment.remove(key);
            totalSize.decrement();
            return true;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ============================== ITERATION ==============================

    @FunctionalInterface
    public interface IntObjectConsumer<V> { void accept(int key, V value); }

    public void forEach(IntObjectConsumer<V> action) {
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.readLock();
            try {
                for (Int2ObjectMap.Entry<V> e : segments[i].int2ObjectEntrySet())
                    action.accept(e.getIntKey(), e.getValue());
            } finally { lock.unlockRead(stamp); }
        }
    }

    @Override
    public void forEach(BiConsumer<? super Integer, ? super V> action) {
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.readLock();
            try {
                for (Int2ObjectMap.Entry<V> e : segments[i].int2ObjectEntrySet())
                    action.accept(e.getIntKey(), e.getValue());
            } finally { lock.unlockRead(stamp); }
        }
    }

    // ============================== MISC ==============================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Int2ObjectMap<?> that)) return false;
        return size() == that.size() && int2ObjectEntrySet().containsAll(that.int2ObjectEntrySet());
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.readLock();
            try {
                for (Int2ObjectMap.Entry<V> e : segments[i].int2ObjectEntrySet())
                    h += Integer.hashCode(e.getIntKey()) ^ Objects.hashCode(e.getValue());
            } finally { lock.unlockRead(stamp); }
        }
        return h;
    }

    @Override public String toString() { return "Int2ObjectConcurrentHashMap[size=" + size() + "]"; }

    @Override
    public Int2ObjectOpenHashMap<V> clone() {
        Int2ObjectConcurrentHashMap<V> c = new Int2ObjectConcurrentHashMap<>(size(), segmentCount);
        c.defaultReturnValue(defaultReturnValue());
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.readLock();
            try {
                for (Int2ObjectMap.Entry<V> e : segments[i].int2ObjectEntrySet())
                    c.put(e.getIntKey(), e.getValue());
            } finally { lock.unlockRead(stamp); }
        }
        return c;
    }

    @Override public boolean trim() { return trim(-1); }

    @Override
    public boolean trim(int n) {
        boolean all = true;
        for (int i = 0; i < segmentCount; i++) {
            StampedLock lock = traversalLock(i);
            if (lock == null) continue;
            long stamp = lock.writeLock();
            try {
                int localN = n <= 0 ? segments[i].size() : Math.max(n / segmentCount, segments[i].size());
                all &= segments[i].trim(localN);
            } finally { lock.unlockWrite(stamp); }
        }
        return all;
    }
}
