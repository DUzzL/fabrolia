package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Clock-domain coherence for Lithium's SLEEPING BLOCK ENTITIES (the
 * never-waking-hopper fix, 2026-08-04) — the block-entity sibling of the
 * vibration-candidate translation in {@link VibrationDomains}.
 *
 * <p>THE DEFECT. Lithium's {@code SleepingBlockEntity.sleepOnlyCurrentTick()}
 * swaps a hopper's tick invoker for a {@code SleepUntilTimeBlockEntityTickInvoker}
 * stamped {@code world.getGameTime() + 1} on the STAMPING thread, and the
 * invoker later wakes on {@code getGameTime() >= deadline} evaluated on the
 * TICKING thread. Vanilla has one clock, so "+1" is always the next tick.
 * Under async islands {@code getGameTime()} is context-dependent
 * ({@link IslandClock#contextTime()}): an island's members read its logical
 * time L, everything else reads the wall counter W, and L trails W by every
 * stall the island ever absorbed. Two ways the deadline lands on the wrong
 * number line, both observed in the farm-hopper field report:
 * <ul>
 *   <li>the stamp runs OUTSIDE the hopper's island (a main-lane insert, a
 *       cross-region drain on main): deadline = W+1, evaluated against L —
 *       unreachable for W−L ticks, effectively forever on a long-lived
 *       lagging island;</li>
 *   <li>the hopper's island identity changes under the stamp: new islands
 *       are BORN at raw game time ({@code IslandRegistry.PIsland}), merges
 *       keep the SURVIVOR's clock and rebase the absorbed side — but the
 *       deadline lives inside a Lithium record that
 *       {@code TickDomains.rebaseTo}/{@code rebaseScheduledTick} cannot see.
 *       A player corridor of fresh terrain (L ≈ W) merging into an old
 *       machine island (L ≪ W) leaves every mid-sleep deadline thousands of
 *       ticks in the future.</li>
 * </ul>
 * The wrapper keeps ticking, the block entity looks alive, but
 * {@code delegate.tick()} never runs: the hopper never pulls again.
 *
 * <p>THE FIX, two halves, both biased toward WAKING (an early wake degrades
 * to vanilla — the hopper simply ticks; a late wake IS the bug):
 * <ul>
 *   <li>STAMP by ownership, not by thread ({@link #ownerNow}): the deadline
 *       belongs to the timeline that will evaluate it — the hopper's owning
 *       island resolved by POSITION, exactly {@code VibrationDomains}'
 *       listener-stamp rule. Same-domain stamps are byte-identical to
 *       Lithium's; a foreign-thread stamp now lands one tick ahead on the
 *       hopper's own clock instead of an arbitrary other clock's.</li>
 *   <li>HEAL residue at the comparison ({@link #deadlineUnreachable}): every
 *       legal stamp is at most {@code now + }{@link #MAX_RELATIVE_SLEEP} in
 *       the evaluator's own timeline (the only producer,
 *       {@code sleepOnlyCurrentTick}, stamps now+1, and clocks are monotone),
 *       so a deadline further ahead than that can only be cross-domain
 *       residue — a pre-fix stamp, or an island merge/rebuild/toggle that
 *       moved the hopper onto an older clock after the stamp. Wake it now.
 *       This keeps the invariant even when the clock reshuffles BETWEEN
 *       stamp and evaluation, which no stamp-side rule can cover, without
 *       making sleeping wrappers visible to the rebase machinery (rejected:
 *       the record is immutable and interned nowhere — a registry of live
 *       wrappers would add lifecycle tracking for a state that lasts one
 *       tick).</li>
 * </ul>
 *
 * <p>Deliberately NOT a wall-clock deadline on both sides: under per-island
 * rate/sprint sessions an island runs several L-ticks per wall tick, and a
 * wall-based "next tick" would over-sleep the difference every single stamp;
 * the owner-clock stamp plus the residue heal is exact in every same-domain
 * case and ≤ one-tick late in every pathological one. And deliberately NOT a
 * cancel of Lithium's sleeping optimisation: same-domain behaviour (the only
 * case vanilla Lithium ever sees) is preserved bit for bit.
 *
 * <p>{@link #healSearchStamp} is the same residue rule for the OTHER
 * forever-hole in the same family: Lithium's hopper caches
 * {@code collectItemEntityAttemptTime} / {@code *FailedSearchTime} stamps of
 * {@code tickedGameTime} and shortcuts the item/inventory search while the
 * shared movement tracker reports "unchanged since stamp". A stamp left in
 * the hopper's FUTURE by a clock drop makes "unchanged" true across the one
 * movement event a landing item ever emits; the next tick refreshes the
 * stamp past that event's time and the pile is invisible forever (the
 * re-sleep listener path then never fires again — items at rest emit no
 * movement). Clamping a future stamp to {@code Long.MIN_VALUE} (Lithium's
 * own "force search" sentinel) costs one vanilla search and re-anchors the
 * stamp to the hopper's current timeline.
 */
public final class BlockEntitySleepDomains {

    /**
     * The largest relative sleep any Lithium site stamps: the sole producer of
     * {@code SleepUntilTimeBlockEntityTickInvoker} is
     * {@code SleepingBlockEntity.sleepOnlyCurrentTick()} with {@code now + 1}
     * (verified against lithium 0.25.0/0.25.1 bytecode — no other
     * constructor call sites exist in the jar). If a future Lithium version
     * stamps longer sleeps, a larger-but-legal deadline would over-wake
     * (vanilla-safe), never over-sleep.
     */
    public static final long MAX_RELATIVE_SLEEP = 1L;

    private BlockEntitySleepDomains() {}

    /**
     * Residue predicate for the invoker's wake comparison: {@code true} when
     * the deadline sits further ahead of the evaluator's clock than any legal
     * same-timeline stamp could — cross-domain residue, wake now. Exclusive
     * deadlines equal to {@code now + MAX_RELATIVE_SLEEP} are legal mid-sleep
     * states and keep sleeping (the Lithium-exact case).
     */
    public static boolean deadlineUnreachable(long evaluatorNow, long deadlineExclusive) {
        return deadlineExclusive - evaluatorNow > MAX_RELATIVE_SLEEP;
    }

    /**
     * Failed-search stamp heal, run at the head of the hopper's tick against
     * its freshly read clock: a stamp in the hopper's own FUTURE cannot have
     * been produced in its current timeline (stamps are copies of past
     * {@code tickedGameTime} values, monotone under one clock) — clamp it to
     * {@code Long.MIN_VALUE}, Lithium's own force-search value, so the next
     * tracker shortcut fails once and the stamp re-anchors.
     */
    public static long healSearchStamp(long stamp, long evaluatorNow) {
        return stamp > evaluatorNow ? Long.MIN_VALUE : stamp;
    }

    /**
     * What the OWNING venue's clock reads right now for the block entity at
     * {@code pos}, resolved by position — the sleeping-BE sibling of
     * {@link VibrationDomains#listenerStamp}. Island-owned chunk under async
     * islands → that island's current time; global lane, async off, or
     * unresolvable → the wall clock (which IS those venues' getGameTime).
     * Safe from any thread: volatile reads only, no locks, no allocation.
     */
    public static long ownerNow(ServerLevel level, BlockPos pos) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) {
            // one clock (async islands off / scheduler down): every BE-tick
            // venue reads the wall counter — vanilla stamp.
            return IslandClock.wallTime(level);
        }
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        Region region = rs.regionizer().peekByGridKey(rs.regionizer().grid.keyFor(chunkKey));
        EpochDomain d = region != null ? region.domain : null;
        IslandRegistry.PIsland owner = d != null ? d.owner : null;
        if (owner == null || owner.retired) {
            // global lane / main-owned / mid-rebuild: getGameTime there is wall.
            return IslandClock.wallTime(level);
        }
        return VibrationDomains.domainNow(owner.logicalTick, d.unitGameTime);
    }
}
