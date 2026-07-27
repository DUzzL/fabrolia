package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * audit11 — retire-in-route-window cohort loss.
 *
 * <p>The defect: {@code CrossDimArrivals.route} checked {@code owner.retired}
 * OUTSIDE any ordering guarantee with {@code reroute()}. A topology rebuild
 * retires an island and reroutes (drains) its {@code pendingArrivals} — ops a
 * concurrent {@code route()} added AFTER that drain landed in the dead island's
 * queue and were unreachable forever: no unit ever drains a retired island and
 * {@code publishLevelPending} iterates only live islands. Pre-fix the window
 * was narrow (one op per activation); the audit11 per-region-claim frames make
 * batches bigger, putting whole cohorts inside the blast radius.
 *
 * <p>The fix re-checks {@code retired} INSIDE the {@code pendingArrivals}
 * monitor — the same monitor {@code reroute} drains under. These tests drive
 * the real {@code enqueueUnlessRetired} against a real {@code PIsland} with a
 * faithful mirror of reroute's retire-then-drain sequence and assert the
 * transport property: <em>no op is ever left in a retired island's queue; a
 * writer that loses the race is told so and delivers via the global lane</em>.
 *
 * <p>SCOPE: MC-free — {@code StagedOp} is constructed with a null destination
 * level (never dereferenced by the enqueue path) and the global lane is modeled
 * by the caller acting on the {@code false} return, exactly as {@code route()}
 * does with {@code destRs.globalArrivals}.
 */
class CrossDimArrivalsRetireRaceTest {

    private static final long JOIN_TIMEOUT_MS = 30_000L;

    private static CrossDimArrivals.StagedOp op() {
        return new CrossDimArrivals.StagedOp(null, 0L, () -> {}, "entity", -1L, 0L);
    }

    private static List<CrossDimArrivals.StagedOp> cohort(int n) {
        ArrayList<CrossDimArrivals.StagedOp> ops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ops.add(op());
        return ops;
    }

    /** Mirror of {@code reroute()}'s collection step: the island is already
     *  retired (registry rebuild), its queue drains under the monitor. */
    private static List<CrossDimArrivals.StagedOp> retireAndDrain(IslandRegistry.PIsland p) {
        p.retired = true;
        synchronized (p.pendingArrivals) {
            List<CrossDimArrivals.StagedOp> drained = new ArrayList<>(p.pendingArrivals);
            p.pendingArrivals.clear();
            return drained;
        }
    }

    @Test
    void aLiveIslandAcceptsTheCohort() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(1L, 0L);
        List<CrossDimArrivals.StagedOp> ops = cohort(3);

        assertTrue(CrossDimArrivals.enqueueUnlessRetired(island, ops));
        assertEquals(3, island.pendingArrivals.size(),
                "a live island's arrival queue receives the whole cohort");
    }

    @Test
    void aRetiredIslandRefusesTheCohortSoTheCallerUsesTheGlobalLane() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(2L, 0L);
        island.retired = true;

        assertFalse(CrossDimArrivals.enqueueUnlessRetired(island, cohort(3)),
                "the writer must be TOLD it lost the retire race so it can fall "
                        + "through to the destination's global lane");
        assertEquals(0, island.pendingArrivals.size(),
                "nothing may land in a dead island's queue — no unit will ever drain it");
    }

    /**
     * The race itself, forced across many interleavings: one thread retires the
     * island and drains its queue (reroute's sequence), another routes a cohort
     * to it concurrently. Whatever the interleaving, the cohort must end up
     * EXACTLY ONCE in a reachable lane — the reroute drain or the global lane —
     * and the retired island's queue must end empty.
     */
    @Test
    void retiringConcurrentlyWithRouteNeverSwallowsOps() throws Exception {
        final int iterations = 5_000;
        final int cohortSize = 4;

        for (int i = 0; i < iterations; i++) {
            IslandRegistry.PIsland island = new IslandRegistry.PIsland(i, 0L);
            List<CrossDimArrivals.StagedOp> ops = cohort(cohortSize);
            ArrayList<CrossDimArrivals.StagedOp> globalLane = new ArrayList<>();
            ArrayList<CrossDimArrivals.StagedOp> drained = new ArrayList<>();

            CountDownLatch start = new CountDownLatch(1);

            Thread retirer = new Thread(() -> {
                await(start);
                drained.addAll(retireAndDrain(island));
            }, "retirer-" + i);

            Thread router = new Thread(() -> {
                await(start);
                // route()'s exact shape post-fix: enqueue unless retired, on a
                // lost race fall through to the destination's global lane.
                if (!CrossDimArrivals.enqueueUnlessRetired(island, ops)) {
                    globalLane.addAll(ops);
                }
            }, "router-" + i);

            retirer.start();
            router.start();
            start.countDown();
            retirer.join(JOIN_TIMEOUT_MS);
            router.join(JOIN_TIMEOUT_MS);
            assertFalse(retirer.isAlive(), "retirer did not finish");
            assertFalse(router.isAlive(), "router did not finish");

            // The retirer may have drained BEFORE the router enqueued — then the
            // ops sit in the queue of an island that is now retired, which is the
            // exact loss mode... unless the router observed `retired` inside the
            // monitor and used the global lane. Either reachable lane is correct;
            // the dead queue is not.
            int reachable = drained.size() + globalLane.size();
            assertEquals(cohortSize, reachable,
                    "iteration " + i + ": cohort must land exactly once in a reachable "
                            + "lane (drained=" + drained.size() + " global=" + globalLane.size()
                            + " deadQueue=" + island.pendingArrivals.size() + ")");
            assertEquals(0, island.pendingArrivals.size(),
                    "iteration " + i + ": ops left in a RETIRED island's queue are "
                            + "unreachable forever — publishLevelPending iterates only "
                            + "live islands");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
