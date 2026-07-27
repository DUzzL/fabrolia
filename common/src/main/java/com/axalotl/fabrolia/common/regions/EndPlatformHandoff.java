package com.axalotl.fabrolia.common.regions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Narrow End-portal falling-block handoff barrier (2026-07-27, phase-drift
 * task). Pure logic — no Minecraft types — so the whole rule is unit-testable.
 *
 * <h2>The phase relationship the machine depends on</h2>
 *
 * The End-portal sand duper's product cycle is, per pair of consecutive
 * activations (cohorts) A then B:
 * <ol>
 *   <li>A's falling blocks tick at the destination until one lands and PLACES
 *       a block (the FallingBlockEntity discards itself on the same tick it
 *       places — placement and removal are one atomic entity tick);</li>
 *   <li>B's teleport runs {@code createEndPlatform(dropResources=true)}, which
 *       REMOVES A's placed block — that popped item is the harvest;</li>
 *   <li>B's falling blocks then tick over a clean platform.</li>
 * </ol>
 *
 * {@code createEndPlatform} mutates block states only: it can never clear an
 * AIRBORNE FallingBlockEntity. So if B's regen runs while any of A's members
 * are still airborne, there is no block to remove; A then lands AFTER the
 * regen, B collides with A's late block, and the cohort scatters (the y=50 /
 * time=5 anomaly). Vanilla can't reach that state because source and
 * destination share one tick clock: consecutive activations are ≥ one full
 * fall apart in DESTINATION ticks by construction. Fabrolia decouples the
 * clocks, so a publication spacing of 1..fall-1 destination epochs becomes
 * reachable — that band is exactly the field failure.
 *
 * <h2>The rule</h2>
 *
 * The invariant is not a tick count — it is the vanilla event ordering:
 * <blockquote>an End-platform regeneration belonging to a falling-block cohort
 * must not execute while a previously published falling-block cohort for that
 * platform still has airborne members.</blockquote>
 *
 * Enforced event-based at the destination's publish boundary:
 * <ul>
 *   <li>When a cohort containing a platform regen AND falling-block members is
 *       released, its members are registered as airborne for the regen's
 *       chunk.</li>
 *   <li>A later such cohort for the same chunk is HELD (left queued, order
 *       preserved) until every registered member has settled — i.e. the
 *       entity was removed, which for a FallingBlockEntity happens on the tick
 *       it places its block, breaks, or drops as an item. "Settled" therefore
 *       means "its placement, if any, has already happened".</li>
 * </ul>
 *
 * No tick constant appears anywhere: at any island TPS the gate opens exactly
 * when the previous cohort's landing phase has completed, because it watches
 * the entities themselves, not a clock.
 *
 * <h2>What is deliberately NOT held</h2>
 * <ul>
 *   <li>Cohorts with no platform regen (nether-portal transfers, items, mobs,
 *       minecarts anywhere) — never touched.</li>
 *   <li>Regen-only cohorts with no falling-block member (a player or mob
 *       walking into the End). Vanilla allows a crossing to regen mid-fall of
 *       an unrelated machine cohort, and it is harmless: nothing is placed
 *       yet, so nothing is lost, and the machine's own next cohort is still
 *       gated on the previous one's landing. Holding a player's regen would be
 *       actively wrong — the player's arrival is not staged, so their platform
 *       must regenerate immediately.</li>
 * </ul>
 * So the ONLY thing this mechanism ever delays is the machine's own next
 * falling-block cohort — the exact delay vanilla's shared clock imposes.
 *
 * <h2>Liveness</h2>
 *
 * A held cohort is released when the tracked members settle. A falling block
 * always settles: it lands (places/breaks), drops as an item after its
 * lifetime cap, or is removed by unload/kill — all reach
 * {@code Entity.isRemoved()}. As containment for the pathological case where a
 * tracked member is never inserted at all (its publish op failed), tracker
 * entries additionally expire after {@link #DEFAULT_TTL_NANOS}: the gate then
 * fails OPEN to pre-gate behaviour rather than holding anything forever. The
 * TTL is a failure belt, not the mechanism — the normal path never waits on
 * wall time.
 */
public final class EndPlatformHandoff {

    /** Containment-only expiry for tracked members that can never settle
     *  (publish-op failure before insertion). 60 s wall — far beyond any
     *  normal landing (a 1-block fall is 7 entity ticks) yet bounded, so the
     *  gate can never hold a cohort indefinitely. Fail-open. */
    public static final long DEFAULT_TTL_NANOS = 60_000_000_000L;

    private EndPlatformHandoff() {}

    /** Index-addressable view of the queued arrival prefix under decision. */
    public interface Ops {
        int size();
        /** Clock domain of the op's source epoch; 0 = wall. */
        long sourceDomain(int i);
        /** Source simulation epoch, or negative if unknown. */
        long sourceTick(int i);
        /** Destination chunk key of the op. */
        long chunkKey(int i);
        /** True for an End-platform regeneration op. */
        boolean isRegen(int i);
        /** Non-null iff this op inserts a falling-block entity whose landing
         *  the gate must observe; the handle is opaque to this class. */
        Object landingHandle(int i);
    }

    /**
     * Per-destination-LEVEL airborne-member tracker. Lives on
     * {@link RegionizedServer} (not on an island) deliberately: island
     * merge/rebuild churn — which player movement causes — retires islands and
     * reroutes their queues, and a per-island tracker would forget the airborne
     * cohort at exactly the moment the player-at-source failure mode strikes.
     * Level identity is stable.
     *
     * <p>Thread-safety: all methods synchronized; the maps are tiny (one entry
     * per End platform with in-flight machine traffic — almost always 0 or 1).
     */
    public static final class Tracker {
        private record Entry(Object handle, long trackedAtNanos) {}

        private final Predicate<Object> settled;
        private final LongSupplier nanoClock;
        private final long ttlNanos;
        private final HashMap<Long, ArrayList<Entry>> airborneByChunk = new HashMap<>(4);

        public Tracker(Predicate<Object> settled, LongSupplier nanoClock, long ttlNanos) {
            this.settled = settled;
            this.nanoClock = nanoClock;
            this.ttlNanos = ttlNanos;
        }

        /** Register a published falling-block member as airborne at {@code chunkKey}. */
        public synchronized void track(long chunkKey, Object handle) {
            airborneByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>(12))
                    .add(new Entry(handle, nanoClock.getAsLong()));
        }

        /** True iff any tracked member at {@code chunkKey} has not yet settled.
         *  Purges settled/expired entries as a side effect. */
        public synchronized boolean airborne(long chunkKey) {
            ArrayList<Entry> list = airborneByChunk.get(chunkKey);
            if (list == null) {
                return false;
            }
            purge(list);
            if (list.isEmpty()) {
                airborneByChunk.remove(chunkKey);
                return false;
            }
            return true;
        }

        /** Drop settled/expired entries everywhere (main-tick housekeeping so a
         *  machine that stops forever doesn't strongly retain its last cohort). */
        public synchronized void sweep() {
            Iterator<java.util.Map.Entry<Long, ArrayList<Entry>>> it =
                    airborneByChunk.entrySet().iterator();
            while (it.hasNext()) {
                ArrayList<Entry> list = it.next().getValue();
                purge(list);
                if (list.isEmpty()) {
                    it.remove();
                }
            }
        }

        /** Diagnostics/tests: total tracked (possibly settled-but-unpurged) members. */
        public synchronized int trackedCount() {
            int n = 0;
            for (ArrayList<Entry> l : airborneByChunk.values()) {
                n += l.size();
            }
            return n;
        }

        private void purge(ArrayList<Entry> list) {
            long now = nanoClock.getAsLong();
            list.removeIf(e -> settled.test(e.handle())
                    || now - e.trackedAtNanos() >= ttlNanos);
        }
    }

    /**
     * Decide, op by op, which of the causally-releasable prefix actually
     * publishes at this head. Returns a mask: {@code mask[i]} true = release op
     * {@code i} now; false = leave it queued (order preserved) for a later head.
     *
     * <p>Cohort = maximal run of consecutive ops sharing (sourceDomain,
     * sourceTick); unknown epochs (negative) are singleton cohorts. A cohort is
     * GATE-ELIGIBLE iff it contains a regen op AND at least one landing handle —
     * i.e. it is a machine falling-block cohort. Only gate-eligible cohorts can
     * ever be held:
     * <ul>
     *   <li>held if any of its regen chunks still has airborne members from a
     *       previously released cohort, or</li>
     *   <li>held if an earlier gate-eligible cohort touching one of its chunks
     *       was held at this head (per-chunk FIFO between machine cohorts —
     *       cohort C must never overtake held cohort B for the same platform).</li>
     * </ul>
     * A cohort released with regen + members registers its members as airborne
     * IMMEDIATELY, so a second machine cohort at the same head is held rather
     * than double-published into one destination epoch.
     */
    public static boolean[] selectReleasable(Ops ops, Tracker tracker) {
        int n = ops.size();
        boolean[] release = new boolean[n];
        java.util.Arrays.fill(release, true);
        if (n == 0 || tracker == null) {
            return release;
        }
        HashSet<Long> heldChunks = null; // lazily allocated — hold is the rare path
        int i = 0;
        while (i < n) {
            int j = i;
            long tick = ops.sourceTick(i);
            long dom = ops.sourceDomain(i);
            if (tick >= 0) {
                while (j + 1 < n && ops.sourceTick(j + 1) == tick
                        && ops.sourceDomain(j + 1) == dom) {
                    j++;
                }
            }
            boolean hasRegen = false;
            boolean hasHandle = false;
            for (int k = i; k <= j; k++) {
                hasRegen |= ops.isRegen(k);
                hasHandle |= ops.landingHandle(k) != null;
            }
            if (hasRegen && hasHandle) { // machine falling-block cohort
                boolean hold = false;
                for (int k = i; k <= j && !hold; k++) {
                    long c = ops.chunkKey(k);
                    if (heldChunks != null && heldChunks.contains(c)) {
                        hold = true; // FIFO behind an earlier held machine cohort
                    } else if (ops.isRegen(k) && tracker.airborne(c)) {
                        hold = true; // previous cohort's landing phase incomplete
                    }
                }
                if (hold) {
                    if (heldChunks == null) {
                        heldChunks = new HashSet<>(4);
                    }
                    for (int k = i; k <= j; k++) {
                        release[k] = false;
                        heldChunks.add(ops.chunkKey(k));
                    }
                } else {
                    // Releasing now: its members become the airborne cohort a
                    // later machine cohort must wait out — registered before
                    // any later cohort at this same head is evaluated.
                    for (int k = i; k <= j; k++) {
                        Object h = ops.landingHandle(k);
                        if (h != null) {
                            tracker.track(ops.chunkKey(k), h);
                        }
                    }
                }
            }
            i = j + 1;
        }
        return release;
    }

    /**
     * audit13 — flush-path registration (the save/stop counterpart of the
     * registration {@link #selectReleasable} performs on release). A flush
     * ({@code CrossDimArrivals.flushLevel}/{@code flushAll}) publishes HELD
     * machine cohorts past the gate BY DESIGN — delivering a queued entity into
     * its level before serialization beats losing it — but pre-fix it never
     * {@code track()}ed the flushed falling-block members. The SUCCESSOR cohort
     * then found no airborne record and released while the flushed members were
     * still falling: its {@code createEndPlatform} ran mid-fall — exactly the
     * scatter the gate exists to prevent, with a window of one hold per save
     * per level. This walks the same maximal (sourceDomain, sourceTick) runs as
     * {@link #selectReleasable}, applies the same gate-eligibility rule (regen
     * op AND at least one landing handle), and registers every landing handle
     * of eligible cohorts as airborne. It decides nothing and never holds.
     */
    public static void trackAll(Ops ops, Tracker tracker) {
        int n = ops.size();
        if (n == 0 || tracker == null) {
            return;
        }
        int i = 0;
        while (i < n) {
            int j = i;
            long tick = ops.sourceTick(i);
            long dom = ops.sourceDomain(i);
            if (tick >= 0) {
                while (j + 1 < n && ops.sourceTick(j + 1) == tick
                        && ops.sourceDomain(j + 1) == dom) {
                    j++;
                }
            }
            boolean hasRegen = false;
            boolean hasHandle = false;
            for (int k = i; k <= j; k++) {
                hasRegen |= ops.isRegen(k);
                hasHandle |= ops.landingHandle(k) != null;
            }
            if (hasRegen && hasHandle) { // machine falling-block cohort
                for (int k = i; k <= j; k++) {
                    Object h = ops.landingHandle(k);
                    if (h != null) {
                        tracker.track(ops.chunkKey(k), h);
                    }
                }
            }
            i = j + 1;
        }
    }
}
