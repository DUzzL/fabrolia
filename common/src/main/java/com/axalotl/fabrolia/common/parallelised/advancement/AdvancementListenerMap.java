package com.axalotl.fabrolia.common.parallelised.advancement;

import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.server.PlayerAdvancements;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Native mutable listener map with a before-publication identity witness.
 * AbstractMap's default mutators route through put or the wrapped entry's setValue;
 * HashMap's overriding compute/replace methods would bypass that witness.
 * Native ownership still governs access: this is not a concurrent map. Publication
 * additionally holds the PlayerAdvancements monitor used by admission snapshots.
 */
public final class AdvancementListenerMap<V extends CriterionTriggerInstance>
        extends AbstractMap<PlayerAdvancements.TriggerInstanceKey, V> {
    private final HashMap<PlayerAdvancements.TriggerInstanceKey, V> listeners = new HashMap<>();
    private final Object monitor;
    private final Consumer<? super V> beforeInsertion;
    private final Set<Entry<PlayerAdvancements.TriggerInstanceKey, V>> entries = new AbstractSet<>() {
        @Override
        public int size() {
            return listeners.size();
        }

        @Override
        public void clear() {
            listeners.clear();
        }

        @Override
        public boolean contains(Object entry) {
            return listeners.entrySet().contains(entry);
        }

        @Override
        public boolean remove(Object entry) {
            return listeners.entrySet().remove(entry);
        }

        @Override
        public Iterator<Entry<PlayerAdvancements.TriggerInstanceKey, V>> iterator() {
            var iterator = listeners.entrySet().iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Entry<PlayerAdvancements.TriggerInstanceKey, V> next() {
                    var entry = iterator.next();
                    return new Entry<>() {
                        @Override
                        public PlayerAdvancements.TriggerInstanceKey getKey() {
                            return entry.getKey();
                        }

                        @Override
                        public V getValue() {
                            return entry.getValue();
                        }

                        @Override
                        public V setValue(V value) {
                            synchronized (monitor) {
                                beforeInsertion.accept(value);
                                return entry.setValue(value);
                            }
                        }

                        @Override
                        public boolean equals(Object other) {
                            return entry.equals(other);
                        }

                        @Override
                        public int hashCode() {
                            return entry.hashCode();
                        }

                        @Override
                        public String toString() {
                            return entry.toString();
                        }
                    };
                }

                @Override
                public void remove() {
                    iterator.remove();
                }
            };
        }
    };

    public AdvancementListenerMap(Object monitor, Consumer<? super V> beforeInsertion) {
        this.monitor = monitor;
        this.beforeInsertion = beforeInsertion;
    }

    @Override
    public V put(PlayerAdvancements.TriggerInstanceKey key, V value) {
        synchronized (monitor) {
            beforeInsertion.accept(value);
            return listeners.put(key, value);
        }
    }

    @Override
    public V get(Object key) {
        return listeners.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return listeners.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return listeners.containsValue(value);
    }

    @Override
    public int size() {
        return listeners.size();
    }

    @Override
    public V remove(Object key) {
        return listeners.remove(key);
    }

    @Override
    public void clear() {
        listeners.clear();
    }

    @Override
    public Set<PlayerAdvancements.TriggerInstanceKey> keySet() {
        return listeners.keySet();
    }

    @Override
    public Collection<V> values() {
        return listeners.values();
    }

    @Override
    public void forEach(BiConsumer<? super PlayerAdvancements.TriggerInstanceKey, ? super V> action) {
        listeners.forEach(action);
    }

    @Override
    public Set<Entry<PlayerAdvancements.TriggerInstanceKey, V>> entrySet() {
        return entries;
    }
}
