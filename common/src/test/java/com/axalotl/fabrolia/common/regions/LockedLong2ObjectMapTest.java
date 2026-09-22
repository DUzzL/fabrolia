package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.LockedLong2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.caffeinemc.mods.lithium.common.util.collections.ListeningLong2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.BitSet;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #38 — the {@code storage} wrapper, exercised against the REAL Lithium
 * {@link ListeningLong2ObjectOpenHashMap} (Lithium is on the test classpath;
 * its MIXINS do not apply here, so the {@code columns} sidecar is emulated with
 * the same listener shape Lithium's {@code onEntryAdded}/{@code onEntryRemoved}
 * use — the established emulation pattern from
 * {@code TickRemoveIfImplAgnosticTest}).
 *
 * <p>The stress shape mirrors production: writers are chunk unpacks/unloads
 * ({@code put}/{@code remove} firing the listeners), readers are POI queries
 * ({@code get}) plus the {@code lithium$getNonEmptyPOISections} pattern — a
 * BitSet probe-and-CLONE under {@link LockedLong2ObjectMap#lock()}'s read lock.
 * Pre-fix (bare Listening map) this dies with the 2026-07-29 01:51 signature:
 * {@code AIOOBE Index -1} in {@code Long2ObjectOpenHashMap.rehash}, or torn
 * BitSet iteration.
 */
@Timeout(60)
class LockedLong2ObjectMapTest {

    @Test
    void wrapsTheListeningMapAndKeepsItsListenersFiring() {
        Long2ObjectOpenHashMap<BitSet> columns = new Long2ObjectOpenHashMap<>();
        ListeningLong2ObjectOpenHashMap<Optional<String>> listening = new ListeningLong2ObjectOpenHashMap<>(
                (k, v) -> columns.computeIfAbsent(k >> 8, x -> new BitSet(16)).set((int) (k & 15), v.isPresent()),
                (k, v) -> {
                    BitSet b = columns.get(k >> 8);
                    if (b != null) {
                        b.clear((int) (k & 15));
                        if (b.isEmpty()) {
                            columns.remove(k >> 8);
                        }
                    }
                });
        LockedLong2ObjectMap<Optional<String>> wrapped = new LockedLong2ObjectMap<>(listening);

        // The load-bearing property: wrapping, not replacing — the delegate's
        // listeners keep maintaining the sidecar through the wrapper.
        wrapped.put(0x101L, Optional.of("bed"));
        assertTrue(columns.containsKey(0x1L), "listener did not fire through the wrapper — columns would stay empty "
                + "and every POI query would die on 'Failed to load POI section data!'");
        assertEquals(Optional.of("bed"), wrapped.get(0x101L));
        wrapped.remove(0x101L);
        assertFalse(columns.containsKey(0x1L));
        assertNull(wrapped.get(0x101L));
    }

    @Test
    void concurrentUnpackUnloadQueryStorm() throws Exception {
        Long2ObjectOpenHashMap<BitSet> columns = new Long2ObjectOpenHashMap<>();
        // Injective (column, bit) mapping like Lithium's (chunk, sectionY).
        ListeningLong2ObjectOpenHashMap<Optional<String>> listening = new ListeningLong2ObjectOpenHashMap<>(
                (k, v) -> columns.computeIfAbsent(k >> 8, x -> new BitSet(256)).set((int) (k & 255), v.isPresent()),
                (k, v) -> {
                    BitSet b = columns.get(k >> 8);
                    if (b != null) {
                        b.clear((int) (k & 255));
                        if (b.isEmpty()) {
                            columns.remove(k >> 8);
                        }
                    }
                });
        LockedLong2ObjectMap<Optional<String>> map = new LockedLong2ObjectMap<>(listening);
        StampedLock lock = map.lock();

        int writers = 4;
        int readers = 8;
        int opsPerWriter = 20_000;
        CyclicBarrier start = new CyclicBarrier(writers + readers);
        AtomicBoolean writersDone = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread[] all = new Thread[writers + readers];
        for (int w = 0; w < writers; w++) {
            long base = w * 1_000_000L;
            all[w] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerWriter; i++) {
                        long key = base + (i % 4096);
                        if ((i & 3) == 3) {
                            map.remove(key);
                        } else {
                            map.put(key, Optional.of("s" + i));
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "storage-writer-" + w);
        }
        for (int r = 0; r < readers; r++) {
            int rr = r;
            all[writers + r] = new Thread(() -> {
                try {
                    start.await();
                    long probes = 0;
                    while (!writersDone.get()) {
                        long key = (rr % 4) * 1_000_000L + (probes % 4096);
                        // Hot path: read-locked get racing rehashes.
                        Optional<String> v = map.get(key);
                        if (v != null && v.isEmpty()) {
                            throw new IllegalStateException("phantom empty section");
                        }
                        // The getNonEmpty pattern: probe + clone under readLock,
                        // iterate the CLONE outside it.
                        BitSet snapshot;
                        long stamp = lock.readLock();
                        try {
                            BitSet live = columns.get(key >> 8);
                            snapshot = live == null ? null : (BitSet) live.clone();
                        } finally {
                            lock.unlockRead(stamp);
                        }
                        if (snapshot != null) {
                            for (int bit = snapshot.nextSetBit(0); bit >= 0; bit = snapshot.nextSetBit(bit + 1)) {
                                probes++;
                            }
                        }
                        probes++;
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "storage-reader-" + rr);
        }
        for (Thread t : all) {
            t.start();
        }
        for (int w = 0; w < writers; w++) {
            all[w].join(50_000);
            assertFalse(all[w].isAlive(), "writer hung");
        }
        writersDone.set(true);
        for (int r = 0; r < readers; r++) {
            all[writers + r].join(50_000);
            assertFalse(all[writers + r].isAlive(), "reader hung");
        }
        if (failure.get() != null) {
            throw new AssertionError("corruption under the storm (pre-fix signature: AIOOBE in rehash)", failure.get());
        }

        // Post-storm coherence: sidecar bits match surviving map content exactly.
        for (Long2ObjectMap.Entry<Optional<String>> e : map.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            BitSet b = columns.get(key >> 8);
            assertTrue(b != null && b.get((int) (key & 255)),
                    "columns sidecar lost a live section at key " + key);
        }
        int liveBits = 0;
        for (BitSet b : columns.values()) {
            liveBits += b.cardinality();
        }
        assertEquals(map.size(), liveBits, "columns sidecar and storage disagree after the storm");
    }

    /**
     * Snapshot views: a wrapper handing out live fastutil views would let a
     * future caller iterate the raw map under concurrent writers and corrupt
     * silently — the review required locked snapshots or a loud throw.
     */
    @Test
    void collectionViewsAreSnapshotsNotLiveViews() {
        LockedLong2ObjectMap<String> map = new LockedLong2ObjectMap<>(new Long2ObjectOpenHashMap<>());
        map.put(1L, "a");
        map.put(2L, "b");
        var keys = map.keySet();
        var values = map.values();
        var entries = map.long2ObjectEntrySet();
        map.put(3L, "c");
        map.remove(1L);
        assertEquals(2, keys.size(), "keySet must be a snapshot");
        assertEquals(2, values.size(), "values must be a snapshot");
        assertEquals(2, entries.size(), "entrySet must be a snapshot");
        assertTrue(keys.contains(1L));
        assertFalse(keys.contains(3L));
        assertEquals(2, map.entrySet().stream().filter(e -> e.getKey() != 1L).count());
    }
}
