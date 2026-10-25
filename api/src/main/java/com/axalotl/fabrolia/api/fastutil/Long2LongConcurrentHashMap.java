package com.axalotl.fabrolia.api.fastutil;

import it.unimi.dsi.fastutil.longs.AbstractLong2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongUnaryOperator;

public final class Long2LongConcurrentHashMap extends AbstractLong2LongMap {

    private static final long EMPTY     = Long.MIN_VALUE;
    private static final long TOMBSTONE = Long.MIN_VALUE + 1;

    private static final int NSEG_BITS;
    private static final int NSEG;
    private static final int SEGMASK;
    static {
        int cores = Runtime.getRuntime().availableProcessors();
        int n = Math.max(8, Math.min(64, cores * 2));
        int p = Integer.highestOneBit(n - 1) << 1;
        NSEG = p;
        NSEG_BITS = Integer.numberOfTrailingZeros(p);
        SEGMASK = p - 1;
    }

    private static final VarHandle VERSIONS_VH = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle KEYS_VH = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle VALS_VH = MethodHandles.arrayElementVarHandle(long[].class);

    private static int hash(long h) {
        h *= 0xC6BC279692B5C323L;
        return (int) (h ^ (h >>> 32));
    }

    private static final class State {
        final long[] keys;
        final long[] versions;
        final long[] vals;
        final int    mask;
        State(int capPow2) {
            keys = new long[capPow2];
            versions = new long[capPow2];
            vals = new long[capPow2];
            mask = capPow2 - 1;
            Arrays.fill(keys, EMPTY);
        }
    }

    private static final class Segment {
        volatile State state;
        volatile int size;
        int tombstones;
        final int minimumCapacity;
        Segment(int capPow2) {
            minimumCapacity = capPow2;
            state = new State(capPow2);
        }
    }

    private final Segment[] segments;

    /** Optional key grouping; null when the map keeps no group index. */
    private final LongUnaryOperator groupOf;
    /** Group key -> live map keys of that group. A key's set entry changes only
     *  inside {@code groups.compute*} for its group, called by the writer that
     *  holds the key's segment monitor right after the key's slot transition, so
     *  each group set always equals the map's keys in that group. */
    private final ConcurrentHashMap<Long, LongOpenHashSet> groups;

    public Long2LongConcurrentHashMap() { this(64); }
    public Long2LongConcurrentHashMap(int expectedSize) {
        this(expectedSize, null);
    }

    /** A map that also indexes its keys by {@code groupOf(key)}; see
     *  {@link #collectKeysInGroup}. */
    public Long2LongConcurrentHashMap(int expectedSize, LongUnaryOperator groupOf) {
        segments = new Segment[NSEG];
        int perSegEntries = Math.max(8, expectedSize / NSEG);
        int cap = 16;
        while (cap < perSegEntries * 2) cap <<= 1;
        for (int i = 0; i < NSEG; i++) segments[i] = new Segment(cap);
        this.groupOf = groupOf;
        this.groups = groupOf == null ? null : new ConcurrentHashMap<>();
    }
    @SuppressWarnings("unused")
    public Long2LongConcurrentHashMap(int initialCapacity, float loadFactor) { this(initialCapacity); }

    /**
     * Appends the keys of {@code group} to {@code into}: one lookup instead of
     * probing every possible key of the group. Exact for keys whose writers are
     * quiescent; a key written concurrently may or may not be included, as with
     * a {@link #get} that races the write.
     */
    public void collectKeysInGroup(long group, LongArrayList into) {
        if (groups == null) throw new IllegalStateException("map keeps no key groups");
        groups.computeIfPresent(group, (g, keys) -> {
            into.addAll(keys);
            return keys;
        });
    }

    private void groupAdd(long key) {
        if (groups == null) return;
        groups.compute(groupOf.applyAsLong(key), (g, keys) -> {
            if (keys == null) keys = new LongOpenHashSet(4);
            keys.add(key);
            return keys;
        });
    }

    private void groupRemove(long key) {
        if (groups == null) return;
        groups.computeIfPresent(groupOf.applyAsLong(key), (g, keys) -> {
            keys.remove(key);
            return keys.isEmpty() ? null : keys;
        });
    }

    private Segment segmentFor(long key) {
        return segments[(hash(key) >>> (32 - NSEG_BITS)) & SEGMASK];
    }

    @Override public long get(long key) {
        return getOrDefault(key, defaultReturnValue());
    }

    @Override public long put(long key, long value) {
        if (key == EMPTY || key == TOMBSTONE) throw new IllegalArgumentException("reserved key");
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State s = seg.state;
            if (needsResize(seg, s)) s = resizeLocked(seg);
            return putLocked(seg, s, key, value, false);
        }
    }

    @Override public long putIfAbsent(long key, long value) {
        if (key == EMPTY || key == TOMBSTONE) throw new IllegalArgumentException("reserved key");
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State s = seg.state;
            if (needsResize(seg, s)) s = resizeLocked(seg);
            return putLocked(seg, s, key, value, true);
        }
    }

    @Override public long remove(long key) {
        if (key == EMPTY || key == TOMBSTONE) return defaultReturnValue();
        Segment seg = segmentFor(key);
        synchronized (seg) {
            return removeLocked(seg, seg.state, key, false, 0L);
        }
    }

    @Override public boolean remove(long key, long value) {
        if (key == EMPTY || key == TOMBSTONE) return false;
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State state = seg.state;
            int index = findKey(state, key);
            if (index < 0 || state.vals[index] != value) return false;
            removeLocked(seg, state, key, false, value);
            return true;
        }
    }

    @Override public boolean replace(long key, long oldValue, long newValue) {
        if (key == EMPTY || key == TOMBSTONE) return false;
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State s = seg.state;
            int idx = findKey(s, key);
            if (idx < 0) return false;
            if (s.vals[idx] != oldValue) return false;
            writeSlot(s, idx, s.keys[idx], newValue);
            return true;
        }
    }

    @Override public long replace(long key, long value) {
        if (key == EMPTY || key == TOMBSTONE) return defaultReturnValue();
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State s = seg.state;
            int idx = findKey(s, key);
            if (idx < 0) return defaultReturnValue();
            long prev = s.vals[idx];
            writeSlot(s, idx, s.keys[idx], value);
            return prev;
        }
    }

    @Override public boolean containsKey(long key) {
        if (key == EMPTY || key == TOMBSTONE) return false;
        Segment seg = segmentFor(key);
        State s = seg.state;
        long[] k = s.keys;
        int mask = s.mask;
        int idx = hash(key) & mask;
        while (true) {
            long kk = (long) KEYS_VH.getAcquire(k, idx);
            if (kk == EMPTY) return false;
            if (kk == key) return true;
            idx = (idx + 1) & mask;
        }
    }

    @Override public boolean containsValue(long value) {
        for (Segment segment : segments) {
            State state = segment.state;
            for (int i = 0; i <= state.mask; i++) {
                Entry entry = snapshotEntry(state, i);
                if (entry != null && entry.getLongValue() == value) return true;
            }
        }
        return false;
    }

    @Override public long getOrDefault(long key, long defaultValue) {
        if (key == EMPTY || key == TOMBSTONE) return defaultValue;
        Segment seg = segmentFor(key);
        restart:
        while (true) {
            State s = seg.state;
            int idx = hash(key) & s.mask;
            while (true) {
                long version = (long) VERSIONS_VH.getVolatile(s.versions, idx);
                long found = (long) KEYS_VH.getAcquire(s.keys, idx);
                if (found == EMPTY) return defaultValue;
                if (found == key) {
                    if ((version & 1L) != 0) continue restart;
                    long value = (long) VALS_VH.getAcquire(s.vals, idx);
                    // Validate the slot generation, not just its key: A -> B -> A
                    // can otherwise pair A with B's value. Every mutation of this
                    // slot is bracketed by writeSlot; callbacks run outside it.
                    VarHandle.acquireFence();
                    if ((long) VERSIONS_VH.getVolatile(s.versions, idx) == version) return value;
                    continue restart;
                }
                idx = (idx + 1) & s.mask;
            }
        }
    }

    @Override public long compute(long key, BiFunction<? super Long, ? super Long, ? extends Long> remappingFunction) {
        if (key == EMPTY || key == TOMBSTONE) throw new IllegalArgumentException("reserved key");
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State s = seg.state;
            int idx = findKey(s, key);
            Long old = (idx >= 0) ? s.vals[idx] : null;
            Long nv = remappingFunction.apply(key, old);
            if (nv == null) {
                if (idx >= 0) {
                    writeSlot(s, idx, TOMBSTONE, s.vals[idx]);
                    seg.size--; seg.tombstones++;
                    shrinkAfterRemoveLocked(seg);
                    groupRemove(key);
                }
                return defaultReturnValue();
            }
            if (idx >= 0) {
                writeSlot(s, idx, s.keys[idx], nv);
            } else {
                if (needsResize(seg, s)) s = resizeLocked(seg);
                putLocked(seg, s, key, nv, false);
            }
            return nv;
        }
    }

    @Override public long computeIfAbsent(long key, LongUnaryOperator mappingFunction) {
        if (key == EMPTY || key == TOMBSTONE) throw new IllegalArgumentException("reserved key");
        long existing = get(key);
        if (existing != defaultReturnValue()) return existing;
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State s = seg.state;
            int idx = findKey(s, key);
            if (idx >= 0) return s.vals[idx];
            long nv = mappingFunction.applyAsLong(key);
            if (needsResize(seg, s)) s = resizeLocked(seg);
            putLocked(seg, s, key, nv, false);
            return nv;
        }
    }

    @Override public long computeIfPresent(long key, BiFunction<? super Long, ? super Long, ? extends Long> remappingFunction) {
        if (key == EMPTY || key == TOMBSTONE) return defaultReturnValue();
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State s = seg.state;
            int idx = findKey(s, key);
            if (idx < 0) return defaultReturnValue();
            Long nv = remappingFunction.apply(key, s.vals[idx]);
            if (nv == null) {
                writeSlot(s, idx, TOMBSTONE, s.vals[idx]);
                seg.size--; seg.tombstones++;
                shrinkAfterRemoveLocked(seg);
                groupRemove(key);
                return defaultReturnValue();
            }
            writeSlot(s, idx, s.keys[idx], nv);
            return nv;
        }
    }

    @Override public long merge(long key, long value, BiFunction<? super Long, ? super Long, ? extends Long> remappingFunction) {
        if (key == EMPTY || key == TOMBSTONE) throw new IllegalArgumentException("reserved key");
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State s = seg.state;
            int idx = findKey(s, key);
            long nv;
            if (idx >= 0) {
                Long res = remappingFunction.apply(s.vals[idx], value);
                if (res == null) {
                    writeSlot(s, idx, TOMBSTONE, s.vals[idx]);
                    seg.size--; seg.tombstones++;
                    shrinkAfterRemoveLocked(seg);
                    groupRemove(key);
                    return defaultReturnValue();
                }
                nv = res;
                writeSlot(s, idx, s.keys[idx], nv);
            } else {
                nv = value;
                if (needsResize(seg, s)) s = resizeLocked(seg);
                putLocked(seg, s, key, nv, false);
            }
            return nv;
        }
    }

    @Override public void putAll(Map<? extends Long, ? extends Long> m) {
        for (Map.Entry<? extends Long, ? extends Long> e : m.entrySet())
            put(e.getKey().longValue(), e.getValue().longValue());
    }

    @Override public void clear() {
        for (Segment seg : segments) {
            synchronized (seg) {
                // A terrain-generation burst can grow the scheduled-tick index by
                // orders of magnitude. Keeping those empty arrays forever makes
                // every later primitive snapshot pay the historical high-water
                // capacity rather than the live entry count.
                State old = seg.state;
                State empty = new State(seg.minimumCapacity);
                // Readers are lock-free and may already hold the old State.  Blank
                // its keys before publishing the replacement so a get which began
                // before clear cannot return an old value after clear completes.
                for (int i = 0; i <= old.mask; i++) {
                    long key = old.keys[i];
                    writeSlot(old, i, EMPTY, old.vals[i]);
                    if (key != EMPTY && key != TOMBSTONE) groupRemove(key);
                }
                seg.state = empty;
                seg.size = 0;
                seg.tombstones = 0;
            }
        }
    }

    @Override public int size() {
        long total = 0;
        for (Segment seg : segments) total += seg.size;
        return (int) Math.max(0L, Math.min(total, Integer.MAX_VALUE));
    }

    @Override public boolean isEmpty() { return size() == 0; }

    /** Caller owns the segment; the odd/even stamp covers only bounded array
     * writes, never a user callback or another lock. */
    private static void writeSlot(State state, int index, long key, long value) {
        long version = state.versions[index];
        VERSIONS_VH.setVolatile(state.versions, index, version + 1);
        VALS_VH.setRelease(state.vals, index, value);
        KEYS_VH.setRelease(state.keys, index, key);
        VERSIONS_VH.setVolatile(state.versions, index, version + 2);
    }

    private boolean removeExistingKey(long key) {
        if (key == EMPTY || key == TOMBSTONE) return false;
        Segment seg = segmentFor(key);
        synchronized (seg) {
            State state = seg.state;
            if (findKey(state, key) < 0) return false;
            removeLocked(seg, state, key, false, 0);
            return true;
        }
    }

    private static int findKey(State s, long key) {
        long[] k = s.keys;
        int mask = s.mask;
        int idx = hash(key) & mask;
        while (true) {
            long kk = k[idx];
            if (kk == EMPTY) return -1;
            if (kk == key) return idx;
            idx = (idx + 1) & mask;
        }
    }

    private long putLocked(Segment seg, State s, long key, long value, boolean ifAbsent) {
        long[] k = s.keys;
        long[] v = s.vals;
        int mask = s.mask;
        int idx = hash(key) & mask;
        int firstTomb = -1;
        while (true) {
            long kk = k[idx];
            if (kk == EMPTY) {
                int writeIdx = firstTomb >= 0 ? firstTomb : idx;
                if (firstTomb >= 0) seg.tombstones--;
                // VALUE FIRST, key second — a reader that acquires the key must never
                // see the slot's previous tenant's value (virgin slot = 0, tombstone
                // reuse = a dead entry's value; removeLocked leaves values in place).
                // Same fix as Long2IntConcurrentHashMap.putLocked (flying-machine
                // chunk-boundary breaks, 2026-07-06).
                writeSlot(s, writeIdx, key, value);
                seg.size++;
                groupAdd(key);
                return defaultReturnValue();
            }
            if (kk == TOMBSTONE) {
                if (firstTomb < 0) firstTomb = idx;
            } else if (kk == key) {
                long prev = v[idx];
                if (!ifAbsent) {
                    writeSlot(s, idx, key, value);
                }
                return prev;
            }
            idx = (idx + 1) & mask;
        }
    }

    private long removeLocked(Segment seg, State s, long key, boolean checkValue, long expVal) {
        long[] k = s.keys;
        long[] v = s.vals;
        int mask = s.mask;
        int idx = hash(key) & mask;
        while (true) {
            long kk = k[idx];
            if (kk == EMPTY) return defaultReturnValue();
            if (kk == key) {
                long prev = v[idx];
                if (checkValue && prev != expVal) return defaultReturnValue();
                writeSlot(s, idx, TOMBSTONE, v[idx]);
                seg.size--; seg.tombstones++;
                shrinkAfterRemoveLocked(seg);
                groupRemove(key);
                return prev;
            }
            idx = (idx + 1) & mask;
        }
    }

    private boolean needsResize(Segment seg, State s) {
        return (seg.size + seg.tombstones + 1) > ((s.mask + 1) >> 1);
    }

    private State resizeLocked(Segment seg) {
        State old = seg.state;
        int newCap;
        if (seg.tombstones > seg.size) newCap = old.mask + 1;
        else                            newCap = (old.mask + 1) << 1;
        return rehashLocked(seg, newCap);
    }

    /**
     * Recover from a transient high-water population without resize thrash.
     * Growth happens at 50% occupancy; shrink waits until occupancy reaches
     * 12.5% and rebuilds to roughly 25%, leaving a full 2x insertion margin.
     * The caller already owns the segment monitor used by every writer.
     */
    private static void shrinkAfterRemoveLocked(Segment seg) {
        State old = seg.state;
        int oldCap = old.mask + 1;
        if (oldCap <= seg.minimumCapacity || seg.size > (oldCap >>> 3)) return;

        long wanted = Math.max((long) seg.minimumCapacity,
                Math.max(16L, (long) Math.max(1, seg.size) << 2));
        int newCap = seg.minimumCapacity;
        while ((long) newCap < wanted && newCap < (1 << 30)) {
            newCap <<= 1;
        }
        if (newCap >= oldCap) return;
        rehashLocked(seg, newCap);
    }

    private static State rehashLocked(Segment seg, int newCap) {
        State old = seg.state;
        State ns = new State(newCap);
        long[] ok = old.keys; long[] ov = old.vals;
        long[] nk = ns.keys;  long[] nv = ns.vals;
        int nmask = ns.mask;
        for (int i = 0; i <= old.mask; i++) {
            long kk = ok[i];
            if (kk == EMPTY || kk == TOMBSTONE) continue;
            int idx = hash(kk) & nmask;
            while (nk[idx] != EMPTY) idx = (idx + 1) & nmask;
            nk[idx] = kk;
            nv[idx] = ov[i];
        }
        seg.state = ns;
        seg.tombstones = 0;
        return ns;
    }

    @Override public LongSet keySet() {
        return new it.unimi.dsi.fastutil.longs.AbstractLongSet() {
            @Override public int size() { return Long2LongConcurrentHashMap.this.size(); }
            @Override public boolean contains(long key) { return containsKey(key); }
            @Override public boolean remove(long key) { return removeExistingKey(key); }
            @Override public void clear() { Long2LongConcurrentHashMap.this.clear(); }
            @Override public it.unimi.dsi.fastutil.longs.LongIterator iterator() {
                ObjectIterator<Entry> entries = long2LongEntrySet().iterator();
                return new it.unimi.dsi.fastutil.longs.LongIterator() {
                    @Override public boolean hasNext() { return entries.hasNext(); }
                    @Override public long nextLong() { return entries.next().getLongKey(); }
                    @Override public void remove() { entries.remove(); }
                };
            }
        };
    }

    @Override public LongCollection values() {
        return new it.unimi.dsi.fastutil.longs.AbstractLongCollection() {
            @Override public int size() { return Long2LongConcurrentHashMap.this.size(); }
            @Override public boolean contains(long value) { return containsValue(value); }
            @Override public void clear() { Long2LongConcurrentHashMap.this.clear(); }
            @Override public it.unimi.dsi.fastutil.longs.LongIterator iterator() {
                ObjectIterator<Entry> entries = long2LongEntrySet().iterator();
                return new it.unimi.dsi.fastutil.longs.LongIterator() {
                    @Override public boolean hasNext() { return entries.hasNext(); }
                    @Override public long nextLong() { return entries.next().getLongValue(); }
                    @Override public void remove() { entries.remove(); }
                };
            }
        };
    }


    /**
     * Copies a segment-consistent entry snapshot into reusable primitive
     * buffers. Both outputs are cleared first and have matching indexes on
     * return. The table scan is complete before this method returns, so callers
     * may safely mutate this map while consuming the copied pairs.
     *
     * <p>This is the allocation-free counterpart to {@link #long2LongEntrySet()}
     * for hot paths which already own scratch buffers. Each segment is held
     * stable while its pairs are copied. Writes to other segments may still be
     * observed or missed, so the result is not a globally atomic snapshot, but
     * every key appears at most once and every key/value pair comes from one
     * stable segment state.</p>
     *
     * @param keysOut destination for primitive keys
     * @param valuesOut destination for the corresponding primitive values
     * @throws IllegalArgumentException if both arguments are the same list
     */
    public void copyPrimitiveEntries(LongArrayList keysOut, LongArrayList valuesOut) {
        if (keysOut == valuesOut) {
            throw new IllegalArgumentException("key and value outputs must be distinct");
        }
        keysOut.clear();
        valuesOut.clear();
        int expected = size();
        keysOut.ensureCapacity(expected);
        valuesOut.ensureCapacity(expected);
        for (Segment seg : segments) {
            synchronized (seg) {
                State s = seg.state;
                long[] keys = s.keys;
                long[] values = s.vals;
                for (int i = 0; i <= s.mask; i++) {
                    long key = keys[i];
                    if (key == EMPTY || key == TOMBSTONE) continue;
                    keysOut.add(key);
                    valuesOut.add(values[i]);
                }
            }
        }
    }

    /**
     * Snapshot entry whose {@code setValue} writes through, as a live fastutil
     * entry does: vanilla {@code LevelTicks.sortContainersToTick} re-indexes a
     * container with {@code entry.setValue(head)} while iterating this set.
     * The write is conditional on the snapshot value: a mapping another thread
     * changed or removed since the snapshot is left as that thread wrote it.
     */
    private final class WriteThroughEntry extends AbstractLong2LongMap.BasicEntry {
        WriteThroughEntry(long key, long value) {
            super(key, value);
        }

        @Override public long setValue(long v) {
            long old = value;
            if (replace(key, old, v)) value = v;
            return old;
        }
    }

    private Entry snapshotEntry(State state, int index) {
        for (;;) {
            long version = (long) VERSIONS_VH.getVolatile(state.versions, index);
            if ((version & 1L) != 0) { Thread.onSpinWait(); continue; }
            long key = (long) KEYS_VH.getAcquire(state.keys, index);
            long value = (long) VALS_VH.getAcquire(state.vals, index);
            VarHandle.acquireFence();
            if ((long) VERSIONS_VH.getVolatile(state.versions, index) != version) continue;
            return key == EMPTY || key == TOMBSTONE ? null : new WriteThroughEntry(key, value);
        }
    }

    @Override public ObjectSet<Long2LongMap.Entry> long2LongEntrySet() {
        List<Long2LongMap.Entry> snap = new ArrayList<>(size());
        for (Segment seg : segments) {
            State s = seg.state;
            for (int i = 0; i <= s.mask; i++) {
                Entry entry = snapshotEntry(s, i);
                if (entry != null) snap.add(entry);
            }
        }
        return new ObjectSet<>() {
            @Override public int size() { return snap.size(); }
            @Override public boolean isEmpty() { return snap.isEmpty(); }
            @Override public boolean contains(Object o) {
                if (!(o instanceof Long2LongMap.Entry en)) return false;
                long key = en.getLongKey();
                if (key == EMPTY || key == TOMBSTONE) return false;
                Segment seg = segmentFor(key);
                synchronized (seg) {
                    State s = seg.state;
                    int idx = findKey(s, key);
                    return idx >= 0 && s.vals[idx] == en.getLongValue();
                }
            }
            @Override public ObjectIterator<Long2LongMap.Entry> iterator() {
                Iterator<Long2LongMap.Entry> it = snap.iterator();
                return new ObjectIterator<>() {
                    private Long2LongMap.Entry last;
                    @Override public boolean hasNext() { return it.hasNext(); }
                    @Override public Long2LongMap.Entry next() { last = it.next(); return last; }
                    @Override public void remove() {
                        if (last == null) throw new IllegalStateException();
                        Long2LongConcurrentHashMap.this.remove(last.getLongKey(), last.getLongValue());
                        last = null;
                    }
                };
            }
            @Override public boolean add(Long2LongMap.Entry e) { throw new UnsupportedOperationException(); }
            @Override public boolean remove(Object o) {
                if (!(o instanceof Long2LongMap.Entry en)) return false;
                return Long2LongConcurrentHashMap.this.remove(en.getLongKey(), en.getLongValue());
            }
            @Override public void clear() { Long2LongConcurrentHashMap.this.clear(); }
            @Override public Object[] toArray() { return snap.toArray(); }
            @Override public <T> T[] toArray(T[] a) { return snap.toArray(a); }
            @Override public boolean containsAll(java.util.Collection<?> c) { for (Object o : c) if (!contains(o)) return false; return true; }
            @Override public boolean addAll(java.util.Collection<? extends Long2LongMap.Entry> c) { boolean m = false; for (Long2LongMap.Entry e : c) m |= add(e); return m; }
            @Override public boolean removeAll(java.util.Collection<?> c) { boolean m = false; for (Object o : c) m |= remove(o); return m; }
            @Override public boolean retainAll(java.util.Collection<?> c) { throw new UnsupportedOperationException(); }
        };
    }
}
