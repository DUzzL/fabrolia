package com.axalotl.fabrolia.common.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Immutable, insertion-order-preserving snapshot of a grouped membership map.
 *
 * <p>The live map and every live value collection are copied while holding the
 * caller's exact mutation monitor. No predicate, consumer or stream operation
 * runs under that monitor: callers receive only immutable lists and can safely
 * execute arbitrary query callbacks after the writer lock has been released.
 * This is the shape required by {@code PoiSection}: its vanilla stream is lazy,
 * so merely synchronising the method that creates the stream does not protect
 * the later terminal traversal.
 */
public final class GroupedSnapshot<K, V> {

    public record Group<K, V>(K key, List<V> values) {
        public Group {
            Objects.requireNonNull(values, "values");
            values = List.copyOf(values);
        }
    }

    private final List<Group<K, V>> groups;

    private GroupedSnapshot(List<Group<K, V>> groups) {
        this.groups = List.copyOf(groups);
    }

    public static <K, V> GroupedSnapshot<K, V> capture(
            Object monitor, Map<K, ? extends Collection<V>> source) {
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(source, "source");
        synchronized (monitor) {
            ArrayList<Group<K, V>> groups = new ArrayList<>(source.size());
            for (Map.Entry<K, ? extends Collection<V>> entry : source.entrySet()) {
                groups.add(new Group<>(entry.getKey(), new ArrayList<>(entry.getValue())));
            }
            return new GroupedSnapshot<>(groups);
        }
    }

    public List<Group<K, V>> groups() {
        return this.groups;
    }

    public Stream<V> stream(Predicate<? super K> keyPredicate,
                            Predicate<? super V> valuePredicate) {
        Objects.requireNonNull(keyPredicate, "keyPredicate");
        Objects.requireNonNull(valuePredicate, "valuePredicate");
        return this.groups.stream()
                .filter(group -> keyPredicate.test(group.key()))
                .flatMap(group -> group.values().stream())
                .filter(valuePredicate);
    }
}
