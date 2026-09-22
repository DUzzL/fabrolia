package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTickActorTest {

    @Test
    void executeAndAwaitPublishesBodyWritesAndReusesOnePhysicalThread() {
        PlayerTickActor actor = new PlayerTickActor("PlayerTickActorTest-hb");
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicInteger published = new AtomicInteger();
        try {
            actor.executeAndAwait(() -> {
                firstThread.set(Thread.currentThread());
                published.set(1);
            });
            assertEquals(1, published.get(), "completion must publish every actor-body write");
            assertNotSame(Thread.currentThread(), firstThread.get());
            assertSame(firstThread.get(), actor.thread());

            actor.executeAndAwait(() -> {
                assertSame(firstThread.get(), Thread.currentThread());
                assertEquals(1, published.get());
                published.set(2);
            });
            assertEquals(2, published.get());
        } finally {
            actor.shutdownAndAwait();
        }
    }

    @Test
    void acceptedJobsRunFifoWithoutBodyOverlap() throws Exception {
        PlayerTickActor actor = new PlayerTickActor("PlayerTickActorTest-fifo");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maximumInside = new AtomicInteger();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();

        Thread first = caller("actor-caller-1", actor, callerFailure, () -> {
            enter(1, order, inside, maximumInside);
            firstStarted.countDown();
            awaitUninterruptibly(releaseFirst);
            inside.decrementAndGet();
        });
        Thread second = caller("actor-caller-2", actor, callerFailure, () -> {
            enter(2, order, inside, maximumInside);
            inside.decrementAndGet();
        });
        Thread third = caller("actor-caller-3", actor, callerFailure, () -> {
            enter(3, order, inside, maximumInside);
            inside.decrementAndGet();
        });

        try {
            first.start();
            assertTrue(firstStarted.await(5L, TimeUnit.SECONDS));
            second.start();
            awaitWaiting(second);
            third.start();
            awaitWaiting(third);
            releaseFirst.countDown();
            join(first);
            join(second);
            join(third);

            assertNull(callerFailure.get());
            assertEquals(List.of(1, 2, 3), order);
            assertEquals(1, maximumInside.get(), "actor bodies must never overlap");
        } finally {
            releaseFirst.countDown();
            joinIfStarted(first);
            joinIfStarted(second);
            joinIfStarted(third);
            actor.shutdownAndAwait();
        }
    }

    @Test
    void exactThrowableIdentityIsRethrownAndTerminatesAdmission() {
        PlayerTickActor actor = new PlayerTickActor("PlayerTickActorTest-throwable");
        AssertionError exact = new AssertionError("exact actor body failure");
        AtomicBoolean nextRan = new AtomicBoolean();
        try {
            AssertionError observed = assertThrows(AssertionError.class,
                    () -> actor.executeAndAwait(() -> { throw exact; }));
            assertSame(exact, observed);
            assertFalse(actor.available(), "a body failure must close future admission");
            AssertionError terminal = assertThrows(AssertionError.class,
                    () -> actor.executeAndAwait(() -> nextRan.set(true)));
            assertSame(exact, terminal,
                    "later admission must surface the sticky first body Throwable");
            assertSame(exact, actor.terminalFailure());
            assertFalse(nextRan.get(), "no successor body may run after the first failure");
        } finally {
            actor.shutdownAndAwait();
        }
    }

    @Test
    void acceptedBacklogIsFailedWithTheExactPrimaryWithoutRunning() throws Exception {
        PlayerTickActor actor = new PlayerTickActor("PlayerTickActorTest-fatal-backlog");
        AssertionError exact = new AssertionError("fatal actor body");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> queuedFailure = new AtomicReference<>();
        AtomicBoolean queuedBodyRan = new AtomicBoolean();

        Thread first = caller("fatal-actor-caller", actor, firstFailure, () -> {
            firstStarted.countDown();
            awaitUninterruptibly(releaseFailure);
            throw exact;
        });
        Thread queued = caller("fatal-actor-backlog", actor, queuedFailure,
                () -> queuedBodyRan.set(true));
        try {
            first.start();
            assertTrue(firstStarted.await(5L, TimeUnit.SECONDS));
            queued.start();
            awaitWaiting(queued);
            releaseFailure.countDown();
            join(first);
            join(queued);

            assertSame(exact, firstFailure.get());
            assertSame(exact, queuedFailure.get(),
                    "accepted unstarted obligations must observe the first body Throwable");
            assertFalse(queuedBodyRan.get());
            assertFalse(actor.available());
        } finally {
            releaseFailure.countDown();
            joinIfStarted(first);
            joinIfStarted(queued);
            actor.shutdownAndAwait();
        }
    }

    @Test
    void peerGenerationWinnerDuringOneActorJobRefusesTheNextAcceptedJob()
            throws Exception {
        Field activeField = RegionScheduler.class.getDeclaredField("active");
        Field poolField = RegionScheduler.class.getDeclaredField("pool");
        Field orphanFatalField = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        activeField.setAccessible(true);
        poolField.setAccessible(true);
        orphanFatalField.setAccessible(true);

        boolean previousActive = activeField.getBoolean(null);
        Object previousPool = poolField.get(null);
        Object previousOrphanFatal = orphanFatalField.get(null);
        RegionWorkerPool generation = new RegionWorkerPool(1);
        PlayerTickActor actor = new PlayerTickActor("PlayerTickActorTest-peer-W");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> queuedFailure = new AtomicReference<>();
        AtomicBoolean queuedBodyRan = new AtomicBoolean();
        AssertionError winner = new AssertionError("peer generation W");
        Thread first = caller("actor-peer-W-first", actor, firstFailure, () -> {
            firstStarted.countDown();
            awaitUninterruptibly(releaseFirst);
        });
        Thread queued = caller("actor-peer-W-queued", actor, queuedFailure,
                () -> queuedBodyRan.set(true));

        generation.start();
        try {
            poolField.set(null, generation);
            orphanFatalField.set(null, null);
            activeField.setBoolean(null, true);
            first.start();
            assertTrue(firstStarted.await(5L, TimeUnit.SECONDS));
            queued.start();
            awaitWaiting(queued);

            generation.publishFatalFailure(winner);
            releaseFirst.countDown();
            join(first);
            join(queued);

            assertSame(winner, firstFailure.get(),
                    "the entered job must surface the peer winner at its terminal");
            assertSame(winner, queuedFailure.get(),
                    "the actor backlog must close with the same exact winner");
            assertFalse(queuedBodyRan.get(),
                    "a later accepted actor job entered after generation W");
        } finally {
            releaseFirst.countDown();
            joinIfStarted(first);
            joinIfStarted(queued);
            actor.shutdownAndAwait();
            if (!generation.isTerminated()) {
                try {
                    generation.stop();
                } catch (Throwable ignored) {
                }
            }
            activeField.setBoolean(null, previousActive);
            poolField.set(null, previousPool);
            orphanFatalField.set(null, previousOrphanFatal);
        }
    }

    @Test
    void callerInterruptionNeverAbandonsAnAcceptedBodyAndIsRestored() throws Exception {
        PlayerTickActor actor = new PlayerTickActor("PlayerTickActorTest-interrupt");
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicBoolean returnedInterrupted = new AtomicBoolean();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                actor.executeAndAwait(() -> {
                    bodyStarted.countDown();
                    awaitUninterruptibly(releaseBody);
                });
                returnedInterrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable thrown) {
                callerFailure.set(thrown);
            }
        }, "actor-interrupted-caller");

        try {
            caller.start();
            assertTrue(bodyStarted.await(5L, TimeUnit.SECONDS));
            caller.interrupt();
            awaitWaiting(caller);
            assertTrue(caller.isAlive(), "interrupt must not force-open the completion wait");
            releaseBody.countDown();
            join(caller);
            assertNull(callerFailure.get());
            assertTrue(returnedInterrupted.get(), "the caller interrupt flag must be restored");
        } finally {
            releaseBody.countDown();
            joinIfStarted(caller);
            actor.shutdownAndAwait();
        }
    }

    @Test
    void acceptedSuccessReleasesTheFatalCutBeforeWakingMainAndWaiterRethrowsPeerWinner()
            throws Exception {
        Field activeField = RegionScheduler.class.getDeclaredField("active");
        Field poolField = RegionScheduler.class.getDeclaredField("pool");
        Field orphanFatalField = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        Field actorLockField = PlayerTickActor.class.getDeclaredField("lock");
        activeField.setAccessible(true);
        poolField.setAccessible(true);
        orphanFatalField.setAccessible(true);
        actorLockField.setAccessible(true);

        boolean previousActive = activeField.getBoolean(null);
        Object previousPool = poolField.get(null);
        Object previousOrphanFatal = orphanFatalField.get(null);
        PlayerTickActor actor = new PlayerTickActor("PlayerTickActorTest-success-receipt");
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch fatalPublished = new CountDownLatch(1);
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        AtomicReference<Throwable> publisherFailure = new AtomicReference<>();
        AssertionError exact = new AssertionError("peer generation failure after actor accept");
        Thread caller = caller("actor-success-receipt-caller", actor, callerFailure, () -> {
            bodyStarted.countDown();
            awaitUninterruptibly(releaseBody);
        });
        Thread publisher = new Thread(() -> {
            try {
                assertSame(exact, RegionScheduler.publishFatalAndSelectWinner(exact));
            } catch (Throwable thrown) {
                publisherFailure.set(thrown);
            } finally {
                fatalPublished.countDown();
            }
        }, "actor-success-receipt-publisher");

        try {
            poolField.set(null, null);
            orphanFatalField.set(null, null);
            activeField.setBoolean(null, true);
            caller.start();
            assertTrue(bodyStarted.await(5L, TimeUnit.SECONDS));
            Thread actorThread = actor.thread();
            Object actorLock = actorLockField.get(actor);

            synchronized (actorLock) {
                releaseBody.countDown();
                awaitState(actorThread, Thread.State.BLOCKED);
                publisher.start();
                assertTrue(fatalPublished.await(2L, TimeUnit.SECONDS),
                        "fatal publication must not wait for the actor monitor after receipt accept");
            }

            join(publisher);
            join(caller);
            assertNull(publisherFailure.get());
            assertSame(exact, callerFailure.get(),
                    "the normal local waiter must rethrow a peer-selected generation W");
        } finally {
            releaseBody.countDown();
            joinIfStarted(publisher);
            joinIfStarted(caller);
            actor.shutdownAndAwait();
            activeField.setBoolean(null, previousActive);
            poolField.set(null, previousPool);
            orphanFatalField.set(null, previousOrphanFatal);
        }
    }

    @Test
    void shutdownDrainsEveryAcceptedJobThenRejectsNewAdmission() throws Exception {
        PlayerTickActor actor = new PlayerTickActor("PlayerTickActorTest-shutdown");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> bodies = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();

        Thread first = caller("shutdown-caller-1", actor, callerFailure, () -> {
            bodies.add(1);
            firstStarted.countDown();
            awaitUninterruptibly(releaseFirst);
        });
        Thread acceptedBacklog = caller("shutdown-caller-2", actor, callerFailure,
                () -> bodies.add(2));
        Thread shutdown = new Thread(() -> {
            try {
                actor.shutdownAndAwait();
            } catch (Throwable thrown) {
                shutdownFailure.set(thrown);
            }
        }, "actor-shutdown-caller");

        try {
            assertTrue(actor.available());
            first.start();
            assertTrue(firstStarted.await(5L, TimeUnit.SECONDS));
            acceptedBacklog.start();
            awaitWaiting(acceptedBacklog);
            shutdown.start();
            awaitWaiting(shutdown);

            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> actor.executeAndAwait(() -> bodies.add(3)));
            assertEquals("exact player actor is stopping/stopped", rejected.getMessage());
            assertTrue(shutdown.isAlive(), "shutdown must still be waiting for accepted bodies");

            releaseFirst.countDown();
            join(first);
            join(acceptedBacklog);
            join(shutdown);
            assertNull(callerFailure.get());
            assertNull(shutdownFailure.get());
            assertEquals(List.of(1, 2), bodies);
            assertFalse(actor.thread().isAlive());
            assertFalse(actor.available(), "a terminal actor must make the runtime gate ineligible");
        } finally {
            releaseFirst.countDown();
            joinIfStarted(first);
            joinIfStarted(acceptedBacklog);
            joinIfStarted(shutdown);
            actor.shutdownAndAwait();
        }
    }

    @Test
    void nestedSubmissionFailsBeforeTheNestedBody() {
        PlayerTickActor actor = new PlayerTickActor("PlayerTickActorTest-nested");
        AtomicInteger nestedBodies = new AtomicInteger();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> actor.executeAndAwait(() ->
                            actor.executeAndAwait(nestedBodies::incrementAndGet)));
            assertEquals("nested exact player actor submission", failure.getMessage());
            assertEquals(0, nestedBodies.get());
        } finally {
            actor.shutdownAndAwait();
        }
    }

    private static Thread caller(String name, PlayerTickActor actor,
                                 AtomicReference<Throwable> failure, Runnable body) {
        return new Thread(() -> {
            try {
                actor.executeAndAwait(body);
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, name);
    }

    private static void enter(int ordinal, List<Integer> order, AtomicInteger inside,
                              AtomicInteger maximumInside) {
        int current = inside.incrementAndGet();
        maximumInside.accumulateAndGet(current, Math::max);
        order.add(ordinal);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(thread.getName() + " did not enter WAITING; state="
                        + thread.getState());
            }
            Thread.sleep(1L);
        }
        assertTrue(thread.isAlive(), thread.getName() + " terminated before waiting");
    }

    private static void awaitState(Thread thread, Thread.State expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (thread.isAlive() && thread.getState() != expected) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(thread.getName() + " did not enter " + expected
                        + "; state=" + thread.getState());
            }
            Thread.sleep(1L);
        }
        assertTrue(thread.isAlive(), thread.getName() + " terminated before entering " + expected);
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(Duration.ofSeconds(5));
        assertFalse(thread.isAlive(), thread.getName() + " did not terminate");
    }

    private static void joinIfStarted(Thread thread) throws InterruptedException {
        if (thread.getState() == Thread.State.NEW) return;
        thread.join(Duration.ofSeconds(5));
        assertFalse(thread.isAlive(), thread.getName() + " leaked from the test");
    }
}
