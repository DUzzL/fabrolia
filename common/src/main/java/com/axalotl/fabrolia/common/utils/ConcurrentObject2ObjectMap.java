package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.objects.AbstractObject2ObjectMap;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Object2ObjectMap} over a {@link ConcurrentHashMap}, for vanilla fields
 * typed as the fastutil interface that several threads read and write by
 * distinct keys (the scoreboard's team-by-member index). Lock-free reads;
 * iteration is weakly consistent. Null keys and values are not stored: a null
 * key reads as absent, and the default return value stands for "no mapping".
 */
public final class ConcurrentObject2ObjectMap<K, V> extends AbstractObject2ObjectMap<K, V> {
    private final ConcurrentHashMap<K, V> map = new ConcurrentHashMap<>();

    @Override
    public V get(Object key) {
        if (key == null) return defRetValue;
        V value = map.get(key);
        return value == null ? defRetValue : value;
    }

    @Override
    public V put(K key, V value) {
        V previous = map.put(key, value);
        return previous == null ? defRetValue : previous;
    }

    @Override
    public V remove(Object key) {
        if (key == null) return defRetValue;
        V previous = map.remove(key);
        return previous == null ? defRetValue : previous;
    }

    @Override
    public boolean containsKey(Object key) {
        return key != null && map.containsKey(key);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public ObjectSet<Object2ObjectMap.Entry<K, V>> object2ObjectEntrySet() {
        return new AbstractObjectSet<>() {
            @Override
            public ObjectIterator<Object2ObjectMap.Entry<K, V>> iterator() {
                Iterator<Map.Entry<K, V>> entries = map.entrySet().iterator();
                return new ObjectIterator<>() {
                    private K last;

                    @Override
                    public boolean hasNext() {
                        return entries.hasNext();
                    }

                    @Override
                    public Object2ObjectMap.Entry<K, V> next() {
                        Map.Entry<K, V> entry = entries.next();
                        last = entry.getKey();
                        return new BasicEntry<>(entry.getKey(), entry.getValue());
                    }

                    @Override
                    public void remove() {
                        if (last == null) throw new IllegalStateException();
                        map.remove(last);
                        last = null;
                    }
                };
            }

            @Override
            public int size() {
                return map.size();
            }
        };
    }
}
