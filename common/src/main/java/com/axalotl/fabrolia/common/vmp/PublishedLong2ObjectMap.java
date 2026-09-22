package com.axalotl.fabrolia.common.vmp;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Primitive-long map for an externally serialized writer and concurrent readers.
 *
 * <p>Every bucket has immutable topology and is published with a volatile array
 * store. Existing values are replaced with one volatile write, avoiding a node
 * allocation on the player-movement path. Readers capture one table and one
 * bucket head, then traverse final links: no lock, key boxing, allocation,
 * retry, or exposure to an in-progress structural mutation. Rehashing builds a
 * detached table and publishes it with one volatile root write, so a reader
 * observes either complete table generation.</p>
 *
 * <p>Mutation methods are deliberately not internally locked. Callers must
 * serialize every {@link #put(long, Object)} and {@link #remove(long)} operation.
 * Different writer threads are supported when an external lock establishes that
 * ordering.</p>
 */
public final class PublishedLong2ObjectMap<V> {

    private static final int MIN_CAPACITY = 64;
    private static final int MAX_AVERAGE_BUCKET_SIZE = 2;

    private volatile Table<V> table = new Table<>(MIN_CAPACITY);
    private int size;

    /** Lock-free, allocation-free read. */
    public V get(long key) {
        Table<V> current = table;
        Node<V> node = current.buckets.get(bucket(key, current.mask));
        while (node != null) {
            if (node.key == key) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    /**
     * Publish a non-null value. All calls to this method and {@link #remove(long)}
     * must be externally serialized.
     */
    public void put(long key, V value) {
        Objects.requireNonNull(value, "value");

        Table<V> current = table;
        int index = bucket(key, current.mask);
        Node<V> head = current.buckets.get(index);
        for (Node<V> node = head; node != null; node = node.next) {
            if (node.key == key) {
                node.value = value;
                return;
            }
        }

        if (size + 1 > current.buckets.length() * MAX_AVERAGE_BUCKET_SIZE) {
            current = rehash(current.buckets.length() << 1);
            index = bucket(key, current.mask);
            head = current.buckets.get(index);
        }
        current.buckets.set(index, new Node<>(key, value, head));
        size++;
    }

    /**
     * Remove a key if present. All calls to this method and {@link #put(long, Object)}
     * must be externally serialized.
     */
    public void remove(long key) {
        Table<V> current = table;
        int index = bucket(key, current.mask);
        Node<V> head = current.buckets.get(index);
        if (!contains(head, key)) {
            return;
        }

        current.buckets.set(index, without(head, key));
        size--;
        if (current.buckets.length() > MIN_CAPACITY
                && size < current.buckets.length() / 2) {
            rehash(current.buckets.length() >> 1);
        }
    }

    int size() {
        return size;
    }

    int capacity() {
        return table.buckets.length();
    }

    private Table<V> rehash(int requestedCapacity) {
        int capacity = Math.max(MIN_CAPACITY, requestedCapacity);
        Table<V> replacement = new Table<>(capacity);
        Table<V> previous = table;
        for (int index = 0; index < previous.buckets.length(); index++) {
            for (Node<V> node = previous.buckets.get(index);
                 node != null; node = node.next) {
                int replacementIndex = bucket(node.key, replacement.mask);
                Node<V> replacementHead = replacement.buckets.get(replacementIndex);
                replacement.buckets.set(replacementIndex,
                        new Node<>(node.key, node.value, replacementHead));
            }
        }
        table = replacement;
        return replacement;
    }

    private static boolean contains(Node<?> head, long key) {
        for (Node<?> node = head; node != null; node = node.next) {
            if (node.key == key) {
                return true;
            }
        }
        return false;
    }


    private static <V> Node<V> without(Node<V> head, long key) {
        Node<V> replacement = null;
        for (Node<V> node = head; node != null; node = node.next) {
            if (node.key != key) {
                replacement = new Node<>(node.key, node.value, replacement);
            }
        }
        return replacement;
    }

    private static int bucket(long key, int mask) {
        long mixed = key;
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        mixed ^= mixed >>> 31;
        return (int) (mixed ^ (mixed >>> 32)) & mask;
    }

    private static final class Table<V> {
        final AtomicReferenceArray<Node<V>> buckets;
        final int mask;

        Table(int capacity) {
            if (Integer.bitCount(capacity) != 1) {
                throw new IllegalArgumentException("capacity must be a power of two");
            }
            this.buckets = new AtomicReferenceArray<>(capacity);
            this.mask = capacity - 1;
        }
    }

    private static final class Node<V> {
        final long key;
        volatile V value;
        final Node<V> next;

        Node(long key, V value, Node<V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}
