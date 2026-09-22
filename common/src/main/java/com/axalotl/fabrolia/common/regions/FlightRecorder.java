package com.axalotl.fabrolia.common.regions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;

/**
 * Bounded scheduler flight recorder (2026-07-13, non-deterministic field
 * incident: machines broke after distant generation, controlled repeat clean).
 * Records RARE structural events only — topology changes, clock deltas,
 * trigger rebases, mode transitions, invariant anomalies — never per-tick
 * work, so steady-state overhead is zero and event overhead is one string +
 * one synchronized deque op. When a machine-correctness invariant trips
 * (overdue-trigger burst, event burst, island clock discontinuity), the full
 * ring dumps once, giving the next field occurrence a self-contained trace:
 * every merge/split/rebase/toggle in the minutes leading up to the first
 * divergent transition, with clocks and deltas attached.
 */
public final class FlightRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-FlightRecorder");
    private static final int CAPACITY = 4096;
    private static final ArrayDeque<String> RING = new ArrayDeque<>(CAPACITY);
    private static volatile long lastDumpNanos;

    /** Cheap aggregate for EXPECTED dormant fringe aging (never logged
     *  per-entry; see /fabrolia regions flightrec status). */
    public static final java.util.concurrent.atomic.LongAdder DORMANT_REFUSED =
            new java.util.concurrent.atomic.LongAdder();
    /** Exact-gate refusals/drift that held an island at the same candidate L.
     *  Always-on aggregate; individual records are rate-bounded by
     *  {@link IslandScheduledTicks}. */
    public static final java.util.concurrent.atomic.LongAdder SCHEDULED_HOLDS =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAccumulator DORMANT_MAX_AGE =
            new java.util.concurrent.atomic.LongAccumulator(Math::max, 0);

    public static void noteDormantAge(long age) {
        DORMANT_MAX_AGE.accumulate(age);
    }

    /** One-line summary for the status command. */
    public static String status() {
        synchronized (RING) {
            return "flightrec ring=" + RING.size() + "/" + CAPACITY
                    + " dormantRefused=" + DORMANT_REFUSED.sum()
                    + " dormantMaxAge=" + DORMANT_MAX_AGE.get()
                    + " scheduledHolds=" + SCHEDULED_HOLDS.sum()
                    + " | " + "delivery: see chunksender status";
        }
    }

    /** Explicit operator dump — bypasses the rate limit, INFO level. */
    public static String dumpNow() {
        StringBuilder sb = new StringBuilder(16384);
        synchronized (RING) {
            for (String line : RING) {
                sb.append("\n    ").append(line);
            }
        }
        LOGGER.info("[flight-recorder] operator dump ({} events):{}",
                sb.length() == 0 ? 0 : RING.size(), sb);
        return "dumped " + RING.size() + " events to server log";
    }

    public static String clear() {
        int n;
        synchronized (RING) {
            n = RING.size();
            RING.clear();
        }
        DORMANT_REFUSED.reset();
        SCHEDULED_HOLDS.reset();
        return "cleared " + n + " ring events";
    }

    private FlightRecorder() {}

    /** Append one pre-formatted event. Callers format only on rare events. */
    public static void record(String event) {
        String line = System.currentTimeMillis() + " " + event;
        synchronized (RING) {
            if (RING.size() >= CAPACITY) {
                RING.pollFirst();
            }
            RING.addLast(line);
        }
    }

    /** Diagnostics master switch — {@code /fabrolia regions async debug on|off}.
     *  OFF (default): burst detectors are inert and invariant ring dumps are
     *  suppressed (corrective actions still log their own one-line message).
     *  A large TNT flying machine legitimately runs 68-80 block events per
     *  unit — with this off, normal contraptions produce zero log noise. */
    public static volatile boolean DEBUG = false;

    /** Dump the ring once per minute at most — invariant failures only.
     *  No-op unless {@link #DEBUG} is on. */
    public static void dump(String reason) {
        if (!DEBUG) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastDumpNanos < 60_000_000_000L) {
            return;
        }
        lastDumpNanos = now;
        StringBuilder sb = new StringBuilder(16384);
        synchronized (RING) {
            for (String line : RING) {
                sb.append("\n    ").append(line);
            }
        }
        LOGGER.error("[flight-recorder] INVARIANT TRIPPED: {} — ring ({} events):{}",
                reason, sb.length() == 0 ? 0 : RING.size(), sb);
    }
}
