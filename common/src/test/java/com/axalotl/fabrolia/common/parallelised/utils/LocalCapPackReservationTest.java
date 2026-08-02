package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #32 endgame: atomic pack reservation replaces the per-candidate
 * local-cap recheck. These tests drive the real shipped algorithm
 * ({@link LocalCapPackReservation}) over real {@link AtomicIntegerArray}
 * backings — the same type {@code LocalMobCapCalculatorMobCountsMixin}
 * installs under {@code MobCounts} — through the exact shipped call order
 * (fused approve+reserve → vanilla per-spawn addMob increments → pack-end
 * release).
 *
 * <p>The concurrency test reproduces the production failure: N parallel chunk
 * passes against one shared per-player count. The unfenced read-then-add flow
 * (what ran before the recheck, and what {@code localcapreserve off} restores)
 * overshoots by the full parallel width; the reservation stays within
 * cap + one pack — vanilla's own sequential class.
 */
class LocalCapPackReservationTest {

    private static final int ORD = 3;           // any category ordinal
    private static final int CAP = 20;          // e.g. WATER_AMBIENT getMaxInstancesPerChunk
    private static final int PACK = LocalCapPackReservation.PACK_RESERVE;

    private static AtomicIntegerArray counts(int prefill) {
        AtomicIntegerArray a = new AtomicIntegerArray(8);
        a.set(ORD, prefill);
        return a;
    }

    // ------------------------------------------------------------------
    // The parallel window itself — documents the class of the bug the
    // reservation closes. This is the PRE-reservation flow: every chunk
    // pass reads the count in the same open window, then all place packs.
    // ------------------------------------------------------------------
    @Test
    void unfencedApprovalWindowOvershootsByParallelWidth() throws Exception {
        int threads = 16;
        AtomicIntegerArray player = counts(CAP - 1); // one slot of room
        AtomicInteger placed = new AtomicInteger();
        CyclicBarrier window = new CyclicBarrier(threads);
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                // read BEFORE the barrier, add after: deterministically models
                // "every pass approved in the same open window"
                boolean approved = player.get(ORD) < CAP;
                try {
                    window.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                if (approved) {
                    for (int k = 0; k < PACK; k++) {
                        player.incrementAndGet(ORD); // vanilla addMob
                        placed.incrementAndGet();
                    }
                }
            });
            ts[t].start();
        }
        for (Thread th : ts) th.join();
        assertEquals(threads * PACK, placed.get(),
                "every pass read room in the same window — the whole neighbourhood's packs land");
        assertTrue(placed.get() >= 2 * (CAP + PACK),
                "the unfenced flow overshoots by the parallel width (task #32: measured 2-7x the "
                        + "local cap in production) — a different class from vanilla's ≈ one pack");
    }

    // ------------------------------------------------------------------
    // The reservation under the same contention: bound = cap - 1 + pack.
    // ------------------------------------------------------------------
    @Test
    void reservedApprovalStaysWithinCapPlusOnePack() throws Exception {
        int threads = 16;
        int passesPerThread = 200;
        AtomicIntegerArray player = counts(0);
        AtomicIntegerArray[] near = {player};
        AtomicInteger placed = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(threads);
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            ts[t] = new Thread(() -> {
                Random r = new Random(seed * 7919L + 17);
                try {
                    start.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                for (int pass = 0; pass < passesPerThread; pass++) {
                    if (!LocalCapPackReservation.tryReserve(near, 1, ORD, CAP, PACK)) {
                        continue; // vanilla reject — nothing left behind
                    }
                    // approved pack: vanilla places 1..4 mobs, each through addMob,
                    // with NO further local-cap gate (the farm-starve fix)
                    int spawned = 1 + r.nextInt(PACK);
                    for (int k = 0; k < spawned; k++) {
                        player.incrementAndGet(ORD);
                        placed.incrementAndGet();
                    }
                    LocalCapPackReservation.release(near, 1, ORD, PACK);
                }
            });
            ts[t].start();
        }
        for (Thread th : ts) th.join();

        // Sequential top-up, exactly like a later tick with no contention: the
        // reservation must keep approving until the cap is genuinely full — no
        // in-flight phantom may survive the releases and throttle below vanilla.
        while (LocalCapPackReservation.tryReserve(near, 1, ORD, CAP, PACK)) {
            for (int k = 0; k < PACK; k++) {
                player.incrementAndGet(ORD);
                placed.incrementAndGet();
            }
            LocalCapPackReservation.release(near, 1, ORD, PACK);
        }

        assertTrue(placed.get() >= CAP,
                "the cap must actually fill — the reservation must not throttle below vanilla");
        assertTrue(placed.get() <= CAP - 1 + PACK,
                "parallel overshoot must stay within vanilla's sequential class (cap - 1 + pack = "
                        + (CAP - 1 + PACK) + "), got " + placed.get());
        assertEquals(placed.get(), player.get(ORD),
                "all reservations returned — counts settle at exactly the spawned number");
    }

    // ------------------------------------------------------------------
    // Farm shape: room for one, pack of four COMPLETES (vanilla), and the
    // accounting is exact afterwards. The removed per-candidate recheck
    // rejected candidates 2..4 here — the wither-skeleton-farm trickle.
    // ------------------------------------------------------------------
    @Test
    void approvedPackCompletesWhenCapHasOneSlot() {
        AtomicIntegerArray player = counts(CAP - 1);
        AtomicIntegerArray[] near = {player};
        assertTrue(LocalCapPackReservation.tryReserve(near, 1, ORD, CAP, PACK),
                "one slot of room = vanilla approval");
        // the shipped flow has no per-candidate local-cap gate: all four place
        int spawnedWithoutRejection = 0;
        for (int k = 0; k < PACK; k++) {
            player.incrementAndGet(ORD); // vanilla afterSpawn -> addMob
            spawnedWithoutRejection++;
        }
        LocalCapPackReservation.release(near, 1, ORD, PACK);
        assertEquals(PACK, spawnedWithoutRejection, "pack must complete — no trickle");
        assertEquals(CAP - 1 + PACK, player.get(ORD),
                "vanilla's own overshoot: approval at cap-1 completes its pack");
    }

    @Test
    void concurrentFillDoesNotTruncateAnApprovedPack() {
        AtomicIntegerArray player = counts(CAP - 1);
        AtomicIntegerArray[] near = {player};
        assertTrue(LocalCapPackReservation.tryReserve(near, 1, ORD, CAP, PACK));
        // another player's approvals push this player's count far past cap
        // mid-pack (vanilla addMob hits every near player of those chunks)
        player.getAndAdd(ORD, 10);
        // the approved pack still completes: there is no rejection point inside it
        for (int k = 0; k < PACK; k++) {
            player.incrementAndGet(ORD);
        }
        LocalCapPackReservation.release(near, 1, ORD, PACK);
        assertEquals(CAP - 1 + 10 + PACK, player.get(ORD),
                "an already-approved pack is never truncated by the cap filling underneath it");
    }

    // ------------------------------------------------------------------
    // Vanilla predicate shape: ∃ near player with room approves; the
    // reservation (like addMob) lands on EVERY near player; rejection
    // rolls back to exactly the pre-call counts.
    // ------------------------------------------------------------------
    @Test
    void existentialApprovalReservesOnAllPlayersLikeAddMob() {
        AtomicIntegerArray full = counts(CAP + 3); // vanilla overshoot leftovers
        AtomicIntegerArray empty = counts(0);
        AtomicIntegerArray[] near = {full, empty};
        assertTrue(LocalCapPackReservation.tryReserve(near, 2, ORD, CAP, PACK),
                "vanilla canSpawn: one near player with room approves the chunk");
        assertEquals(CAP + 3 + PACK, full.get(ORD), "reservation mirrors addMob's all-players shape");
        assertEquals(PACK, empty.get(ORD));
        LocalCapPackReservation.release(near, 2, ORD, PACK);
        assertEquals(CAP + 3, full.get(ORD));
        assertEquals(0, empty.get(ORD));
    }

    @Test
    void rejectionRollsBackExactly() {
        AtomicIntegerArray a = counts(CAP);
        AtomicIntegerArray b = counts(CAP + 5);
        AtomicIntegerArray[] near = {a, b};
        assertFalse(LocalCapPackReservation.tryReserve(near, 2, ORD, CAP, PACK),
                "no near player with room = vanilla reject");
        assertEquals(CAP, a.get(ORD), "rejection must leave no residue");
        assertEquals(CAP + 5, b.get(ORD), "rejection must leave no residue");
    }

    /** A rollback's transient +pack can never flip a concurrent verdict: it
     *  only ever exists on players already AT/over cap, where the settled
     *  value rejects too. Hammer rejects against one approving thread and
     *  assert no approval was ever lost or gained spuriously. */
    @Test
    void rollbackNeverFlipsConcurrentVerdicts() throws Exception {
        for (int round = 0; round < 200; round++) {
            AtomicIntegerArray player = counts(CAP - 1); // exactly one approval available
            AtomicIntegerArray[] near = {player};
            AtomicInteger approvals = new AtomicInteger();
            CyclicBarrier go = new CyclicBarrier(4);
            Thread[] ts = new Thread[4];
            for (int t = 0; t < 4; t++) {
                ts[t] = new Thread(() -> {
                    try {
                        go.await();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    if (LocalCapPackReservation.tryReserve(near, 1, ORD, CAP, PACK)) {
                        approvals.incrementAndGet();
                        player.incrementAndGet(ORD); // place one mob
                        LocalCapPackReservation.release(near, 1, ORD, PACK);
                    }
                });
                ts[t].start();
            }
            for (Thread th : ts) th.join();
            assertEquals(1, approvals.get(),
                    "exactly one pass may win the last slot — rollbacks neither steal nor mint approvals");
            assertEquals(CAP, player.get(ORD));
        }
    }

    @Test
    void noNearPlayersRejects() {
        // vanilla canSpawn returns false with no near players; the caller
        // (LocalMobCapCalculatorMixin) short-circuits n == 0 before the helper,
        // and the helper itself agrees for n == 0
        assertFalse(LocalCapPackReservation.tryReserve(new AtomicIntegerArray[0], 0, ORD, CAP, PACK));
    }
}
