package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises retained gameplay roots on real pool workers; no woven Minecraft tick is simulated. */
@Isolated
final class RegionWorkerPoolExclusiveGameplayTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void mainGrantsOneRetainedRootWithoutLendingItsAuthorityToOtherThreads() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Fixture f = new Fixture()) {
                List<String> events = new ArrayList<>();
                Thread main = Thread.currentThread();
                assertTrue(f.pool.enqueueGlobal(() -> {
                    assertInstanceOf(RegionWorkerThread.class, Thread.currentThread());
                    assertNotSame(main, Thread.currentThread());
                    assertTrue(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                    assertFalse(f.pool.ownsExclusiveGameplay(main));
                    events.add("first");
                }));
                assertTrue(f.pool.enqueueGlobal(() -> {
                    assertTrue(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                    events.add("second");
                }));

                assertFalse(f.pool.ownsExclusiveGameplay(main));
                assertTrue(f.pool.runExclusiveWorkerStep());
                assertEquals(List.of("first"), events);
                assertFalse(f.pool.ownsExclusiveGameplay(main));
                assertTrue(f.pool.runExclusiveWorkerStep());
                assertEquals(List.of("first", "second"), events);
                assertFalse(f.pool.ownsExclusiveGameplay(main));
            }
        });
    }

    @Test
    void workerOwnedPlayerContinuationRetainsMainOwnershipAfterNestedGameplayReturns() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (ChunkServiceFixture f = new ChunkServiceFixture()) {
                var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
                ServerPlayer player = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
                List<String> events = new ArrayList<>();
                assertTrue(f.pool.enqueueGlobal(() -> {
                    assertTrue(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                    RegionScheduler.enqueueGlobalMainGameplay(() -> {
                        assertSame(f.main, Thread.currentThread());
                        RegionScheduler.runGlobalMainBody(f.server,
                                () -> AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(player));
                        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(player);
                        events.add("continuation-completed");
                    });
                    events.add("producer-returned");
                }));

                assertTrue(f.pool.runExclusiveWorkerStep());
                assertEquals(List.of("producer-returned"), events,
                        "owning gameplay does not authorize player tracking on the worker");
                assertTrue(f.pool.runExclusiveWorkerStep());
                assertEquals(List.of("producer-returned", "continuation-completed"), events);
                assertFalse(f.pool.ownsExclusiveGameplay(f.main),
                        "the complete continuation must release its global ownership");
            }
        });
    }

    @Test
    void pendingRootsProgressWhileUnclaimableReadyEntriesKeepArriving() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Fixture f = new Fixture()) {
                Region notClaimable = new Region(null, 0L);
                var publishReady = RegionWorkerPool.class.getDeclaredMethod("publishReady", Region.class);
                publishReady.setAccessible(true);
                List<String> completed = new ArrayList<>();
                AtomicInteger otherUnits = new AtomicInteger();
                assertTrue(f.pool.enqueueUnit(() -> completed.add("unit")));
                assertTrue(f.pool.enqueueGlobal(() -> completed.add("global")));
                // A ready notification does not imply current claim eligibility.
                // Replenish both lanes: swapping their absolute priority would
                // merely move starvation from units to the next pending root.
                for (int grant = 0; grant < 16; grant++) {
                    publishReady.invoke(f.pool, notClaimable);
                    publishReady.invoke(f.pool, notClaimable);
                    assertTrue(f.pool.enqueueUnit(otherUnits::incrementAndGet));
                    f.pool.runExclusiveWorkerStep();
                }
                assertTrue(completed.contains("unit"), "pending opaque unit-lane root must make progress");
                assertTrue(completed.contains("global"), "pending global root must make progress");
            }
        });
    }

    @Test
    void selfReplenishingGameplayCannotStarveNativeChunkCompletion() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (ChunkServiceFixture f = new ChunkServiceFixture()) {
                AtomicInteger units = new AtomicInteger();
                AtomicInteger chunks = new AtomicInteger();
                CompletableFuture<Void> generated = new CompletableFuture<>();
                List<String> nativeOrder = new ArrayList<>();
                f.mailbox.schedule(() -> nativeOrder.add("first"));
                f.mailbox.schedule(() -> {
                    nativeOrder.add("completion");
                    generated.complete(null);
                });
                Runnable replenishedChunk = new Runnable() {
                    @Override public void run() {
                        assertSame(f.main, Thread.currentThread());
                        assertFalse(f.pool.ownsExclusiveGameplay(f.main));
                        if (chunks.incrementAndGet() < 128) f.mailbox.schedule(this);
                    }
                };
                f.mailbox.schedule(replenishedChunk);
                assertTrue(f.pool.enqueueUnit(new Runnable() {
                    @Override public void run() {
                        assertInstanceOf(RegionWorkerThread.class, Thread.currentThread());
                        if (units.incrementAndGet() < 64) assertTrue(f.pool.enqueueUnit(this));
                    }
                }));
                f.pool.wakeAll();
                // Plain JUnit has no poll-return mixin. Pair the actual vanilla
                // native poll with the production residual grant explicitly.
                for (int i = 0; i < 12; i++) {
                    assertTrue(f.server.pollTask());
                    assertTrue(f.pool.runExclusiveWorkerStep());
                }
                assertTrue(generated.isDone(), "native chunk completion must progress despite queued units");
                assertEquals(List.of("first", "completion"), nativeOrder);
                assertTrue(units.get() > 1, "native work must not monopolize gameplay grants");
                assertTrue(chunks.get() > 0 && chunks.get() < 128,
                        "service native callbacks without draining a replenished FIFO to exhaustion");
            }
        });
    }

    @Test
    void exactRunAllTasksStopsAtEmptyNativeFifoAndServicesOnlyPublishedCohort() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            List<String> events = new ArrayList<>();
            AtomicInteger roots = f.enqueueReplenishingRoots(events);
            assertTrue(f.pool.enqueueUnit(() -> events.add("published-second")));
            assertTrue(f.pool.enqueueUnit(() -> events.add("published-third")));

            f.runMainWait(() -> f.drainNativeTasks(events),
                    () -> f.server.managedBlock(() -> true));

            assertEquals(List.of("drained", "root-1", "published-second", "published-third"), events,
                    "native-empty must end the exact drain, then all already-published roots get a turn");
            assertEquals(1, roots.get(),
                    "the causally queued successor belongs to a later cohort, not an unbounded drain");
            assertEquals(0, ((TestDedicatedServer) f.server).nativeIdleWaits,
                    "an empty native checkpoint must not park while captured gameplay is due");
        }
    }

    @Test
    void overdueSlicesRetainTheirCutBeforeLaterHigherPriorityRoots() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            ((TestDedicatedServer) f.server).setNativeTickDeadline(net.minecraft.util.Util.getNanos() - 1L);
            List<String> events = new ArrayList<>();
            assertTrue(f.pool.enqueueUnit(() -> {
                events.add("first");
                assertTrue(f.pool.enqueueGlobal(() -> events.add("later-global")));
            }));
            assertTrue(f.pool.enqueueUnit(() -> events.add("old-unit")));
            assertTrue(f.pool.enqueueGlobalMainSafe(() -> events.add("old-main-safe")));

            for (int slice = 0; slice < 4; slice++) {
                f.runMainWait(() -> f.drainNativeTasks(events),
                        () -> f.server.managedBlock(() -> true));
                assertEquals(slice + 1L, events.stream().filter(event -> !event.equals("drained")).count(),
                        "an overdue slice must yield after one terminal callback");
            }
            assertEquals(List.of("drained", "first", "drained", "old-main-safe",
                    "drained", "old-unit", "drained", "later-global"), events,
                    "resuming must not give a newly published higher-priority lane an old cut's slot");
        }
    }

    @Test
    void resumedCutOffersYoungNativeWorkBeforeItsNextGrant() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            ((TestDedicatedServer) f.server).setNativeTickDeadline(net.minecraft.util.Util.getNanos() - 1L);
            field(BlockableEventLoop.class, "blockingCount").set(f.server, 0);
            List<String> events = new ArrayList<>();
            assertTrue(f.pool.enqueueUnit(() -> {
                events.add("first");
                f.server.schedule(new TickTask(f.server.getTickCount(), () -> events.add("young")));
            }));
            assertTrue(f.pool.enqueueUnit(() -> events.add("second")));
            for (int slice = 0; slice < 2; slice++) {
                f.runMainWait(() -> f.drainNativeTasks(events),
                        () -> f.server.managedBlock(() -> true));
            }
            assertEquals(List.of("drained", "first", "drained", "young", "second"), events,
                    "the resumed first grant still owes an eligible native FIFO opportunity");
            assertEquals(0, ((TestDedicatedServer) f.server).nativeIdleWaits);
        }
    }

    @Test
    void checkpointExhaustingNativeBudgetDefersTheNextWholeGrant() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            List<String> events = new ArrayList<>();
            assertTrue(f.pool.enqueueUnit(() -> {
                events.add("first");
                f.server.schedule(new TickTask(f.server.getTickCount(), () -> {
                    events.add("checkpoint");
                    ((TestDedicatedServer) f.server)
                            .setNativeTickDeadline(net.minecraft.util.Util.getNanos() - 1L);
                }));
            }));
            assertTrue(f.pool.enqueueUnit(() -> events.add("second")));
            f.runMainWait(() -> f.drainNativeTasks(events),
                    () -> f.server.managedBlock(() -> true));
            assertEquals(List.of("drained", "first", "checkpoint"), events,
                    "a native checkpoint cannot authorize another grant after it consumes the budget");
            f.runMainWait(() -> f.drainNativeTasks(events),
                    () -> f.server.managedBlock(() -> true));
            assertEquals(List.of("drained", "first", "checkpoint", "drained", "second"), events);
        }
    }

    @Test
    void expiredOuterPollBudgetDoesNotBlockNestedOrPredicateDependencies() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            ((TestDedicatedServer) f.server).setNativeTickDeadline(net.minecraft.util.Util.getNanos() - 1L);
            List<String> events = new ArrayList<>();
            f.enqueueReplenishingRoots(events);
            f.runMainWait(() -> f.drainNativeTasks(events), () ->
                    f.server.managedBlock(() -> {
                        assertTrue(f.pollMain(), "a direct predicate poll has no optional-poll receipt");
                        f.server.managedBlock(() -> {
                            assertTrue(RconCommandFenceScope.callManagedBlockPoll(f.server, f::pollMain),
                                    "the nested native count must keep dependency progress uncapped");
                            return true;
                        });
                        Error failure = new Error("outer native poll");
                        assertSame(failure, assertThrows(Error.class, () ->
                                RconCommandFenceScope.callManagedBlockPoll(f.server, () ->
                                        RconCommandFenceScope.callMainPoll(f.server, () -> { throw failure; }))));
                        assertFalse(RconCommandFenceScope.callManagedBlockPoll(f.server, f::pollMain),
                                "restored outer provenance must retain this loop's already-spent minimum");
                        return true;
                    }));
            assertEquals(List.of("drained", "root-1", "root-2", "root-3"), events);
        }
    }

    @Test
    void nativeConsumptionOfCapturedMainSafeRootCannotAdmitItsSuccessor() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            List<String> events = new ArrayList<>();
            assertTrue(f.pool.enqueueUnit(() -> {
                events.add("unit-first");
                RegionScheduler.scheduleMainControlTask(f.server, () -> {
                    events.add("native");
                    RegionScheduler.runGlobalMainBody(f.server, () ->
                            assertTrue(f.pool.runOnePublishedMainSafeUnderMainOwnership()));
                    assertTrue(f.pool.enqueueGlobalMainSafe(() -> events.add("new-main-safe")));
                });
            }));
            assertTrue(f.pool.enqueueUnit(() -> events.add("unit-second")));
            assertTrue(f.pool.enqueueGlobalMainSafe(() -> events.add("captured-main-safe")));

            f.runMainWait(() -> f.drainNativeTasks(events),
                    () -> f.server.managedBlock(() -> true));

            assertEquals(List.of("drained", "unit-first", "native",
                    "captured-main-safe", "unit-second"), events);
            assertTrue(f.pool.runExclusiveWorkerStep());
            assertEquals("new-main-safe", events.getLast(),
                    "the replacement remains accepted, but belongs to the next cut");
        }
    }

    @Test
    void refusedReadyBatchDoesNotStrandUnitsOrSpendCapturedSlotsOnNewNotifications() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            Region notClaimable = new Region(null, 0L);
            var publishReady = RegionWorkerPool.class.getDeclaredMethod("publishReady", Region.class);
            publishReady.setAccessible(true);
            for (int i = 0; i < 1100; i++) publishReady.invoke(f.pool, notClaimable);
            AtomicInteger units = new AtomicInteger();
            assertTrue(f.pool.enqueueUnit(() -> {
                units.incrementAndGet();
                for (int i = 0; i < 100; i++) {
                    try {
                        publishReady.invoke(f.pool, notClaimable);
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                }
            }));

            f.runMainWait(() -> f.drainNativeTasks(new ArrayList<>()),
                    () -> f.server.managedBlock(() -> true));

            assertEquals(1, units.get(), "a body-free ready grant does not exhaust other lanes");
            assertEquals(100, notClaimable.readyNotifications,
                    "batched retirement stops at the original ready prefix, not its newly linked tail");
        }
    }

    @Test
    void nativeCheckpointSuppressesOnlyItsOuterPollAndRestoresPolicyAfterFailure() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            List<String> events = new ArrayList<>();
            f.enqueueReplenishingRoots(events);
            RegionScheduler.scheduleMainControlTask(f.server, () -> {
                events.add("control");
                assertTrue(f.pollMain(), "nested dependency polling retains residual progress");
                events.add("returned");
            });
            RconCommandFenceScope.runNativeCheckpoint(f.server, () -> assertTrue(f.pollMain()));
            assertEquals(List.of("control", "root-1", "returned"), events,
                    "the outer checkpoint cannot grant the successor after its native body");
            Error failure = new Error("checkpoint");
            assertSame(failure, assertThrows(Error.class, () ->
                    RconCommandFenceScope.runNativeCheckpoint(f.server, () -> { throw failure; })));
            assertTrue(f.pollMain());
            assertEquals("root-2", events.getLast(), "scope cleanup restores ordinary poll policy");
        }
    }

    @Test
    void exactRunAllTasksRetainsYoungNativeHeadAndGrantsOneRootAfterDrain() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            field(BlockableEventLoop.class, "blockingCount").set(f.server, 0);
            List<String> events = new ArrayList<>();
            AtomicInteger roots = f.enqueueReplenishingRoots(events);
            int tick = f.server.getTickCount();
            f.server.schedule(new TickTask(tick - 8, () -> events.add("first")));
            TickTask young = new TickTask(tick, () -> events.add("young"));
            f.server.schedule(young);
            f.server.schedule(new TickTask(tick - 8, () -> events.add("older-tail")));

            f.runMainWait(() -> f.drainNativeTasks(events),
                    () -> f.server.managedBlock(() -> true));

            assertEquals(List.of("first", "drained", "root-1"), events);
            assertEquals(1, roots.get());
            assertSame(young, f.workers.nativeTasks.peek(),
                    "residual work must neither remove an ineligible head nor skip to its eligible tail");

            field(MinecraftServer.class, "tickCount").set(f.server, tick + 8);
            f.runMainWait(() -> f.drainNativeTasks(events),
                    () -> f.server.managedBlock(() -> true));

            assertEquals(List.of("first", "drained", "root-1",
                    "young", "older-tail", "drained", "root-2"), events);
            assertTrue(f.workers.nativeTasks.isEmpty());
        }
    }

    @Test
    void nestedMainControlPollMakesResidualProgressBeforeItsCallbackReturns() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            List<String> events = new ArrayList<>();
            f.enqueueReplenishingRoots(events);
            RegionScheduler.scheduleMainControlTask(f.server, () -> {
                events.add("control-enter");
                assertTrue(f.pollMain());
                assertEquals(List.of("control-enter", "root-1"), events,
                        "a nested empty native poll must not inherit the outer exact false-cut");
                events.add("control-return");
            });

            f.runMainWait(() -> f.drainNativeTasks(events),
                    () -> f.server.managedBlock(() -> true));

            assertEquals(List.of("control-enter", "root-1", "control-return",
                    "drained", "root-2"), events);
        }
    }

    @Test
    void managedBlockPollRetainsResidualProgressAfterExactDrainCloses() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            List<String> events = new ArrayList<>();
            f.enqueueReplenishingRoots(events);

            f.runMainWait(() -> f.drainNativeTasks(events), () -> {
                assertEquals(List.of("drained", "root-1"), events);
                assertTrue(f.pollMain());
                assertEquals(List.of("drained", "root-1", "root-2"), events);
            });
        }
    }

    @Test
    void nativeDrainErrorSkipsPostdrainGrantAndRetiresItsPollReceipt() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            List<String> events = new ArrayList<>();
            AtomicInteger roots = f.enqueueReplenishingRoots(events);
            Error failure = new Error("native task failure");
            f.server.schedule(new TickTask(f.server.getTickCount(), () -> {
                events.add("native-failure");
                throw failure;
            }));

            assertSame(failure, assertThrows(Error.class, () ->
                    f.runMainWait(() -> f.drainNativeTasks(events),
                            () -> fail("failed native drain must not reach managedBlock"))));
            assertEquals(List.of("native-failure"), events);
            assertEquals(0, roots.get(), "failure must not enter a postdrain success root");

            f.runMainWait(() -> f.drainNativeTasks(events),
                    () -> f.server.managedBlock(() -> true));
            assertEquals(List.of("native-failure", "drained", "root-1"), events);
        }
    }

    @Test
    void swallowedNativeExceptionSurfacesCanonicalFailureBeforeResidualProgress() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            List<String> events = new ArrayList<>();
            AtomicInteger roots = f.enqueueReplenishingRoots(events);
            RuntimeException failure = new IllegalStateException("native control failure");
            RegionScheduler.scheduleMainControlTask(f.server, () -> {
                events.add("native-failure");
                assertSame(failure, RegionScheduler.publishFatalAndSelectWinner(failure));
                // Vanilla catches Exception from a queued callback; the fatal
                // receipt must still abort the composed poll and its drain.
                throw failure;
            });

            assertSame(failure, assertThrows(RuntimeException.class, () ->
                    f.runMainWait(() -> f.drainNativeTasks(events),
                            () -> fail("failed native drain must not reach managedBlock"))));
            assertEquals(List.of("native-failure"), events);
            assertEquals(0, roots.get());
        }
    }

    @Test
    void exactEmptyPollSurfacesFailurePublishedBeforeItsReturnPolicy() throws Exception {
        try (ChunkServiceFixture f = new ChunkServiceFixture()) {
            List<String> events = new ArrayList<>();
            AtomicInteger roots = f.enqueueReplenishingRoots(events);
            RuntimeException failure = new IllegalStateException("failure at native false cut");

            assertSame(failure, assertThrows(RuntimeException.class, () ->
                    f.runMainWait(() -> {
                        RconCommandFenceScope.callRunAllTasksPoll(f.server, () ->
                                RconCommandFenceScope.callMainPoll(f.server, () -> {
                                    boolean nativeProgress = f.server.pollTask();
                                    assertFalse(nativeProgress);
                                    // Deterministically publish W after native polling,
                                    // where the method-entry failure check cannot see it.
                                    assertSame(failure,
                                            RegionScheduler.publishFatalAndSelectWinner(failure));
                                    return RegionScheduler.serviceMainGameplayAfterPoll(
                                            f.server) || nativeProgress;
                                }));
                        fail("the false-cut policy must surface W before returning to the drain");
                    }, () -> fail("failed native drain must not reach managedBlock"))));
            assertEquals(List.of(), events);
            assertEquals(0, roots.get());
        }
    }

    @Test
    void nativeDependencyServiceCannotBorrowTheSelectedWorkersAuthority() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (ChunkServiceFixture f = new ChunkServiceFixture()) {
                AtomicReference<Thread> selected = new AtomicReference<>();
                AtomicInteger serviced = new AtomicInteger();
                f.mailbox.schedule(() -> {
                    assertSame(f.main, Thread.currentThread());
                    assertTrue(RegionScheduler.inNativeMainService());
                    assertFalse(f.pool.ownsExclusiveGameplay(f.main));
                    assertFalse(f.pool.ownsExclusiveGameplay(selected.get()));
                    assertFalse(RegionScheduler.ownsExclusiveGameplay());
                    assertFalse(f.pool.runExclusiveWorkerStep(),
                            "native service cannot upgrade itself into a nested global grant");
                    serviced.incrementAndGet();
                });
                assertTrue(f.pool.enqueueGlobal(() -> {
                    selected.set(Thread.currentThread());
                    assertTrue(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                    assertTrue(f.pool.parkExclusiveWorkerForMainService());
                    assertEquals(1, serviced.get());
                    assertTrue(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                }));
                assertTrue(f.pool.runExclusiveWorkerStep());
                assertEquals(1, serviced.get());
                assertFalse(f.pool.ownsExclusiveGameplay(f.main));
            }
        });
    }

    @Test
    void detachedFencedBodyWaitsUntilSelectedGlobalBodyEnds() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (ChunkServiceFixture f = new ChunkServiceFixture()) {
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                CountDownLatch detachedRan = new CountDownLatch(1);
                ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
                AtomicReference<Thread> selected = new AtomicReference<>();
                AtomicReference<Thread> detachedThread = new AtomicReference<>();
                AtomicReference<Boolean> selectedOwnedAtDetachedEntry = new AtomicReference<>();
                AtomicReference<Throwable> producerFailure = new AtomicReference<>();
                AtomicInteger detachedBodies = new AtomicInteger();
                assertTrue(f.pool.enqueueGlobal(() -> {
                    selected.set(Thread.currentThread());
                    assertInstanceOf(RegionWorkerThread.class, Thread.currentThread());
                    assertTrue(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                    events.add("global-enter");
                    entered.countDown();
                    try {
                        assertTrue(release.await(3, TimeUnit.SECONDS),
                                "the detached producer must release the entered global body");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    } finally {
                        events.add("global-end");
                    }
                }));
                Thread producer = Thread.ofPlatform().name("detached-fenced-body-producer").start(() -> {
                    try {
                        assertTrue(entered.await(2, TimeUnit.SECONDS));
                        // This publisher has no selected-worker command waiter or
                        // inherited causal authority: only the accepted body lifetime.
                        IslandCommands.enqueueFencedBody(() -> {
                            detachedThread.set(Thread.currentThread());
                            selectedOwnedAtDetachedEntry.set(
                                    f.pool.ownsExclusiveGameplay(selected.get()));
                            events.add("detached");
                            detachedBodies.incrementAndGet();
                            detachedRan.countDown();
                        });
                        // Give the broken inner drain a bounded opportunity to run.
                        // Timeout is not a failure: the correct implementation defers
                        // this body. Only the observed body order decides overlap.
                        detachedRan.await(500, TimeUnit.MILLISECONDS);
                    } catch (Throwable failure) {
                        producerFailure.set(failure);
                    } finally {
                        release.countDown();
                    }
                });
                try {
                    assertTrue(f.pool.runExclusiveWorkerStep());
                    IslandCommands.drainRoutedBatch();
                    // Also consume the ordinary queued wakeup and retry the batch:
                    // neither may replay an already completed accepted body.
                    f.server.pollTask();
                    IslandCommands.drainRoutedBatch();
                    assertNull(producerFailure.get());
                    assertEquals(List.of("global-enter", "global-end", "detached"),
                            new ArrayList<>(events),
                            "a detached Main command must not overlap the entered global body");
                    assertSame(f.main, detachedThread.get());
                    assertEquals(Boolean.FALSE, selectedOwnedAtDetachedEntry.get(),
                            "the selected global worker must relinquish authority before detached Main work");
                    assertEquals(1, detachedBodies.get());
                } finally {
                    release.countDown();
                    try {
                        producer.join(Duration.ofSeconds(3));
                        assertFalse(producer.isAlive());
                    } finally {
                        // Healthy cleanup must service accepted detached work even
                        // after an assertion fails; Fixture.close handles W cancellation.
                        if (f.pool.fatalFailureOrNull() == null) {
                            f.pool.stop(IslandCommands::drainRoutedBatch);
                        }
                    }
                }
            }
        });
    }

    @Test
    void eligibleRegionProgressesWhileUnitRootsKeepArriving() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (RegionFixture f = new RegionFixture()) {
                CompletableFuture<Void> entities = f.enqueueAndDispatch();
                AtomicInteger units = new AtomicInteger();
                // Keep units nonempty at every selection. Absolute unit-first
                // priority would consume all grants without entering the region.
                for (int grant = 0; grant < 16; grant++) {
                    assertTrue(f.pool.enqueueUnit(units::incrementAndGet));
                    assertTrue(f.pool.runExclusiveWorkerStep());
                    assertFalse(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                }
                assertEquals(List.of("queue", "pre", "post"), f.entered);
                assertTrue(entities.isDone(), "eligible entity phase must finish within bounded grants");
                entities.join();
                assertTrue(units.get() > 0, "unit roots must also make progress");
            }
        });
    }

    @Test
    void obsoleteReadyPrefixIsRetiredBeforeOneLiveRegionBody() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (RegionFixture f = new RegionFixture()) {
                Region notClaimable = new Region(null, 0L);
                var publishReady = RegionWorkerPool.class.getDeclaredMethod("publishReady", Region.class);
                publishReady.setAccessible(true);
                for (int i = 0; i < 16; i++) publishReady.invoke(f.pool, notClaimable);
                Region second = f.regionizer.regionForGridKey(2L);
                assertTrue(RegionScheduler.enqueueRegionTask(f.region, () -> {
                    f.assertOwned(f.region);
                    f.entered.add("first");
                }));
                assertTrue(RegionScheduler.enqueueRegionTask(second, () -> {
                    f.assertOwned(second);
                    f.entered.add("second");
                }));
                CompletableFuture<Void> entities = f.runtime.dispatchEntityPhase(
                        f.domain, f.regionizer.snapshotAtEntityDispatchCut(f.domain));

                assertTrue(f.pool.runExclusiveWorkerStep());
                assertEquals(List.of("first", "pre", "post"), f.entered,
                        "retire obsolete notifications, but admit only one live region body");
                assertFalse(entities.isDone());
                assertFalse(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                assertTrue(f.pool.runExclusiveWorkerStep());
                assertEquals(List.of("first", "pre", "post", "second", "pre", "post"), f.entered);
                assertTrue(entities.isDone());
                entities.join();
            }
        });
    }

    @Test
    void readyRetirementYieldsBeforeConsumingAnEntireLargeBacklog() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (RegionFixture f = new RegionFixture()) {
                Region notClaimable = new Region(null, 0L);
                var publishReady = RegionWorkerPool.class.getDeclaredMethod("publishReady", Region.class);
                publishReady.setAccessible(true);
                for (int i = 0; i < 65_536; i++) publishReady.invoke(f.pool, notClaimable);
                CompletableFuture<Void> entities = f.enqueueAndDispatch();
                AtomicInteger units = new AtomicInteger();
                assertTrue(f.pool.enqueueUnit(units::incrementAndGet));

                assertTrue(f.pool.runExclusiveWorkerStep());
                assertTrue(f.entered.isEmpty(), "ready retirement must yield before exhausting the backlog");
                assertFalse(entities.isDone());
                assertTrue(f.pool.runExclusiveWorkerStep());
                assertEquals(1, units.get(), "a pending unit must regain control before the ready tail");
                assertTrue(f.entered.isEmpty());
            }
        });
    }

    @Test
    void unitsOnlyGrantsLeaveReadyAndGlobalBodiesForOrdinaryGrants() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (RegionFixture f = new RegionFixture()) {
                CompletableFuture<Void> entities = f.enqueueAndDispatch();
                AtomicInteger globals = new AtomicInteger();
                AtomicInteger units = new AtomicInteger();
                assertTrue(f.pool.enqueueGlobal(globals::incrementAndGet));
                assertTrue(f.pool.enqueueUnit(units::incrementAndGet));

                assertTrue(f.pool.runExclusiveWorkerStep(true));
                assertEquals(1, units.get());
                assertFalse(f.pool.runExclusiveWorkerStep(true));
                assertEquals(List.of(), f.entered);
                assertFalse(entities.isDone());
                assertEquals(0, globals.get());
                assertFalse(f.pool.ownsExclusiveGameplay(Thread.currentThread()));

                for (int grant = 0; grant < 16
                        && (!entities.isDone() || globals.get() == 0); grant++) {
                    assertTrue(f.pool.runExclusiveWorkerStep());
                }
                assertEquals(List.of("queue", "pre", "post"), f.entered);
                assertTrue(entities.isDone());
                entities.join();
                assertEquals(1, globals.get());
                assertFalse(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
            }
        });
    }

    @Test
    void causalForeignComputationKeepsItsLifetimeButCannotEvaluateGameplay() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Fixture f = new Fixture()) {
                AtomicInteger computed = new AtomicInteger();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                assertTrue(f.pool.enqueueGlobal(() -> {
                    Runnable handoff = f.pool.wrapCausalHandoff(() -> {
                        assertTrue(RegionWorkerPool.currentThreadHasLiveCausalStep());
                        assertFalse(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                        computed.set(42);
                    });
                    Thread foreign = Thread.ofPlatform().name("exclusive-root-pure-handoff").start(() -> {
                        try {
                            handoff.run();
                        } catch (Throwable thrown) {
                            failure.set(thrown);
                        }
                    });
                    try {
                        foreign.join(Duration.ofSeconds(2));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    assertFalse(foreign.isAlive());
                    assertNull(failure.get());
                    assertEquals(42, computed.get());
                    assertTrue(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
                }));
                assertTrue(f.pool.runExclusiveWorkerStep());
                assertFalse(f.pool.ownsExclusiveGameplay(Thread.currentThread()));
            }
        });
    }

    @Test
    void selectedFailureReachesMainAndQueuedBodyCannotStartAfterIt() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Fixture f = new Fixture()) {
                IllegalStateException failure = new IllegalStateException("selected gameplay body");
                AtomicInteger laterBodies = new AtomicInteger();
                assertTrue(f.pool.enqueueGlobal(() -> { throw failure; }));
                assertTrue(f.pool.enqueueGlobal(laterBodies::incrementAndGet));
                assertSame(failure, assertThrows(Throwable.class, f.pool::runExclusiveWorkerStep));
                assertSame(failure, assertThrows(Throwable.class, f.pool::runExclusiveWorkerStep));
                assertEquals(0, laterBodies.get());
            }
        });
    }

    private static final class Fixture implements AutoCloseable {
        final RegionWorkerPool pool;
        final MinecraftServer server;
        final ConcurrentLinkedQueue<TickTask> nativeTasks = new ConcurrentLinkedQueue<>();

        Fixture() throws Exception {
            var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
            field(MinecraftServer.class, "levels").set(server, Map.of());
            field(BlockableEventLoop.class, "pendingRunnables").set(server, nativeTasks);
            pool = new RegionWorkerPool(4, server);
            pool.start();
        }

        @Override
        public void close() {
            if (pool.isTerminated()) return;
            Throwable failure = pool.fatalFailureOrNull();
            if (failure == null) pool.stop(pool::runExclusiveWorkerStep);
            else assertSame(failure, assertThrows(Throwable.class, pool::stop));
        }
    }

    private static final class ChunkServiceFixture implements AutoCloseable {
        final Thread main = Thread.currentThread();
        final Fixture workers = new Fixture();
        final RegionWorkerPool pool = workers.pool;
        final MinecraftServer server = workers.server;
        final ChunkMailbox mailbox = new ChunkMailbox(main);
        final Object previousPool = field(RegionScheduler.class, "pool").get(null);
        final Object previousServer = field(RegionScheduler.class, "server").get(null);
        final Object previousActive = field(RegionScheduler.class, "active").get(null);
        final Object previousFatal = field(RegionScheduler.class, "orphanFatalFailure").get(null);

        ChunkServiceFixture() throws Exception {
            var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            var level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            var source = (ChunkSource) unsafe.allocateInstance(ChunkSource.class);
            source.mailbox = mailbox;
            level.chunkSource = source;
            field(MinecraftServer.class, "levels").set(server, Map.of(Level.OVERWORLD, level));
            field(MinecraftServer.class, "tickRateManager").set(server, new ServerTickRateManager(server));
            field(BlockableEventLoop.class, "blockingCount").set(server, 1);
            field(RegionScheduler.class, "pool").set(null, pool);
            field(RegionScheduler.class, "server").set(null, server);
            field(RegionScheduler.class, "active").set(null, true);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
        }

        AtomicInteger enqueueReplenishingRoots(List<String> events) {
            AtomicInteger roots = new AtomicInteger();
            assertTrue(pool.enqueueUnit(new Runnable() {
                @Override public void run() {
                    int entered = roots.incrementAndGet();
                    events.add("root-" + entered);
                    // Bound cleanup and the old broken drain: failure is an exact
                    // observable count/order mismatch, never an infinite producer.
                    if (entered < 8) assertTrue(pool.enqueueUnit(this));
                }
            }));
            return roots;
        }

        boolean pollMain() {
            // JUnit does not weave the poll wrapper/RETURN hook. Execute the
            // literal native poll once, then use the same production policy.
            return RconCommandFenceScope.callMainPoll(server, () -> {
                RegionScheduler.throwWorkerFailureIfAny();
                boolean nativeProgress = server.pollTask();
                return RegionScheduler.serviceMainGameplayAfterPoll(server)
                        || nativeProgress;
            });
        }

        void drainNativeTasks(List<String> events) {
            while (RconCommandFenceScope.callRunAllTasksPoll(server, this::pollMain)) {
                // The native runAllTasks continuation depends on this poll alone.
            }
            events.add("drained");
        }

        void runMainWait(Runnable runAllTasks, Runnable managedBlock) {
            RconCommandFenceScope.runServerLoopRoot(server, () ->
                    RconCommandFenceScope.runServerEntry(server, () ->
                            RconCommandFenceScope.runServerWaitUntilNextTick(server, () ->
                                    RconCommandFenceScope.runWaitUntilNextTickEntry(server, () -> {
                                        RconCommandFenceScope.runWaitUntilNextTickRunAllTasks(
                                                server, runAllTasks);
                                        RconCommandFenceScope.runWaitUntilNextTickManagedBlock(
                                                server, managedBlock);
                                    }))));
        }

        @Override public void close() throws Exception {
            try {
                workers.close();
            } finally {
                field(RegionScheduler.class, "pool").set(null, previousPool);
                field(RegionScheduler.class, "server").set(null, previousServer);
                field(RegionScheduler.class, "active").set(null, previousActive);
                field(RegionScheduler.class, "orphanFatalFailure").set(null, previousFatal);
            }
        }
    }

    private static final class ChunkMailbox extends BlockableEventLoop<Runnable> {
        private final Thread main;
        ChunkMailbox(Thread main) { super("native-chunk-progress", false); this.main = main; }
        @Override public Runnable wrapRunnable(Runnable task) { return task; }
        @Override protected boolean shouldRun(Runnable task) { return true; }
        @Override protected Thread getRunningThread() { return main; }
        boolean pollOne() { return super.pollTask(); }
    }

    private static final class ChunkSource extends ServerChunkCache {
        ChunkMailbox mailbox;
        private ChunkSource() { super(null, null, null, null, null, null, 0, 0, false, null, null); }
        @Override public boolean pollTask() { return mailbox.pollOne(); }
    }

    private static final class RegionFixture implements AutoCloseable {
        final Fixture workers;
        final RegionWorkerPool pool;
        final EpochDomain domain = new EpochDomain();
        final TestLevel level;
        final RegionizedServer runtime;
        final ThreadedRegionizer regionizer;
        final Region region;
        final List<String> entered = new ArrayList<>();
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool = field(RegionScheduler.class, "pool").get(null);
        final Object previousActive = field(RegionScheduler.class, "active").get(null);
        final Object previousOrphanFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final Object previousHook = field(RegionTickHooks.class, "hook").get(null);
        final boolean previousEventScheduler = FabroliaConfig.regionEventScheduler;
        final boolean previousRegionThreading = FabroliaConfig.enableRegionThreading;

        @SuppressWarnings("unchecked")
        RegionFixture() throws Exception {
            var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            level.rate = new ServerTickRateManager(null);
            level.chunkSource = (ServerChunkCache) unsafe.allocateInstance(ServerChunkCache.class);
            // The real empty-entity body reads getDistanceManager, but has no
            // entities that could dereference it; no entity tick is mocked.
            field(ServerChunkCache.class, "chunkMap").set(level.chunkSource,
                    unsafe.allocateInstance(ChunkMap.class));
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            domain.epoch = 8L;
            regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
            regionizer.owner = runtime;
            field(RegionizedServer.class, "level").set(runtime, level);
            field(RegionizedServer.class, "globalDomain").set(runtime, domain);
            field(RegionizedServer.class, "regionizer").set(runtime, regionizer);
            field(RegionizedServer.class, "islandRegistry").set(runtime, new IslandRegistry());
            field(RegionizedServer.class, "queueOnlyEntityEpoch").set(runtime, Long.MIN_VALUE);
            region = regionizer.regionForGridKey(0L);
            levels = (Map<ServerLevel, RegionizedServer>)
                    field(RegionScheduler.class, "PER_LEVEL").get(null);
            workers = new Fixture();
            pool = workers.pool;
            try {
                field(RegionScheduler.class, "pool").set(null, pool);
                field(RegionScheduler.class, "active").set(null, true);
                field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
                levels.put(level, runtime);
                FabroliaConfig.regionEventScheduler = true;
                FabroliaConfig.enableRegionThreading = true;
                RegionTickHooks.register(new RegionTickHooks.Hook() {
                    @Override
                    public void preEntityTick(Region current) {
                        assertOwned(current);
                        entered.add("pre");
                    }

                    @Override
                    public void postEntityTick(Region current) {
                        assertOwned(current);
                        entered.add("post");
                    }
                });
            } catch (Exception | Error failure) {
                try {
                    close();
                } catch (Exception | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        void assertOwned(Region current) {
            assertSame(level, current.level);
            RegionWorkerThread worker = assertInstanceOf(RegionWorkerThread.class, Thread.currentThread());
            assertSame(worker, current.currentWorker());
            assertSame(current, worker.currentRegion);
            assertTrue(pool.ownsExclusiveGameplay(worker));
        }

        CompletableFuture<Void> enqueueAndDispatch() {
            assertTrue(RegionScheduler.enqueueRegionTask(region, () -> {
                assertOwned(region);
                entered.add("queue");
            }));
            return runtime.dispatchEntityPhase(domain, regionizer.snapshotAtEntityDispatchCut(domain));
        }

        @Override
        public void close() throws Exception {
            try {
                workers.close();
            } finally {
                levels.remove(level, runtime);
                field(RegionScheduler.class, "pool").set(null, previousPool);
                field(RegionScheduler.class, "active").set(null, previousActive);
                field(RegionScheduler.class, "orphanFatalFailure").set(null, previousOrphanFailure);
                field(RegionTickHooks.class, "hook").set(null, previousHook);
                FabroliaConfig.regionEventScheduler = previousEventScheduler;
                FabroliaConfig.enableRegionThreading = previousRegionThreading;
            }
        }
    }

    /** Accessors used by the real empty-entity body, matching the dispatch fixture. */
    private static final class TestLevel extends ServerLevel {
        ServerTickRateManager rate;
        ServerChunkCache chunkSource;

        private TestLevel() {
            super(null, null, null, null, null, null, false, 0L, null, false);
            throw new AssertionError("allocated via Unsafe");
        }

        @Override public ServerTickRateManager tickRateManager() { return rate; }
        @Override public ServerChunkCache getChunkSource() { return chunkSource; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
