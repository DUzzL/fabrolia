package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class AutonomousPacerNormalStopTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void stoppingDaemonRetiresItsArmSoMainCanJoinWithoutAZombieTicket() throws Exception {
        try (StoppingOwner fixture = new StoppingOwner()) {
            fixture.beginStop();
            fixture.level.resumeLookup.countDown();
            assertTrue(fixture.armReturned.await(2, TimeUnit.SECONDS),
                    "the daemon must retire its arm, not wait for Main to finish joining it");
            assertTrue(fixture.ownerInterrupted.get(),
                    "retiring the arm must leave the normal-stop interrupt intact");
            assertTrue(fixture.queue.isEmpty(),
                    "the stale successful eligibility read must not publish a zombie ticket");
            assertSame(fixture.owner, fixture.pacer.ownerThreadForTest(),
                    "retirement must not install a replacement while the old owner is alive");
            fixture.releaseOwner.countDown();
            fixture.stopper.join(2_000L);
            assertFalse(fixture.stopper.isAlive(), "normal shutdown must finish its exact-owner join");
            assertFalse(fixture.owner.isAlive());
            assertNull(fixture.failure.get());
            assertNull(RegionScheduler.workerFailureOrNull(), "normal stop is not a fatal interrupt");
            assertNull(fixture.pacer.ownerThreadForTest(),
                    "a retired daemon's arm must not leave a zombie owner");
            assertTrue(fixture.queue.isEmpty());
        }
    }

    @Test
    void foreignPublisherStillWaitsForTheOldOwnerAndPreservesItsInterrupt() throws Exception {
        try (StoppingOwner fixture = new StoppingOwner()) {
            fixture.beginStop();
            AtomicBoolean publisherInterrupted = new AtomicBoolean();
            fixture.publisher = new Thread(() -> {
                try {
                    fixture.pacer.ensureStarted();
                    publisherInterrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable failure) {
                    fixture.failure.compareAndSet(null, failure);
                }
            }, "pacer-new-runtime-publisher");
            fixture.publisher.setDaemon(true);
            fixture.publisher.start();
            awaitWaiting(fixture.publisher);
            fixture.publisher.interrupt();
            fixture.level.resumeLookup.countDown();
            assertTrue(fixture.armReturned.await(2, TimeUnit.SECONDS),
                    "the old owner must not take the foreign publisher's wait path");
            assertTrue(fixture.publisher.isAlive());
            assertSame(fixture.owner, fixture.pacer.ownerThreadForTest(),
                    "a foreign publisher cannot replace an owner that has not settled");
            assertTrue(fixture.queue.isEmpty(), "the retiring arm must remain unpublished");
            fixture.releaseOwner.countDown();
            fixture.stopper.join(2_000L);
            fixture.publisher.join(2_000L);
            assertFalse(fixture.stopper.isAlive());
            assertFalse(fixture.publisher.isAlive());
            assertTrue(publisherInterrupted.get());
            assertNull(fixture.failure.get());
            Thread replacement = fixture.pacer.ownerThreadForTest();
            assertNotNull(replacement);
            assertNotSame(fixture.owner, replacement);
            assertTrue(replacement.isAlive());
        }
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < end) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.WAITING, thread.getState(),
                "the new publisher must reach the old-owner settlement wait");
    }

    private static void awaitPreservingInterrupt(CountDownLatch latch, CountDownLatch interruptedSignal) {
        boolean interrupted = false;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (true) {
                try {
                    assertTrue(latch.await(Math.max(0L, end - System.nanoTime()),
                            TimeUnit.NANOSECONDS), "test rendezvous timed out");
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    if (interruptedSignal != null) interruptedSignal.countDown();
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** The real daemon runs an arm at its queue boundary; no production hooks are installed. */
    private static final class StoppingOwner implements AutoCloseable {
        final TemporalControl.Pacer pacer = new TemporalControl.Pacer();
        final CountDownLatch armReturned = new CountDownLatch(1);
        final CountDownLatch releaseOwner = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean ownerInterrupted = new AtomicBoolean();
        final Map<Field, Object> previousScheduler = new LinkedHashMap<>();
        final Map<ServerLevel, RegionizedServer> levels;
        final TestLevel level;
        final RegionizedServer runtime;
        final IslandRegistry.PIsland island;
        final DelayQueue<Delayed> queue;
        final Thread owner;
        final Thread stopper;
        Thread publisher;

        @SuppressWarnings({"unchecked", "rawtypes"})
        StoppingOwner() throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            level.eligibleLookup = new CountDownLatch(1);
            level.resumeLookup = new CountDownLatch(1);
            level.stopInterrupt = new CountDownLatch(1);
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            island = new IslandRegistry.PIsland(1L, 100L);
            island.level = level;
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            for (String name : new String[]{"pool", "transitionPool", "orphanFatalFailure",
                    "active", "accepting", "lifecycleState"}) {
                Field field = field(RegionScheduler.class, name);
                previousScheduler.put(field, field.get(null));
            }
            assertNull(field(RegionScheduler.class, "pool").get(null));
            field(RegionScheduler.class, "pool").set(null, new RegionWorkerPool(1));
            field(RegionScheduler.class, "transitionPool").set(null, null);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            field(RegionScheduler.class, "active").setBoolean(null, true);
            field(RegionScheduler.class, "accepting").setBoolean(null, true);
            Field lifecycle = field(RegionScheduler.class, "lifecycleState");
            lifecycle.set(null, Enum.valueOf((Class) lifecycle.getType(), "RUNNING"));
            levels.put(level, runtime);

            Method prepare = TemporalControl.Pacer.class.getDeclaredMethod("prepareArm",
                    RegionizedServer.class, IslandRegistry.PIsland.class,
                    long.class, long.class, boolean.class);
            prepare.setAccessible(true);
            AtomicBoolean firstPoll = new AtomicBoolean(true);
            queue = new DelayQueue<>() {
                @Override
                public Delayed poll(long timeout, TimeUnit unit) throws InterruptedException {
                    if (firstPoll.compareAndSet(true, false)) {
                        level.lookupThread = Thread.currentThread();
                        try {
                            TemporalControl.Pacer.PreparedArm arm =
                                    (TemporalControl.Pacer.PreparedArm) prepare.invoke(pacer,
                                            runtime, island, Long.MAX_VALUE, Long.MAX_VALUE, false);
                            // Even an attempted acceptance after the stop must remain inert.
                            arm.commit();
                            ownerInterrupted.set(Thread.currentThread().isInterrupted());
                            armReturned.countDown();
                            awaitPreservingInterrupt(releaseOwner, null);
                        } catch (InvocationTargetException thrown) {
                            throw new AssertionError(thrown.getCause());
                        } catch (ReflectiveOperationException thrown) {
                            throw new AssertionError(thrown);
                        }
                    }
                    return super.poll(timeout, unit);
                }
            };
            field(TemporalControl.Pacer.class, "queue").set(pacer, queue);
            pacer.ensureStarted();
            owner = pacer.ownerThreadForTest();
            owner.setUncaughtExceptionHandler((thread, thrown) -> failure.compareAndSet(null, thrown));
            stopper = new Thread(() -> {
                try {
                    pacer.shutdownNormally();
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            }, "pacer-normal-stopper");
            stopper.setDaemon(true);
        }

        void beginStop() throws Exception {
            assertTrue(level.eligibleLookup.await(2, TimeUnit.SECONDS),
                    "the arm must pass scheduler admission before shutdown begins");
            // This is the scheduler's normal-stop admission cut; keep its runtime
            // registered until the exact daemon joins, just as production does.
            synchronized (field(RegionScheduler.class, "LIFECYCLE_LOCK").get(null)) {
                field(RegionScheduler.class, "accepting").setBoolean(null, false);
            }
            stopper.start();
            assertTrue(level.stopInterrupt.await(2, TimeUnit.SECONDS),
                    "shutdown must install its owner marker and interrupt the daemon");
        }

        @Override
        public void close() throws Exception {
            try {
                level.resumeLookup.countDown();
                if (stopper.getState() == Thread.State.NEW) stopper.start();
                if (!armReturned.await(100, TimeUnit.MILLISECONDS)) {
                    // The original self-join cycle ignores interrupts. Release its
                    // wait, then restore the marker before letting run consume the
                    // normal-stop interrupt, so a red run cannot poison later tests.
                    synchronized (pacer) {
                        field(TemporalControl.Pacer.class, "normalStopOwner").set(pacer, null);
                        pacer.notifyAll();
                    }
                    assertTrue(armReturned.await(2, TimeUnit.SECONDS));
                    synchronized (pacer) {
                        field(TemporalControl.Pacer.class, "normalStopOwner").set(pacer, owner);
                    }
                }
                releaseOwner.countDown();
                owner.join(2_000L);
                stopper.join(2_000L);
                if (publisher != null) publisher.join(2_000L);
                if (!stopper.isAlive() && (publisher == null || !publisher.isAlive())) {
                    Thread cleanup = new Thread(pacer::shutdownNormally, "pacer-test-cleanup");
                    cleanup.setDaemon(true);
                    cleanup.start();
                    cleanup.join(2_000L);
                    assertFalse(cleanup.isAlive(), "pacer cleanup must finish");
                }
                assertFalse(owner.isAlive(), "old owner must not escape test teardown");
                assertFalse(stopper.isAlive(), "stopper must not escape test teardown");
                if (publisher != null) assertFalse(publisher.isAlive());
            } finally {
                levels.remove(level);
                for (Map.Entry<Field, Object> previous : previousScheduler.entrySet()) {
                    previous.getKey().set(null, previous.getValue());
                }
            }
        }
    }

    private static final class TestLevel extends ServerLevel {
        CountDownLatch eligibleLookup;
        CountDownLatch resumeLookup;
        CountDownLatch stopInterrupt;
        volatile Thread lookupThread;
        int lookups;

        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public long getGameTime() { return 100L; }

        @Override
        public int hashCode() {
            // eligible() first checks admission under LIFECYCLE_LOCK, then resolves
            // forLevel outside that lock. Pause the latter lookup while preserving
            // its old registered identity: eligibility returns true after stop.
            if (Thread.currentThread() == lookupThread && ++lookups == 2) {
                eligibleLookup.countDown();
                awaitPreservingInterrupt(resumeLookup, stopInterrupt);
            }
            return System.identityHashCode(this);
        }
    }
}
