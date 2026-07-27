package com.axalotl.fabrolia.common.regions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A2 — one color-DAG scheduling timeline: an epoch counter plus the
 * entity-phase barrier armed against it.
 *
 * <p>Every {@link Region} belongs to exactly one domain ({@link Region#domain});
 * ALL eligibility, completion and barrier bookkeeping compare the region's
 * epoch stamps against ITS domain — never a global counter. Sync mode
 * (asyncIslands off): a single instance per level
 * ({@code RegionizedServer.globalDomain}) — field-for-field the state that
 * used to live directly on RegionizedServer, so behavior is identical.
 * Async mode: each persistent island ({@link IslandRegistry.PIsland#domain})
 * advances its own epoch once per island tick unit, so two islands ticking
 * at different rates never share a barrier, an eligibility horizon, or an
 * entity tick token. Unpopulated gap regions stay on the global domain.
 *
 * <p>The island separation invariant (≥2 unpopulated cells between islands,
 * radius-2 connectivity) guarantees a region's radius-1 ColorGate neighbors
 * are either same-domain or gap regions; cross-domain neighbors are excluded
 * from the gate (see ColorGate) because no same-tick causal chain crosses
 * the gap.
 *
 * <p>Stamp discipline: a region's doneEpoch/entityEpoch/groupedEpoch and the
 * CAS gates (entityEnqueuedEpoch/entityBarrierEpoch) are meaningful only in
 * the timeline of the domain that stamped them. Whoever reassigns
 * {@link Region#domain} MUST reset all five to the new domain's current
 * epoch (RegionizedServer.assignDomain), and may only do so at a safe point
 * (no unit/dispatch in flight for either domain).
 */
final class EpochDomain {

    private static final long TICK_TOKEN_EPOCH_MASK = (1L << 48) - 1L;
    /** Distinct prefix per domain: an entity's dedup token can never collide
     *  across domains even when two timelines pass through equal epoch values
     *  (e.g. after a merge/split reassignment). Starts at 1L<<48 so token 0
     *  (the unmarked state) is never produced. */
    private static final AtomicLong NEXT_TICK_TOKEN_PREFIX = new AtomicLong(1L << 48);

    /** Color-DAG epoch — bumped once per drive (level tick in sync mode,
     *  island tick unit in async mode), read by the eligibility gate and
     *  completion marks of THIS domain's regions. */
    volatile long epoch;

    /** Epoch whose entity dispatch has run (groupedEpochs stamped, sweep done).
     *  Done-publication is FORBIDDEN before this matches {@link #epoch}: until
     *  dispatch decides who owes an entity phase, "complete" is undecidable —
     *  the early-done window was the certified crossUNSAFE leak (2026-06-12). */
    volatile long dispatchedEpoch;

    /** Completed by the LAST owing region's barrier contribution (R5 event path). */
    volatile CompletableFuture<Void> entityBarrier;

    /** Owing regions still to contribute this epoch. */
    final AtomicInteger entityPending = new AtomicInteger();

    /** Epoch the current barrier was armed for — contributions for any other
     *  epoch (e.g. after a mid-session scheduler toggle flip) are refused. */
    volatile long entityBarrierArmedEpoch = -1L;

    /** Watchdog anchor for stall forensics. */
    volatile long entityBarrierArmedNanos;

    /** Cross-region effects deferred INTO this timeline (CrossRegionGuard,
     *  routed by target position). Global domain: replayed by main after the
     *  phase pump (the pre-A2 behavior, unchanged in sync mode). Island
     *  domains: replayed by the island's unit coordinator between its entity
     *  barrier and its BE phase — same island-internal ordering the main
     *  replay gave ([entities → replay → BEs]), all member regions quiescent,
     *  and no other island reachable by construction. MPSC-ish: workers offer
     *  during phases, exactly one drainer per domain per tick. */
    /** Cross-dimension arrival staging (2026-07-15 cohort atomicity): the
     *  destination-side halves of teleports executed during THIS domain's
     *  phases, sealed into one cohort batch at phase end. */
    /** Vanilla ServerLevel.handlingTick equivalent for THIS timeline
     *  (2026-07-15 piston D1 fix): true while the unit runs its scheduled-
     *  tick + block-event segments (vanilla phases 0-6), false during the
     *  entity/BE segments (vanilla 7-10). PistonBaseBlock.checkIfExtend's
     *  retract-code disambiguation (b0=1 pull-back vs b0=2 TRIGGER_DROP)
     *  reads isHandlingTick — un-redirected, island workers read MAIN's
     *  flag at an arbitrary point of MAIN's own tick: a per-cycle coin flip
     *  that yanked duper sand back instead of dropping it (source loss in
     *  ~5 cycles on the production machine). */
    volatile boolean handlingTickPhase;

    final java.util.concurrent.ConcurrentLinkedQueue<CrossDimArrivals.StagedOp> crossDimStage =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    final java.util.concurrent.ConcurrentLinkedQueue<Runnable> deferredOps =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** A2 L-clock: the island-local game time presented to getGameTime readers
     *  executing under this domain (set to L+1 at unit dispatch — the tick
     *  being executed, matching vanilla's increment-at-head convention; stays
     *  put between units, where it equals the completed-ticks count, exactly
     *  what vanilla getGameTime reads between ticks). 0 = no unit has ever run
     *  (global timeline / sync mode) → readers fall through to the level's
     *  real game time. */
    volatile long unitGameTime;

    // Flight-recorder streak state (coordinator-only writes, one unit at a time):
    // strong-signal gating so production never dumps for expected fringe aging.
    long overdueStreakChunkB; int overdueStreakB;
    long overdueStreakChunkF; int overdueStreakF;
    int budgetExhaustStreak;

    /** The island this timeline belongs to (null = the global domain). Lets
     *  registration-time seams route by execution context (e.g. mid-unit BE
     *  registrations into the island's same-tick pending list).
     *  audit13: volatile — written once at island construction, but READ from
     *  arbitrary threads with no other happens-before edge (vibration emitters
     *  via VibrationDomains, packet-routing threads via
     *  RegionalPlayerTicks.islandFor, cross-dim route()'s owner resolution). A
     *  reader reaching the domain through a non-volatile path could observe
     *  null and mis-resolve ownership (wall-clock stamp / global-lane detour)
     *  for that read; every sibling field consumed cross-thread here is
     *  already volatile. */
    volatile IslandRegistry.PIsland owner;

    private volatile long tickTokenPrefix = NEXT_TICK_TOKEN_PREFIX.getAndAdd(1L << 48);

    /**
     * audit10 — adopt {@code pred}'s CURRENT tick identity, so a mark stamped before
     * an island transition still compares as already-ticked immediately after it.
     *
     * <p>Both halves are required. {@link #tickToken()} is
     * {@code tickTokenPrefix | (epoch & MASK)}: inheriting the prefix alone leaves
     * the epochs free to differ, and the token would still not match.
     *
     * <p>This CANNOT freeze an entity. The token embeds the epoch, so the
     * successor's very next {@code epoch++} yields a different token and every
     * entity is eligible again. An inherited mark can therefore suppress at most
     * the single unit that was already accounted for under the predecessor — never
     * a later one.
     *
     * <p>Called at a safe point during rebuild/split, before the successor is
     * dispatched, so no worker is reading these fields concurrently.
     */
    void adoptTickIdentityFrom(EpochDomain pred) {
        if (pred == null || pred == this) return;
        this.tickTokenPrefix = pred.tickTokenPrefix;
        this.epoch = pred.epoch;
    }

    /** Per-entity dedup token for the current epoch (an entity migrating
     *  between two regions ticking in the same epoch runs exactly once). */
    long tickToken() {
        return tickTokenPrefix | (epoch & TICK_TOKEN_EPOCH_MASK);
    }
}
