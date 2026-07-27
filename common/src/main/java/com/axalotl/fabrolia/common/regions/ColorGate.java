package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;

/**
 * R4 stage A — the color-DAG scheduling discipline (user-approved trade:
 * a few ms of wave latency for BY-CONSTRUCTION correctness of every
 * cross-border effect up to a region wide, recouped at stage B when the
 * global drain locks come off).
 *
 * <p>Rule: a region of color C may be CLAIMED for epoch E only when every
 * EXISTING lower-color neighbor (8-neighborhood) has fully completed epoch E.
 * Colors form a DAG (same color is never adjacent), so there are no cycles;
 * liveness comes from {@link #markDone} re-arming higher-color neighbors
 * after the volatile done-write (a racy stale read on the checking side is
 * always followed by the blocker's own re-arm of the checker).
 *
 * <p>Completion = the region's entity phase ran this epoch AND its queues are
 * empty; regions with no work this tick are swept done at entity dispatch.
 */
public final class ColorGate {

    private ColorGate() {}

    private static long key(int rx, int rz) {
        return (rx & 0xFFFFFFFFL) | ((rz & 0xFFFFFFFFL) << 32);
    }

    /** May {@code r} run now under the color discipline? Epochs are compared in
     *  {@code r}'s OWN domain (A2): neighbors in a different domain are skipped —
     *  their stamps live in another timeline, and the island separation
     *  invariant (≥2 unpopulated cells between islands) guarantees no same-tick
     *  causal chain connects the two. The only cross-domain radius-1 neighbors
     *  that exist are unpopulated gap regions (global domain), whose queue
     *  drains are claim-serialized and cross-region-guarded independently. */
    public static boolean eligible(Region r, RegionizedServer rs) {
        if (rs == null) {
            return false;
        }
        // A2 ownership fix (2026-07-13): an island-owned region is claimable
        // ONLY inside its unit's entity-phase window — checked BEFORE the
        // color-0 shortcut, which would otherwise bypass it. See inUnitWindow.
        if (!inUnitWindow(r, rs)) {
            return false;
        }
        if (!FabroliaConfig.useRegionColorGate() || r.color == 0) {
            return true;
        }
        EpochDomain d = r.domain;
        long epoch = d.epoch;
        ThreadedRegionizer reg = rs.regionizer();
        int rx = (int) r.gridKey;
        int rz = (int) (r.gridKey >>> 32);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nColor = ((rx + dx) & 1) | (((rz + dz) & 1) << 1);
                if (nColor >= r.color) {
                    continue;
                }
                Region n = reg.peekByGridKey(key(rx + dx, rz + dz));
                if (n != null && n.state() == RegionState.ACTIVE
                        && n.domain == d && n.doneEpoch < epoch) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * A2 ownership rule (2026-07-13 production log): while async is live, an
     * island-owned region's queue has exactly ONE legal consumer — its unit's
     * entity-phase drain (grouped for the current epoch, not yet done). The
     * unit's other segments (scheduled ticks, block events, deferred replay,
     * BE pass) run under the ISLAND claim only, so an out-of-window region
     * claim-drain would mutate the same cells concurrently; the claim-free
     * main-safe reroute was worse still (wrong timeline attribution → false
     * cross-domain fail-closed trips on vanilla random ticks). A refused claim
     * just drops the ready entry — dispatch re-seeds every member each unit.
     * Gap regions (global domain) and sync mode are unaffected.
     */
    public static boolean inUnitWindow(Region r, RegionizedServer rs) {
        if (!rs.asyncActive) {
            return true;
        }
        EpochDomain d = r.domain;
        if (d == null || d.owner == null) {
            return true;
        }
        long e = d.epoch;
        return r.groupedEpoch == e && r.doneEpoch < e;
    }

    /**
     * Completion attempt: entity phase ran this epoch and the queues are
     * drained → publish done + re-arm dependents. Carries EVERY refusal
     * guard, so any thread may call it blindly on any region (the heal
     * sweep does exactly that). Safe to call repeatedly.
     */
    public static void tryMarkDone(Region r, RegionizedServer rs) {
        tryMarkDone(r, rs, true);
    }

    /** {@code wakeOnPush=false} = the dispatch-sweep variant: hundreds of no-work regions
     *  complete back-to-back on main and each push would unpark a worker individually
     *  (680ms of Unsafe.unpark on the server thread, xMDQKeDhXO profile 2026-07-02);
     *  the sweep caller issues ONE flag-scan wake for everything pushed instead. */
    public static void tryMarkDone(Region r, RegionizedServer rs, boolean wakeOnPush) {
        if (rs == null) {
            return;
        }
        EpochDomain d = r.domain;
        long epoch = d.epoch;
        if (r.doneEpoch >= epoch) {
            return;
        }
        // Pre-dispatch, "complete" is undecidable: a region whose spawn task
        // drained early may still be grouped for an entity phase moments later.
        // Publishing done here let dependents claim BEFORE that entity phase —
        // the certified crossUNSAFE=1 (2026-06-12) was exactly this window.
        // Until dispatch has stamped groupedEpochs, nobody completes.
        if (d.dispatchedEpoch != epoch) {
            return;
        }
        // Claimed = a drain is writing world state right now; the holder's
        // release epilogue re-attempts. (Also closes the sweep-vs-drain race:
        // a queue can read empty mid-task.)
        if (r.currentWorker() != null) {
            return;
        }
        if (!r.queue().isEmpty() || !r.helpQueue.isEmpty()) {
            return;
        }
        if (r.groupedEpoch == epoch && r.entityEpoch < epoch) {
            return;   // entity phase still owed this epoch
        }
        markDone(r, rs, wakeOnPush);
    }

    /** Wedge forensics: which lower-color neighbors hold {@code r} back. */
    public static String diagnose(Region r, RegionizedServer rs) {
        EpochDomain d = r.domain;
        long epoch = d.epoch;
        ThreadedRegionizer reg = rs.regionizer();
        int rx = (int) r.gridKey;
        int rz = (int) (r.gridKey >>> 32);
        StringBuilder sb = new StringBuilder(128);
        sb.append("region key=").append(Long.toHexString(r.gridKey))
          .append(" color=").append(r.color)
          .append(" epoch=").append(epoch)
          .append(" dispatched=").append(d.dispatchedEpoch)
          .append(" blockers:");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nColor = ((rx + dx) & 1) | (((rz + dz) & 1) << 1);
                if (nColor >= r.color) continue;
                Region n = reg.peekByGridKey(key(rx + dx, rz + dz));
                if (n != null && n.state() == RegionState.ACTIVE
                        && n.domain == d && n.doneEpoch < epoch) {
                    sb.append(" [key=").append(Long.toHexString(n.gridKey))
                      .append(" c=").append(n.color)
                      .append(" done=").append(n.doneEpoch)
                      .append(" grouped=").append(n.groupedEpoch)
                      .append(" entity=").append(n.entityEpoch)
                      .append(" q=").append(n.queue().size())
                      .append(" h=").append(n.helpQueue.size())
                      .append(" claimed=").append(n.currentWorker() != null)
                      .append(" ents=").append(n.entityCount.get()).append(']');
                }
            }
        }
        return sb.toString();
    }

    /** Unconditional done (dispatch sweep for no-work regions). */
    public static void markDone(Region r, RegionizedServer rs) {
        markDone(r, rs, true);
    }

    public static void markDone(Region r, RegionizedServer rs, boolean wakeOnPush) {
        if (rs == null) {
            return;
        }
        long epoch = r.domain.epoch;
        if (r.doneEpoch >= epoch) {
            return;
        }
        r.doneEpoch = epoch;
        // Re-arm below is domain-agnostic on purpose: every check on the
        // neighbor (owesEntityPhase, eligible, queue emptiness) evaluates in
        // the NEIGHBOR's own domain, so a global gap region completing may
        // legitimately push an island neighbor and vice versa.
        if (!FabroliaConfig.useRegionColorGate()) {
            return;
        }
        // Re-arm higher-color neighbors with pending work.
        ThreadedRegionizer reg = rs.regionizer();
        int rx = (int) r.gridKey;
        int rz = (int) (r.gridKey >>> 32);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nColor = ((rx + dx) & 1) | (((rz + dz) & 1) << 1);
                if (nColor <= r.color) {
                    continue;
                }
                Region n = reg.peekByGridKey(key(rx + dx, rz + dz));
                if (n == null || n.state() != RegionState.ACTIVE) {
                    continue;
                }
                if (!n.queue().isEmpty() || !n.helpQueue.isEmpty()) {
                    RegionScheduler.enqueueReady(n);
                    if (wakeOnPush) RegionScheduler.wakeWorkers(1);
                } else if (FabroliaConfig.regionEventScheduler
                        && rs.owesEntityPhase(n) && eligible(n, rs)) {
                    // R5 event push: this completion may have been n's LAST lower-color
                    // blocker — deliver readiness NOW instead of letting a group
                    // continuation poll for it. When two blockers finish concurrently,
                    // volatile-write/read total order guarantees at least one of them
                    // sees the other's doneEpoch and passes eligible(); the CAS gate
                    // in tryEnqueueEntity dedupes if both do.
                    if (rs.tryEnqueueEntity(n) && wakeOnPush) {
                        RegionScheduler.wakeWorkers(1);
                    }
                }
            }
        }
    }
}
