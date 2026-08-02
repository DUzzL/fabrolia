package com.axalotl.fabrolia.common.parallelised.utils;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Atomic pack reservation for the LOCAL per-player mob cap (task #32 endgame —
 * replaces the per-candidate recheck that starved farms sitting AT the cap).
 *
 * <p>Vanilla ticks spawning chunks SEQUENTIALLY: chunk N's once-per-pass
 * {@code canSpawnForCategoryLocal} sees chunks 1..N-1's spawns, so the local
 * cap can only be overshot by the one pack whose approval squeezed through at
 * cap-1 (a pack completes without re-checking). Fabrolia runs chunk passes in
 * parallel (region workers + one-tick-late island arms), so every eligible
 * chunk used to read the count in the same open window, approve together, and
 * a whole neighbourhood's packs landed at once — measured 2-7x the local cap.
 *
 * <p>The fix: fuse the ∃-room check with the pack's worth of increments into
 * one atomic step per near player. Approval RESERVES {@link #PACK_RESERVE}
 * slots on every near player (the same all-players shape as vanilla
 * {@code addMob}); a concurrent pass's {@code getAndAdd} then already sees
 * them taken. Inside the approved pack, spawning proceeds exactly like
 * vanilla — no per-candidate gate, the pack always completes. When the pack
 * ends, the full reservation is returned; the actual spawns arrived through
 * vanilla {@code afterSpawn → addMob} (+1 each), so the counts settle at
 * precisely the spawned number.
 *
 * <p>Overshoot bound (per player, per category): every approval attributable
 * to player P serializes on P's cell via {@code getAndAdd}; an approval
 * requires the observed value (real count + all in-flight reservations)
 * &lt; cap, and each in-flight pack's eventual real adds never exceed its
 * reservation. Hence P's real count after any approval ≤ cap - 1 + pack —
 * vanilla's own sequential "≈ cap + one pack" class, independent of how many
 * chunk passes run concurrently. (Additions granted because a DIFFERENT near
 * player had room hit P too, exactly as vanilla's addMob does.)
 *
 * <p>Failed approvals roll back and can never flip a concurrent verdict: a
 * rollback only exists where the observed value was already ≥ cap, so a
 * concurrent pass that read the transient +pack would have been rejected on
 * the settled value as well.
 *
 * <p>Static, allocation-free, lock-free — operates directly on the
 * {@code AtomicIntegerArray}s that back {@code MobCounts} (installed by
 * {@code LocalMobCapCalculatorMobCountsMixin}).
 */
public final class LocalCapPackReservation {

    /** Slots reserved per approved chunk pass. Vanilla's pack is bounded by
     *  {@code Mob.getMaxSpawnClusterSize()} — 4 for every vanilla natural
     *  spawn. A modded mob with a larger cluster under-reserves by the
     *  difference (bound loosens to cap + that mob's own pack — still the
     *  "one pack" class); a smaller cluster only over-reserves transiently
     *  until the pack-end release. */
    public static final int PACK_RESERVE = 4;

    private LocalCapPackReservation() {}

    /**
     * Vanilla {@code LocalMobCapCalculator.canSpawn} (∃ near player with room)
     * fused with an all-players pack reservation.
     *
     * @param counts per-near-player category counts (ordinal-indexed), first
     *               {@code n} entries valid
     * @param ord    category ordinal
     * @param cap    {@code MobCategory.getMaxInstancesPerChunk()}
     * @param pack   slots to reserve (normally {@link #PACK_RESERVE})
     * @return true = approved, {@code +pack} left on EVERY player (release
     *         after the pack); false = rejected, counts unchanged
     */
    public static boolean tryReserve(AtomicIntegerArray[] counts, int n, int ord, int cap, int pack) {
        boolean anyRoom = false;
        for (int i = 0; i < n; i++) {
            if (counts[i].getAndAdd(ord, pack) < cap) {
                anyRoom = true;
            }
        }
        if (anyRoom) {
            return true;
        }
        for (int i = 0; i < n; i++) {
            counts[i].getAndAdd(ord, -pack);
        }
        return false;
    }

    /** Return an unconsumed reservation (the pack's real spawns were added
     *  separately by vanilla addMob, so net = spawned). */
    public static void release(AtomicIntegerArray[] counts, int n, int ord, int pack) {
        for (int i = 0; i < n; i++) {
            counts[i].getAndAdd(ord, -pack);
        }
    }
}
