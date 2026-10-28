package com.axalotl.fabrolia.common.parallelised.utils;

import java.util.concurrent.atomic.LongAdder;

/**
 * Global "anything-moved-or-changed" counter used to short-circuit VMP's
 * {@code NearbyEntityTracking.tick0} when nothing has happened.
 *
 * <p>Bumped on:
 * <ul>
 *   <li>{@code PersistentEntitySectionManager.Callback.onMove} — entity crossed a section
 *       (i.e. its chunk pos likely changed). VMP's phase 1 reacts to chunk changes.</li>
 *   <li>VMP {@code addEntityTracker / removeEntityTracker / addPlayer / removePlayer} —
 *       structural changes that VMP's tick0 must process.</li>
 *   <li>{@code StagingTracker} additions (newly-spawned entities entering staging).</li>
 * </ul>
 *
 * <p>This is intentionally JVM-global (one counter for all dimensions). The trade-off:
 * a section-cross in one dimension forces {@code tick0} to run in every dimension's
 * {@code NearbyEntityTracking} instance — but those empty-work runs are cheap, while the
 * win when ALL dimensions are quiet (stationary bots) is a full {@code tick0} skipped.
 * Per-dimension counters would need a back-reference from {@code NearbyEntityTracking}
 * to its {@code ServerLevel} which VMP doesn't expose.
 */
public final class FabroliaTrackingDirtyFlag {
    // Every worker bumps this on each entity move: a striped adder keeps the
    // writers off one cache line. sum() includes every increment that completed
    // before it started, so a move is never missed, at most seen one read later
    // when it races the read (as with a single atomic).
    private static final LongAdder COUNTER = new LongAdder();

    static {
        COUNTER.increment();
    }

    private FabroliaTrackingDirtyFlag() {}

    public static void markDirty() { COUNTER.increment(); }

    public static long current() { return COUNTER.sum(); }
}
