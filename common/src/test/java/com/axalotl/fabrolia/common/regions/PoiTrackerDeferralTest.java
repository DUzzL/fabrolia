package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.movement.PoiDistanceTrackerMixin;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the production deferral methods and Minecraft's actual distance graph. */
class PoiTrackerDeferralTest {
    private static final long FIRST = SectionPos.asLong(0, 0, 0);
    // Outside FIRST's graph propagation radius, so FIRST cannot repair a lost notification.
    private static final long SECOND = SectionPos.asLong(64, 0, 0);
    private static final long SECOND_NEIGHBOR = SectionPos.asLong(65, 0, 0);

    @Test
    void notificationAcrossSnapshotBoundarySurvivesTheClear() throws Exception {
        Tracker tracker = new Tracker();
        SnapshotGate pending = new SnapshotGate();
        Field field = PoiDistanceTrackerMixin.class.getDeclaredField("fabrolia$pending");
        field.setAccessible(true);
        field.set(tracker, pending);
        tracker.changeSource(FIRST, true);

        FutureTask<Void> drain = new FutureTask<>(tracker::fabrolia$drainTrackerUpdates, null);
        start("poi-snapshot-drain", drain);
        FutureTask<Void> producer = new FutureTask<>(() -> tracker.changeSource(SECOND, true), null);
        try {
            await(pending.captured);
            Thread producerThread = start("poi-snapshot-producer", producer);
            // Old drain: enqueue finishes between snapshot and clear. Atomic drain:
            // enqueue waits for detachment's leaf monitor, then joins the next batch.
            awaitEnqueuedOrBlocked(producerThread, producer);
        } finally {
            pending.resume.countDown();
        }
        drain.get(10, TimeUnit.SECONDS);
        producer.get(10, TimeUnit.SECONDS);
        tracker.drainAndRelax();

        assertEquals(0, tracker.distance(FIRST));
        assertEquals(0, tracker.distance(SECOND), "accepted village source must not disappear at clear");
        assertEquals(1, tracker.distance(SECOND_NEIGHBOR), "the actual graph must propagate the new source");
    }

    @Test
    void sectionOwnerCanNotifyWhileGraphDrainWaitsForTheSection() throws Exception {
        Tracker tracker = new Tracker();
        tracker.changeSource(SECOND, true);
        tracker.drainAndRelax();
        assertEquals(0, tracker.distance(SECOND));

        tracker.changeSource(FIRST, true);
        tracker.readEntered = new CountDownLatch(1);
        CountDownLatch sectionHeld = new CountDownLatch(1);
        FutureTask<Void> producer = new FutureTask<>(() -> {
            synchronized (tracker.section) {
                sectionHeld.countDown();
                await(tracker.readEntered);
                // This executes with the section monitor held while the drainer
                // owns the graph monitor and is about to read the same section.
                tracker.changeSource(SECOND, false);
            }
            return null;
        });
        start("poi-section-owner", producer);
        await(sectionHeld);
        FutureTask<Void> drain = new FutureTask<>(tracker::fabrolia$drainTrackerUpdates, null);
        start("poi-section-drain", drain);
        producer.get(10, TimeUnit.SECONDS);
        drain.get(10, TimeUnit.SECONDS);
        tracker.drainAndRelax();

        Tracker serial = new Tracker();
        serial.changeSource(SECOND, true);
        serial.drainAndRelax();
        serial.changeSource(FIRST, true);
        serial.changeSource(SECOND, false);
        serial.drainAndRelax();
        assertTrue(tracker.distance(SECOND) > 0, "section mutation must remove the old village source");
        assertEquals(serial.distance(SECOND), tracker.distance(SECOND));
        assertEquals(serial.distance(SECOND_NEIGHBOR), tracker.distance(SECOND_NEIGHBOR),
                "concurrent removal must match the actual graph's serial propagation");
        assertEquals(0, tracker.distance(FIRST));
    }

    private static Thread start(String name, FutureTask<Void> task) {
        Thread thread = new Thread(task, name);
        // A broken lock-order regression must fail without hanging the test JVM.
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out at POI interleaving gate");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitEnqueuedOrBlocked(Thread thread, FutureTask<Void> producer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!producer.isDone() && thread.getState() != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < deadline, "producer never reached the snapshot boundary");
            Thread.yield();
        }
    }

    private static final class SnapshotGate extends LongLinkedOpenHashSet {
        final CountDownLatch captured = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);

        @Override
        public long[] toLongArray() {
            long[] snapshot = super.toLongArray();
            captured.countDown();
            await(resume);
            return snapshot;
        }
    }

    /** Only village truth and level storage are supplied; enqueue, drain and graph are real. */
    private static final class Tracker extends PoiDistanceTrackerMixin {
        final Object section = new Object();
        final LongLinkedOpenHashSet sources = new LongLinkedOpenHashSet();
        final Long2ByteOpenHashMap levels = new Long2ByteOpenHashMap();
        volatile CountDownLatch readEntered;

        Tracker() {
            super(7, 16, 256);
            levels.defaultReturnValue((byte) 7);
        }

        void changeSource(long pos, boolean village) {
            synchronized (section) {
                if (village) {
                    sources.add(pos);
                } else {
                    sources.remove(pos);
                }
                fabrolia$enqueueTrackerUpdate(pos);
            }
        }

        void drainAndRelax() {
            fabrolia$drainTrackerUpdates();
            synchronized (this) {
                runUpdates(Integer.MAX_VALUE);
            }
        }

        int distance(long pos) {
            return getLevel(pos);
        }

        @Override
        protected int getLevelFromSource(long pos) {
            CountDownLatch entered = readEntered;
            if (entered != null) {
                entered.countDown();
            }
            synchronized (section) {
                return sources.contains(pos) ? 0 : 7;
            }
        }

        @Override
        protected int getLevel(long pos) {
            return levels.get(pos);
        }

        @Override
        protected void setLevel(long pos, int level) {
            if (level > 6) {
                levels.remove(pos);
            } else {
                levels.put(pos, (byte) level);
            }
        }
    }
}
