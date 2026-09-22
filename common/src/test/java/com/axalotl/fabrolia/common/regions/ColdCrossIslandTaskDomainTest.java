package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises executeAt publication, cold materialization and the actual ready consumer. */
final class ColdCrossIslandTaskDomainTest {
    @Test
    void coldDestinationOverridesClaimedAndCoordinatorSourceContexts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<Long> clocks = new ArrayList<>();
            List<IslandRegistry.PIsland> owners = new ArrayList<>();
            ChunkPos claimedTarget = new ChunkPos(32, 0);
            ChunkPos coordinatorTarget = new ChunkPos(64, 0);
            fixture.publishCell(claimedTarget, fixture.target.domain);
            fixture.publishCell(coordinatorTarget, fixture.target.domain);

            fixture.onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                worker.currentRegion = fixture.sourceRegion;
                worker.currentDomain = fixture.source.domain;
                assertNull(fixture.regionizer.peekRegionForChunk(claimedTarget.pack()));
                RegionScheduler.executeAt(fixture.level, claimedTarget,
                        () -> fixture.observe(clocks, owners));
                worker.currentRegion = null;
                assertNull(fixture.regionizer.peekRegionForChunk(coordinatorTarget.pack()));
                RegionScheduler.executeAt(fixture.level, coordinatorTarget,
                        () -> fixture.observe(clocks, owners));
            });

            // No entity or chunk phase has visited either target. The ready
            // lane must retain both bodies until their destination unit admits
            // the region's queue phase, not run them claim-free while waiting.
            fixture.onWorker(() -> fixture.drainReady());
            fixture.pool.drainMainSafeAll(true);
            assertEquals(List.of(), clocks);
            fixture.openQueuePhase(claimedTarget);
            fixture.openQueuePhase(coordinatorTarget);
            fixture.onWorker(() -> fixture.drainReady());
            fixture.pool.stop();

            assertEquals(List.of(202L, 202L), clocks);
            assertEquals(List.of(fixture.target, fixture.target), owners);
        }
    }

    @Test
    void retainedColdTaskMigratesOnceWhenItsInitialOwnerRetires() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<Long> clocks = new ArrayList<>();
            List<IslandRegistry.PIsland> owners = new ArrayList<>();
            ChunkPos target = new ChunkPos(32, 0);
            fixture.target.domain.entityDispatchCutEpoch = fixture.target.domain.epoch;
            fixture.publishCell(target, fixture.target.domain);
            fixture.onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                worker.currentRegion = fixture.sourceRegion;
                RegionScheduler.executeAt(fixture.level, target,
                        () -> fixture.observe(clocks, owners));
            });
            fixture.onWorker(() -> fixture.drainReady());
            assertEquals(List.of(), clocks);
            Region region = fixture.regionizer.peekRegionForChunk(target.pack());
            assertThrowsDrop(fixture.regionizer, region);

            IslandRegistry.PIsland successor = fixture.island(3L, 303L);
            fixture.target.retired = true;
            Method assign = RegionizedServer.class.getDeclaredMethod(
                    "assignDomain", Region.class, EpochDomain.class);
            assign.setAccessible(true);
            assign.invoke(null, region, successor.domain);
            fixture.publishCell(target, successor.domain);
            assertEquals(List.of(), clocks,
                    "topology migration entered next-epoch work before the successor phase");
            fixture.openQueuePhase(target);
            fixture.onWorker(() -> fixture.drainReady());
            fixture.pool.stop();

            assertEquals(List.of(303L), clocks);
            assertEquals(List.of(successor), owners);
        }
    }

    @Test
    void publishedColdOwnerRetainsItsSnapshotRequestBeforeMaterialization() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ChunkPos destination = new ChunkPos(32, 0);
            long chunk = destination.pack();
            fixture.publishCell(destination, fixture.target.domain);
            fixture.onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                worker.currentDomain = fixture.source.domain;
                worker.currentRegion = fixture.sourceRegion;
                assertSame(fixture.target, fixture.runtime.islandOwnerOfChunk(chunk),
                        "published destination ownership must not become a false Main gap");
                assertNull(fixture.regionizer.peekRegionForChunk(chunk),
                        "ownership lookup must not materialize a Region");
                ChunkDelivery.requeueToCurrentOwner(fixture.runtime, chunk);
                ChunkDelivery.requeueToCurrentOwner(fixture.runtime, chunk);
            });
            assertEquals(Long.valueOf(chunk), fixture.target.pendingChunkSnapshots.poll());
            assertNull(fixture.target.pendingChunkSnapshots.poll(),
                    "one retained seal request, despite repeated owner resolution");
            assertTrue(fixture.source.pendingChunkSnapshots.isEmpty());
            Region materialized = fixture.regionizer.regionForGridKey(
                    fixture.regionizer.grid.keyFor(chunk));
            assertSame(fixture.target.domain, materialized.domain);
            assertSame(fixture.target, fixture.runtime.islandOwnerOfChunk(chunk));
        }
    }

    @Test
    void coldGapsAndUnavailableOwnersCannotBorrowTheCallingIsland() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ChunkPos retired = new ChunkPos(32, 0);
            ChunkPos live = new ChunkPos(64, 0);
            ChunkPos gap = new ChunkPos(96, 0);
            fixture.publishCell(retired, fixture.target.domain);
            fixture.publishCell(live, fixture.source.domain);
            fixture.target.retired = true;
            fixture.onWorker(() -> {
                RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
                worker.currentDomain = fixture.source.domain;
                worker.currentRegion = fixture.sourceRegion;
                assertNull(fixture.runtime.islandOwnerOfChunk(gap.pack()));
                assertNull(fixture.runtime.islandOwnerOfChunk(retired.pack()));
                fixture.runtime.asyncActive = false;
                assertSame(fixture.source, fixture.runtime.islandOwnerOfChunk(live.pack()),
                        "OFF intent cannot revoke the committed destination timeline owner");
            });
        }
    }

    @Test
    void materializedDomainRemainsAuthoritativeOverTheColdSnapshot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ChunkPos global = new ChunkPos(32, 0);
            ChunkPos owned = new ChunkPos(64, 0);
            Region globalRegion = fixture.regionizer.regionForGridKey(
                    fixture.regionizer.grid.keyFor(global.pack()));
            assertSame(fixture.global, globalRegion.domain);
            fixture.publishCell(global, fixture.target.domain);
            assertNull(fixture.runtime.islandOwnerOfChunk(global.pack()),
                    "a published halo must not override an existing global region");
            fixture.publishCell(owned, fixture.target.domain);
            fixture.regionizer.regionForGridKey(fixture.regionizer.grid.keyFor(owned.pack()));
            fixture.publishCell(owned, fixture.source.domain);
            assertSame(fixture.target, fixture.runtime.islandOwnerOfChunk(owned.pack()),
                    "the live Region, not a conflicting snapshot, remains authoritative");
        }
    }

    private static void assertThrowsDrop(ThreadedRegionizer regionizer, Region region) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> regionizer.drop(region),
                "retirement must not discard a retained cold target obligation");
    }

    private static final class Fixture implements AutoCloseable {
        final RegionWorkerPool pool = new RegionWorkerPool(1);
        final EpochDomain global = new EpochDomain();
        final ServerLevel level;
        final RegionizedServer runtime;
        final ThreadedRegionizer regionizer;
        final IslandRegistry.PIsland source;
        final IslandRegistry.PIsland target;
        final Region sourceRegion;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool;
        final Object previousActive;
        final Object previousOrphanFailure;
        final boolean previousEventScheduler = FabroliaConfig.regionEventScheduler;
        final boolean previousRegionThreading = FabroliaConfig.enableRegionThreading;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(
                    sun.misc.Unsafe.class, null, "theUnsafe");
            level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            Object levelData = unsafe.allocateInstance(
                    net.minecraft.world.level.storage.PrimaryLevelData.class);
            set(net.minecraft.world.level.Level.class, level, "levelData", levelData);
            set(ServerLevel.class, level, "serverLevelData", levelData);
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            global.epoch = 8L;
            global.entityDispatchCutEpoch = 7L;
            regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
            regionizer.owner = runtime;
            set(RegionizedServer.class, runtime, "level", level);
            set(RegionizedServer.class, runtime, "globalDomain", global);
            set(RegionizedServer.class, runtime, "regionizer", regionizer);
            set(RegionizedServer.class, runtime, "islandLastL", new Long2LongOpenHashMap());
            runtime.asyncActive = true;
            source = island(1L, 101L);
            target = island(2L, 202L);
            publishCell(new ChunkPos(0, 0), source.domain);
            sourceRegion = regionizer.regionForGridKey(0L);
            previousPool = get(RegionScheduler.class, null, "pool");
            previousActive = get(RegionScheduler.class, null, "active");
            previousOrphanFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
            levels = (Map<ServerLevel, RegionizedServer>) get(
                    RegionScheduler.class, null, "PER_LEVEL");
            set(RegionWorkerPool.class, pool, "running", true);
            set(RegionWorkerPool.class, pool, "accepting", true);
            set(RegionScheduler.class, null, "pool", pool);
            set(RegionScheduler.class, null, "active", true);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            levels.put(level, runtime);
            // This fixture exercises queue work only, not Minecraft entities.
            FabroliaConfig.regionEventScheduler = false;
            FabroliaConfig.enableRegionThreading = true;
        }

        IslandRegistry.PIsland island(long id, long time) {
            IslandRegistry.PIsland island = new IslandRegistry.PIsland(id, time);
            island.level = level;
            island.domain.epoch = 8L;
            island.domain.entityDispatchCutEpoch = 7L;
            island.domain.unitGameTime = time;
            return island;
        }

        @SuppressWarnings("unchecked")
        void publishCell(ChunkPos pos, EpochDomain domain) throws Exception {
            Long2ObjectOpenHashMap<EpochDomain> previous =
                    (Long2ObjectOpenHashMap<EpochDomain>) get(
                            RegionizedServer.class, runtime, "cellDomainSnapshot");
            Long2ObjectOpenHashMap<EpochDomain> next = previous == null
                    ? new Long2ObjectOpenHashMap<>() : previous.clone();
            next.put(regionizer.grid.keyFor(pos.pack()), domain);
            set(RegionizedServer.class, runtime, "cellDomainSnapshot", next);
        }

        void observe(List<Long> clocks, List<IslandRegistry.PIsland> owners) {
            RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
            assertSame(worker, worker.currentRegion.currentWorker());
            clocks.add(IslandClock.contextTime());
            owners.add(runtime.islandOwnerOfChunk(
                    new ChunkPos((int) worker.currentRegion.gridKey << 3,
                            (int) (worker.currentRegion.gridKey >>> 32) << 3).pack()));
        }

        void openQueuePhase(ChunkPos pos) {
            Region region = regionizer.peekRegionForChunk(pos.pack());
            EpochDomain domain = region.domain;
            // Model the unit's dispatch admission; the production queue
            // publication, exact claim and body execution are not mocked.
            region.groupedEpoch = domain.epoch;
            assertTrue(pool.enqueueReady(region));
        }

        void drainReady() {
            RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
            worker.currentDomain = source.domain; // a foreign coordinator helps B
            for (int steps = 0; pool.workOnce(worker, false); steps++) {
                if (steps == 20) throw new AssertionError("ready lane failed to settle");
            }
        }

        void onWorker(Runnable body) throws Exception {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new RegionWorkerThread(body, "cold-domain-regression");
            worker.setUncaughtExceptionHandler((thread, thrown) -> failure.set(thrown));
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive(), "cold task worker did not finish");
            if (failure.get() != null) throw new AssertionError(failure.get());
        }

        @Override
        public void close() throws Exception {
            try {
                if (!pool.isTerminated()) {
                    IllegalStateException cleanup = new IllegalStateException("fixture teardown");
                    pool.publishFatalFailure(cleanup);
                    for (Region region : regionizer.snapshot()) {
                        RegionScheduler.cancelFailedGenerationRegionQueues(pool, region, cleanup);
                    }
                    try {
                        pool.stop(() -> {});
                    } catch (Throwable failure) {
                        if (failure != cleanup) throw failure;
                    }
                }
            } finally {
                levels.remove(level, runtime);
                set(RegionScheduler.class, null, "pool", previousPool);
                set(RegionScheduler.class, null, "active", previousActive);
                set(RegionScheduler.class, null, "orphanFatalFailure", previousOrphanFailure);
                FabroliaConfig.regionEventScheduler = previousEventScheduler;
                FabroliaConfig.enableRegionThreading = previousRegionThreading;
            }
        }
    }

    private static Object get(Class<?> owner, Object target, String name)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> owner, Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
