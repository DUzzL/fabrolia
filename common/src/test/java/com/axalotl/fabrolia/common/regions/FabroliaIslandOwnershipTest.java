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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    void exactPoolPauseAuthenticatesMainAndOnlyTheMatchingLiveActor() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            assertFalse(fixture.owns(OWN), "wall mode alone does not exclude parallel bodies");
            fixture.actorQuery(false);
            fixture.pool.pauseExactTurnAndAwait(() -> {});
            try {
                assertTrue(fixture.owns(OWN));
                onThread(false, () -> assertFalse(fixture.owns(OWN)));
                fixture.actorQuery(true);
            } finally {
                fixture.pool.resumeExactTurn();
            }
            assertFalse(fixture.owns(OWN));
            fixture.actorQuery(false);
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
            assertTrue(fixture.pool.enqueueGlobal(() -> {
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
            }));
            assertTrue(fixture.pool.runExclusiveWorkerStep());
        }
    }

    @Test
    void foreignCallbackCannotRunWhileAnotherThreadHoldsItsRegion() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            CountDownLatch completed = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean ran =
                    new java.util.concurrent.atomic.AtomicBoolean();
            withForeignClaim(fixture.ownRegion, () -> {
                FabroliaIslandApi.executeOnOwner(fixture.level, OWN, () -> {
                    assertTrue(fixture.owns(OWN));
                    ran.set(true);
                    completed.countDown();
                });
                assertFalse(ran.get(), "a foreign caller borrowed the existing claim");
            });
            // The fixture's bare foreign claim has no worker release tail.
            var publishReady = RegionWorkerPool.class.getDeclaredMethod("publishReady", Region.class);
            publishReady.setAccessible(true);
            publishReady.invoke(fixture.pool, fixture.ownRegion);
            assertTrue(fixture.pool.runExclusiveWorkerStep());
            assertTrue(completed.await(5, TimeUnit.SECONDS),
                    "the queued callback must run after the foreign claim releases");
        }
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
        final RegionWorkerPool pool = new RegionWorkerPool(1, server);
        final TestLevel level = TestLevel.create(server);
        final TestLevel otherLevel = TestLevel.create(server);
        final TestLevel foreignServerLevel = TestLevel.create(serverOwnedBy(physicalMain));
        final RegionizedServer runtime = new RegionizedServer(level, new RegionGrid(3));
        final IslandRegistry.PIsland own = new IslandRegistry.PIsland(1, 0);
        final IslandRegistry.PIsland other = new IslandRegistry.PIsland(2, 0);
        final Region ownRegion;
        final Region otherRegion;
        final Map<ServerLevel, RegionizedServer> levels;
        final AtomicReference<Object> logicalTurn;
        final Object previousPool = get(RegionScheduler.class, null, "pool");
        final Object previousServer = get(RegionScheduler.class, null, "server");
        final Object previousActive = get(RegionScheduler.class, null, "active");

        @SuppressWarnings("unchecked")
        Fixture(boolean autonomous) throws Exception {
            assertNull(previousPool, "fixture requires no live scheduler generation");
            levels = (Map<ServerLevel, RegionizedServer>) get(
                    RegionScheduler.class, null, "PER_LEVEL");
            logicalTurn = (AtomicReference<Object>) get(
                    RegionalPlayerEntityTicksV2.class, null, "LOGICAL_TURN");
            assertNull(logicalTurn.get());
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
            levels.put(level, runtime);
        }

        boolean owns(BlockPos pos) {
            return FabroliaIslandApi.ownsCurrentContext(level, pos);
        }

        void actorQuery(boolean expected) throws Exception {
            onThread(false, () -> {
                Class<?> type = Class.forName(
                        RegionalPlayerEntityTicksV2.class.getName() + "$LogicalTurn");
                Constructor<?> constructor = type.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                Object turn = constructor.newInstance(
                        server, 1L, null, physicalMain, Thread.currentThread());
                assertTrue(logicalTurn.compareAndSet(null, turn));
                try {
                    if (expected) assertTrue(owns(OWN));
                    else assertFalse(owns(OWN), "a logical stamp cannot outlive the real pool pause");
                    assertFalse(FabroliaIslandApi.ownsCurrentContext(foreignServerLevel, OWN),
                            "an authenticated actor does not own another server");
                } finally {
                    assertTrue(logicalTurn.compareAndSet(turn, null));
                }
            });
        }

        @Override
        public void close() throws Exception {
            try {
                pool.stop(() -> pool.drainMainSafeAll(true));
            } finally {
                levels.remove(level);
                set(RegionScheduler.class, null, "active", previousActive);
                set(RegionScheduler.class, null, "server", previousServer);
                set(RegionScheduler.class, null, "pool", previousPool);
            }
        }
    }

    private static final class TestLevel extends ServerLevel {
        private MinecraftServer owner;

        private TestLevel() {
            super(null, null, null, null, null, null, false, 0, null, false);
            throw new AssertionError("constructor bypassed for the level identity fixture");
        }

        static TestLevel create(MinecraftServer server) throws Exception {
            TestLevel level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
            level.owner = server;
            return level;
        }

        @Override public MinecraftServer getServer() { return owner; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
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
