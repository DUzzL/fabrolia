package com.axalotl.fabrolia.api.fastutil;

import it.unimi.dsi.fastutil.SafeMath;
import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.longs.AbstractLong2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

public final class Long2ByteConcurrentHashMap extends AbstractLong2ByteMap {

    private final Long2IntConcurrentHashMap delegate;

    public Long2ByteConcurrentHashMap() { this(16); }
    public Long2ByteConcurrentHashMap(int expectedSize) {
        delegate = new Long2IntConcurrentHashMap(expectedSize);
    }
    public Long2ByteConcurrentHashMap(int initialCapacity, float loadFactor) { this(initialCapacity); }
    public Long2ByteConcurrentHashMap(int initialCapacity, float loadFactor, int concurrencyLevel) { this(initialCapacity); }
    public Long2ByteConcurrentHashMap(int initialCapacity, int concurrencyLevel) { this(initialCapacity); }

    @Override public byte get(long key) { return (byte) delegate.get(key); }
    @Override public byte put(long key, byte value) { return (byte) delegate.put(key, (int) value); }
    @Override public byte remove(long key) { return (byte) delegate.remove(key); }

    @Override public boolean containsKey(long key) { return delegate.containsKey(key); }
    @Override public boolean containsValue(byte value) { return delegate.containsValue((int) value); }
    @Override public byte getOrDefault(long key, byte defaultValue) { return (byte) delegate.getOrDefault(key, (int) defaultValue); }
    @Override public byte putIfAbsent(long key, byte value) { return (byte) delegate.putIfAbsent(key, (int) value); }
    @Override public boolean remove(long key, byte value) { return delegate.remove(key, (int) value); }
    @Override public boolean replace(long key, byte oldValue, byte newValue) { return delegate.replace(key, (int) oldValue, (int) newValue); }
    @Override public byte replace(long key, byte value) { return (byte) delegate.replace(key, (int) value); }

    @Override public byte compute(long key, BiFunction<? super Long, ? super Byte, ? extends Byte> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        int res = delegate.compute(key, (k, vInt) -> {
            Byte old = vInt == null ? null : Byte.valueOf(vInt.byteValue());
            Byte nv = remappingFunction.apply(k, old);
            return nv == null ? null : (int) (byte) nv;
        });
        return (byte) res;
    }

    @Override public byte computeIfAbsent(long key, java.util.function.LongToIntFunction mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        return (byte) delegate.computeIfAbsent(key, (java.util.function.LongFunction<Integer>)
                k -> (int) SafeMath.safeIntToByte(mappingFunction.applyAsInt(k)));
    }

    @Override public byte computeIfPresent(long key, BiFunction<? super Long, ? super Byte, ? extends Byte> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        return (byte) delegate.computeIfPresent(key, (k, old) -> {
            Byte value = remappingFunction.apply(k, old.byteValue());
            return value == null ? null : Integer.valueOf(value.intValue());
        });
    }

    @Override public byte merge(long key, byte value, java.util.function.BiFunction<? super Byte, ? super Byte, ? extends Byte> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        return (byte) delegate.merge(key, (int) value, (old, incoming) -> {
            Byte merged = remappingFunction.apply(old.byteValue(), incoming.byteValue());
            return merged == null ? null : Integer.valueOf(merged.intValue());
        });
    }

    @Override public void putAll(Map<? extends Long, ? extends Byte> m) {
        for (Map.Entry<? extends Long, ? extends Byte> e : m.entrySet())
            put(e.getKey().longValue(), e.getValue().byteValue());
    }
    @Override public void clear() { delegate.clear(); }
    @Override public int size() { return delegate.size(); }
    @Override public boolean isEmpty() { return delegate.isEmpty(); }
    @Override public void defaultReturnValue(byte rv) { delegate.defaultReturnValue((int) rv); }
    @Override public byte defaultReturnValue() { return (byte) delegate.defaultReturnValue(); }

    @Override public LongSet keySet() { return delegate.keySet(); }

    @Override public ByteCollection values() {
        return new it.unimi.dsi.fastutil.bytes.AbstractByteCollection() {
            @Override public int size() { return delegate.size(); }
            @Override public boolean contains(byte value) { return delegate.containsValue(value); }
            @Override public void clear() { delegate.clear(); }
            @Override public it.unimi.dsi.fastutil.bytes.ByteIterator iterator() {
                var entries = long2ByteEntrySet().iterator();
                return new it.unimi.dsi.fastutil.bytes.ByteIterator() {
                    @Override public boolean hasNext() { return entries.hasNext(); }
                    @Override public byte nextByte() { return entries.next().getByteValue(); }
                    @Override public void remove() { entries.remove(); }
                };
            }
        };
    }

    @Override public ObjectSet<Entry> long2ByteEntrySet() { return new EntrySetView(); }

    private final class EntrySetView extends it.unimi.dsi.fastutil.objects.AbstractObjectSet<Entry> {
        @Override public ObjectIterator<Entry> iterator() {
            ObjectIterator<Long2IntMap.Entry> it = delegate.long2IntEntrySet().iterator();
            return new ObjectIterator<>() {
                private Entry last;
                @Override public boolean hasNext() { return it.hasNext(); }
                @Override public Entry next() {
                    Long2IntMap.Entry e = it.next();
                    last = new BasicEntry(e.getLongKey(), (byte) e.getIntValue());
                    return last;
                }
                @Override public void remove() {
                    if (last == null) throw new IllegalStateException();
                    Long2ByteConcurrentHashMap.this.remove(last.getLongKey(), last.getByteValue());
                    last = null;
                }
            };
        }
        @Override public int size() { return delegate.size(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public void clear() { delegate.clear(); }
        @Override public boolean contains(Object o) {
            if (!(o instanceof Entry e)) return false;
            return containsKey(e.getLongKey()) && get(e.getLongKey()) == e.getByteValue();
        }
        @Override public boolean remove(Object o) {
            if (!(o instanceof Entry e)) return false;
            return Long2ByteConcurrentHashMap.this.remove(e.getLongKey(), e.getByteValue());
        }
        @Override public boolean add(Entry e) { put(e.getLongKey(), e.getByteValue()); return true; }
        @Override public Object[] toArray() { java.util.List<Entry> list = new java.util.ArrayList<>(size()); for (Entry e : this) list.add(e); return list.toArray(); }
        @Override public <T> T[] toArray(T[] a) { java.util.List<Entry> list = new java.util.ArrayList<>(size()); for (Entry e : this) list.add(e); return list.toArray(a); }
        @Override public boolean containsAll(java.util.Collection<?> c) { for (Object o : c) if (!contains(o)) return false; return true; }
        @Override public boolean addAll(java.util.Collection<? extends Entry> c) { boolean m = false; for (Entry e : c) m |= add(e); return m; }
        @Override public boolean removeAll(java.util.Collection<?> c) { boolean m = false; for (Object o : c) m |= remove(o); return m; }
        @Override public boolean retainAll(java.util.Collection<?> c) { boolean m = false; for (Entry e : this) if (!c.contains(e)) m |= remove(e); return m; }
    }

    private final class BasicEntry extends AbstractLong2ByteMap.BasicEntry {
        BasicEntry(long key, byte value) { super(key, value); }
        @Override public byte setValue(byte next) {
            byte old = value;
            if (replace(key, old, next)) value = next;
            return old;
        }
    }

    @Override public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Map<?, ?> other)) return false;
        if (other.size() != size()) return false;
        for (Entry e : long2ByteEntrySet()) {
            Object ov = ((Map<?, ?>) other).get(e.getLongKey());
            if (!(ov instanceof Byte) || ((Byte) ov) != e.getByteValue()) return false;
        }
        return true;
    }
    @Override public int hashCode() {
        int h = 0;
        for (Entry e : long2ByteEntrySet()) h += Long.hashCode(e.getLongKey()) ^ Byte.hashCode(e.getByteValue());
        return h;
    }
    @Override public String toString() { return "Long2ByteConcurrentHashMap[size=" + size() + "]"; }
}
