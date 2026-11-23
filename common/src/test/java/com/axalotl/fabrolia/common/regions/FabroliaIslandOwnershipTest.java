package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.api.FabroliaIslandApi;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the public query against real region/unit claims and the pool pause. */
class FabroliaIslandOwnershipTest {
    private static final BlockPos OWN = new BlockPos(0, 64, 0);
    private static final BlockPos OTHER = new BlockPos(512, 64, 0);

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void exactRegionClaimsWorkInWallAndAutonomousModesWithoutRedstoneGuards() throws Exception {
        boolean previousGuard = FabroliaConfig.fabroliaRedstoneCrossRegionGuard;
        try {
            FabroliaConfig.fabroliaRedstoneCrossRegionGuard = false;
            for (boolean autonomous : new boolean[]{false, true}) {
                try (Fixture fixture = new Fixture(autonomous)) {
                    onThread(true, () -> {
                        RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                        worker.currentRegion = fixture.ownRegion;
                        assertFalse(fixture.owns(OWN), "a context stamp is not a region claim");
                        claim(fixture.ownRegion);
                        try {
                            assertTrue(fixture.owns(OWN));
                            assertFalse(fixture.owns(OTHER));
                            assertFalse(FabroliaIslandApi.ownsCurrentContext(
                                    fixture.otherLevel, OWN), "grid keys do not identify a dimension");
                        } finally {
                            release(fixture.ownRegion);
                        }
                        assertFalse(fixture.owns(OWN), "a released claim cannot authorize stale context");
                    });
                    onThread(false, () -> assertFalse(fixture.owns(OWN)));
                    onThread(true, () -> assertFalse(fixture.owns(OWN)));
                }
            }
        } finally {
            FabroliaConfig.fabroliaRedstoneCrossRegionGuard = previousGuard;
        }
    }

    @Test
    void coordinatorRequiresItsLiveUnitAndCannotBorrowAHelperContext() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            onThread(true, () -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                worker.currentDomain = fixture.own.domain;
                assertFalse(fixture.owns(OWN), "an unclaimed coordinator has no spatial authority");
                assertTrue(fixture.own.tryClaimAutonomousWork());
                fixture.own.noteClaim("ownership-regression-unit");
                try {
                    assertTrue(fixture.owns(OWN));
                    assertFalse(fixture.owns(OTHER));
                    withForeignClaim(fixture.ownRegion,
                            () -> assertFalse(fixture.owns(OWN),
                                    "the unit coordinator must not overlap its member helper"));
                    assertTrue(fixture.owns(OWN));
                    claim(fixture.otherRegion);
                    worker.currentRegion = fixture.otherRegion;
                    try {
                        assertTrue(fixture.owns(OTHER));
                        assertFalse(fixture.owns(OWN),
                                "a foreign helper region shadows the inherited coordinator domain");
                    } finally {
                        worker.currentRegion = null;
                        release(fixture.otherRegion);
                    }
                } finally {
                    fixture.own.clearClaim();
                    fixture.own.unitClaim.set(false);
                }
                assertFalse(fixture.owns(OWN));
            });
        }
    }

    /**
     * After the unit's barrier an empty halo is stamped done for E without a
     * sealed-done stamp, so a stale ready claimant's CAS can still land there
     * before its post-CAS eligibility check backs it out. That transient
     * holder must not revoke the coordinator's raid/lifecycle authority.
     */
    @Test
    void aStaleClaimOnARegionAlreadyDoneForTheUnitDoesNotRevokeTheCoordinator() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            onThread(true, () -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                worker.currentDomain = fixture.own.domain;
                assertTrue(fixture.own.tryClaimAutonomousWork());
                fixture.own.noteClaim("ownership-stale-claimant");
                long previousDone = fixture.ownRegion.doneEpoch;
                try {
                    withForeignClaim(fixture.ownRegion, () -> assertFalse(fixture.owns(OWN),
                            "an in-window helper still excludes the coordinator"));
                    fixture.ownRegion.doneEpoch = fixture.own.domain.epoch;
                    withForeignClaim(fixture.ownRegion, () -> assertTrue(fixture.owns(OWN),
                            "a stale claimant on a done region revoked the coordinator"));
                } finally {
                    fixture.ownRegion.doneEpoch = previousDone;
                    fixture.own.clearClaim();
                    fixture.own.unitClaim.set(false);
                }
            });
        }
    }

    @Test
    void physicalMainNeedsAnExactClaimAndForeignObserversCannotBorrowIt() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            withForeignClaim(fixture.ownRegion, () -> {
                assertFalse(fixture.owns(OWN), "physical Main is not an autonomous island fence");
                onThread(false, () -> assertFalse(fixture.owns(OWN)));
            });
            assertFalse(fixture.owns(OWN), "an idle-looking island is not a held fence");
            IslandCommands.mainClaim(fixture.own);
            try {
                assertTrue(fixture.owns(OWN));
                assertFalse(fixture.owns(OTHER));
                onThread(false, () -> assertFalse(fixture.owns(OWN)));
            } finally {
                IslandCommands.mainRelease(fixture.own);
            }
            assertFalse(fixture.owns(OWN));
            assertTrue(fixture.runtime.quiesceAllIslands());
            try {
                assertTrue(fixture.owns(OWN));
                assertTrue(fixture.owns(OTHER));
                onThread(false, () -> assertFalse(fixture.owns(OTHER)));
            } finally {
                fixture.runtime.releaseAllIslands();
            }
            assertFalse(fixture.owns(OTHER));
        }
    }

    @Test
    void exactPoolPauseAuthenticatesOnlyPhysicalMain() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            assertFalse(fixture.owns(OWN), "wall mode alone does not exclude parallel bodies");
            fixture.pool.pauseExactTurnAndAwait(() -> {});
            try {
                assertTrue(fixture.owns(OWN));
                onThread(false, () -> assertFalse(fixture.owns(OWN),
                        "the pool pause authorizes its physical owner, not another thread"));
            } finally {
                fixture.pool.resumeExactTurn();
            }
            assertFalse(fixture.owns(OWN));
        }
    }

    @Test
    void missingRuntimeDoesNotInventFlatMainQuiescence() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.levels.remove(fixture.level);
            assertFalse(fixture.owns(OWN));
            onThread(false, () -> assertFalse(fixture.owns(OWN)));
        }
    }

    @Test
    void ownerCallbackCompletesBeforeReturnWithoutReleasingItsClaim() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
            assertTrue(fixture.pool.enqueueGlobal(() -> {
                try {
                    RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                    worker.currentRegion = fixture.ownRegion;
                    claim(fixture.ownRegion);
                    try {
                        java.util.ArrayList<String> order = new java.util.ArrayList<>();
                        order.add("before");
                        FabroliaIslandApi.executeOnOwner(fixture.level, OWN, () -> {
                            assertTrue(fixture.owns(OWN));
                            order.add("body");
                        });
                        order.add("after");
                        org.junit.jupiter.api.Assertions.assertEquals(
                                java.util.List.of("before", "body", "after"), order);
                    } finally {
                        release(fixture.ownRegion);
                        worker.currentRegion = null;
                    }
                    done.complete(null);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            }));
            done.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void sharedClockCallbacksWaitForTheExclusiveTurnAndKeepReadModifyWriteOrder() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            set(MinecraftServer.class, fixture.server, "levels", Map.of(Level.OVERWORLD, fixture.level));
            var values = new java.util.ArrayList<Integer>();
            FlatOwnerTasks.start(fixture.server);
            try {
                withForeignClaim(fixture.ownRegion, () -> onThread(false, () -> {
                    for (int delta : new int[]{5, -2}) {
                        FabroliaIslandApi.executeOnOwner(fixture.level, OWN, () -> {
                            assertTrue(fixture.owns(OWN));
                            values.add((values.isEmpty() ? 0 : values.getLast()) + delta);
                        });
                    }
                }));
                assertTrue(values.isEmpty(), "submission did not borrow the worker's claim");
                drainSharedOnMain(fixture);
                org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(5, 3), values);
            } finally {
                closeFlatFixture(null);
            }
        }
    }

    @Test
    void unloadedFlatTargetCannotWriteIntoItsReplacementOrCancelTheNextWorldsWork() throws Exception {
        MinecraftServer server = serverOwnedBy(Thread.currentThread());
        TestLevel old = TestLevel.create(server);
        TestLevel replacement = TestLevel.create(server);
        set(MinecraftServer.class, server, "levels", Map.of(Level.OVERWORLD, old));
        var cancelled = new java.util.ArrayList<Throwable>();
        var writes = new java.util.ArrayList<String>();
        FlatOwnerTasks.start(server);
        try {
            onThread(false, () -> FabroliaIslandApi.executeOnOwner(old, OWN,
                    RegionScheduler.cancellableRoot(() -> writes.add("unloaded"), cancelled::add)));
            set(MinecraftServer.class, server, "levels", Map.of(Level.OVERWORLD, replacement));
            onThread(false, () -> FabroliaIslandApi.executeOnOwner(replacement, OWN,
                    () -> writes.add("replacement")));
            FlatOwnerTasks.closeAdmission();
            assertNull(FlatOwnerTasks.finish(null));
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("replacement"), writes);
            org.junit.jupiter.api.Assertions.assertEquals(1, cancelled.size());
            org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.concurrent.RejectedExecutionException.class, cancelled.getFirst());
        } finally {
            closeFlatFixture(null);
        }
    }

    @Test
    void flatMainDrainsAcceptedOwnerCallbacksBeforeSaveEvenWithoutAnotherPoll() throws Exception {
        MinecraftServer server = serverOwnedBy(Thread.currentThread());
        TestLevel level = TestLevel.create(server);
        set(MinecraftServer.class, server, "levels", Map.of(Level.OVERWORLD, level));
        var order = new java.util.ArrayList<String>();
        FlatOwnerTasks.start(server);
        try {
            Thread main = Thread.currentThread();
            onThread(false, () -> {
                FabroliaIslandApi.executeOnOwner(level, OWN, () -> {
                    org.junit.jupiter.api.Assertions.assertSame(main, Thread.currentThread());
                    assertTrue(FabroliaIslandApi.ownsCurrentContext(level, OWN));
                    order.add("first");
                    FabroliaIslandApi.executeOnOwner(level, OWN, () -> order.add("nested"));
                });
                FabroliaIslandApi.executeOnOwner(level, OWN, () -> order.add("second"));
            });
            assertTrue(order.isEmpty(), "the submitting thread cannot enter the world");
            FlatOwnerTasks.closeAdmission();
            assertNull(FlatOwnerTasks.finish(null));
            for (var wake : nativeTasks(server)) wake.run();
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("first", "nested", "second"), order);
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> FabroliaIslandApi.executeOnOwner(level, OWN, () -> order.add("late")));
        } finally {
            closeFlatFixture(null);
        }
    }

    @Test
    void flatFailureBeforeNativePollCancelsWithOriginalCauseWithoutRunningWorldBody() throws Exception {
        MinecraftServer server = serverOwnedBy(Thread.currentThread());
        TestLevel level = TestLevel.create(server);
        set(MinecraftServer.class, server, "levels", Map.of(Level.OVERWORLD, level));
        Object previous = get(RegionScheduler.class, null, "orphanFatalFailure");
        var cancellations = new java.util.ArrayList<Throwable>();
        var winner = new IllegalStateException("first worker failure");
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        FlatOwnerTasks.start(server);
        try {
            onThread(false, () -> FabroliaIslandApi.executeOnOwner(level, OWN,
                    RegionScheduler.cancellableRoot(bodies::incrementAndGet, cancellations::add)));
            RegionScheduler.publishFatalFailure(winner);
            FlatOwnerTasks.closeAdmission();
            org.junit.jupiter.api.Assertions.assertSame(winner, FlatOwnerTasks.finish(null));
            for (var wake : nativeTasks(server)) wake.run();
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(winner), cancellations);
            org.junit.jupiter.api.Assertions.assertEquals(0, bodies.get());
        } finally {
            closeFlatFixture(winner);
            set(RegionScheduler.class, null, "orphanFatalFailure", previous);
        }
    }

    @Test
    void flatSubmissionReroutesAfterRegionsStartRatherThanBorrowingMain() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            set(MinecraftServer.class, fixture.server, "levels", Map.of(Level.OVERWORLD, fixture.level));
            var writes = new java.util.concurrent.CompletableFuture<Boolean>();
            FlatOwnerTasks.start(fixture.server);
            try {
                set(RegionScheduler.class, null, "active", false);
                set(RegionScheduler.class, null, "server", null);
                onThread(false, () -> FabroliaIslandApi.executeOnOwner(fixture.level, OWN,
                        () -> writes.complete(fixture.owns(OWN))));
                set(RegionScheduler.class, null, "server", fixture.server);
                set(RegionScheduler.class, null, "active", true);
                var shared = new java.util.ArrayList<String>();
                onThread(false, () -> FabroliaIslandApi.executeOnOwner(fixture.level, OWN, () -> shared.add("shared")));
                drainSharedOnMain(fixture);
                org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("shared"), shared);
                assertFalse(writes.isDone(), "the shared cut must not steal a callback from vanilla's FIFO");
                withForeignClaim(fixture.ownRegion, () -> {
                    nativeTasks(fixture.server).remove().run();
                    assertFalse(writes.isDone(), "physical Main did not acquire the occupied region");
                });
                drainSharedOnMain(fixture);
                assertTrue(writes.get(5, TimeUnit.SECONDS), "the eventual write must own its target");
            } finally {
                closeFlatFixture(null);
            }
        }
    }

    @Test
    void nativeReceiptReroutedToSharedStillFollowsTheEntityWhenItsFirstLevelUnloads() throws Exception {
        try (Fixture f = new Fixture(false)) {
            TestLevel destination = f.otherLevel;
            destination.key = Level.NETHER;
            f.levels.put(destination, new RegionizedServer(destination, new RegionGrid(3)));
            FlatOwnerTasks.start(f.server);
            try {
                set(MinecraftServer.class, f.server, "levels", Map.of(Level.OVERWORLD, f.level, Level.NETHER, destination));
                var entity = (net.minecraft.world.entity.item.ItemEntity) unsafe().allocateInstance(
                        net.minecraft.world.entity.item.ItemEntity.class);
                var type = net.minecraft.world.entity.Entity.class;
                set(type, entity, "level", f.level);
                set(type, entity, "chunkPosition", net.minecraft.world.level.ChunkPos.containing(OWN));
                var writes = new java.util.ArrayList<ServerLevel>();
                set(RegionScheduler.class, null, "active", false);
                set(RegionScheduler.class, null, "server", null);
                onThread(false, () -> FabroliaIslandApi.executeOnOwner(entity, () -> {
                    assertTrue(FabroliaIslandApi.ownsCurrentContext(destination, OWN));
                    writes.add((ServerLevel) entity.level());
                }));
                set(RegionScheduler.class, null, "server", f.server);
                set(RegionScheduler.class, null, "active", true);
                nativeTasks(f.server).remove().run();
                assertTrue(writes.isEmpty());
                set(type, entity, "level", destination);
                set(MinecraftServer.class, f.server, "levels", Map.of(Level.NETHER, destination));
                drainSharedOnMain(f);
                org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(destination), writes);
            } finally {
                try { closeFlatFixture(null); } finally { f.levels.remove(destination); }
            }
        }
    }

    private static void drainSharedOnMain(Fixture fixture) {
        fixture.pool.pauseExactTurnAndAwait(() -> {});
        try { FlatOwnerTasks.drainSharedAtTickBoundary(fixture.server); }
        finally { fixture.pool.resumeExactTurn(); }
    }

    private static void closeFlatFixture(Throwable failure) {
        FlatOwnerTasks.closeAdmission();
        try { FlatOwnerTasks.finish(failure); } finally { FlatOwnerTasks.retire(); }
    }

    @Test
    void submissionAndDeliveryFailuresCancelTheUnstartedBodyWithFirstFailure() throws Exception {
        for (String stage : java.util.List.of("server lookup", "after enqueue", "during delivery")) {
            MinecraftServer server = serverOwnedBy(Thread.currentThread());
            TestLevel level = TestLevel.create(server);
            var expected = new IllegalStateException(stage);
            var cancelled = new java.util.ArrayList<Throwable>();
            var writes = new java.util.concurrent.atomic.AtomicInteger();
            Runnable root = RegionScheduler.cancellableRoot(writes::incrementAndGet, cancelled::add);
            Object previous = get(RegionScheduler.class, null, "orphanFatalFailure");
            FlatOwnerTasks.start(server);
            try {
                if (stage.equals("server lookup")) {
                    level.lookupFailure = expected;
                    onThread(false, () -> org.junit.jupiter.api.Assertions.assertSame(expected,
                            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                                    () -> FabroliaIslandApi.executeOnOwner(level, OWN, root))));
                } else if (stage.equals("after enqueue")) {
                    set(net.minecraft.util.thread.BlockableEventLoop.class, server, "pendingRunnables",
                            new java.util.concurrent.ConcurrentLinkedQueue<net.minecraft.server.TickTask>() {
                                @Override public boolean add(net.minecraft.server.TickTask wake) {
                                    super.add(wake);
                                    throw expected;
                                }
                            });
                    onThread(false, () -> org.junit.jupiter.api.Assertions.assertSame(expected,
                            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                                    () -> FabroliaIslandApi.executeOnOwner(level, OWN, root))));
                    nativeTasks(server).remove().run();
                } else {
                    onThread(false, () -> FabroliaIslandApi.executeOnOwner(level, OWN, root));
                    set(MinecraftServer.class, server, "levels", new java.util.HashMap<>() {
                        @Override public Object get(Object key) { throw expected; }
                    });
                    org.junit.jupiter.api.Assertions.assertSame(expected,
                            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                                    nativeTasks(server).remove()::run));
                }
                org.junit.jupiter.api.Assertions.assertSame(expected, RegionScheduler.workerFailureOrNull());
                org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(expected), cancelled);
                org.junit.jupiter.api.Assertions.assertEquals(0, writes.get());
            } finally {
                closeFlatFixture(expected);
                set(RegionScheduler.class, null, "orphanFatalFailure", previous);
            }
        }
    }

    @Test
    void aClosedFlatQueueHonorsOnlyTheAlreadyEnteredMainRewardNotTheWiderBatch() throws Exception {
        MinecraftServer server = serverOwnedBy(Thread.currentThread());
        TestLevel level = TestLevel.create(server);
        set(MinecraftServer.class, server, "levels", Map.of(Level.OVERWORLD, level));
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var scope = com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBatchScope.class;
        var enter = scope.getDeclaredMethod("enterRewardBody");
        var exit = scope.getDeclaredMethod("exitRewardBody");
        enter.setAccessible(true);
        exit.setAccessible(true);
        FlatOwnerTasks.start(server);
        FlatOwnerTasks.closeAdmission();
        try {
            for (int pass = 0; pass < 2; pass++) {
                if (pass == 1) assertNull(FlatOwnerTasks.finish(null));
                com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBatchScope.begin();
                try {
                    org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.RejectedExecutionException.class,
                            () -> FabroliaIslandApi.executeOnOwner(level, OWN, writes::incrementAndGet));
                    enter.invoke(null);
                    try {
                        onThread(false, () -> org.junit.jupiter.api.Assertions.assertThrows(
                                java.util.concurrent.RejectedExecutionException.class,
                                () -> FabroliaIslandApi.executeOnOwner(level, OWN, writes::incrementAndGet)));
                        FabroliaIslandApi.executeOnOwner(level, OWN, writes::incrementAndGet);
                    } finally { exit.invoke(null); }
                } finally {
                    com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBatchScope.end();
                }
                org.junit.jupiter.api.Assertions.assertEquals(pass + 1, writes.get());
            }
        } finally {
            closeFlatFixture(null);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Queue<net.minecraft.server.TickTask> nativeTasks(MinecraftServer server)
            throws Exception {
        return (java.util.Queue<net.minecraft.server.TickTask>) get(
                net.minecraft.util.thread.BlockableEventLoop.class, server, "pendingRunnables");
    }

    @Test
    void foreignWaitProbeUsesPublishedIdentitiesWhileTopologyIsRebuilding() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.runtime.islandRegistry.applyRebuildCells(
                    java.util.List.of(it.unimi.dsi.fastutil.longs.LongArrayList.of(11)), 1);
            IslandRegistry.PIsland island = fixture.runtime.islandRegistry.byCell(11);
            java.util.Iterator<IslandRegistry.PIsland> retained =
                    fixture.runtime.islandRegistry.identitySnapshot().iterator();
            onThread(false, () -> {
                island.unitClaim.set(true);
                island.claimHolder = Thread.currentThread();
                try {
                    assertTrue(RegionScheduler.currentThreadHoldsLiveIslandClaim());
                    // Rebuild staging mutates the private live map before publication.
                    // A guard must keep seeing its immutable committed generation.
                    @SuppressWarnings("unchecked")
                    Long2ObjectOpenHashMap<IslandRegistry.PIsland> live =
                            (Long2ObjectOpenHashMap<IslandRegistry.PIsland>) get(
                                    IslandRegistry.class, fixture.runtime.islandRegistry, "islands");
                    live.clear();
                    assertTrue(RegionScheduler.currentThreadHoldsLiveIslandClaim());
                    org.junit.jupiter.api.Assertions.assertSame(island, retained.next());
                } finally {
                    island.claimHolder = null;
                    island.unitClaim.set(false);
                }
            });
        }
    }

    private static void claim(Region region) {
        assertTrue(region.tryClaim(Thread.currentThread(), region.domain, region.domain.epoch));
    }

    private static void release(Region region) {
        region.release(Thread.currentThread());
        region.finishClaimTransaction();
    }

    private static void withForeignClaim(Region region, CheckedRunnable body) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> {
            claim(region);
            try {
                entered.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
            } finally {
                release(region);
            }
            return null;
        });
        Thread holder = new RegionWorkerThread(task, "ownership-claim-holder");
        holder.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            body.run();
        } finally {
            finish.countDown();
            task.get(5, TimeUnit.SECONDS);
            holder.join(5_000);
            assertFalse(holder.isAlive());
        }
    }

    private static void onThread(boolean worker, CheckedRunnable body) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            body.run();
            return null;
        });
        Thread thread = worker ? new RegionWorkerThread(task, "ownership-query-worker")
                : new Thread(task, "ownership-query-foreign");
        thread.start();
        try {
            task.get(5, TimeUnit.SECONDS);
        } finally {
            thread.join(5_000);
            assertFalse(thread.isAlive());
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class Fixture implements AutoCloseable {
        final Thread physicalMain = Thread.currentThread();
        final MinecraftServer server = serverOwnedBy(physicalMain);
        final RegionWorkerPool pool = new RegionWorkerPool(1);
        final TestLevel level = TestLevel.create(server);
        final TestLevel otherLevel = TestLevel.create(server);
        final RegionizedServer runtime = new RegionizedServer(level, new RegionGrid(3));
        final IslandRegistry.PIsland own = new IslandRegistry.PIsland(1, 0);
        final IslandRegistry.PIsland other = new IslandRegistry.PIsland(2, 0);
        final Region ownRegion;
        final Region otherRegion;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool = get(RegionScheduler.class, null, "pool");
        final Object previousServer = get(RegionScheduler.class, null, "server");
        final Object previousActive = get(RegionScheduler.class, null, "active");
        final Object previousAccepting = get(RegionScheduler.class, null, "accepting");

        @SuppressWarnings("unchecked")
        Fixture(boolean autonomous) throws Exception {
            assertNull(previousPool, "fixture requires no live scheduler generation");
            levels = (Map<ServerLevel, RegionizedServer>) get(
                    RegionScheduler.class, null, "PER_LEVEL");
            runtime.asyncActive = autonomous;
            runtime.globalDomain.epoch = 8;
            runtime.globalDomain.entityDispatchCutEpoch = 7;
            Long2ObjectOpenHashMap<IslandRegistry.PIsland> islands =
                    (Long2ObjectOpenHashMap<IslandRegistry.PIsland>) get(
                            IslandRegistry.class, runtime.islandRegistry, "islands");
            Long2ObjectOpenHashMap<EpochDomain> domains = new Long2ObjectOpenHashMap<>();
            for (IslandRegistry.PIsland island : new IslandRegistry.PIsland[]{own, other}) {
                island.level = level;
                island.domain.epoch = 8;
                island.domain.entityDispatchCutEpoch = 7;
                islands.put(island.id, island);
            }
            domains.put(runtime.regionizer().grid.keyFor(0, 0), own.domain);
            domains.put(runtime.regionizer().grid.keyFor(32, 0), other.domain);
            set(RegionizedServer.class, runtime, "cellDomainSnapshot", domains);
            ownRegion = runtime.regionizer().regionForGridKey(
                    runtime.regionizer().grid.keyFor(0, 0));
            otherRegion = runtime.regionizer().regionForGridKey(
                    runtime.regionizer().grid.keyFor(32, 0));
            pool.start();
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "server", server);
            set(RegionScheduler.class, null, "active", true);
            set(RegionScheduler.class, null, "accepting", true);
            levels.put(level, runtime);
        }

        boolean owns(BlockPos pos) {
            return FabroliaIslandApi.ownsCurrentContext(level, pos);
        }

        @Override
        public void close() throws Exception {
            try {
                pool.stop(() -> pool.drainMainSafeAll(true));
            } finally {
                levels.remove(level);
                set(RegionScheduler.class, null, "active", previousActive);
                set(RegionScheduler.class, null, "accepting", previousAccepting);
                set(RegionScheduler.class, null, "server", previousServer);
                set(RegionScheduler.class, null, "pool", previousPool);
            }
        }
    }

    private static final class TestLevel extends ServerLevel {
        private MinecraftServer owner;
        private ResourceKey<Level> key;
        private RuntimeException lookupFailure;

        private TestLevel() {
            super(null, null, null, null, null, null, false, 0, null, false);
            throw new AssertionError("constructor bypassed for the level identity fixture");
        }

        static TestLevel create(MinecraftServer server) throws Exception {
            TestLevel level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
            level.owner = server;
            level.key = Level.OVERWORLD;
            return level;
        }

        @Override public MinecraftServer getServer() {
            if (lookupFailure != null) throw lookupFailure;
            return owner;
        }
        @Override public ResourceKey<Level> dimension() { return key; }
    }

    private static MinecraftServer serverOwnedBy(Thread thread) throws Exception {
        MinecraftServer server = (MinecraftServer) unsafe().allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
        set(MinecraftServer.class, server, "serverThread", thread);
        set(MinecraftServer.class, server, "levels", Map.of());
        set(net.minecraft.util.thread.BlockableEventLoop.class, server, "pendingRunnables",
                new java.util.concurrent.ConcurrentLinkedQueue<net.minecraft.server.TickTask>());
        return server;
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        return (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
    }

    private static Object get(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
