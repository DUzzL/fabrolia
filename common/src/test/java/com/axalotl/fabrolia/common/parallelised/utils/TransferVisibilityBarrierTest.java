package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task #26 regression coverage for {@link TransferVisibilityBarrier}.
 *
 * <p>The production bug: a cross-dimension portal transfer was published to a
 * queue from inside the source entity's tick, on an island worker that main does
 * not await. Main could therefore dequeue and execute the whole teleport while
 * that worker tick was still writing the entity's position, so the destination
 * calculation — which reads the live entity — computed a coordinate from a pose
 * caught mid-write. For a rail minecart the corruption was exactly the rail
 * offset, landing the cart off its track at the destination, where gravity
 * dropped it and portal contact re-armed its cooldown forever.
 *
 * <p>{@link #consumerNeverObservesAnOpenTickFrame()} is the test that fails on
 * the pre-fix design, and {@link #immediatePublicationDoesRaceTheTickFrame()}
 * proves that assertion is not vacuous by exercising the old behaviour and
 * demanding the violation actually appear.
 *
 * <p>SCOPE: this covers the ordering primitive only. The Minecraft-side
 * behaviour it protects — that the destination Y equals rail height, that the
 * cart resumes cycling, that no transfer is lost across a save or a restart —
 * cannot be asserted without a running dedicated server and remains dependent on
 * the operator's real-world soak.
 */
class TransferVisibilityBarrierTest {

    private static final long JOIN_TIMEOUT_MS = 30_000L;

    // ---------------------------------------------------------------
    // Single-threaded contract
    // ---------------------------------------------------------------

    @Test
    void workSubmittedOutsideATickFrameIsPublishedImmediately() {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);
        Object owner = new Object();

        barrier.submit(owner, () -> {}, false);

        assertEquals(1, out.size(), "no tick frame is open, so there is nothing to wait for");
        assertEquals(0, barrier.stagedOnThisThread());
        assertFalse(barrier.hasDeparted(owner),
                "an immediate publication does not freeze the owner's tick");
    }

    @Test
    void workSubmittedInsideATickFrameIsInvisibleUntilTheFrameCloses() {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);
        Object owner = new Object();

        barrier.submit(owner, () -> {}, true);

        assertNull(out.poll(), "the consumer must not see the transfer mid-tick");
        assertEquals(1, barrier.stagedOnThisThread());

        barrier.publishStaged();

        assertEquals(1, out.size());
        assertEquals(0, barrier.stagedOnThisThread());
    }

    @Test
    void departureMarkerIsSetForTheSubmitterAndClearedAtTheFrameBoundary() {
        TransferVisibilityBarrier barrier =
                new TransferVisibilityBarrier(new ConcurrentLinkedQueue<>());
        Object owner = new Object();
        Object other = new Object();

        assertFalse(barrier.hasDeparted(owner));

        barrier.submit(owner, () -> {}, true);

        assertTrue(barrier.hasDeparted(owner),
                "the submitting entity is causally gone for the rest of its tick");
        assertFalse(barrier.hasDeparted(other), "unrelated entities keep ticking");
        assertFalse(barrier.hasDeparted(null), "null owner must never match");

        barrier.publishStaged();

        assertFalse(barrier.hasDeparted(owner), "the marker is tick-scoped");
    }

    @Test
    void publishingAnEmptyFrameIsANoOp() {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);

        barrier.publishStaged();
        barrier.publishStaged();

        assertEquals(0, out.size());
    }

    @Test
    void multipleSubmissionsInOneFrameArePublishedTogetherInOrder() {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);
        StringBuilder order = new StringBuilder();

        barrier.submit(new Object(), () -> order.append('a'), true);
        barrier.submit(new Object(), () -> order.append('b'), true);
        assertEquals(0, out.size());

        barrier.publishStaged();

        // audit11: the frame is ONE queue element — a concurrent drainer can
        // never take 'a' this drain and 'b' the next.
        assertEquals(1, out.size(), "a frame publishes as a single composite element");
        out.poll().run();
        assertEquals("ab", order.toString(), "a cohort keeps its submission order");
    }

    // ---------------------------------------------------------------
    // Thread confinement
    // ---------------------------------------------------------------

    @Test
    void oneThreadsFrameIsNotPublishedByAnother() throws Exception {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);
        Object owner = new Object();
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch otherPublished = new CountDownLatch(1);
        AtomicBoolean sawForeignDeparture = new AtomicBoolean(true);

        Thread worker = new Thread(() -> {
            barrier.submit(owner, () -> {}, true);
            staged.countDown();
            try {
                otherPublished.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "worker");
        worker.start();

        assertTrue(staged.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Another thread closing its own (empty) frame must not release the
        // worker's staged transfer, and must not see the worker's departure.
        sawForeignDeparture.set(barrier.hasDeparted(owner));
        barrier.publishStaged();
        otherPublished.countDown();
        worker.join(JOIN_TIMEOUT_MS);

        assertFalse(sawForeignDeparture.get(),
                "the departure marker is thread-confined to the ticking worker");
        assertEquals(0, out.size(),
                "only the owning thread may release its own frame");
    }

    // ---------------------------------------------------------------
    // The barrier property — the actual regression test
    // ---------------------------------------------------------------

    /**
     * Models the production shape: a worker opens a tick frame, submits a
     * transfer part-way through, keeps mutating shared state for the rest of the
     * frame, then closes it. A consumer polls continuously, exactly as main's
     * entity-batch epilogue does. The transfer must never execute while the
     * producing frame is still open — that window is where the destination
     * calculation read a half-written pose.
     */
    @Test
    void consumerNeverObservesAnOpenTickFrame() throws Exception {
        Result r = runProducerConsumer(20_000, /* insideTick = */ true);

        assertEquals(0, r.violations,
                "a transfer executed while its source tick frame was still open — "
                        + "the destination calculation can then read a pose mid-write "
                        + "(" + r.violations + " of " + r.executed + " transfers)");
        assertEquals(20_000, r.executed, "every transfer must still be delivered");
    }

    /**
     * Guards against the test above passing vacuously. Publishing immediately —
     * the pre-fix behaviour — must actually produce the violation the barrier
     * exists to prevent; if this ever stops racing, the positive test has
     * stopped proving anything and both need revisiting.
     */
    @Test
    void immediatePublicationDoesRaceTheTickFrame() throws Exception {
        Result r = runProducerConsumer(20_000, /* insideTick = */ false);

        assertTrue(r.violations > 0,
                "expected the unbarriered path to race the open tick frame at least once "
                        + "in " + r.executed + " transfers, but it never did — this test can "
                        + "no longer detect the regression it guards");
    }

    /**
     * DETERMINISTIC interleaving, not stress: the consumer is released at the
     * exact instant the producer sits between {@code submit} and
     * {@code publishStaged}, with the producing frame still open, and is given a
     * bounded window to observe the queue. This is the precise moment the
     * production bug fired, forced rather than waited for — so the test cannot
     * pass merely because the race window happened not to open.
     */
    @Test
    void consumerReleasedExactlyInsideTheOpenFrameSeesNothing() throws Exception {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);
        Object owner = new Object();

        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch consumerLooked = new CountDownLatch(1);
        AtomicBoolean sawWorkMidFrame = new AtomicBoolean(false);
        AtomicBoolean frameOpen = new AtomicBoolean(false);

        Thread producer = new Thread(() -> {
            frameOpen.set(true);
            barrier.submit(owner, () -> {}, true);
            submitted.countDown();
            try {
                // Hold the frame open until the consumer has definitely looked.
                consumerLooked.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            frameOpen.set(false);
            barrier.publishStaged();
        }, "producer");

        Thread consumer = new Thread(() -> {
            try {
                submitted.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                // The frame is provably still open here.
                if (frameOpen.get() && !out.isEmpty()) {
                    sawWorkMidFrame.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerLooked.countDown();
            }
        }, "consumer");

        producer.start();
        consumer.start();
        producer.join(JOIN_TIMEOUT_MS);
        consumer.join(JOIN_TIMEOUT_MS);

        assertFalse(sawWorkMidFrame.get(),
                "the consumer observed the transfer while the producing tick frame was "
                        + "still open — the destination calculation could read a pose mid-write");
        assertEquals(1, out.size(), "the transfer must still be delivered once the frame closes");
    }

    /**
     * Same forced interleaving against the pre-fix behaviour. If this ever stops
     * observing the work mid-frame, the deterministic test above has stopped
     * proving anything.
     */
    @Test
    void deterministicHarnessDetectsTheUnbarrieredCase() throws Exception {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);

        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch consumerLooked = new CountDownLatch(1);
        AtomicBoolean sawWorkMidFrame = new AtomicBoolean(false);
        AtomicBoolean frameOpen = new AtomicBoolean(false);

        Thread producer = new Thread(() -> {
            frameOpen.set(true);
            barrier.submit(this, () -> {}, false); // pre-fix: publish immediately
            submitted.countDown();
            try {
                consumerLooked.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            frameOpen.set(false);
        }, "producer");

        Thread consumer = new Thread(() -> {
            try {
                submitted.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (frameOpen.get() && !out.isEmpty()) {
                    sawWorkMidFrame.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerLooked.countDown();
            }
        }, "consumer");

        producer.start();
        consumer.start();
        producer.join(JOIN_TIMEOUT_MS);
        consumer.join(JOIN_TIMEOUT_MS);

        assertTrue(sawWorkMidFrame.get(),
                "the unbarriered path did not expose work inside the open frame even under a "
                        + "forced interleaving — the deterministic test above is no longer meaningful");
    }

    // ---------------------------------------------------------------
    // audit11 — frame atomicity against a concurrent drainer, and the
    // multi-departure frame (a frame now spans a whole region claim)
    // ---------------------------------------------------------------

    /**
     * The audit11 production bug: a source portal activation's transfers were
     * offered to PENDING individually, so main's drain (a poll loop) could take
     * a prefix of the activation this wall tick and the remainder the next —
     * two batches publishing at different destination epochs. The frame must be
     * one queue element: a drainer sees the whole frame or none of it.
     */
    @Test
    void aConcurrentDrainerSeesTheWholeFrameOrNothing() throws Exception {
        final int frames = 10_000;
        final int frameSize = 5;
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);

        AtomicInteger executedInFrame = new AtomicInteger();
        AtomicInteger partialObservations = new AtomicInteger();
        AtomicInteger framesDrained = new AtomicInteger();
        AtomicBoolean producerDone = new AtomicBoolean(false);

        // Models PortalTeleportationManager.drainPending exactly: poll, run,
        // repeat. With per-task offers this loop could exhaust the queue in the
        // middle of a frame; with the composite element every successful poll
        // delivers the complete frame.
        Thread drainer = new Thread(() -> {
            while (!producerDone.get() || !out.isEmpty()) {
                Runnable task = out.poll();
                if (task == null) {
                    Thread.onSpinWait();
                    continue;
                }
                task.run();
                // The drain observed a batch boundary here — exactly where the
                // pre-fix design bisected activations. The frame it just ran
                // must be complete.
                if (executedInFrame.getAndSet(0) != frameSize) {
                    partialObservations.incrementAndGet();
                }
                framesDrained.incrementAndGet();
            }
        }, "drainer");

        Thread producer = new Thread(() -> {
            for (int i = 0; i < frames; i++) {
                for (int j = 0; j < frameSize; j++) {
                    barrier.submit(new Object(), executedInFrame::incrementAndGet, true);
                }
                barrier.publishStaged(); // races the drainer's poll loop
                // One frame in flight at a time, so the drainer's per-element
                // completeness check is exact (frames never concatenate).
                while (!out.isEmpty()) {
                    Thread.onSpinWait();
                }
            }
            producerDone.set(true);
        }, "producer");

        drainer.start();
        producer.start();
        producer.join(JOIN_TIMEOUT_MS);
        drainer.join(JOIN_TIMEOUT_MS);
        assertFalse(producer.isAlive(), "producer did not finish");
        assertFalse(drainer.isAlive(), "drainer did not finish");

        assertEquals(0, partialObservations.get(),
                "the drainer observed a PARTIAL frame at a drain boundary — a portal "
                        + "activation was bisected into two publication batches");
        assertEquals(frames, framesDrained.get(), "every frame must still be delivered");
    }

    /**
     * DETERMINISTIC counterpart, in the style of
     * {@link #consumerReleasedExactlyInsideTheOpenFrameSeesNothing()}: the
     * drainer is released exactly at the instant the frame is published and
     * drains the queue to empty in one burst — the burst must contain either
     * the whole frame or nothing, never a prefix.
     */
    @Test
    void aDrainBurstAtThePublishInstantNeverSplitsTheFrame() throws Exception {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);

        AtomicInteger executed = new AtomicInteger();
        CountDownLatch published = new CountDownLatch(1);
        AtomicInteger drainedInBurst = new AtomicInteger(-1);

        Thread producer = new Thread(() -> {
            barrier.submit(new Object(), executed::incrementAndGet, true);
            barrier.submit(new Object(), executed::incrementAndGet, true);
            barrier.submit(new Object(), executed::incrementAndGet, true);
            barrier.publishStaged();
            published.countDown();
        }, "producer");

        Thread drainer = new Thread(() -> {
            try {
                published.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                Runnable task;
                while ((task = out.poll()) != null) {
                    task.run();
                }
                drainedInBurst.set(executed.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "drainer");

        producer.start();
        drainer.start();
        producer.join(JOIN_TIMEOUT_MS);
        drainer.join(JOIN_TIMEOUT_MS);

        assertEquals(3, drainedInBurst.get(),
                "one drain burst after publication must deliver the WHOLE frame");
        assertEquals(0, out.size());
    }

    @Test
    void multipleDeparturesInOneFrameAreAllRecordedAndQueryable() {
        TransferVisibilityBarrier barrier =
                new TransferVisibilityBarrier(new ConcurrentLinkedQueue<>());
        Object cartA = new Object();
        Object cartB = new Object();
        Object cartC = new Object();

        barrier.submit(cartA, () -> {}, true);
        barrier.submit(cartB, () -> {}, true);
        barrier.submit(cartC, () -> {}, true);

        // Pre-fix, `departed` was a single slot: cartB's departure OVERWROTE
        // cartA's, un-freezing an entity vanilla had already removed. Now a
        // frame spans a whole region-claim drain, so this is load-bearing.
        assertTrue(barrier.hasDeparted(cartA),
                "the FIRST departure of the frame must survive later departures");
        assertTrue(barrier.hasDeparted(cartB));
        assertTrue(barrier.hasDeparted(cartC));
        assertFalse(barrier.hasDeparted(new Object()), "unrelated entities keep ticking");
        assertFalse(barrier.hasDeparted(null), "null owner must never match");
    }

    @Test
    void publishStagedClearsEveryDepartureOfTheFrame() {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);
        Object cartA = new Object();
        Object cartB = new Object();

        barrier.submit(cartA, () -> {}, true);
        barrier.submit(cartB, () -> {}, true);
        barrier.publishStaged();

        assertFalse(barrier.hasDeparted(cartA), "the departure set is frame-scoped");
        assertFalse(barrier.hasDeparted(cartB), "the departure set is frame-scoped");

        // And the cleared set does not leak into the next frame's queries.
        Object cartD = new Object();
        barrier.submit(cartD, () -> {}, true);
        assertTrue(barrier.hasDeparted(cartD));
        assertFalse(barrier.hasDeparted(cartA));
        barrier.publishStaged();
        assertFalse(barrier.hasDeparted(cartD));
    }

    /** Mirrors the production shape (tickEntity / the region task publish from
     *  a finally): a tick that THROWS after submitting must still publish its
     *  staged transfer — otherwise the entity is causally gone from the source
     *  but never arrives anywhere. */
    @Test
    void aThrowingTickStillPublishesItsFrame() {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);
        AtomicInteger executed = new AtomicInteger();

        try {
            barrier.submit(new Object(), executed::incrementAndGet, true);
            throw new IllegalStateException("entity tick blew up after handlePortal");
        } catch (IllegalStateException expected) {
            // the production callers publish from a finally
        } finally {
            barrier.publishStaged();
        }

        Runnable frame = out.poll();
        assertNotNull(frame, "the throwing tick's transfer must still be delivered");
        frame.run();
        assertEquals(1, executed.get());
        assertEquals(0, barrier.stagedOnThisThread());
    }

    /** A throwing TRANSFER inside the composite must not swallow the rest of
     *  its frame — every remaining member still runs, and the failure still
     *  surfaces to the drain's error handling. */
    @Test
    void aThrowingTransferDoesNotSwallowTheRestOfTheFrame() {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);
        AtomicInteger executed = new AtomicInteger();

        barrier.submit(new Object(), executed::incrementAndGet, true);
        barrier.submit(new Object(), () -> {
            throw new IllegalStateException("transfer 2 failed");
        }, true);
        barrier.submit(new Object(), executed::incrementAndGet, true);
        barrier.publishStaged();

        Runnable frame = out.poll();
        assertNotNull(frame);
        IllegalStateException surfaced = null;
        try {
            frame.run();
        } catch (IllegalStateException e) {
            surfaced = e; // what drainPending's catch would log
        }
        assertEquals(2, executed.get(),
                "the members after a throwing transfer must still execute");
        assertNotNull(surfaced, "the failure must still reach the drain's error handling");
    }

    private record Result(long executed, long violations) {}

    private Result runProducerConsumer(int iterations, boolean insideTick) throws Exception {
        Queue<Runnable> out = new ConcurrentLinkedQueue<>();
        TransferVisibilityBarrier barrier = new TransferVisibilityBarrier(out);

        // Id of the tick frame currently open on the producer, or NO_FRAME.
        // Correlating by ID matters: a consumer that lags into a LATER frame is
        // perfectly safe — that frame is ticking some other entity and is not
        // writing the pose this transfer captured. Only re-entering the frame
        // that produced the transfer is the bug.
        final long NO_FRAME = -1L;
        AtomicLong openFrame = new AtomicLong(NO_FRAME);
        AtomicInteger violations = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        AtomicLong sharedPose = new AtomicLong();
        AtomicBoolean producerDone = new AtomicBoolean(false);

        Thread consumer = new Thread(() -> {
            while (!producerDone.get() || !out.isEmpty()) {
                Runnable task = out.poll();
                if (task != null) {
                    task.run();
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "consumer");

        Thread producer = new Thread(() -> {
            for (int i = 0; i < iterations; i++) {
                final long frame = i;
                openFrame.set(frame);             // tick frame opens
                barrier.submit(this, () -> {
                    // Stands in for executeSnapshot: whatever it reads of the
                    // source entity must not be concurrently written by the tick
                    // frame that produced this transfer.
                    if (openFrame.get() == frame) {
                        violations.incrementAndGet();
                    }
                    executed.incrementAndGet();
                }, insideTick);
                // Post-submission mutation: AbstractMinecart.tick continuing into
                // behavior.tick() / updateFluidInteraction() after handlePortal.
                for (int s = 0; s < 200; s++) {
                    sharedPose.incrementAndGet();
                }
                openFrame.set(NO_FRAME);          // tick frame closes
                barrier.publishStaged();
            }
            producerDone.set(true);
        }, "producer");

        consumer.start();
        producer.start();
        producer.join(JOIN_TIMEOUT_MS);
        consumer.join(JOIN_TIMEOUT_MS);
        assertFalse(producer.isAlive(), "producer did not finish");
        assertFalse(consumer.isAlive(), "consumer did not finish");
        assertNotNull(sharedPose);

        return new Result(executed.get(), violations.get());
    }
}
