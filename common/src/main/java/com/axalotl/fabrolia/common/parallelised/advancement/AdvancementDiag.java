package com.axalotl.fabrolia.common.parallelised.advancement;

import java.util.concurrent.atomic.LongAdder;

/**
 * Advancement-pipeline counters plus last-batch gauges; surfaced by
 * {@code /fabrolia regions debug advstats}. Counters are cheap LongAdders on
 * paths that already allocate; no gating needed.
 */
public final class AdvancementDiag {

    private AdvancementDiag() {}

    /** trigger() calls that produced a non-empty Pending (criterion events). */
    public static final LongAdder EVENTS_PRODUCED = new LongAdder();
    /** Trigger-map entries walked at fire time (inspected). */
    public static final LongAdder ENTRIES_INSPECTED = new LongAdder();
    /** Instances surviving the fire-time per-trigger predicate. */
    public static final LongAdder INSTANCES_KEPT = new LongAdder();
    /** Player-predicate matches (award candidates before dedup). */
    public static final LongAdder MATCHES = new LongAdder();
    /** award() invocations actually made. */
    public static final LongAdder AWARDS = new LongAdder();
    /** Matches dropped by the per-batch (player, key) dedup. */
    public static final LongAdder DEDUP_DROPPED = new LongAdder();
    /** Main-frame per-player drain() calls that PARKED into the tick batch. */
    public static final LongAdder DRAIN_PARKS = new LongAdder();
    /** Per-player drainOnMain executions (legacy/async-off path only). */
    public static final LongAdder DRAIN_LEGACY = new LongAdder();
    /** Batch applies at entity-batch end. */
    public static final LongAdder BATCH_APPLIES = new LongAdder();
    /** Reward FUNCTIONs executed inside an advancement batch scope. */
    public static final LongAdder REWARD_FUNCTIONS = new LongAdder();
    /** Global quiesce windows taken by advancement scopes: sticky reward fences,
     *  deferred batches, and the tick-head window (which also takes the fence the
     *  tick-tag functions then share, so ≈1 per tick with tick functions present). */
    public static final LongAdder QUIESCE_TAKES = new LongAdder();
    /** Nanos spent inside advancement-owned quiesce windows + eval pumps. */
    public static final LongAdder BARRIER_NANOS = new LongAdder();
    /** flushDirty calls issued by the pipeline (≈ packets flushed upper bound). */
    public static final LongAdder FLUSHES = new LongAdder();
    /** Direct off-main award/revoke replays. */
    public static final LongAdder DIRECT_REPLAYS = new LongAdder();
    /** Predicate-evaluation fan-outs over the tick pool (batches above the grain). */
    public static final LongAdder EVAL_FANOUTS = new LongAdder();
    /** Tasks dispatched by those fan-outs. */
    public static final LongAdder EVAL_TASKS = new LongAdder();
    /** Tick-head windows that applied worker-lane awards under the tick-function fence. */
    public static final LongAdder HEAD_DRAINS = new LongAdder();

    /** Last-batch gauges (main-only writes). */
    public static volatile int lastBatchPendings;
    public static volatile int lastBatchMatches;
    public static volatile int lastBatchAffectedPlayers;

    public static String report() {
        return "advstats events=" + EVENTS_PRODUCED.sum()
                + " inspected=" + ENTRIES_INSPECTED.sum()
                + " kept=" + INSTANCES_KEPT.sum()
                + " matches=" + MATCHES.sum()
                + " awards=" + AWARDS.sum()
                + " dedupDropped=" + DEDUP_DROPPED.sum()
                + " drainParks=" + DRAIN_PARKS.sum()
                + " drainLegacy=" + DRAIN_LEGACY.sum()
                + " batches=" + BATCH_APPLIES.sum()
                + " rewardFns=" + REWARD_FUNCTIONS.sum()
                + " quiesceTakes=" + QUIESCE_TAKES.sum()
                + " barrierMs=" + BARRIER_NANOS.sum() / 1_000_000L
                + " flushes=" + FLUSHES.sum()
                + " directReplays=" + DIRECT_REPLAYS.sum()
                + " evalFanouts=" + EVAL_FANOUTS.sum()
                + " evalTasks=" + EVAL_TASKS.sum()
                + " headDrains=" + HEAD_DRAINS.sum()
                + " lastBatch[pend=" + lastBatchPendings
                + " match=" + lastBatchMatches
                + " players=" + lastBatchAffectedPlayers + "]";
    }
}
