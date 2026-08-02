package com.axalotl.fabrolia.api.spawn;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Mixed into {@code LocalMobCapCalculator.MobCounts} by
 * {@code LocalMobCapCalculatorMobCountsMixin}. Exposes the per-player
 * per-category {@link AtomicIntegerArray} (ordinal-indexed) that backs the
 * atomized counts map, so the pack-reservation path can {@code getAndAdd}
 * against the SAME cells vanilla's {@code add}/{@code canSpawn} observe —
 * reservation visibility and spawn accounting share one source of truth.
 */
public interface FabroliaMobCountsView {
    AtomicIntegerArray fabrolia$countsArray();
}
