package com.axalotl.fabrolia.common.regions;

/**
 * A2 L-clock context resolution — the execution-context side of the island
 * getGameTime redirect (docs/ASYNC-ISLANDS.md timing model).
 *
 * <p>Vanilla already splits the clocks: {@code getGameTime()} is the monotonic
 * L-lane every relative mechanic consumes (LevelTicks trigger comparisons,
 * hopper cooldowns, brain memory expiry), while the day/weather calendar rides
 * the 26.2 clock system ({@code getOverworldClockTime()} etc. — the W lane,
 * untouched). When async islands tick independently, L-lane readers executing
 * FOR an island must see that island's logical time, not the global counter —
 * this class answers "which timeline is the current thread executing in?".
 *
 * <p>Resolution order: a region worker holding a region claim answers with the
 * claimed region's domain (covers entity phases, helper fan-out batches, and
 * queue drains — drains between units read the completed-ticks value, exactly
 * vanilla's between-ticks semantics); a unit-coordinator segment without a
 * claim answers with the published {@code currentDomain}. Everything else —
 * main thread, chunk workers, IO — reads the level's real game time (W-lane
 * callers and sync mode are byte-identical: {@code unitGameTime} is only ever
 * non-zero on island domains that have dispatched a unit).
 */
public final class IslandClock {

    /** ServerLevel.isHandlingTick redirect (piston D1): a region worker
     *  executing an island unit of THIS level answers from its own
     *  timeline's phase window — vanilla-deterministic again instead of
     *  racing main's flag. null = caller should use the vanilla field
     *  (main thread, cross-level query, no context). */
    public static Boolean workerHandlingTick(net.minecraft.server.level.ServerLevel level) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread w)) {
            return null;
        }
        EpochDomain d = w.currentRegion != null && w.currentRegion.domain != null
                ? w.currentRegion.domain : w.currentDomain;
        if (d == null) {
            return null;
        }
        if (d.owner != null) {
            if (d.owner.level != level) {
                return null; // cross-level query: vanilla semantics
            }
            return d.handlingTickPhase;
        }
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs != null && rs.ownsGlobalDomain(d)) {
            return d.handlingTickPhase;
        }
        return null;
    }


    private IslandClock() {}

    /** The WALL clock for {@code level}, safe from any thread and any
     *  dimension. Nether/End levels carry {@code DerivedLevelData} (a wrapper
     *  around the overworld's primary data) — casting their getLevelData()
     *  to RawGameTime crashed the End ticket purge (rig r2, 2026-07-18);
     *  and calling getGameTime() from a worker context returns the island
     *  L-clock. This unwraps to the primary data's raw counter. */
    public static long wallTime(net.minecraft.server.level.ServerLevel level) {
        if (level.getLevelData() instanceof RawGameTime r) {
            return r.fabrolia$rawGameTime();
        }
        var server = level.getServer();
        if (server != null
                && server.overworld().getLevelData() instanceof RawGameTime r2) {
            return r2.fabrolia$rawGameTime();
        }
        return level.getGameTime();
    }

    /** Island-local game time for the current execution context, or -1 when
     *  the context is global (caller falls through to the real counter).
     *  Hot path: two reference loads on worker threads, one type check on
     *  everything else; no allocation. */
    public static long contextTime() {
        if (!(Thread.currentThread() instanceof RegionWorkerThread w)) {
            return -1L;
        }
        Region r = w.currentRegion;
        if (r != null) {
            EpochDomain d = r.domain;
            if (d != null) {
                long t = d.unitGameTime;
                if (t != 0) return t;
            }
            return -1L;
        }
        EpochDomain d = w.currentDomain;
        if (d != null) {
            long t = d.unitGameTime;
            if (t != 0) return t;
        }
        return -1L;
    }
}
