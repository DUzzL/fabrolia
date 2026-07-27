package com.axalotl.fabrolia.api.fastutil;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.*;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe LongSortedSet backed by ConcurrentSkipListSet.
 *
 * <p>Hot path: {@code toLongArray()} is called per-tick by VMP/vanilla code that snapshots
 * section IDs. The original implementation used {@code backing.stream().mapToLong(...).toArray()},
 * which costs ~0.37 ms/tick under bot load (stream/spliterator overhead on a skip list +
 * Long-boxed iterator). This rewrite:
 * <ul>
 *   <li>Maintains an {@link AtomicLong} modCount, bumped on every mutation.</li>
 *   <li>Caches the last snapshot together with the modCount at which it was built.</li>
 *   <li>{@code toLongArray()} returns the cache when modCount is unchanged — the common
 *       case when many call sites snapshot in the same tick without intervening adds/removes.</li>
 *   <li>Cache-miss path iterates the skip list directly into a fastutil {@code LongArrayList}
 *       (no boxing in the result, amortized growth).</li>
 * </ul>
 *
 * <p>Snapshot semantics intentionally relaxed: a concurrent add/remove that races with
 * {@code toLongArray} may see either the pre- or post-mod state. Vanilla's original
 * {@code LongLinkedOpenHashSet}-based storage was likewise non-atomic vs concurrent ticking;
 * the {@link ConcurrentSkipListSet} backing already provides safe iteration but not snapshot
 * atomicity. Callers that need an atomic snapshot must synchronize externally.
 */
public final class ConcurrentLongSortedSet implements LongSortedSet {

    private final ConcurrentSkipListSet<Long> backing;

    private final AtomicLong modCount = new AtomicLong();

    private volatile Snapshot cached;

    private record Snapshot(long version, long[] data) {}

    public ConcurrentLongSortedSet() { this.backing = new ConcurrentSkipListSet<>(); }

    public ConcurrentLongSortedSet(Collection<Long> collection) {
        this();
        addAll(Objects.requireNonNull(collection));
    }

    @Override public LongBidirectionalIterator iterator(long fromElement) {
        return wrap(backing.tailSet(fromElement).iterator());
    }

    @Override public LongBidirectionalIterator iterator() { return wrap(backing.iterator()); }
    @Override public int size() { return backing.size(); }
    @Override public boolean isEmpty() { return backing.isEmpty(); }
    @Override public Object[] toArray() { return backing.toArray(); }
    @Override public <T> T[] toArray(T[] a) { return backing.toArray(a); }
    @Override public boolean containsAll(Collection<?> c) { return backing.containsAll(c); }

    @Override public boolean addAll(Collection<? extends Long> c) {
        boolean mod = backing.addAll(c);
        if (mod) modCount.incrementAndGet();
        return mod;
    }

    @Override public boolean removeAll(Collection<?> c) {
        boolean mod = backing.removeAll(c);
        if (mod) modCount.incrementAndGet();
        return mod;
    }

    @Override public boolean retainAll(Collection<?> c) {
        boolean mod = backing.retainAll(c);
        if (mod) modCount.incrementAndGet();
        return mod;
    }

    @Override public void clear() {
        if (!backing.isEmpty()) {
            backing.clear();
            modCount.incrementAndGet();
        }
    }

    @Override public boolean add(long key) {
        boolean mod = backing.add(key);
        if (mod) modCount.incrementAndGet();
        return mod;
    }

    @Override public boolean contains(long key) { return backing.contains(key); }

    @Override public boolean remove(long k) {
        boolean mod = backing.remove(k);
        if (mod) modCount.incrementAndGet();
        return mod;
    }

    @Override public long[] toLongArray() {
        long version = modCount.get();
        Snapshot snap = cached;
        if (snap != null && snap.version == version) {
            return snap.data;
        }

        LongArrayList list = new LongArrayList(Math.max(16, snap == null ? 16 : snap.data.length));
        for (Long boxed : backing) list.add(boxed.longValue());
        long[] data = list.toLongArray();

        // Only publish if no mutation happened between the version read and now;
        // otherwise the in-flight mutator's call will publish a fresher snapshot.
        if (modCount.get() == version) {
            cached = new Snapshot(version, data);
        }
        return data;
    }

    @Override public long[] toArray(long[] a) {
        long[] result = toLongArray();
        if (a.length < result.length) return result;
        System.arraycopy(result, 0, a, 0, result.length);
        if (a.length > result.length) a[result.length] = 0L;
        return a;
    }

    @Override public boolean addAll(LongCollection c) {
        boolean mod = false;
        for (LongIterator it = c.iterator(); it.hasNext(); ) if (backing.add(it.nextLong())) mod = true;
        if (mod) modCount.incrementAndGet();
        return mod;
    }
    @Override public boolean containsAll(LongCollection c) {
        for (LongIterator it = c.iterator(); it.hasNext(); ) if (!backing.contains(it.nextLong())) return false;
        return true;
    }
    @Override public boolean removeAll(LongCollection c) {
        boolean mod = false;
        for (LongIterator it = c.iterator(); it.hasNext(); ) if (backing.remove(it.nextLong())) mod = true;
        if (mod) modCount.incrementAndGet();
        return mod;
    }
    @Override public boolean retainAll(LongCollection c) {
        boolean mod = backing.retainAll(c);
        if (mod) modCount.incrementAndGet();
        return mod;
    }

    @Override public LongSortedSet subSet(long from, long to) {
        return new ConcurrentLongSortedSet(backing.subSet(Math.min(from, to), Math.max(from, to)));
    }
    @Override public LongSortedSet headSet(long to) { return new ConcurrentLongSortedSet(backing.headSet(to)); }
    @Override public LongSortedSet tailSet(long from) { return new ConcurrentLongSortedSet(backing.tailSet(from)); }
    @Override public LongComparator comparator() { return null; }
    @Override public long firstLong() { return backing.first(); }
    @Override public long lastLong() { return backing.last(); }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LongSet that)) return false;
        if (size() != that.size()) return false;
        for (LongIterator it = that.iterator(); it.hasNext(); ) if (!contains(it.nextLong())) return false;
        return true;
    }

    @Override public int hashCode() {
        int h = 0;
        for (long v : backing) h += HashCommon.long2int(v);
        return h;
    }

    @Override public String toString() { return backing.toString(); }

    private static LongBidirectionalIterator wrap(Iterator<Long> it) {
        return new LongBidirectionalIterator() {
            @Override public long previousLong() { throw new UnsupportedOperationException(); }
            @Override public boolean hasPrevious() { throw new UnsupportedOperationException(); }
            @Override public long nextLong() { return it.next(); }
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public void remove() { it.remove(); }
        };
    }
}
