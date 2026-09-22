package com.axalotl.fabrolia.api.fastutil;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.objects.AbstractObject2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.ObjectIntBiConsumer;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.world.entity.MobCategory;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.function.ToIntFunction;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BiFunction;

public final class AtomicMobCategoryCounts extends Object2IntOpenHashMap<MobCategory> {
    // Fixed-domain view: every category is present; removal resets its count to zero.
    private static final MobCategory[] CATEGORIES = MobCategory.values();

    private final AtomicIntegerArray counts;

    public AtomicMobCategoryCounts(AtomicIntegerArray counts) {
        super(0);
        this.counts = counts;
    }

    @Override
    public int getInt(Object key) {
        return key instanceof MobCategory cat ? this.counts.get(cat.ordinal()) : this.defaultReturnValue();
    }

    @Override
    public int getOrDefault(Object key, int defaultValue) {
        return key instanceof MobCategory cat ? this.counts.get(cat.ordinal()) : defaultValue;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof MobCategory;
    }

    @Override
    public boolean containsValue(int value) {
        for (int i = 0, n = CATEGORIES.length; i < n; i++) {
            if (this.counts.get(i) == value) return true;
        }
        return false;
    }

    @Override
    public int size() {
        return CATEGORIES.length;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public int put(MobCategory k, int v) {
        return this.counts.getAndSet(k.ordinal(), v);
    }

    @Override
    public int addTo(MobCategory k, int incr) {
        return this.counts.getAndAdd(k.ordinal(), incr);
    }

    @Override
    public int computeInt(MobCategory key, BiFunction<? super MobCategory, ? super Integer, ? extends Integer> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        int index = key.ordinal();
        int previous;
        Integer updated;
        do {
            previous = counts.get(index);
            updated = remappingFunction.apply(key, previous);
        } while (!counts.compareAndSet(index, previous, updated == null ? 0 : updated));
        return updated == null ? defaultReturnValue() : updated;
    }

    @Override
    public int putIfAbsent(MobCategory key, int value) {
        return counts.get(key.ordinal());
    }

    @Override
    public boolean remove(Object key, int value) {
        return key instanceof MobCategory category && counts.compareAndSet(category.ordinal(), value, 0);
    }

    @Override
    public boolean replace(MobCategory key, int previous, int value) {
        return counts.compareAndSet(key.ordinal(), previous, value);
    }

    @Override
    public int replace(MobCategory key, int value) {
        return put(key, value);
    }

    @Override
    public int computeIfAbsent(MobCategory key, ToIntFunction<? super MobCategory> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        return counts.get(key.ordinal());
    }

    @Override
    public int computeIfAbsent(MobCategory key, Object2IntFunction<? super MobCategory> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        return counts.get(key.ordinal());
    }

    @Override
    public int computeIntIfPresent(MobCategory key,
            BiFunction<? super MobCategory, ? super Integer, ? extends Integer> remappingFunction) {
        return computeInt(key, remappingFunction);
    }

    @Override
    public int mergeInt(MobCategory key, int value, IntBinaryOperator remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        return counts.accumulateAndGet(key.ordinal(), value, remappingFunction);
    }

    @Override
    public int merge(MobCategory key, int value,
            BiFunction<? super Integer, ? super Integer, ? extends Integer> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        int index = key.ordinal();
        int previous;
        Integer updated;
        do {
            previous = counts.get(index);
            updated = remappingFunction.apply(previous, value);
        } while (!counts.compareAndSet(index, previous, updated == null ? 0 : updated));
        return updated == null ? defaultReturnValue() : updated;
    }

    @Override
    public void replaceAll(BiFunction<? super MobCategory, ? super Integer, ? extends Integer> function) {
        Objects.requireNonNull(function);
        for (MobCategory category : CATEGORIES) {
            int index = category.ordinal();
            int previous;
            int updated;
            do {
                previous = counts.get(index);
                updated = Objects.requireNonNull(function.apply(category, previous));
            } while (!counts.compareAndSet(index, previous, updated));
        }
    }

    @Override
    public void forEach(ObjectIntBiConsumer<? super MobCategory> action) {
        Objects.requireNonNull(action);
        for (MobCategory category : CATEGORIES) action.accept(category, counts.get(category.ordinal()));
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (MobCategory category : CATEGORIES) result += category.hashCode() ^ counts.get(category.ordinal());
        return result;
    }

    @Override
    public AtomicMobCategoryCounts clone() {
        AtomicIntegerArray copy = new AtomicIntegerArray(CATEGORIES.length);
        for (int i = 0; i < CATEGORIES.length; i++) copy.set(i, counts.get(i));
        AtomicMobCategoryCounts result = new AtomicMobCategoryCounts(copy);
        result.defaultReturnValue(defaultReturnValue());
        return result;
    }

    @Override
    public int removeInt(Object key) {
        return key instanceof MobCategory cat ? this.counts.getAndSet(cat.ordinal(), 0) : this.defaultReturnValue();
    }

    @Override
    public void clear() {
        for (int i = 0, n = CATEGORIES.length; i < n; i++) this.counts.set(i, 0);
    }

    @Override
    public Object2IntMap.FastEntrySet<MobCategory> object2IntEntrySet() {
        return new FastEntrySetView();
    }

    private final class FastEntrySetView extends AbstractObject2IntMap.BasicEntrySet<MobCategory>
            implements Object2IntMap.FastEntrySet<MobCategory> {
        FastEntrySetView() {
            super(AtomicMobCategoryCounts.this);
        }

        @Override
        public ObjectIterator<Object2IntMap.Entry<MobCategory>> iterator() {
            return new ObjectIterator<>() {
                int idx = 0;

                @Override public boolean hasNext() { return idx < CATEGORIES.length; }

                @Override public Object2IntMap.Entry<MobCategory> next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    MobCategory cat = CATEGORIES[idx];
                    int value = counts.get(idx);
                    idx++;
                    return new AbstractObject2IntMap.BasicEntry<>(cat, value);
                }
            };
        }

        @Override
        public ObjectIterator<Object2IntMap.Entry<MobCategory>> fastIterator() {
            return iterator();
        }

        @Override
        public int size() {
            return CATEGORIES.length;
        }
    }

    @Override
    public ObjectSet<MobCategory> keySet() {
        ObjectOpenHashSet<MobCategory> set = new ObjectOpenHashSet<>(CATEGORIES.length);
        for (MobCategory cat : CATEGORIES) set.add(cat);
        return set;
    }

    @Override
    public IntCollection values() {
        IntArrayList list = new IntArrayList(CATEGORIES.length);
        for (int i = 0, n = CATEGORIES.length; i < n; i++) list.add(this.counts.get(i));
        return list;
    }
}
