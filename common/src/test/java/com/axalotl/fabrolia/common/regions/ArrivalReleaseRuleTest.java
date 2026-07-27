package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-portal falling-block scatter: the destination arrival release rule.
 *
 * <p>Each test drives the rule with cohorts shaped like the real thing — one
 * completed source pass per {@code sourceTick}, ops in vanilla order
 * {@code [regen, add, add, …]} — and asserts the causal invariant:
 *
 * <blockquote>a destination island must not begin its entity phase while it
 * still holds arrivals from source passes that already completed.</blockquote>
 *
 * <p>Scenario coverage matches the operator's field matrix: equal rates,
 * source-faster, destination-faster, equal rate with phase drift, global
 * sprint, and merge/rebuild re-anchoring.
 */
class ArrivalReleaseRuleTest {

    private static final long DOM_SRC = 7L;   // one source island's clock domain

    private record Op(long domain, long tick, String kind) {}

    private static final class Q implements ArrivalReleaseRule.Queue {
        final List<Op> ops;
        Q(List<Op> ops) { this.ops = ops; }
        @Override public int size() { return ops.size(); }
        @Override public long sourceDomain(int i) { return ops.get(i).domain(); }
        @Override public long sourceTick(int i) { return ops.get(i).tick(); }
    }

    /** One completed source pass: platform regen followed by its falling blocks. */
    private static void cohort(List<Op> out, long domain, long tick, int blocks) {
        out.add(new Op(domain, tick, "regen"));
        for (int i = 0; i < blocks; i++) out.add(new Op(domain, tick, "entity"));
    }

    private static int release(List<Op> queued, long destNow, HashMap<Long, long[]> chains) {
        return ArrivalReleaseRule.prefix(new Q(queued), destNow, chains);
    }

    // ------------------------------------------------------------------
    // The causal invariant
    // ------------------------------------------------------------------

    @Test
    void multipleCompletedSourceCohortsAllDrainBeforeOneDestinationPhase() {
        List<Op> queued = new ArrayList<>();
        cohort(queued, DOM_SRC, 100, 3);
        cohort(queued, DOM_SRC, 101, 3);
        cohort(queued, DOM_SRC, 102, 3);

        int take = release(queued, 50, new HashMap<>());

        assertEquals(queued.size(), take,
                "every cohort in the queue was sealed by a source pass that already completed, "
                        + "so all of it is causally prior to the phase about to run");
    }

    @Test
    void noEntityIsPublishedWhileAnOlderCompletedCohortIsWithheld() {
        List<Op> queued = new ArrayList<>();
        cohort(queued, DOM_SRC, 100, 2);
        cohort(queued, DOM_SRC, 101, 2);

        int take = release(queued, 50, new HashMap<>());

        // The invariant, stated over the released prefix: if ANY entity is released,
        // every op belonging to an earlier-or-equal source pass must be released too.
        long newestReleasedEntity = Long.MIN_VALUE;
        for (int i = 0; i < take; i++) {
            if (queued.get(i).kind().equals("entity")) {
                newestReleasedEntity = Math.max(newestReleasedEntity, queued.get(i).tick());
            }
        }
        for (int i = take; i < queued.size(); i++) {
            assertTrue(queued.get(i).tick() > newestReleasedEntity,
                    "a withheld op belongs to source pass " + queued.get(i).tick()
                            + " which is not newer than a released entity's pass "
                            + newestReleasedEntity + " — its regen would destroy blocks that "
                            + "entity is about to place");
        }
    }

    @Test
    void cohortAndInterCohortOrderingIsPreserved() {
        List<Op> queued = new ArrayList<>();
        cohort(queued, DOM_SRC, 100, 2);
        cohort(queued, DOM_SRC, 101, 2);

        int take = release(queued, 50, new HashMap<>());

        // Released prefix must be the queue's own FIFO order: regen before its blocks,
        // and cohort 100 entirely before cohort 101.
        assertEquals("regen", queued.get(0).kind());
        assertEquals(100L, queued.get(0).tick());
        assertEquals("regen", queued.get(3).kind());
        assertEquals(101L, queued.get(3).tick());
        assertEquals(6, take);
    }

    // ------------------------------------------------------------------
    // Rate / phase matrix — the rule must not depend on either
    // ------------------------------------------------------------------

    @Test
    void sourceFasterThanDestination() {
        // Source ran 5 passes while the destination advanced 1 epoch.
        List<Op> queued = new ArrayList<>();
        for (long t = 200; t < 205; t++) cohort(queued, DOM_SRC, t, 2);

        assertEquals(queued.size(), release(queued, 10, new HashMap<>()),
                "a fast source must not leave completed passes queued behind a ticking "
                        + "destination");
    }

    @Test
    void destinationFasterThanSource() {
        // Destination is way ahead; only one source pass is queued.
        List<Op> queued = new ArrayList<>();
        cohort(queued, DOM_SRC, 300, 2);

        assertEquals(queued.size(), release(queued, 9_999, new HashMap<>()));
    }

    @Test
    void equalRatesWithPhaseDriftStillDrainsFully() {
        // Identical TPS, but the destination's epoch numbering is offset — the case
        // that made "both at 40 TPS" behave the same as 20/20.
        HashMap<Long, long[]> chains = new HashMap<>();
        for (int step = 0; step < 8; step++) {
            List<Op> queued = new ArrayList<>();
            cohort(queued, DOM_SRC, 400 + step, 2);
            long destNow = 400 + step - 137;   // constant phase offset
            assertEquals(queued.size(), release(queued, destNow, chains),
                    "phase offset must not withhold a completed cohort (step " + step + ")");
        }
    }

    @Test
    void globalSprintLockstepStillDrainsFully() {
        // Under global sprint each island runs exactly one unit per sprint tick, so
        // at most one cohort is ever queued — it must always release whole.
        for (int step = 0; step < 5; step++) {
            List<Op> queued = new ArrayList<>();
            cohort(queued, DOM_SRC, 500 + step, 3);
            assertEquals(queued.size(), release(queued, 500 + step, new HashMap<>()));
        }
    }

    // ------------------------------------------------------------------
    // Concurrency separation and topology churn
    // ------------------------------------------------------------------

    @Test
    void opsArrivingDuringThePhaseAreNotInThisPrefix() {
        // The prefix boundary is the snapshot taken at the head, under the queue
        // monitor. Work routed by a source worker DURING the phase is genuinely
        // concurrent, is not in that snapshot, and waits for the next head — which
        // is where the task #20/#23 separation still comes from.
        List<Op> atHead = new ArrayList<>();
        cohort(atHead, DOM_SRC, 600, 2);
        int take = release(atHead, 60, new HashMap<>());
        assertEquals(3, take, "only what was queued at the head is released");

        List<Op> nextHead = new ArrayList<>();
        cohort(nextHead, DOM_SRC, 601, 2);   // arrived during the previous phase
        assertEquals(3, release(nextHead, 61, new HashMap<>()),
                "it is delivered at the following head, one destination epoch later");
    }

    @Test
    void distinctSourceDomainsAreTrackedIndependently() {
        // Two source islands feeding one destination: each domain's epochs are only
        // comparable within that domain (task #20).
        List<Op> queued = new ArrayList<>();
        cohort(queued, 11L, 700, 1);
        cohort(queued, 22L, 900, 1);

        HashMap<Long, long[]> chains = new HashMap<>();
        assertEquals(queued.size(), release(queued, 70, chains));
        assertEquals(2, chains.size(), "one chain per source domain");
    }

    @Test
    void mergeOrRebuildReanchorsWithoutWithholdingCompletedWork() {
        // reroute() re-queues arrivals against a fresh island (new id/gen), so the
        // chain map starts empty. A completed cohort must still drain immediately.
        List<Op> requeued = new ArrayList<>();
        cohort(requeued, DOM_SRC, 800, 4);

        assertEquals(requeued.size(), release(requeued, 0, new HashMap<>()),
                "after a merge/rebuild the re-routed backlog is still causally prior");
    }

    @Test
    void unknownSourceEpochStillDrains() {
        List<Op> queued = List.of(new Op(0L, -1L, "entity"), new Op(0L, -1L, "regen"));
        assertEquals(2, release(new ArrayList<>(queued), 5, new HashMap<>()));
    }

    @Test
    void emptyQueueReleasesNothing() {
        assertEquals(0, release(new ArrayList<>(), 5, new HashMap<>()));
    }
}
