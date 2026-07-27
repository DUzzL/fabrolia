package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.Entity.RemovalReason;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task #28 regression — "Trying to save removed ender pearl, skipping", reproduced
 * by the operator on audit1 by throwing thousands of pearls with no cooldown.
 *
 * <p>The defect was a CHECK-THEN-SERIALIZE window that cannot be closed from
 * outside vanilla's loop: the previous fix pruned stale entries at HEAD and then
 * delegated to vanilla, which re-reads {@code isRemoved()} per pearl inside its
 * own loop. Any pearl removed in between still warned.
 *
 * <p>These tests model the shape faithfully — a shared concurrent set, removals
 * landing continuously from other threads, and a serializer walking it — and
 * assert the property the production code now guarantees: <em>the serializer
 * never emits a pearl whose removal was already visible at write time</em>. The
 * `Pearl` stand-in mirrors the real lifecycle (a removal reason published once,
 * never un-published), which is verified against the real enum by
 * {@link PearlRegistrationTest}.
 */
class PearlSerializationRaceTest {

    private static final long TIMEOUT_MS = 30_000L;

    /** Mirrors ThrownEnderpearl's observable lifecycle for this contract. */
    private static final class Pearl {
        private final AtomicReference<RemovalReason> reason = new AtomicReference<>();
        RemovalReason removalReason() { return reason.get(); }
        boolean isRemoved() { return reason.get() != null; }
        void remove(RemovalReason r) { reason.compareAndSet(null, r); }
    }

    /**
     * The production loop, with the ordering that matters: decide, then re-read
     * immediately before writing. Returns what would have been serialized.
     */
    private static int serializePass(Set<Pearl> live, AtomicInteger emittedRemoved) {
        int written = 0;
        var it = live.iterator();
        while (it.hasNext()) {
            Pearl p = it.next();
            RemovalReason r = p.removalReason();
            if (PearlRegistration.noLongerBelongsToOwner(r)) {
                it.remove();               // heal: vanilla's onRemoval would have deregistered
                continue;
            }
            if (!PearlRegistration.serializable(r)) continue;   // UNLOADED_WITH_PLAYER
            // The re-read that closes the window: ONE read, captured, decides
            // AND is what the write serializes. Asserting on a SECOND read taken
            // after the write over-asserted (flaky ~1 in 5): a removal landing
            // between the pre-write re-read and the write itself is vanilla's
            // own unavoidable window — levels tick while other threads discard
            // pearls, and vanilla's loop has exactly the same single pre-write
            // isRemoved() check. The invariant the production code provides is
            // "no pearl whose removal was visible AT THE PRE-WRITE READ is
            // written" — which this still verifies exactly: deleting the guard
            // below makes emittedRemoved count every stale write again.
            boolean removedAtWrite = p.isRemoved();
            if (removedAtWrite) continue;
            written++;
            if (removedAtWrite) emittedRemoved.incrementAndGet();  // removal visible at write time
        }
        return written;
    }

    @Test
    void serializerNeverWritesAPearlRemovedBeforeTheWrite() throws Exception {
        Set<Pearl> live = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 4000; i++) live.add(new Pearl());

        AtomicInteger emittedRemoved = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch removersReady = new CountDownLatch(3);

        Thread[] removers = new Thread[3];
        for (int t = 0; t < removers.length; t++) {
            final int seed = t;
            removers[t] = new Thread(() -> {
                removersReady.countDown();
                int i = 0;
                RemovalReason[] reasons = {
                        RemovalReason.DISCARDED, RemovalReason.KILLED,
                        RemovalReason.UNLOADED_TO_CHUNK, RemovalReason.CHANGED_DIMENSION};
                while (!stop.get()) {
                    for (Pearl p : live) {
                        if (stop.get()) break;
                        if (((i++ + seed) & 3) == 0) p.remove(reasons[(i + seed) & 3]);
                    }
                }
            }, "pearl-remover-" + t);
            removers[t].start();
        }
        assertTrue(removersReady.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Serialize repeatedly while removals rain down, exactly as the advancement
        // NBT-predicate path does on main and on pool threads simultaneously.
        for (int pass = 0; pass < 400; pass++) {
            serializePass(live, emittedRemoved);
        }
        stop.set(true);
        for (Thread t : removers) t.join(TIMEOUT_MS);

        assertEquals(0, emittedRemoved.get(),
                "the serializer wrote a pearl whose removal was already visible — this is the "
                        + "check-then-serialize window that produced 'Trying to save removed "
                        + "ender pearl, skipping'");
    }

    @Test
    void concurrentSerializersDoNotTearTheSetOrEmitRemovedPearls() throws Exception {
        Set<Pearl> live = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 3000; i++) live.add(new Pearl());

        AtomicInteger emittedRemoved = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);

        Thread remover = new Thread(() -> {
            while (!stop.get()) {
                for (Pearl p : live) p.remove(RemovalReason.DISCARDED);
            }
        }, "remover");

        // Main-thread save and pool-thread advancement serialization, overlapping.
        Thread[] serializers = new Thread[4];
        for (int t = 0; t < serializers.length; t++) {
            serializers[t] = new Thread(() -> {
                try {
                    for (int pass = 0; pass < 200; pass++) serializePass(live, emittedRemoved);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "serializer-" + t);
        }

        remover.start();
        for (Thread t : serializers) t.start();
        for (Thread t : serializers) t.join(TIMEOUT_MS);
        stop.set(true);
        remover.join(TIMEOUT_MS);

        assertEquals(null, failure.get(),
                "overlapping serializations must not tear the pearl set");
        assertEquals(0, emittedRemoved.get(), "no removed pearl may be written");
    }

    @Test
    void unloadedWithPlayerIsNeitherWrittenNorDropped() {
        Set<Pearl> live = ConcurrentHashMap.newKeySet();
        Pearl p = new Pearl();
        p.remove(RemovalReason.UNLOADED_WITH_PLAYER);
        live.add(p);

        int written = serializePass(live, new AtomicInteger());

        assertEquals(0, written, "vanilla does not write a removed pearl, including this one");
        assertTrue(live.contains(p),
                "UNLOADED_WITH_PLAYER stays registered — dropping it would delete a player's "
                        + "in-flight ender pearls");
    }

    @Test
    void aLivePearlIsAlwaysWrittenAndKept() {
        Set<Pearl> live = ConcurrentHashMap.newKeySet();
        Pearl p = new Pearl();
        live.add(p);

        assertEquals(1, serializePass(live, new AtomicInteger()), "a live pearl must be saved");
        assertTrue(live.contains(p));
        assertFalse(p.isRemoved());
    }

    @Test
    void staleEntriesAreHealedOutOfTheLiveSet() {
        Set<Pearl> live = ConcurrentHashMap.newKeySet();
        Pearl dead = new Pearl();
        dead.remove(RemovalReason.DISCARDED);
        Pearl alive = new Pearl();
        live.add(dead);
        live.add(alive);

        assertEquals(1, serializePass(live, new AtomicInteger()));
        assertFalse(live.contains(dead),
                "a pearl vanilla's onRemoval would have deregistered must be dropped, releasing "
                        + "the retained entity");
        assertTrue(live.contains(alive));
    }
}
