package com.axalotl.fabrolia.api.fastutil;

import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.Map;
import java.util.concurrent.locks.StampedLock;

/**
 * A delegating {@link Long2ObjectMap} that serialises every operation on a
 * per-instance {@link StampedLock}, with optimistic reads on the hot lookup
 * path. Built for {@code SectionStorage.storage} (task #38), where the map that
 * is actually installed at runtime is decided by ANOTHER mod:
 *
 * <p>Lithium's {@code ai.poi.SectionStorageMixin} {@code @Inject}s at
 * {@code <init>} RETURN and installs its {@code ListeningLong2ObjectOpenHashMap}
 * — a plain fastutil map whose {@code put}/{@code remove} fire the
 * {@code onEntryAdded}/{@code onEntryRemoved} callbacks that maintain the
 * {@code columns} BitSet sidecar every optimised POI query depends on. Replacing
 * that map kills the sidecar (every query then dies on
 * {@code "Failed to load POI section data!"}); leaving it bare is the 2026-07-29
 * 01:51 crash ({@code AIOOBE Index -1} in {@code Long2ObjectOpenHashMap.rehash}).
 * So this class <b>wraps whatever is already installed</b> — the proven Sepals
 * {@code ActivableLong2ObjectMap} shape, minus the trigger machinery (the
 * listeners here live inside the delegate itself and therefore fire inside this
 * wrapper's write lock, which is exactly what serialises the sidecar writes).
 *
 * <h2>Locking rules</h2>
 * <ul>
 *   <li>{@code get}/{@code containsKey}: {@link StampedLock#tryOptimisticRead()}
 *       → probe → {@link StampedLock#validate(long)}, falling back to the
 *       pessimistic read lock. The {@code catch (Throwable)} inside the
 *       optimistic block is load-bearing: a probe torn by a concurrent rehash
 *       can throw, and that must demote to "failed optimistic read", not
 *       propagate.</li>
 *   <li>writes: exclusive write lock; delegate listeners run inside it.</li>
 *   <li>collection views: <b>locked snapshots</b>, never live views — there are
 *       zero iteration sites over {@code storage} in vanilla 26.2 / Lithium /
 *       C2ME today (verified), and a future caller iterating a live fastutil
 *       view under concurrent writers would corrupt silently.</li>
 * </ul>
 *
 * <p><b>Non-reentrancy invariant:</b> {@link StampedLock} does not support
 * upgrade or reentry. No caller may hold {@link #lock()} (in either mode)
 * across any call that can re-enter this wrapper or reach
 * {@code SectionStorage.unpackChunk}/{@code loadLock} — see the lock-order
 * javadoc on {@code SectionStorageMixin}. External readers that want a
 * consistent view of collaborator state mutated by the delegate's listeners
 * (Lithium's {@code columns}) take {@link #lock()}'s read lock around the
 * collaborator access only.
 */
public final class LockedLong2ObjectMap<V> implements Long2ObjectMap<V> {

    private final Long2ObjectMap<V> delegate;
    private final StampedLock lock = new StampedLock();

    public LockedLong2ObjectMap(Long2ObjectMap<V> delegate) {
        this.delegate = delegate;
    }

    /**
     * The consistency boundary. Exposed so collaborators mutated from inside
     * the delegate's own callbacks (Lithium's {@code columns} sidecar) can be
     * read under the same lock that serialises their writes.
     */
    public StampedLock lock() {
        return this.lock;
    }

    /** For the one-shot startup assertion: what did we actually wrap? */
    public Long2ObjectMap<V> delegate() {
        return this.delegate;
    }

    // --- Hot reads: optimistic --------------------------------------------

    @Override
    public V get(long key) {
        long stamp = this.lock.tryOptimisticRead();
        if (stamp != 0L) {
            V result;
            try {
                result = this.delegate.get(key);
            } catch (Throwable t) {
                // Writer caught us mid-probe; fall through to the read lock.
                stamp = 0L;
                result = null;
            }
            if (stamp != 0L && this.lock.validate(stamp)) {
                return result;
            }
        }
        long readStamp = this.lock.readLock();
        try {
            return this.delegate.get(key);
        } finally {
            this.lock.unlockRead(readStamp);
        }
    }

    @SuppressWarnings("deprecation") // boxed Map<Long,V> override; fastutil prefers get(long)
    @Override
    public V get(Object key) {
        long stamp = this.lock.tryOptimisticRead();
        if (stamp != 0L) {
            V result;
            try {
                result = this.delegate.get(key);
            } catch (Throwable t) {
                stamp = 0L;
                result = null;
            }
            if (stamp != 0L && this.lock.validate(stamp)) {
                return result;
            }
        }
        long readStamp = this.lock.readLock();
        try {
            return this.delegate.get(key);
        } finally {
            this.lock.unlockRead(readStamp);
        }
    }

    @Override
    public boolean containsKey(long key) {
        long stamp = this.lock.tryOptimisticRead();
        if (stamp != 0L) {
            boolean result;
            try {
                result = this.delegate.containsKey(key);
            } catch (Throwable t) {
                stamp = 0L;
                result = false;
            }
            if (stamp != 0L && this.lock.validate(stamp)) {
                return result;
            }
        }
        long readStamp = this.lock.readLock();
        try {
            return this.delegate.containsKey(key);
        } finally {
            this.lock.unlockRead(readStamp);
        }
    }

    @SuppressWarnings("deprecation") // boxed Map<Long,V> override
    @Override
    public boolean containsKey(Object key) {
        long readStamp = this.lock.readLock();
        try {
            return this.delegate.containsKey(key);
        } finally {
            this.lock.unlockRead(readStamp);
        }
    }

    // --- Cold reads: pessimistic ------------------------------------------

    @Override
    public int size() {
        long stamp = this.lock.readLock();
        try {
            return this.delegate.size();
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    @Override
    public boolean isEmpty() {
        long stamp = this.lock.readLock();
        try {
            return this.delegate.isEmpty();
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    @Override
    public boolean containsValue(Object value) {
        long stamp = this.lock.readLock();
        try {
            return this.delegate.containsValue(value);
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    // --- Writes: exclusive; delegate listeners fire INSIDE the lock -------

    @Override
    public V put(long key, V value) {
        long stamp = this.lock.writeLock();
        try {
            return this.delegate.put(key, value);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    @Override
    public V remove(long key) {
        long stamp = this.lock.writeLock();
        try {
            return this.delegate.remove(key);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    @Override
    public void putAll(Map<? extends Long, ? extends V> m) {
        long stamp = this.lock.writeLock();
        try {
            this.delegate.putAll(m);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    @Override
    public void clear() {
        long stamp = this.lock.writeLock();
        try {
            this.delegate.clear();
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    // defaultReturnValue is set once at owner init, read-only thereafter;
    // publication of `this` provides the happens-before.

    @Override
    public void defaultReturnValue(V rv) {
        this.delegate.defaultReturnValue(rv);
    }

    @Override
    public V defaultReturnValue() {
        return this.delegate.defaultReturnValue();
    }

    // --- Collection views: locked snapshots, never live -------------------

    @Override
    public ObjectSet<Long2ObjectMap.Entry<V>> long2ObjectEntrySet() {
        long stamp = this.lock.readLock();
        try {
            ObjectSet<Long2ObjectMap.Entry<V>> copy = new ObjectOpenHashSet<>(this.delegate.size());
            for (Long2ObjectMap.Entry<V> e : this.delegate.long2ObjectEntrySet()) {
                copy.add(new AbstractLong2ObjectMap.BasicEntry<>(e.getLongKey(), e.getValue()));
            }
            return copy;
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    @Override
    public LongSet keySet() {
        long stamp = this.lock.readLock();
        try {
            return new LongOpenHashSet(this.delegate.keySet());
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    @Override
    public ObjectCollection<V> values() {
        long stamp = this.lock.readLock();
        try {
            return new ObjectArrayList<>(this.delegate.values());
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    @Override
    public ObjectSet<Map.Entry<Long, V>> entrySet() {
        return (ObjectSet) long2ObjectEntrySet();
    }
}
