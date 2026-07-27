package com.axalotl.fabrolia.common.regions;

import java.util.Map;

/**
 * Decides how much of a destination island's queued arrival backlog is released
 * at its unit head. Extracted from {@code CrossDimArrivals} so the rule is
 * unit-testable without a running server.
 *
 * <h2>Why the rule is causal (End-portal falling-block scatter)</h2>
 *
 * A cohort is one COMPLETED source pass: {@code seal()} runs at the source
 * island unit's phase end, and {@code route()} appends only sealed batches. Its
 * internal order is {@code [createEndPlatform, add falling blocks, …]} and that
 * is already atomic, because both ops are staged inside one source entity tick
 * and therefore share a {@code sourceTick}.
 *
 * <p>A rate rule keyed on the destination's own clock (at most one cohort per
 * destination epoch) was field-falsified: when the source produces cohorts
 * faster than the destination advances epochs — from a rate difference OR from
 * pure phase drift at identical rates — cohorts back up. Each destination epoch
 * released one, while the destination entity phase ticked the already-published
 * falling blocks, which landed and placed blocks on the End platform. The next
 * epoch then released the next cohort, whose
 * {@code createEndPlatform(dropResources=true)} destroyed those just-placed
 * blocks and dropped them as items: "the sand becomes a block and some of it
 * scatters".
 *
 * <p>Vanilla cannot reach that state. Levels tick sequentially on one thread, so
 * every regen and add from a source pass is applied to the destination level
 * BEFORE the destination's own entity pass runs. There is never a backlog of
 * unapplied arrivals while destination entities tick.
 *
 * <h2>The causal-prefix rule</h2>
 *
 * Everything present in {@code pendingArrivals} when the destination unit head
 * runs was sealed by a source pass that has ALREADY COMPLETED, so all of it is
 * causally prior to the entity phase about to run and all of it must be applied
 * first. That prefix is released in one go, preserving order within each cohort
 * and between cohorts.
 *
 * <p>This is a PREFIX, not unrestricted draining: the boundary is the snapshot
 * taken at the head (the caller holds the queue monitor while deciding). Ops
 * appended DURING the phase by a source worker are genuinely concurrent — not
 * causally prior — and are left for the next head, which is exactly where the
 * task #20/#23 separation still comes from: a cohort routed during phase N is
 * delivered at head N+1, one destination epoch later.
 */
public final class ArrivalReleaseRule {

    private ArrivalReleaseRule() {}

    /** Index-addressable view of the queued ops, so the rule needs no MC types. */
    public interface Queue {
        int size();
        /** Clock domain the op's source epoch was read from; 0 = wall. */
        long sourceDomain(int i);
        /** Source simulation epoch, or negative if unknown. */
        long sourceTick(int i);
    }

    /**
     * @param ops     queued arrivals in FIFO order, as captured under the queue monitor
     * @param destNow the destination island's current logical tick
     * @param chains  per-source-domain {lastTick, lastDestEpoch} anchors; mutated
     * @return how many leading ops to release now (the whole causal prefix)
     */
    public static int prefix(Queue ops, long destNow, Map<Long, long[]> chains) {
        int n = ops.size();
        if (n == 0) return 0;

        // Anchor bookkeeping: per-source-domain {lastTick, lastDestEpoch}, so
        // the idle-reset semantics (a domain's chain re-anchors after the queue
        // drains) stay well-defined for callers that clear the map when idle.
        for (int i = 0; i < n; i++) {
            long tick = ops.sourceTick(i);
            if (tick < 0) continue;
            long dom = ops.sourceDomain(i);
            long[] c = chains.get(dom);
            if (c == null) {
                chains.put(dom, new long[]{tick, destNow});
            } else if (tick != c[0]) {
                c[0] = tick;
                c[1] = destNow;
            }
        }
        if (chains.size() > 64) {
            chains.clear();
        }
        return n;
    }
}
