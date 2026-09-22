package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class RegionPreparationPauseTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void preparationRetainsNextUnitFifoWhileCanonicalPlayerTurnStillDrainsIt() throws Exception {
        RegionWorkerPool pool = workerlessPool();
        RegionizedTaskQueue nextUnit = new RegionizedTaskQueue();
        List<String> effects = new ArrayList<>();
        nextUnit.submit(regionalTask(pool, () -> effects.add("first")));
        nextUnit.submit(regionalTask(pool, () -> effects.add("second")));
        boolean paused = false;
        try {
            assertTrue(pool.pauseMainPreparationAndAwait(() -> assertEquals(List.of(), effects)));
            paused = true;
            assertTrue(pool.ownsPausedExactTurn(Thread.currentThread()));
            assertEquals(List.of(), effects, "preparation must not advance next-unit mutations");
            pool.resumeExactTurn();
            paused = false;

            pool.pauseExactTurnAndAwait(() -> {
                if (!nextUnit.isEmpty()) {
                    assertFalse(pool.ownsPausedExactTurn(Thread.currentThread()),
                            "canonical entity iteration still owes the accepted regional work");
                    nextUnit.drain(Integer.MAX_VALUE);
                }
            });
            paused = true;
            assertEquals(List.of("first", "second"), effects,
                    "the original FIFO must survive preparation unchanged");
        } finally {
            if (paused) pool.resumeExactTurn();
            nextUnit.drain(Integer.MAX_VALUE);
            pool.stop();
        }
    }

    @Test
    void queuedForeignReceiptStillPinsPreparation() throws Exception {
        RegionWorkerPool pool = workerlessPool();
        RegionWorkerPool.ExternalPermit foreign = pool.reserveExternalPermit();
        assertNotNull(foreign);
        AtomicBoolean completed = new AtomicBoolean();
        boolean paused = false;
        try {
            assertTrue(pool.pauseMainPreparationAndAwait(() -> {
                if (!completed.get()) {
                    assertFalse(pool.ownsPausedExactTurn(Thread.currentThread()),
                            "foreign/MAIN_SYNC work is not retained regional data");
                    assertTrue(foreign.begin());
                    assertNull(foreign.finish(null));
                    completed.set(true);
                }
            }));
            paused = true;
            assertTrue(completed.get());
        } finally {
            if (paused) pool.resumeExactTurn();
            if (!completed.get() && foreign.begin()) foreign.finish(null);
            pool.stop();
        }
    }

    @Test
    void enteredRegionalBodyIsNoLongerSuspendableQueueData() throws Exception {
        RegionWorkerPool pool = workerlessPool();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable task = regionalTask(pool, () -> {
            entered.countDown();
            await(release);
        });
        assertBodyPinsPreparation(pool, task, entered, release);
    }

    @Test
    void foreignQueueHelperPinsPreparationBeforeDetachingItsBody() throws Exception {
        RegionWorkerPool pool = workerlessPool();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(pool.enqueueGlobalMainSafe(() -> {
            entered.countDown();
            await(release);
        }));
        assertBodyPinsPreparation(pool, () -> assertTrue(pool.stealOne(false)), entered, release);
    }

    private static void assertBodyPinsPreparation(RegionWorkerPool pool, Runnable body,
                                                  CountDownLatch entered, CountDownLatch release)
            throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                finished.countDown();
            }
        }, "preparation-prior-consumer");
        boolean paused = false;
        consumer.start();
        try {
            await(entered);
            assertTrue(pool.pauseMainPreparationAndAwait(() -> {
                if (finished.getCount() != 0) {
                    assertFalse(pool.ownsPausedExactTurn(Thread.currentThread()),
                            "Main preparation overtook an already-entered body");
                    release.countDown();
                    await(finished);
                }
            }));
            paused = true;
            assertNull(failure.get());
        } finally {
            release.countDown();
            if (paused) pool.resumeExactTurn();
            consumer.join(2_000L);
            assertFalse(consumer.isAlive());
            pool.stop();
        }
    }

    @Test
    void abortReplaysAcceptedRootsBeforeNewcomersWithoutExcludingLiveCausalBodies() throws Exception {
        try (AbortFixture f = new AbortFixture()) {
            RegionWorkerPool pool = f.pool;
            Thread main = Thread.currentThread();
            List<String> roots = new CopyOnWriteArrayList<>();
            List<String> causal = new CopyOnWriteArrayList<>();
            CountDownLatch parentEntered = new CountDownLatch(1);
            CountDownLatch releaseParent = f.latch();
            CountDownLatch headPublished = new CountDownLatch(1);
            CountDownLatch publishDuringClosing = f.latch();
            CountDownLatch closingPublished = new CountDownLatch(1);
            CountDownLatch oldAdmissionEntered = new CountDownLatch(1);
            CountDownLatch publishTail = f.latch();
            CountDownLatch tailAdmissionEntered = new CountDownLatch(1);
            CountDownLatch releaseTailAdmission = f.latch();
            CountDownLatch lateAccepted = new CountDownLatch(1);
            AtomicReference<Runnable> headHandoff = new AtomicReference<>();
            AtomicReference<Runnable> tailHandoff = new AtomicReference<>();
            AtomicBoolean commandRan = new AtomicBoolean();
            f.start("abort-live-parent", () -> pool.runSuccessBodyIfHealthy(() -> {
                headHandoff.set(pool.wrapCausalHandoff(() -> {
                    assertFalse(pool.ownsPausedExactTurn(main));
                    assertTrue(pool.enqueueGlobalMainSafe(() -> causal.add("head")));
                    headPublished.countDown();
                    await(publishDuringClosing);
                    assertFalse(pool.ownsPausedExactTurn(main));
                    assertTrue(pool.enqueueGlobalMainSafe(() -> causal.add("closing")));
                    closingPublished.countDown();
                }));
                tailHandoff.set(pool.wrapCausalHandoff(() -> {
                    assertFalse(pool.ownsPausedExactTurn(main));
                    assertTrue(pool.enqueueGlobalMainSafe(() -> causal.add("tail")));
                    // Hold a genuinely live causal publication across the final
                    // replay cut; it is not a deferred unrelated root.
                    invoke(pool, "enterRootAdmission");
                    try {
                        tailAdmissionEntered.countDown();
                        await(releaseTailAdmission);
                    } finally {
                        invoke(pool, "leaveRootAdmission");
                    }
                }));
                parentEntered.countDown();
                await(releaseParent);
            }));
            await(parentEntered);
            AtomicBoolean progressRan = new AtomicBoolean();
            assertFalse(pool.pauseMainPreparationAndAwait(() -> {
                if (!progressRan.compareAndSet(false, true)) return;
                assertFalse(pool.ownsPausedExactTurn(main));
                f.start("abort-accepted-roots", () -> {
                    invoke(pool, "enterRootAdmission");
                    try {
                        deferRoot(pool, () -> {
                            assertFalse(pool.ownsPausedExactTurn(main),
                                    "rollback must not manufacture exclusive authority");
                            roots.add("head-first");
                            f.start("abort-head-handoff", headHandoff.get());
                            await(headPublished);
                        });
                        deferRoot(pool, () -> roots.add("head-second"));
                        oldAdmissionEntered.countDown();
                        await(publishTail);
                        deferRoot(pool, () -> {
                            assertFalse(pool.ownsPausedExactTurn(main));
                            assertEquals(1L, lateAccepted.getCount(),
                                    "unrelated admission reopened before accepted tail replay");
                            roots.add("tail");
                            f.start("abort-tail-handoff", tailHandoff.get());
                            await(tailAdmissionEntered);
                        });
                    } finally {
                        invoke(pool, "leaveRootAdmission");
                    }
                });
                await(oldAdmissionEntered);
                f.start("abort-closing-controller", () -> {
                    awaitReplayClosing(pool);
                    Thread late = f.start("abort-new-root", () -> {
                        assertTrue(pool.enqueueGlobalMainSafe(() -> roots.add("newer")));
                        lateAccepted.countDown();
                    });
                    awaitWaiting(late);
                    assertEquals(1L, lateAccepted.getCount(),
                            "a newcomer entered while unrelated root admission was closed");
                    publishDuringClosing.countDown();
                    await(closingPublished);
                    publishTail.countDown();
                });
                IslandCommands.enqueueFencedBody(() -> {
                    assertTrue(pool.mayServiceDetachedTransfers());
                    commandRan.set(true);
                });
                assertTrue(IslandCommands.hasRoutedPending());
            }));
            assertFalse(pool.ownsPausedExactTurn(main));
            assertTrue(pool.mayServiceDetachedTransfers(), "false must fully unwind the request");
            assertEquals(List.of("head-first", "head-second", "tail"), roots);
            assertEquals(1L, releaseParent.getCount(), "the original accepted body is still live");
            assertFalse(commandRan.get(), "abort must leave routed work to the outer Main turn");
            await(lateAccepted);
            releaseTailAdmission.countDown();
            releaseParent.countDown();
            f.joinBodies();
            IslandCommands.drainRoutedBatch();
            assertTrue(commandRan.get());
            assertFalse(IslandCommands.hasRoutedPending());
            while (pool.stealOne(false)) { }
            assertEquals(List.of("head", "closing", "tail"), causal);
            assertEquals(List.of("head-first", "head-second", "tail", "newer"), roots,
                    "accepted head/tail roots must replay exactly once before newer roots");
            assertNull(pool.fatalFailureOrNull());
        }
    }

    @Test
    void abortReplayPreservesFirstFailureAndNeverGrantsPreparationAuthority() throws Exception {
        try (AbortFixture f = new AbortFixture()) {
            RegionWorkerPool pool = f.pool;
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = f.latch();
            IllegalStateException winner = new IllegalStateException("first replay failure");
            IllegalArgumentException later = new IllegalArgumentException("later replay failure");
            f.start("abort-failing-parent", () -> pool.runSuccessBodyIfHealthy(() -> {
                entered.countDown();
                await(release);
            }));
            await(entered);
            Throwable observed = assertThrows(IllegalStateException.class,
                    () -> pool.pauseMainPreparationAndAwait(() -> {
                        CountDownLatch accepted = new CountDownLatch(1);
                        f.start("abort-failing-root", () -> {
                            invoke(pool, "enterRootAdmission");
                            try {
                                deferRoot(pool, () -> {
                                    assertFalse(pool.ownsPausedExactTurn(Thread.currentThread()));
                                    pool.publishFatalFailure(winner);
                                    throw later;
                                });
                            } finally {
                                invoke(pool, "leaveRootAdmission");
                                accepted.countDown();
                            }
                        });
                        await(accepted);
                        IslandCommands.enqueueFencedBody(() -> {
                            fail("a failed abort must not execute its routed body");
                        });
                    }));
            assertSame(winner, observed, "replay cleanup must preserve the generation's first W");
            assertSame(winner, pool.fatalFailureOrNull());
            assertFalse(pool.ownsPausedExactTurn(Thread.currentThread()));
            release.countDown();
        }
    }

    // These are the same admission/defer seams used by exact-turn replay tests.
    // No lifecycle flags or active-step counts are written: the entered bodies
    // above own real accepted permits and causal tokens throughout the races.
    private static void deferRoot(RegionWorkerPool pool, Runnable root) {
        synchronized (field(RegionWorkerPool.class, pool, "lifecycleLock")) {
            try {
                Method method = RegionWorkerPool.class.getDeclaredMethod("deferExactRootLocked", Runnable.class);
                method.setAccessible(true);
                assertEquals(Boolean.TRUE, method.invoke(pool, root));
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static void invoke(RegionWorkerPool pool, String name) {
        try {
            Method method = RegionWorkerPool.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(pool);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitReplayClosing(RegionWorkerPool pool) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            synchronized (field(RegionWorkerPool.class, pool, "lifecycleLock")) {
                if (Boolean.TRUE.equals(field(RegionWorkerPool.class, pool, "exactReplayClosing"))) return;
            }
            assertTrue(System.nanoTime() < deadline, "abort never reached the replay closing seam");
            Thread.onSpinWait();
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.WAITING) {
            assertTrue(thread.isAlive() && System.nanoTime() < deadline,
                    "unrelated producer did not wait for replay release");
            Thread.onSpinWait();
        }
    }

    private static Object field(Class<?> owner, Object target, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setScheduler(String name, Object value) {
        try {
            Field field = RegionScheduler.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class AbortFixture implements AutoCloseable {
        final RegionWorkerPool pool = workerlessPool();
        final Object previousPool = field(RegionScheduler.class, null, "pool");
        final Object previousServer = field(RegionScheduler.class, null, "server");
        final Object previousActive = field(RegionScheduler.class, null, "active");
        final Object previousFailure = field(RegionScheduler.class, null, "orphanFatalFailure");
        final List<Thread> bodies = new CopyOnWriteArrayList<>();
        final List<CountDownLatch> releases = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final AssertionError timeout = new AssertionError("preparation abort failed to unwind its live causal work");
        final Thread watchdog;

        AbortFixture() throws Exception {
            assertFalse(IslandCommands.hasRoutedPending(), "fixture requires an empty routed lane");
            setScheduler("pool", pool);
            setScheduler("server", null);
            setScheduler("active", false);
            setScheduler("orphanFatalFailure", null);
            watchdog = new Thread(() -> {
                try {
                    if (!done.await(5, TimeUnit.SECONDS)) {
                        failure.compareAndSet(null, timeout);
                        pool.publishFatalFailure(timeout);
                        releases.forEach(CountDownLatch::countDown);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "abort-replay-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        CountDownLatch latch() {
            CountDownLatch latch = new CountDownLatch(1);
            releases.add(latch);
            return latch;
        }

        Thread start(String name, Runnable body) {
            Thread thread = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable thrown) {
                    // Preserve an expected W, but surface every independent race failure.
                    if (thrown != pool.fatalFailureOrNull()) failure.compareAndSet(null, thrown);
                    pool.publishFatalFailure(thrown);
                    releases.forEach(CountDownLatch::countDown);
                }
            }, name);
            thread.setDaemon(true);
            bodies.add(thread);
            thread.start();
            return thread;
        }

        void joinBodies() throws InterruptedException {
            for (Thread body : bodies) {
                body.join(2_000L);
                assertFalse(body.isAlive(), "accepted body did not finish: " + body.getName());
            }
            assertNull(failure.get(), "background abort race failed");
        }

        @Override
        public void close() throws Exception {
            releases.forEach(CountDownLatch::countDown);
            Throwable winner = pool.fatalFailureOrNull();
            try {
                if (IslandCommands.hasRoutedPending()) {
                    if (winner == null) {
                        winner = new AssertionError("test exited with an undrained routed receipt");
                        pool.publishFatalFailure(winner);
                    }
                    IslandCommands.cancelFailedGeneration(winner);
                }
                if (pool.ownsPausedExactTurn(Thread.currentThread())) pool.resumeExactTurn();
                try {
                    pool.stop();
                } catch (Throwable cleanup) {
                    if (cleanup != winner) throw new AssertionError(cleanup);
                }
                joinBodies();
            } finally {
                done.countDown();
                watchdog.join(2_000L);
                setScheduler("pool", previousPool);
                setScheduler("server", previousServer);
                setScheduler("active", previousActive);
                setScheduler("orphanFatalFailure", previousFailure);
            }
        }
    }

    private static RegionWorkerPool workerlessPool() throws Exception {
        RegionWorkerPool pool = new RegionWorkerPool(1);
        for (String name : List.of("running", "accepting")) {
            Field field = RegionWorkerPool.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(pool, true);
        }
        return pool;
    }

    private static Runnable regionalTask(RegionWorkerPool pool, Runnable body) throws Exception {
        Method reserve = RegionWorkerPool.class.getDeclaredMethod("reserveExternalTask", Runnable.class);
        reserve.setAccessible(true);
        return (Runnable) reserve.invoke(pool, body);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS), "prior consumer failed to reach its boundary");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
