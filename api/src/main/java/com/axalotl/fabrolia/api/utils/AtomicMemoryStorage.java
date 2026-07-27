package com.axalotl.fabrolia.api.utils;

import net.minecraft.core.Registry;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AtomicMemoryStorage implements Map<MemoryModuleType<?>, MemorySlot<?>> {

    private static final int[] EMPTY_IDS = new int[0];

    public interface MemoryTypeIdAccess {
        int fabrolia$memoryId();

        void fabrolia$setMemoryId(int id);
    }

    private final Registry<MemoryModuleType<?>> registry;
    private final AtomicReferenceArray<MemorySlot<?>> slots;
    private volatile int[] ids;

    public AtomicMemoryStorage(Registry<MemoryModuleType<?>> registry) {
        this.registry = registry;
        this.slots = new AtomicReferenceArray<>(Math.max(1, registry.size()));
        this.ids = EMPTY_IDS;
    }

    public AtomicMemoryStorage(Registry<MemoryModuleType<?>> registry, Map<? extends MemoryModuleType<?>, ? extends MemorySlot<?>> initial) {
        this(registry);
        int[] collected = new int[initial.size()];
        int n = 0;
        for (Map.Entry<? extends MemoryModuleType<?>, ? extends MemorySlot<?>> e : initial.entrySet()) {
            MemorySlot<?> value = e.getValue();
            if (value == null) continue;
            int id = rawId(e.getKey());
            if (id < 0 || id >= slots.length()) {
                throw new IllegalArgumentException("MemoryModuleType id out of storage capacity: " + id);
            }
            if (slots.getAndSet(id, value) == null) {
                collected[n++] = id;
            }
        }
        int[] arr = Arrays.copyOf(collected, n);
        Arrays.sort(arr);
        this.ids = arr;
    }

    private int rawId(MemoryModuleType<?> t) {
        MemoryTypeIdAccess access = (MemoryTypeIdAccess) t;
        int id = access.fabrolia$memoryId();
        if (id == -1) {
            id = registry.getId(t);
            if (id >= 0) access.fabrolia$setMemoryId(id);
        }
        return id;
    }

    private int idOf(@Nullable Object key) {
        if (!(key instanceof MemoryModuleType<?> t)) return -1;
        int id = rawId(t);
        return (id < 0 || id >= slots.length()) ? -1 : id;
    }

    @Override public int size() { return ids.length; }
    @Override public boolean isEmpty() { return ids.length == 0; }

    @Override
    public boolean containsKey(Object key) {
        int id = idOf(key);
        return id >= 0 && slots.get(id) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        for (int id : ids) {
            if (Objects.equals(slots.get(id), value)) return true;
        }
        return false;
    }

    @Override
    public MemorySlot<?> get(Object key) {
        int id = idOf(key);
        return id < 0 ? null : slots.get(id);
    }

    @Override
    public MemorySlot<?> put(MemoryModuleType<?> key, MemorySlot<?> value) {
        int id = rawId(key);
        if (id < 0 || id >= slots.length()) {
            throw new IllegalArgumentException("MemoryModuleType id out of storage capacity: " + id);
        }
        MemorySlot<?> prev = slots.getAndSet(id, value);
        if ((prev == null) != (value == null)) reconcile(id);
        return prev;
    }

    @Override
    public MemorySlot<?> remove(Object key) {
        int id = idOf(key);
        if (id < 0) return null;
        MemorySlot<?> prev = slots.getAndSet(id, null);
        if (prev != null) reconcile(id);
        return prev;
    }

    @Override
    public void putAll(@NotNull Map<? extends MemoryModuleType<?>, ? extends MemorySlot<?>> m) {
        for (Map.Entry<? extends MemoryModuleType<?>, ? extends MemorySlot<?>> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public void clear() {
        for (int i = 0, n = slots.length(); i < n; i++) {
            if (slots.getAndSet(i, null) != null) reconcile(i);
        }
    }

    private synchronized void reconcile(int id) {
        boolean occupied = slots.get(id) != null;
        int[] cur = ids;
        int pos = Arrays.binarySearch(cur, id);
        boolean present = pos >= 0;
        if (occupied == present) return;
        int[] next;
        if (occupied) {
            int ins = -pos - 1;
            next = new int[cur.length + 1];
            System.arraycopy(cur, 0, next, 0, ins);
            next[ins] = id;
            System.arraycopy(cur, ins, next, ins + 1, cur.length - ins);
        } else {
            next = new int[cur.length - 1];
            System.arraycopy(cur, 0, next, 0, pos);
            System.arraycopy(cur, pos + 1, next, pos, cur.length - pos - 1);
        }
        ids = next;
    }

    @Override
    public void forEach(BiConsumer<? super MemoryModuleType<?>, ? super MemorySlot<?>> action) {
        for (int id : ids) {
            MemorySlot<?> slot = slots.get(id);
            if (slot != null) {
                MemoryModuleType<?> type = registry.byId(id);
                if (type != null) action.accept(type, slot);
            }
        }
    }

    @Override
    @NotNull
    public Set<MemoryModuleType<?>> keySet() {
        int[] cur = ids;
        Set<MemoryModuleType<?>> set = new HashSet<>(Math.max(16, cur.length));
        for (int id : cur) {
            if (slots.get(id) != null) {
                MemoryModuleType<?> type = registry.byId(id);
                if (type != null) set.add(type);
            }
        }
        return set;
    }

    @Override
    @NotNull
    public Collection<MemorySlot<?>> values() {
        return new ValuesView();
    }

    @Override
    @NotNull
    public Set<Entry<MemoryModuleType<?>, MemorySlot<?>>> entrySet() {
        int[] cur = ids;
        Set<Entry<MemoryModuleType<?>, MemorySlot<?>>> set = new HashSet<>(Math.max(16, cur.length));
        for (int id : cur) {
            MemorySlot<?> slot = slots.get(id);
            if (slot != null) {
                MemoryModuleType<?> type = registry.byId(id);
                if (type != null) set.add(new AbstractMap.SimpleImmutableEntry<>(type, slot));
            }
        }
        return set;
    }

    private final class ValuesView extends AbstractCollection<MemorySlot<?>> {
        @Override public int size() { return ids.length; }
        @Override public boolean isEmpty() { return ids.length == 0; }

        @Override
        @NotNull
        public Iterator<MemorySlot<?>> iterator() {
            return new Iterator<>() {
                final int[] cur = ids;
                int cursor = 0;
                @Nullable MemorySlot<?> peeked;

                @Override
                public boolean hasNext() {
                    while (peeked == null && cursor < cur.length) {
                        peeked = slots.get(cur[cursor++]);
                    }
                    return peeked != null;
                }

                @Override
                public MemorySlot<?> next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    MemorySlot<?> result = peeked;
                    peeked = null;
                    return result;
                }
            };
        }

        @Override
        public void forEach(Consumer<? super MemorySlot<?>> action) {
            for (int id : ids) {
                MemorySlot<?> slot = slots.get(id);
                if (slot != null) action.accept(slot);
            }
        }
    }
}
