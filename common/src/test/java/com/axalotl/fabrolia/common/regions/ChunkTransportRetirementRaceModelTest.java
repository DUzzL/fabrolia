package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Interleaving model for full-task publication versus chunk retirement. */
class ChunkTransportRetirementRaceModelTest {

    private enum State { RESERVED, ENQUEUED, ACKNOWLEDGED, CANCELLED, REPLACED }

    private static final class Rec {
        final Object transportRetireGate = new Object();
        State state = State.RESERVED;
        boolean clientMayHaveChunk;
    }

    private static final class Model {
        final ConcurrentHashMap<Long, Rec> records = new ConcurrentHashMap<>();
        final List<String> eventLoop = Collections.synchronizedList(new ArrayList<>());

        boolean offerFull(long key, Rec rec, String packet) {
            synchronized (rec.transportRetireGate) {
                if (rec.state != State.RESERVED) return false;
                eventLoop.add(packet); // bounded EventLoop.execute linearization
                rec.clientMayHaveChunk = true;
                rec.state = State.ENQUEUED;
                return true;
            }
        }

        void track(long key) {
            records.compute(key, (ignored, old) -> {
                Rec next = new Rec();
                if (old != null) {
                    synchronized (old.transportRetireGate) {
                        next.clientMayHaveChunk = old.clientMayHaveChunk;
                        old.state = State.REPLACED;
                    }
                }
                return next;
            });
        }

        boolean untrack(long key) {
            return untrack(key, null);
        }

        boolean untrack(long key, Runnable keyLocked) {
            boolean[] exposed = new boolean[1];
            records.compute(key, (ignored, rec) -> {
                if (keyLocked != null) keyLocked.run();
                if (rec == null) return null;
                synchronized (rec.transportRetireGate) {
                    exposed[0] = rec.clientMayHaveChunk;
                    rec.state = State.CANCELLED;
                }
                if (exposed[0]) eventLoop.add("forget");
                return null;
            });
            return exposed[0];
        }
    }

    @Test
    void fullWinsThenDropQueuesForgetAfterFull() {
        Model model = new Model();
        Rec rec = new Rec();
        model.records.put(1L, rec);

        assertTrue(model.offerFull(1L, rec, "old-full"));
        assertTrue(model.untrack(1L));
        assertEquals(List.of("old-full", "forget"), model.eventLoop);
    }

    @Test
    void dropWinsThenFullIsRejectedAndNoForgetIsNeeded() {
        Model model = new Model();
        Rec rec = new Rec();
        model.records.put(1L, rec);

        assertFalse(model.untrack(1L));
        assertFalse(model.offerFull(1L, rec, "old-full"));
        assertEquals(List.of(), model.eventLoop);
    }

    @Test
    void acknowledgedLifecycleStillRequiresForget() {
        Model model = new Model();
        Rec rec = new Rec();
        model.records.put(1L, rec);
        assertTrue(model.offerFull(1L, rec, "old-full"));
        synchronized (rec.transportRetireGate) {
            rec.state = State.ACKNOWLEDGED;
        }

        assertTrue(model.untrack(1L));
        assertEquals(List.of("old-full", "forget"), model.eventLoop);
    }

    @Test
    void keyTransactionPreventsSuccessorFullFromOvertakingOldForget()
            throws Exception {
        Model model = new Model();
        Rec old = new Rec();
        model.records.put(1L, old);
        CountDownLatch fullOwnsRec = new CountDownLatch(1);
        CountDownLatch releaseFull = new CountDownLatch(1);
        CountDownLatch dropOwnsKey = new CountDownLatch(1);

        Thread full = Thread.ofPlatform().start(() -> {
            synchronized (old.transportRetireGate) {
                fullOwnsRec.countDown();
                await(releaseFull);
                model.eventLoop.add("old-full");
                old.clientMayHaveChunk = true;
                old.state = State.ENQUEUED;
            }
        });
        assertTrue(fullOwnsRec.await(5, TimeUnit.SECONDS));

        Thread drop = Thread.ofPlatform().start(() ->
                model.untrack(1L, dropOwnsKey::countDown));
        assertTrue(dropOwnsKey.await(5, TimeUnit.SECONDS));

        Thread retrack = Thread.ofPlatform().start(() -> {
            model.track(1L);
            Rec next = model.records.get(1L);
            model.offerFull(1L, next, "new-full");
        });

        releaseFull.countDown();
        full.join();
        drop.join();
        retrack.join();
        assertEquals(List.of("old-full", "forget", "new-full"), model.eventLoop);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for modeled interleaving");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
