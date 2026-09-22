package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PausedExactActorAdmissionTest {

    @Test
    void pausedPhysicalOwnerAuthorizesActorWithoutAnExternalPermit() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Harness harness = new Harness()) {
                AtomicBoolean bodyRan = new AtomicBoolean();
                AtomicBoolean booleanBodyResult = new AtomicBoolean();
                AtomicInteger nestedBodies = new AtomicInteger();
                PlayerTickActor actor = new PlayerTickActor(
                        "paused-exact-player-actor-test");
                try {
                    harness.executeThroughPlayerActor(actor, () -> {
                        bodyRan.set(true);
                        RegionScheduler.runSuccessBodyIfHealthy(
                                nestedBodies::incrementAndGet);
                        booleanBodyResult.set(RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                            nestedBodies.incrementAndGet();
                            return true;
                        }));
                    });
                } finally {
                    actor.shutdownAndAwait();
                }
                assertTrue(bodyRan.get(),
                        "the legitimate exact actor self-refused behind Main's pause");
                assertEquals(0L, externalObligations(harness.pool),
                        "the exact actor must not create a cyclic external obligation");
                assertTrue(booleanBodyResult.get());
                assertEquals(2, nestedBodies.get(),
                        "a nested success gate self-refused the authenticated actor");
            }
        });
    }

    @Test
    void fatalWinnerBeforeActorCutRefusesBodyWithTheSameThrowable() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            AssertionError winner = new AssertionError("exact pre-entry W");
            try (Harness harness = new Harness()) {
                harness.expectedStopFailure = winner;
                harness.pool.publishFatalFailure(winner);
                AtomicBoolean bodyRan = new AtomicBoolean();
                ActorRun run = harness.startActor(() -> bodyRan.set(true));
                run.join();

                assertSame(winner, run.failure.get());
                assertFalse(bodyRan.get(), "actor body entered after W won the entry cut");
            }
        });
    }

    @Test
    void fatalWinnerAfterEntryIsObservedAtTheActorBodyTerminal() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            AssertionError winner = new AssertionError("exact post-entry W");
            CountDownLatch bodyEntered = new CountDownLatch(1);
            CountDownLatch releaseBody = new CountDownLatch(1);
            Harness harness = new Harness();
            try {
                harness.expectedStopFailure = winner;
                ActorRun run = harness.startActor(() -> {
                    bodyEntered.countDown();
                    await(releaseBody);
                });
                assertTrue(bodyEntered.await(1L, TimeUnit.SECONDS));
                harness.pool.publishFatalFailure(winner);
                releaseBody.countDown();
                run.join();

                assertSame(winner, run.failure.get(),
                        "a locally clean actor body returned past the peer W terminal");
            } finally {
                releaseBody.countDown();
                harness.close();
            }
        });
    }

    @Test
    void nestedCallerCannotSuppressActorBodyFailureAndEnterASuccessor() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            AssertionError winner = new AssertionError("nested actor W");
            AtomicBoolean caughtLocally = new AtomicBoolean();
            AtomicBoolean successorRan = new AtomicBoolean();
            try (Harness harness = new Harness()) {
                harness.expectedStopFailure = winner;
                PlayerTickActor actor = new PlayerTickActor(
                        "paused-exact-suppressor-test");
                try {
                    AssertionError observed = assertThrows(AssertionError.class,
                            () -> harness.executeThroughPlayerActor(actor, () -> {
                                try {
                                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                                        throw winner;
                                    });
                                } catch (AssertionError locallyCaught) {
                                    assertSame(winner, locallyCaught);
                                    caughtLocally.set(true);
                                }
                                assertTrue(RegionalPlayerEntityTicksV2.inExactPlayerActor(),
                                        "W revoked logical actor identity before cleanup finished");
                                assertSame(Thread.currentThread(),
                                        RegionalPlayerEntityTicksV2.logicalServerThread(null));
                                assertTrue(harness.pool.ownsPausedExactTurn(
                                                harness.physicalOwner),
                                        "W erased a physical fence which Main had not resumed");
                                RegionScheduler.runSuccessBodyIfHealthy(
                                        () -> successorRan.set(true));
                            }));
                    assertSame(winner, observed);
                } finally {
                    actor.shutdownAndAwait();
                }
                assertTrue(caughtLocally.get());
                assertFalse(successorRan.get(),
                        "a locally caught actor W allowed the next body to enter");
            }
        });
    }

    private static long externalObligations(RegionWorkerPool pool) throws Exception {
        Field field = RegionWorkerPool.class.getDeclaredField("externalObligations");
        field.setAccessible(true);
        return field.getLong(pool);
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class ActorRun {
        final Thread thread;
        final AtomicReference<Throwable> failure;

        ActorRun(Thread thread, AtomicReference<Throwable> failure) {
            this.thread = thread;
            this.failure = failure;
        }

        void join() throws InterruptedException {
            thread.join(Duration.ofSeconds(2));
            assertFalse(thread.isAlive(), "exact actor did not terminate");
        }
    }

    private static final class Harness implements AutoCloseable {
        final RegionWorkerPool pool = new RegionWorkerPool(1);
        final Thread physicalOwner = Thread.currentThread();
        final Field schedulerPool;
        final Field schedulerActive;
        final AtomicReference<Object> logicalTurn;
        final Object previousPool;
        final Object previousTurn;
        final boolean previousActive;
        Throwable expectedStopFailure;
        boolean paused;
        Object installedTurn;
        Thread actorThread;

        @SuppressWarnings("unchecked")
        Harness() throws Exception {
            schedulerPool = RegionScheduler.class.getDeclaredField("pool");
            schedulerPool.setAccessible(true);
            schedulerActive = RegionScheduler.class.getDeclaredField("active");
            schedulerActive.setAccessible(true);
            Field logicalTurnField = RegionalPlayerEntityTicksV2.class
                    .getDeclaredField("LOGICAL_TURN");
            logicalTurnField.setAccessible(true);
            logicalTurn = (AtomicReference<Object>) logicalTurnField.get(null);
            previousPool = schedulerPool.get(null);
            previousTurn = logicalTurn.get();
            previousActive = schedulerActive.getBoolean(null);
            assertNull(previousPool, "test requires no live scheduler generation");
            assertNull(previousTurn, "test requires no live exact logical turn");

            pool.start();
            schedulerPool.set(null, pool);
            schedulerActive.setBoolean(null, true);
            pool.pauseExactTurnAndAwait(() -> {});
            paused = true;
        }

        void executeThroughPlayerActor(PlayerTickActor actor, Runnable body) {
            actor.executeExactAndAwait(physicalOwner, () -> {
                Object turn;
                try {
                    turn = newLogicalTurn(physicalOwner, Thread.currentThread());
                } catch (Throwable constructionFailure) {
                    PausedExactActorAdmissionTest.<RuntimeException>rethrow(
                            constructionFailure);
                    return;
                }
                if (!logicalTurn.compareAndSet(null, turn)) {
                    throw new IllegalStateException("test could not install logical actor turn");
                }
                try {
                    RegionScheduler.runPausedExactActorBodyIfHealthy(physicalOwner, body);
                } finally {
                    if (!logicalTurn.compareAndSet(turn, null)) {
                        throw new IllegalStateException(
                                "test actor could not release logical actor turn");
                    }
                }
            });
        }

        ActorRun startActor(Runnable body) throws Exception {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Thread> actorRef = new AtomicReference<>();
            Thread actor = Thread.ofPlatform().name("paused-exact-actor-test").unstarted(() -> {
                try {
                    RegionScheduler.runPausedExactActorBodyIfHealthy(physicalOwner, body);
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            actorRef.set(actor);
            installedTurn = newLogicalTurn(physicalOwner, actorRef.get());
            assertTrue(logicalTurn.compareAndSet(null, installedTurn));
            actorThread = actor;
            actor.start();
            return new ActorRun(actor, failure);
        }

        @Override
        public void close() throws Exception {
            if (actorThread != null && actorThread.isAlive()) {
                actorThread.join(Duration.ofSeconds(2));
                assertFalse(actorThread.isAlive(), "test actor remained live during teardown");
            }
            if (installedTurn != null) {
                assertTrue(logicalTurn.compareAndSet(installedTurn, null),
                        "test actor failed to release its logical-turn stamp");
            }
            Throwable cleanupFailure = null;
            if (paused) {
                try {
                    pool.resumeExactTurn();
                    paused = false;
                } catch (Throwable thrown) {
                    cleanupFailure = thrown;
                }
            }
            if (!pool.isTerminated()) {
                try {
                    pool.stop();
                } catch (Throwable thrown) {
                    if (cleanupFailure == null) cleanupFailure = thrown;
                    else if (cleanupFailure != thrown) cleanupFailure.addSuppressed(thrown);
                }
            }
            logicalTurn.set(previousTurn);
            schedulerActive.setBoolean(null, previousActive);
            schedulerPool.set(null, previousPool);
            if (expectedStopFailure == null) {
                if (cleanupFailure != null) rethrow(cleanupFailure);
            } else {
                assertSame(expectedStopFailure, cleanupFailure,
                        "teardown did not retain the generation's exact fatal winner");
            }
        }
    }

    private static Object newLogicalTurn(Thread physicalOwner, Thread actor) throws Exception {
        Class<?> type = Class.forName(
                RegionalPlayerEntityTicksV2.class.getName() + "$LogicalTurn");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(null, 1L, null, physicalOwner, actor);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable thrown) throws T {
        throw (T) thrown;
    }
}
