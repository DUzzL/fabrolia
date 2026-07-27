package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * audit13 — retire-commit-before-arrival-carry ordering.
 *
 * <p>The residual hole: {@code RegionizedServer.rebuildIslandsBody} used to run
 * {@code CrossDimArrivals.reroute} in the pre-rebuild carry loop, BEFORE
 * {@code IslandRegistry.applyRebuild} set {@code retired} and before the domain
 * passes re-pointed any region. Two consequences: (a) the reroute re-resolved
 * every op against the OLD topology — the drained island was still the owner of
 * its own cells and {@code retired} was still false, so a retiring or
 * merge-absorbed island's queue was drained and re-enqueued straight back onto
 * itself, then retired: stranded forever (no unit drains a retired island;
 * {@code publishLevelPending} iterates only live ones); (b) audit11's
 * monitor re-check had no ordering leg — a cross-level {@code route()} landing
 * between the drain and the LATER {@code retired = true} write legitimately
 * observed {@code retired == false} and enqueued onto the soon-dead island.
 *
 * <p>Post-fix the carry runs AFTER the topology commit. These tests pin the two
 * pieces the fix is built from: {@code applyRebuildCells} marks every removed
 * identity {@code retired} (merge-absorption and vanishing alike), and once it
 * has, the commit-then-drain sequence loses nothing against a concurrent
 * writer — whatever the interleaving, a cohort lands exactly once in a
 * reachable lane and a retired queue ends empty.
 */
class RetireCommitBeforeArrivalCarryTest {

    private static final long JOIN_TIMEOUT_MS = 30_000L;

    private static LongArrayList cells(long... keys) {
        LongArrayList l = new LongArrayList(keys.length);
        for (long k : keys) {
            l.add(k);
        }
        return l;
    }

    private static List<CrossDimArrivals.StagedOp> cohort(int n) {
        ArrayList<CrossDimArrivals.StagedOp> ops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ops.add(new CrossDimArrivals.StagedOp(null, i, () -> {}, "entity", 100L, 7L));
        }
        return ops;
    }

    /** The post-commit carry drain — reroute()'s collection step, which now
     *  runs only once {@code retired} is committed by the registry rebuild. */
    private static List<CrossDimArrivals.StagedOp> carryDrain(IslandRegistry.PIsland p) {
        synchronized (p.pendingArrivals) {
            List<CrossDimArrivals.StagedOp> drained = new ArrayList<>(p.pendingArrivals);
            p.pendingArrivals.clear();
            return drained;
        }
    }

    @Test
    void aVanishedIslandIsRetiredAndRefusesLateArrivals() {
        IslandRegistry reg = new IslandRegistry();
        reg.applyRebuildCells(List.of(cells(10L)), 0L);
        IslandRegistry.PIsland a = reg.byCell(10L);
        assertNotNull(a);
        assertFalse(a.retired);
        assertTrue(CrossDimArrivals.enqueueUnlessRetired(a, cohort(3)));

        reg.applyRebuildCells(List.of(), 1L); // all chunks unloaded
        assertTrue(a.retired, "an identity with no populated cells left must retire");
        assertEquals(3, a.pendingArrivals.size(),
                "the queued cohort is still there for the post-commit carry");
        assertEquals(3, carryDrain(a).size());
        assertFalse(CrossDimArrivals.enqueueUnlessRetired(a, cohort(2)),
                "post-commit, a late writer must be refused and use the global lane");
        assertEquals(0, a.pendingArrivals.size());
    }

    @Test
    void aMergeAbsorbedIslandIsRetiredWhileTheSurvivorIsNot() {
        IslandRegistry reg = new IslandRegistry();
        reg.applyRebuildCells(List.of(cells(0L), cells(100L)), 0L);
        IslandRegistry.PIsland first = reg.byCell(0L);
        IslandRegistry.PIsland second = reg.byCell(100L);
        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second);

        // The gap closes: one BFS island spanning both cells → MERGE. The
        // survivor is the larger contributor (ties: lowest id = `first`).
        reg.applyRebuildCells(List.of(cells(0L, 100L)), 1L);
        IslandRegistry.PIsland merged = reg.byCell(100L);
        assertSame(first, merged, "tie merge keeps the lowest id");
        assertFalse(first.retired, "the survivor keeps accepting arrivals");
        assertTrue(second.retired, "the absorbed identity must be retired — its "
                + "cells belong to the survivor now");
        assertTrue(CrossDimArrivals.enqueueUnlessRetired(first, cohort(1)));
        assertFalse(CrossDimArrivals.enqueueUnlessRetired(second, cohort(1)));
    }

    @Test
    void aSplitRetiresNothing() {
        IslandRegistry reg = new IslandRegistry();
        reg.applyRebuildCells(List.of(cells(0L, 1L)), 0L);
        IslandRegistry.PIsland prior = reg.byCell(0L);
        assertNotNull(prior);

        // Connectivity lost between the two cells: both fragments claim the
        // prior identity — a genuine SPLIT (keeper = larger fragment; ties:
        // first), the other fragment is born fresh.
        reg.applyRebuildCells(List.of(cells(0L), cells(1L)), 1L);
        IslandRegistry.PIsland keeper = reg.byCell(0L);
        IslandRegistry.PIsland fragment = reg.byCell(1L);
        assertSame(prior, keeper, "the identity stays with the keeper fragment");
        assertNotNull(fragment);
        assertNotSame(keeper, fragment);
        assertFalse(keeper.retired);
        assertFalse(fragment.retired);
    }

    /**
     * The production ordering itself, raced: the registry commit retires the
     * island FIRST (the real {@code applyRebuildCells}), then the carry drains —
     * while a writer routes a cohort concurrently. Post-commit there is no
     * window in which {@code retired} is false and the drain has already run,
     * so the cohort must always end up exactly once in a reachable lane.
     */
    @Test
    void commitThenCarryNeverStrandsARacedCohort() throws Exception {
        final int iterations = 2_000;
        final int cohortSize = 4;
        for (int i = 0; i < iterations; i++) {
            IslandRegistry reg = new IslandRegistry();
            reg.applyRebuildCells(List.of(cells(10L)), 0L);
            IslandRegistry.PIsland island = reg.byCell(10L);
            List<CrossDimArrivals.StagedOp> ops = cohort(cohortSize);
            ArrayList<CrossDimArrivals.StagedOp> globalLane = new ArrayList<>();
            ArrayList<CrossDimArrivals.StagedOp> drained = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);

            Thread rebuilder = new Thread(() -> {
                await(start);
                reg.applyRebuildCells(List.of(), 1L); // commit: retired = true
                drained.addAll(carryDrain(island));   // THEN the carry drain
            }, "rebuilder-" + i);
            Thread router = new Thread(() -> {
                await(start);
                if (!CrossDimArrivals.enqueueUnlessRetired(island, ops)) {
                    globalLane.addAll(ops);
                }
            }, "router-" + i);

            rebuilder.start();
            router.start();
            start.countDown();
            rebuilder.join(JOIN_TIMEOUT_MS);
            router.join(JOIN_TIMEOUT_MS);
            assertFalse(rebuilder.isAlive(), "rebuilder did not finish");
            assertFalse(router.isAlive(), "router did not finish");

            assertEquals(cohortSize, drained.size() + globalLane.size(),
                    "iteration " + i + ": cohort must land exactly once in a reachable lane"
                            + " (drained=" + drained.size() + " global=" + globalLane.size()
                            + " deadQueue=" + island.pendingArrivals.size() + ")");
            assertEquals(0, island.pendingArrivals.size(),
                    "iteration " + i + ": a retired island's queue must end empty");
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
