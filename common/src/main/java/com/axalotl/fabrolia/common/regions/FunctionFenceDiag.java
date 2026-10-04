package com.axalotl.fabrolia.common.regions;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-tick fence causes of datapack functions ({@code /fabrolia functions fences}).
 * Counters accumulate from the last reset; rates are per Main tick since then.
 */
public final class FunctionFenceDiag {

    private FunctionFenceDiag() {}

    /** Main ticks whose tick tag had Main-resident #tick functions queued. */
    public static final LongAdder TICK_FUNCTIONS_QUEUED = new LongAdder();
    /** ... of those, ticks whose head window held the global fence. */
    public static final LongAdder TICK_HEAD_FENCED = new LongAdder();
    /** TimerQueue ticks fenced once for ≥2 due Main-resident function callbacks. */
    public static final LongAdder TIMER_DUE_FENCES = new LongAdder();
    /** Per-function fences taken by ServerFunctionManager.execute. */
    public static final LongAdder FUNCTION_FENCES = new LongAdder();
    private static final ConcurrentHashMap<Identifier, LongAdder> FUNCTION_FENCES_BY_ID =
            new ConcurrentHashMap<>();
    /** Island head root passes / escalated lines (fenced Main, routed). */
    public static final LongAdder ISLAND_ROOT_PASSES = new LongAdder();
    public static final LongAdder ISLAND_ESCALATIONS = new LongAdder();
    /** Main remainder root passes / escalated lines (fenced Main). */
    public static final LongAdder MAIN_ROOT_PASSES = new LongAdder();
    public static final LongAdder MAIN_ESCALATIONS = new LongAdder();
    public static final LongAdder MAIN_ESCALATION_FENCES = new LongAdder();
    /** Island loop callbacks re-armed on Main without executing the function. */
    public static final LongAdder LOOP_REARMS = new LongAdder();
    /** Escalated bodies dropped because a reload replaced the plan meanwhile. */
    public static final LongAdder STALE_ESCALATIONS = new LongAdder();
    /** Worker-side ServerFunctionManager.execute proven owner-local (C3). */
    public static final LongAdder WORKER_OWNER_LOCAL = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> ESCALATIONS_BY_LINE =
            new ConcurrentHashMap<>();

    private static volatile long resetTick;

    public static void functionFenced(Identifier id) {
        FUNCTION_FENCES.increment();
        FUNCTION_FENCES_BY_ID.computeIfAbsent(id, k -> new LongAdder()).increment();
    }
    /** Tick tag with Main-resident #tick functions queued; {@code fenced}: the head window held the fence. */
    public static void noteTickFunctionsQueued(boolean fenced) {
        RegionScheduler.commitSuccessIfHealthy(() -> {
            TICK_FUNCTIONS_QUEUED.increment();
            if (fenced) TICK_HEAD_FENCED.increment();
        });
    }


    static void escalated(String line) {
        ESCALATIONS_BY_LINE.computeIfAbsent(line, k -> new LongAdder()).increment();
    }

    public static void reset(long tick) {
        for (LongAdder a : new LongAdder[]{TICK_FUNCTIONS_QUEUED, TICK_HEAD_FENCED, TIMER_DUE_FENCES,
                FUNCTION_FENCES, ISLAND_ROOT_PASSES, ISLAND_ESCALATIONS, MAIN_ROOT_PASSES,
                MAIN_ESCALATIONS, MAIN_ESCALATION_FENCES, LOOP_REARMS, STALE_ESCALATIONS,
                WORKER_OWNER_LOCAL}) {
            a.reset();
        }
        FUNCTION_FENCES_BY_ID.clear();
        ESCALATIONS_BY_LINE.clear();
        resetTick = tick;
    }

    public static List<String> report(long tick) {
        long ticks = Math.max(1, tick - resetTick);
        ArrayList<String> out = new ArrayList<>();
        out.add("over " + ticks + " ticks (per tick):");
        out.add(" tick-head fence with Main-resident #tick functions queued: "
                + rate(TICK_HEAD_FENCED, ticks) + " (queued " + rate(TICK_FUNCTIONS_QUEUED, ticks) + ")");
        out.add(" TimerQueue due-functions fence: " + rate(TIMER_DUE_FENCES, ticks));
        out.add(" per-function fences: " + rate(FUNCTION_FENCES, ticks));
        top(out, FUNCTION_FENCES_BY_ID, ticks);
        out.add(" island root passes: " + rate(ISLAND_ROOT_PASSES, ticks)
                + ", island escalations (fenced): " + rate(ISLAND_ESCALATIONS, ticks));
        out.add(" Main remainder passes: " + rate(MAIN_ROOT_PASSES, ticks)
                + ", Main escalations: " + rate(MAIN_ESCALATIONS, ticks)
                + " (fences " + rate(MAIN_ESCALATION_FENCES, ticks) + ")");
        out.add(" loop re-arms without fence: " + rate(LOOP_REARMS, ticks)
                + ", stale escalations: " + STALE_ESCALATIONS.sum()
                + ", worker owner-local functions: " + WORKER_OWNER_LOCAL.sum());
        top(out, ESCALATIONS_BY_LINE, ticks);
        return out;
    }

    private static <K> void top(List<String> out, Map<K, LongAdder> counts, long ticks) {
        counts.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<K, LongAdder> e) -> e.getValue().sum()).reversed())
                .limit(8)
                .forEach(e -> out.add("   " + e.getKey() + ": " + rate(e.getValue(), ticks)));
    }

    private static String rate(LongAdder a, long ticks) {
        long n = a.sum();
        return n + " (" + String.format(java.util.Locale.ROOT, "%.3f", (double) n / ticks) + "/t)";
    }
}
