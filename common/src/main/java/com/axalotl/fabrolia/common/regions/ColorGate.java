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
 * <p>The accepted dispatch/entity decision is also the queue phase cutoff.
 * A grouped participant may publish completion once its entity phase has
 * committed; queue/help work admitted after that point remains queued for the
 * next epoch. Regions outside the accepted dispatch plan are swept done at the
 * plan cutoff and retain any later admission for that same next dispatch.
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
        EpochDomain d = r.domain;
        if (d == null) {
            return false;
        }
        long epoch = d.epoch;
        // Global-domain regions need this explicit self-completion check;
        // island regions also get it from inUnitWindow. It is the post-CAS
        // stale-precheck guard paired with Region's DONE_SEAL handshake.
        if (r.doneEpoch >= epoch || r.hasSealedDone(epoch)) {
            return false;
        }
        // Queue drain precedes the entity body.  Once that body has committed,
        // this region's current-E phase is closed even if a ready duplicate or
        // a late queue admission is visible before the DONE_SEAL tail.  Such
        // work belongs to E+1 and must not reclaim the region after entities.
        if (r.entityEpoch >= epoch) {
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
     * Completion attempt: a grouped entity phase ran and its queues drained,
     * or an ungrouped region crossed the accepted dispatch cutoff → publish
     * done + re-arm dependents. Carries every refusal guard, so any thread may
     * call it blindly on any region (the heal sweep does exactly that). Safe
     * to call repeatedly.
     */
    public static boolean tryMarkDone(Region r, RegionizedServer rs) {
        return tryMarkDone(r, rs, true);
    }

    /** {@code wakeOnPush=false} = the dispatch-sweep variant: hundreds of no-work regions
     *  complete back-to-back on main and each push would unpark a worker individually
     *  (680ms of Unsafe.unpark on the server thread, xMDQKeDhXO profile 2026-07-02);
     *  the sweep caller issues ONE flag-scan wake for everything pushed instead. */
    public static boolean tryMarkDone(Region r, RegionizedServer rs, boolean wakeOnPush) {
        if (RegionScheduler.isActive()) {
            RegionScheduler.throwWorkerFailureIfAny();
        }
        if (rs == null) {
            return false;
        }
        EpochDomain d = r.domain;
        if (d == null) return false;
        long epoch = d.epoch;
        boolean published = publishDoneIfQuiescent(r, d, epoch, true,
                rs.isQueueOnlyEntityEpoch(d, epoch));
        if (published) {
            rearmDependents(r, rs, wakeOnPush);
        }
        return r.hasSealedDone(epoch);
    }

    /**
     * Dispatch-transaction sweep. Every active participant has already been
     * offered behind the domain publication receipt, so this method publishes
     * only the trivial done stamp and never enqueues a dependent. That keeps
     * the post-offer commit section to non-allocating field/queue reads.
     */
    static boolean tryMarkDoneWithoutPush(Region r, RegionizedServer rs) {
        if (rs == null) return false;
        EpochDomain d = r.domain;
        if (d == null) return false;
        long epoch = d.epoch;
        publishDoneIfQuiescent(r, d, epoch, true,
                rs.isQueueOnlyEntityEpoch(d, epoch));
        return r.hasSealedDone(epoch);
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
        if (RegionScheduler.isActive()) {
            RegionScheduler.throwWorkerFailureIfAny();
        }
        if (rs == null) {
            return;
        }
        EpochDomain d = r.domain;
        if (d == null) return;
        long epoch = d.epoch;
        if (!publishDoneIfQuiescent(r, d, epoch, false,
                rs.isQueueOnlyEntityEpoch(d, epoch))) return;
        rearmDependents(r, rs, wakeOnPush);
    }

    /**
     * Linearize queue/help admission, region claims and done publication.
     * DONE_SEAL closes the CAS word before the second validation; queue offers
     * use the same admission monitor. A winner therefore owns either this
     * epoch's last admitted queue/claim body or the sealed done stamp, never
     * both sides of an unchecked null/empty window.
     */
    static boolean publishDoneIfQuiescent(
            Region r, EpochDomain expectedDomain, long epoch,
            boolean requireDispatchedDecision,
            boolean exactQueueOnlyEntityEpoch) {
        boolean published = false;
        Throwable failure = null;
        synchronized (r.admissionLock) {
            if (r.hasSealedDone(epoch)
                    || !doneInputsValid(r, expectedDomain, epoch,
                            requireDispatchedDecision,
                            exactQueueOnlyEntityEpoch)
                    || !r.tryBeginDoneSeal(epoch)) {
                return false;
            }
            try {
                // Required second read after CAS(null -> DONE_SEAL). The first
                // validation is only a fast refusal; this one owns authority.
                if (doneInputsValid(r, expectedDomain, epoch,
                        requireDispatchedDecision,
                        exactQueueOnlyEntityEpoch)) {
                    if (RegionScheduler.isActive()) {
                        RegionScheduler.commitSuccessIfHealthy(
                                () -> r.commitDoneSeal(expectedDomain, epoch));
                    } else {
                        r.commitDoneSeal(expectedDomain, epoch);
                    }
                    published = true;
                }
            } catch (Throwable completionFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        completionFailure);
            } finally {
                try {
                    r.releaseDoneSeal();
                } catch (Throwable releaseFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            mergeFailure(failure, releaseFailure));
                }
            }
        }
        if (failure != null) ColorGate.<RuntimeException>rethrow(failure);
        return published;
    }

    private static boolean doneInputsValid(
            Region r, EpochDomain expectedDomain, long epoch,
            boolean requireDispatchedDecision,
            boolean exactQueueOnlyEntityEpoch) {
        if (r.domain != expectedDomain || expectedDomain.epoch != epoch) {
            return false;
        }
        // Pre-dispatch, completion is undecidable: grouping may still make
        // this region owe the current entity phase.
        boolean dispatched = expectedDomain.dispatchedEpoch == epoch;
        if (requireDispatchedDecision && !dispatched) {
            return false;
        }
        boolean grouped = r.groupedEpoch == epoch;
        // A grouped participant's committed entity body is an exact current-E
        // cutoff in every domain. Any queue/help admission retained on the far
        // side belongs to E+1 and must not be consumed by a ready duplicate.
        if (requireDispatchedDecision && dispatched
                && grouped && r.entityEpoch >= epoch) {
            return true;
        }
        // An island unit also owns an exact accepted-plan cutoff for a region
        // it did not group. Main never awaits island-queue work (executeAt
        // tasks routed into an island region drain in that island's next
        // unit): retained work is re-seeded by the next unit. The global lane
        // is deliberately excluded because its queue may carry a future
        // physical Main is awaiting in this tick; it must drain before
        // current-E done.
        if (requireDispatchedDecision && dispatched
                && !grouped && expectedDomain.owner != null) {
            return true;
        }
        // A grouped participant before its entity commit retains the strict
        // transaction: all queue/help work must drain and the entity body must
        // finish before DONE_SEAL.  The unconditional retirement path also
        // never seals over an accepted queue admission.
        if (!r.queue().isEmpty() || !r.helpQueue.isEmpty()) {
            return false;
        }
        if (!requireDispatchedDecision) {
            return true;
        }
        // Global ungrouped work is strict-empty above and owes no entity body.
        // Exact-player queue-only groups likewise owe no entity body, but the
        // explicit token may authorize completion only after the same strict
        // physical-empty check. Ordinary grouped participants still require
        // their successful entity publication.
        return !grouped || r.entityEpoch >= epoch
                || exactQueueOnlyEntityEpoch;
    }

    private static void rearmDependents(
            Region r, RegionizedServer rs, boolean wakeOnPush) {
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
                if (RegionScheduler.isActive()) {
                    RegionScheduler.throwWorkerFailureIfAny();
                }
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
                EpochDomain neighborDomain = n.domain;
                if (!n.queue().isEmpty() || !n.helpQueue.isEmpty()) {
                    if (RegionScheduler.tryEnqueueReady(n) && wakeOnPush) RegionScheduler.wakeWorkers(1);
                } else if (neighborDomain.isEventEntityEpoch(neighborDomain.epoch)
                        && rs.owesEntityPhase(n) && eligible(n, rs)) {
                    // R5 event push: this completion may have been n's LAST lower-color
                    // blocker — deliver readiness NOW instead of letting a group
                    // continuation poll for it. When two blockers finish concurrently,
                    // volatile-write/read total order guarantees at least one of them
                    // sees the other's doneEpoch and passes eligible(); the CAS gate
                    // in tryEnqueueEntity dedupes if both do.
                    // Duplicate ready entries are benign; avoiding a separate
                    // entityEnqueuedEpoch CAS keeps the successor publication
                    // wholly behind enqueueReady's global receipt cut.
                    if (RegionScheduler.tryEnqueueReady(n) && wakeOnPush) RegionScheduler.wakeWorkers(1);
                }
            }
        }
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                if (primary.getSuppressed().length < 8) {
                    primary.addSuppressed(secondary);
                }
            } catch (Throwable ignored) {
                // Diagnostic suppression must never replace the selected W.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
