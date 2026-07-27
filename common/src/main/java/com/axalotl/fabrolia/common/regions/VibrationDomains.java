package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Clock-domain translation for VIBRATION CANDIDATES (the sculk deafness fix,
 * 2026-07-27) — the {@code VibrationSelector} sibling of the task-#31
 * scheduled-tick rebase in {@code IslandCommands.rebaseScheduledTick}.
 *
 * <p>THE DEFECT. Vanilla's {@code VibrationSystem.Listener.scheduleVibration}
 * stamps every candidate with {@code level.getGameTime()} evaluated on the
 * EMITTING thread, and {@code VibrationSystem.Ticker} later selects it with
 * {@code chosenCandidate(getGameTime())} evaluated on the LISTENER's ticking
 * thread — selection requires {@code candidate.tick < now} STRICTLY, and a
 * pending candidate whose stamp differs from a newcomer's suppresses the
 * newcomer entirely ({@code VibrationSelector.shouldReplaceVibration} returns
 * false for {@code tickTime != previousTick}). Vanilla has one clock, so a
 * stamp is never more than one tick from "now". Under async islands there are
 * MANY clocks: main reads the wall counter W while an island's members read
 * its logical time L, and L trails W permanently by every stall the island
 * ever absorbed (units run at &le;20&nbsp;Hz; a gap never closes — see
 * {@code IslandRegistry.PIsland}: "only real overruns open a gap").
 *
 * <p>Consequence, exactly the operator's field report: a PLAYER event emitted
 * on main (playerticks off: all of them — movement STEP from
 * {@code handleMovePlayer}, every doTick event) carries a WALL stamp W into a
 * sensor whose island trails at L &lt; W. {@code W < L} stays false for W−L
 * ticks — seconds on a fresh local server (the observed delay + stutter),
 * effectively forever on a long-lived multiplayer world (the observed total
 * deafness, curable only by an island rebuild jumping the clock). Meanwhile
 * the future-stamped candidate POISONS the selector: island-local mob events
 * with correct L stamps are discarded because a pending candidate exists with
 * a different stamp — which is why non-player vibrations mostly kept working
 * (no main-stamped candidate in the slot) while player vibrations died.
 *
 * <p>THE FIX (ownership, not polling): a candidate belongs to the LISTENER's
 * data — it must be expressed in the LISTENER's timeline, whatever thread
 * emitted it. {@link #listenerStamp} answers "what does the listener's own
 * clock read right now", resolved by the listener's position:
 * island-owned chunk → that island's current time ({@code unitGameTime}
 * mid-unit, completed {@code logicalTick} between units); global lane, async
 * off, or unresolvable → the wall clock (which IS those venues' getGameTime).
 * In every same-domain case the returned value equals what vanilla would have
 * stamped — the translation is a strict no-op there, byte-identical timing.
 * Cross-domain, delivery becomes "the listener's next tick", which is exactly
 * vanilla's envelope (emit during tick T → select at T+1 → travel time).
 *
 * <p>Residue healing lives in {@code VibrationSelectorMixin}: a pending stamp
 * STRICTLY AHEAD of a newly computed listener-domain stamp is unreachable in
 * a single-domain history (stamps are monotone in the listener clock), so it
 * can only be cross-domain residue — pre-fix saves, or an island merge that
 * moved the listener onto an older clock — and is cleared rather than left to
 * suppress live events forever. Nothing is ever dropped: the fresh candidate
 * takes the slot and delivers on the listener's next tick.
 */
public final class VibrationDomains {

    /** "No pending candidate" sentinel for the selector-side stamp mirror. */
    public static final long NO_STAMP = Long.MIN_VALUE;

    private VibrationDomains() {}

    /**
     * The current time of a domain given its two published counters. Between
     * units {@code unitGameTime} still holds the LAST unit's value (== the
     * completed {@code logicalTick}); mid-unit it holds {@code logicalTick+1};
     * before the first unit it is 0 (never published) — max() answers all
     * three shapes without a flag.
     */
    public static long domainNow(long completedLogicalTicks, long unitGameTime) {
        return Math.max(completedLogicalTicks, unitGameTime);
    }

    /**
     * Selector-heal predicate: a pending candidate stamped STRICTLY after the
     * listener-domain "now" of a new candidate can never have been produced in
     * the listener's own timeline (its stamps are monotone non-decreasing and
     * &le; now) — it is cross-domain residue and would suppress live vibration
     * candidates until the listener clock catches up to a foreign clock, which
     * on a lagging island is never.
     */
    public static boolean pendingIsFutureResidue(long pendingStamp, long newListenerStamp) {
        return pendingStamp != NO_STAMP && pendingStamp > newListenerStamp;
    }

    /**
     * What the LISTENER's own clock reads right now, resolved from the
     * listener's position. Safe from any thread: volatile reads only, no
     * locks, no allocation on the resolved path.
     */
    public static long listenerStamp(ServerLevel level, Vec3 listenerPos) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null) {
            // async islands off (or scheduler down): one clock — vanilla stamp.
            return IslandClock.wallTime(level);
        }
        long chunkKey = ChunkPos.pack(
                net.minecraft.util.Mth.floor(listenerPos.x) >> 4,
                net.minecraft.util.Mth.floor(listenerPos.z) >> 4);
        Region region = rs.regionizer().peekByGridKey(rs.regionizer().grid.keyFor(chunkKey));
        EpochDomain d = region != null ? region.domain : null;
        IslandRegistry.PIsland owner = d != null ? d.owner : null;
        if (owner == null || owner.retired) {
            // global lane / main-owned / mid-rebuild: those venues' getGameTime
            // is the wall counter.
            return IslandClock.wallTime(level);
        }
        return domainNow(owner.logicalTick, d.unitGameTime);
    }
}
