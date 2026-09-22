package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * audit13 — global-lane cohort integrity ({@code appendGlobalLane} /
 * {@code takeGlobalBatch}).
 *
 * <p>The residual holes: (a) {@code ConcurrentLinkedQueue.addAll} is
 * per-element, so two source workers sealing concurrently into one level's
 * global lane could INTERLEAVE their batches; (b) between {@code
 * publishGlobal}'s {@code poll()} drain and its re-add of the gate-held head,
 * a concurrent {@code route()} could append ops that then landed AHEAD of the
 * re-queued held ops. Both fragment the contiguous (sourceDomain, sourceTick)
 * run that IS the cohort under the release rule and the End-platform handoff
 * gate — one machine cohort read back as several successive cohorts.
 *
 * <p>Post-fix, every lane append and the whole drain→requeue window run under
 * one ordering monitor ({@code RegionizedServer.globalArrivalsLock} in
 * production; a plain lock object here). These tests drive the package-private
 * helpers with MC-free {@code StagedOp}s (null destination, null entity — the
 * tracker-less extract path never touches either) and assert the transport
 * property: <em>every cohort is appended whole, drained whole, and delivered
 * exactly once, in member order</em>.
 */
class GlobalLaneCohortIntegrityTest {

    private static final long JOIN_TIMEOUT_MS = 30_000L;

    /** Member index is encoded in chunkKey so tearing is observable. */
    private static CrossDimArrivals.StagedOp op(long dom, long tick, long member) {
        return new CrossDimArrivals.StagedOp(null, member, () -> {}, "entity", tick, dom);
    }

    private static List<CrossDimArrivals.StagedOp> cohort(long dom, long tick, int size) {
        ArrayList<CrossDimArrivals.StagedOp> ops = new ArrayList<>(size);
        for (int m = 0; m < size; m++) {
            ops.add(op(dom, tick, m));
        }
        return ops;
    }

    /** Assert {@code ops} is a concatenation of WHOLE cohorts of {@code size}
     *  members each: every maximal (domain, tick) run is complete and in
     *  member order. Returns the cohort keys seen, in order. */
    private static List<String> assertWholeCohorts(List<CrossDimArrivals.StagedOp> ops,
                                                   int size, String where) {
        ArrayList<String> keys = new ArrayList<>();
        int i = 0;
        while (i < ops.size()) {
            long dom = ops.get(i).sourceDomain();
            long tick = ops.get(i).sourceTick();
            for (int m = 0; m < size; m++) {
                assertTrue(i + m < ops.size(),
                        where + ": cohort (" + dom + "," + tick + ") truncated at member " + m);
                CrossDimArrivals.StagedOp o = ops.get(i + m);
                assertEquals(dom, o.sourceDomain(),
                        where + ": foreign op interleaved into cohort (" + dom + "," + tick + ")");
                assertEquals(tick, o.sourceTick(),
                        where + ": foreign op interleaved into cohort (" + dom + "," + tick + ")");
                assertEquals(m, o.chunkKey(),
                        where + ": cohort (" + dom + "," + tick + ") members out of order");
            }
            keys.add(dom + ":" + tick);
            i += size;
        }
        return keys;
    }

    @Test
    void concurrentSealersNeverInterleaveTheirBatches() throws Exception {
        final int iterations = 300;
        final int cohortsPerThread = 20;
        final int cohortSize = 5;
        for (int it = 0; it < iterations; it++) {
            ConcurrentLinkedQueue<CrossDimArrivals.StagedOp> lane = new ConcurrentLinkedQueue<>();
            Object lock = new Object();
            CountDownLatch start = new CountDownLatch(1);
            Thread[] sealers = new Thread[2];
            for (int t = 0; t < 2; t++) {
                final long dom = t + 1;
                sealers[t] = new Thread(() -> {
                    await(start);
                    for (int c = 0; c < cohortsPerThread; c++) {
                        CrossDimArrivals.appendGlobalLane(lane, lock, cohort(dom, c, cohortSize));
                    }
                }, "sealer-" + t);
                sealers[t].start();
            }
            start.countDown();
            for (Thread t : sealers) {
                t.join(JOIN_TIMEOUT_MS);
                assertFalse(t.isAlive(), "sealer did not finish");
            }
            List<CrossDimArrivals.StagedOp> all = new ArrayList<>(lane);
            assertEquals(2 * cohortsPerThread * cohortSize, all.size(), "ops lost or duplicated");
            List<String> keys = assertWholeCohorts(all, cohortSize, "iteration " + it);
            assertEquals(2 * cohortsPerThread, keys.size());
        }
    }

    @Test
    void drainRacedAgainstAppendsDeliversEveryCohortWholeAndExactlyOnce() throws Exception {
        final int cohorts = 2_000;
        final int cohortSize = 5;
        ConcurrentLinkedQueue<CrossDimArrivals.StagedOp> lane = new ConcurrentLinkedQueue<>();
        Object lock = new Object();
        HashMap<Long, long[]> chains = new HashMap<>();
        CountDownLatch start = new CountDownLatch(1);

        Thread producer = new Thread(() -> {
            await(start);
            for (int c = 0; c < cohorts; c++) {
                // route()'s exact shape: one sealed cohort per append.
                CrossDimArrivals.appendGlobalLane(lane, lock, cohort(1L, c, cohortSize));
            }
        }, "producer");
        producer.start();
        start.countDown();

        ArrayList<String> delivered = new ArrayList<>();
        long destNow = 0;
        while (producer.isAlive() || !lane.isEmpty()) {
            ArrayList<CrossDimArrivals.StagedOp> batch = CrossDimArrivals.takeGlobalBatch(lane, lock, chains, destNow++, null);
            if (batch != null) {
                // The drain must never observe a fragment of a mid-append batch.
                delivered.addAll(assertWholeCohorts(batch, cohortSize, "published batch"));
            }
        }
        producer.join(JOIN_TIMEOUT_MS);
        assertFalse(producer.isAlive(), "producer did not finish");

        assertEquals(cohorts, delivered.size(), "every cohort exactly once");
        for (int c = 0; c < cohorts; c++) {
            assertEquals("1:" + c, delivered.get(c),
                    "cohorts must publish in FIFO order");
        }
    }

    @Test
    void takeGlobalBatchClearsChainsOnlyWhenTheLaneIsIdle() {
        ConcurrentLinkedQueue<CrossDimArrivals.StagedOp> lane = new ConcurrentLinkedQueue<>();
        Object lock = new Object();
        HashMap<Long, long[]> chains = new HashMap<>();
        chains.put(9L, new long[]{1, 2});

        lane.addAll(cohort(1L, 7, 2));
        ArrayList<CrossDimArrivals.StagedOp> batch =
                CrossDimArrivals.takeGlobalBatch(lane, lock, chains, 100L, null);
        assertEquals(2, batch.size(), "causal rule releases the whole prefix");
        assertTrue(lane.isEmpty());
        assertTrue(chains.containsKey(1L), "release must anchor the domain chain");

        // Idle head: the chain map resets so the next arrival re-anchors.
        assertEquals(null, CrossDimArrivals.takeGlobalBatch(lane, lock, chains, 101L, null));
        assertTrue(chains.isEmpty(), "idle head must clear the spacing chains");
    }


    private static void await(CountDownLatch latch) {
        try {
            latch.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
