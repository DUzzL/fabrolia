package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Clock-domain ownership of a player's warden warning cooldown (warden switch
 * fix, 2026-11-01) — the {@code WardenSpawnTracker} sibling of
 * {@link VibrationDomains}.
 *
 * <p>THE DEFECT. Vanilla arms {@code WardenSpawnTracker.cooldownTicks = 200}
 * when a shrieker warns a player and counts it down in that player's own tick.
 * A "warden switch" (Shulkercraft / madman2572) parks the player's arrow on a
 * piston that a sculk sensor drives: every landing reaches a {@code can_summon}
 * shrieker, which warns the arrow's owner wherever they are. The loop period
 * (sensor 30 + cooldown 10 = 40 ticks) divides 200, so in vanilla the switch
 * re-arms the cooldown in the very tick it runs out, and real shriekers in
 * other ancient cities never see the player off cooldown. Under async islands
 * the switch island arms the cooldown while the player's island counts it
 * down. The two clocks are not in lockstep: when the player's island is one
 * tick behind at the switch's re-arm tick, the switch still reads 1, skips the
 * warning and only re-arms 40 ticks later; when the switch island is behind,
 * the player's counter reaches 0 before the re-arm. Either way the player is
 * unprotected for about one loop period every cycle (measured: up to 47 ticks
 * every 10 s with the player on another island; none with the player on the
 * switch's island or with {@code -Dfabrolia.syncMode=true}).
 *
 * <p>THE FIX. An arm records the clock that armed it (the island timeline
 * that owns the warning shrieker, or the wall counter for the global lane)
 * and that clock's reading. While the record is readable, every reader
 * measures the cooldown in that clock, exactly as vanilla measures it in the
 * one clock it has: on cooldown while fewer than 200 of the arming clock's
 * ticks have passed. The vanilla counter is still written and ticked, so
 * {@code /data} and saves stay vanilla, and it decides whenever no record
 * applies: async islands off, an arm made outside a warning, or an arming
 * clock that was retired or rebased (the record is unreadable then).
 * A reader on another island counts only the arming clock's completed ticks,
 * so it never sees the cooldown end inside the tick that re-arms it. An
 * arming island that stops advancing ends the cooldown after
 * {@link #PLAYER_TICK_BOUND} of the player's own ticks at the latest.
 * Deviation: such a reader sees the cooldown end up to one of the arming
 * clock's ticks later than a reader on that clock (named in the release
 * notes).
 */
public final class WardenWarningClock {

    /** {@code WardenSpawnTracker.WARNING_LEVEL_INCREASE_COOLDOWN}. */
    public static final int COOLDOWN_TICKS = 200;

    /** No readable arming clock: the vanilla counter decides. */
    public static final long UNREADABLE = -1L;

    /**
     * The clock that armed a cooldown and its reading at that moment.
     * {@code domain == null} means the level's wall counter.
     */
    public record Arm(ServerLevel level, EpochDomain domain, IslandRegistry.PIsland owner, long stamp) {}

    /** Duck on {@code WardenSpawnTracker}: the arm record its cooldown follows. */
    public interface Tracked {
        Arm fabrolia$warningArm();
    }

    /** The clock of the warning in progress on this thread (null: none or async off). */
    private static final ThreadLocal<Arm> WARNING = new ThreadLocal<>();

    private WardenWarningClock() {}

    /**
     * The clock that owns a warning shrieker at {@code pos} right now, read at
     * this moment; null when async islands are off (one clock, the vanilla
     * counter is exact).
     */
    public static Arm clockAt(ServerLevel level, BlockPos pos) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null) {
            return null;
        }
        EpochDomain d = VibrationDomains.islandDomainAt(rs, Vec3.atCenterOf(pos));
        if (d == null) {
            return new Arm(level, null, null, IslandClock.wallTime(level));
        }
        IslandRegistry.PIsland owner = d.owner;
        return new Arm(level, d, owner, VibrationDomains.domainNow(owner.logicalTick, d.unitGameTime));
    }

    /** Opens a warning scope on this thread; returns the scope it replaces. */
    public static Arm beginWarning(Arm clock) {
        Arm previous = WARNING.get();
        WARNING.set(clock);
        return previous;
    }

    /** Closes the scope opened by {@link #beginWarning}. */
    public static void endWarning(Arm previous) {
        if (previous == null) {
            WARNING.remove();
        } else {
            WARNING.set(previous);
        }
    }

    /** The clock of the warning in progress on this thread, or null. */
    public static Arm currentWarning() {
        return WARNING.get();
    }

    /**
     * Ticks the arming clock has advanced since {@code arm}, as seen by a
     * reader whose own clock is {@code reader} (null: no warning in progress),
     * or {@link #UNREADABLE} when there is no record or its clock can no
     * longer be read as the same timeline (retired or replaced island, a
     * clock that moved backwards).
     *
     * <p>A reader on the arming clock counts the tick it is executing, as
     * vanilla does. Any other reader counts only completed ticks of the
     * arming clock: the tick in progress there may still re-arm (the switch
     * re-arms in its block-entity pass, late in the unit), and a foreign
     * reader must not see the gap before that.
     */
    public static long elapsed(Arm arm, Arm reader) {
        if (arm == null) {
            return UNREADABLE;
        }
        boolean sameClock = reader != null && reader.level() == arm.level() && reader.domain() == arm.domain();
        long now;
        if (arm.domain() == null) {
            // The wall counter advances at the head of Main's level tick and
            // cannot tell "in progress" from "completed": a foreign reader
            // counts one tick less (at worst one tick of extra cooldown).
            now = IslandClock.wallTime(arm.level()) - (sameClock ? 0L : 1L);
        } else {
            IslandRegistry.PIsland owner = arm.owner();
            if (owner == null || owner.retired || arm.domain().owner != owner) {
                return UNREADABLE;
            }
            now = sameClock
                    ? VibrationDomains.domainNow(owner.logicalTick, arm.domain().unitGameTime)
                    : owner.logicalTick;
        }
        long elapsed = now - arm.stamp();
        return elapsed < 0 ? UNREADABLE : elapsed;
    }

    /**
     * Upper bound of an arm in the player's own ticks: an arming island that
     * stays alive but stops advancing (frozen rate, held units) must not keep
     * the player protected forever, where vanilla's counter would have run out.
     * Twice the cooldown leaves room for an arming island that runs slower
     * than the player's.
     */
    public static final int PLAYER_TICK_BOUND = 2 * COOLDOWN_TICKS;

    /**
     * The cooldown rule: with a readable arming clock, on cooldown while fewer
     * than {@link #COOLDOWN_TICKS} of its ticks have passed, and never past
     * {@link #PLAYER_TICK_BOUND} of the player's ticks since the warning;
     * otherwise the vanilla counter decides.
     */
    public static boolean onCooldown(long elapsed, int cooldownTicks, int ticksSinceLastWarning) {
        return elapsed == UNREADABLE
                ? cooldownTicks > 0
                : elapsed < COOLDOWN_TICKS && ticksSinceLastWarning < PLAYER_TICK_BOUND;
    }
}
