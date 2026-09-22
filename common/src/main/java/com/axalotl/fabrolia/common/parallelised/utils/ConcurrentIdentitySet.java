package com.axalotl.fabrolia.common.parallelised.utils;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weakly-consistent concurrent set whose keys use stable object identity.
 *
 * <p>Minecraft {@code Entity.equals/hashCode} are based on the mutable numeric
 * entity id. Fabrolia's staged cross-dimension publication assigns the
 * destination id after {@code restoreFrom} has already restored the projectile
 * owner and registered the new pearl. A normal HashSet/ConcurrentHashMap key is
 * then stranded in the bucket for the old id; source and destination also
 * temporarily share an id despite being distinct lifecycle objects. Identity
 * is the registry contract: remove exactly the object whose onRemoval fired.
 */
public final class ConcurrentIdentitySet<E> extends AbstractSet<E> {

    private final ConcurrentHashMap<IdentityKey<E>, Boolean> entries =
            new ConcurrentHashMap<>();

    @Override
    public boolean add(E element) {
        if (element == null) throw new NullPointerException("element");
        return entries.putIfAbsent(new IdentityKey<>(element), Boolean.TRUE) == null;
    }

    @Override
    public boolean contains(Object candidate) {
        return candidate != null && entries.containsKey(IdentityKey.lookup(candidate));
    }

    @Override
    public boolean remove(Object candidate) {
        return candidate != null && entries.remove(IdentityKey.lookup(candidate)) != null;
    }

    @Override
    public Iterator<E> iterator() {
        Iterator<IdentityKey<E>> keys = entries.keySet().iterator();
        return new Iterator<>() {
            private E last;

            @Override
            public boolean hasNext() {
                return keys.hasNext();
            }

            @Override
            public E next() {
                if (!keys.hasNext()) throw new NoSuchElementException();
                last = keys.next().referent;
                return last;
            }

            @Override
            public void remove() {
                E value = last;
                if (value == null) throw new IllegalStateException();
                ConcurrentIdentitySet.this.remove(value);
                last = null;
            }
        };
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public void clear() {
        entries.clear();
    }

    private static final class IdentityKey<E> {
        private final E referent;
        private final int hash;

        private IdentityKey(E referent) {
            this.referent = referent;
            this.hash = System.identityHashCode(referent);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static IdentityKey lookup(Object referent) {
            return new IdentityKey(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey<?> key && referent == key.referent;
        }
    }
}
