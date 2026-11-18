package com.axalotl.fabrolia.common.regions;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Effect summary of a command line (and its call-graph closure) executed with
 * {@code @s} bound to one partition-owned entity.
 *
 * <p>{@link #globalReason} non-null means the body is NOT owner-local: it may
 * touch state outside the executing entity's partition (fake-player writes,
 * storage writes, team changes, unbounded selectors, macros, unknown commands,
 * ...). Read/write keys feed the Main-resident conflict rule:
 * {@code score:<objective>}, {@code tag:<name>}, {@code team}, {@code adv},
 * {@code inventory}, {@code entitydata}, {@code effects}, {@code xp},
 * {@code attribute}, {@code storage:<id>}, {@code fake:<holder>:<objective>}.
 */
final class FunctionEffects {

    /** One fake-player (name-only holder) score read by a body. */
    record FakeScore(String holder, String objective) {}

    String globalReason;
    /**
     * Non-null when the closure writes the server TimerQueue ({@code schedule}).
     * Vanilla keys that queue on the world tick and dedupes {@code (id, time)},
     * which assumes the writing line runs once per world tick. An island head
     * runs its root once per ISLAND tick and Main drains those escalations in
     * bursts, so a per-tick root holding such a line cannot be partitioned
     * (FunctionPlanner.classifyRootLine keeps it Main-resident).
     */
    String timerReason;
    final Set<String> reads = new LinkedHashSet<>();
    final Set<String> writes = new LinkedHashSet<>();
    final Set<FakeScore> fakeReads = new LinkedHashSet<>();
    /** Body sends to vanilla's default viewer list (all players). */
    boolean playerSnapshot;

    boolean isGlobal() {
        return globalReason != null;
    }

    void global(String where, String why) {
        if (globalReason == null) globalReason = where + ": " + why;
    }

    /** A TimerQueue write: global (Main-only state) and root-blocking. */
    void timerQueue(String where, String why) {
        global(where, why);
        if (timerReason == null) timerReason = where + ": " + why;
    }

    void read(String key) {
        reads.add(key);
    }

    void write(String key) {
        writes.add(key);
    }

    void fakeRead(String holder, String objective) {
        fakeReads.add(new FakeScore(holder, objective));
        reads.add("fake:" + holder + ":" + objective);
    }

    void addAll(FunctionEffects other) {
        if (other.globalReason != null && globalReason == null) globalReason = other.globalReason;
        if (other.timerReason != null && timerReason == null) timerReason = other.timerReason;
        reads.addAll(other.reads);
        writes.addAll(other.writes);
        fakeReads.addAll(other.fakeReads);
        playerSnapshot |= other.playerSnapshot;
    }
}
