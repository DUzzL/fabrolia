package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledFluidFrontierTest {
    @BeforeAll
    static void bootstrap() { TickDomainsProductionPathTest.bootstrapMinecraft(); }

    @Test
    void populatedHaloCannotConsumeFluidBeforeItsWriteFrontierIsClosed() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.run();
            assertEquals(0, fixture.writes.get(), "new FULL halo is not yet a protected tick core");
            assertSame(fixture.tick, fixture.container.peek(), "deferred native tick must remain pending");
            fixture.closeFrontier(true);
            fixture.run();
            assertEquals(1, fixture.writes.get(), "closed FULL halo must make progress without core promotion");
            assertNull(fixture.container.peek());
        }
    }

    @Test
    void coreFluidCanSpreadAcrossItsCellBoundaryIntoOwnedHalo() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.run();
            assertEquals(1, fixture.writes.get());
            assertNull(fixture.container.peek());
        }
    }
    @Test
    void coldSnapshotCannotAuthorizeAnExistingForeignNeighbor() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.closeFrontier(false);
            fixture.run();
            assertEquals(0, fixture.writes.get());
            assertSame(fixture.tick, fixture.container.peek());
        }
    }
    @Test
    void directUnownedWriteStillFailsClosed() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.run(() -> assertThrows(IllegalStateException.class,
                    () -> RegionAccessDetector.onBlockWrite(fixture.tick.pos().east())));
            assertTrue(FabroliaConfig.asyncFailClosedTripped);
        }
    }


    @Test
    void openHaloPulseHoldsBothProducerPreflightAndWholeScheduledUnit() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var blockTicks = new TickDomainsProductionPathTest.TestLevelTicks<net.minecraft.world.level.block.Block>();
            blockTicks.next.defaultReturnValue(Long.MAX_VALUE);
            var pulse = new PulseChunkTicks(fixture.owner);
            var head = new ScheduledTick<>(net.minecraft.world.level.block.Blocks.OBSERVER,
                    fixture.tick.pos(), 100, TickPriority.NORMAL, 0);
            pulse.schedule(head);
            long chunk = ChunkPos.pack(head.pos());
            blockTicks.containers.put(chunk, pulse);
            blockTicks.next.put(chunk, 100);
            fixture.runtime.blockTicksAccess = blockTicks;
            fixture.runtime.blockTicker = (pos, block) -> fail("pulse handler entered open frontier");
            fixture.run(() -> {
                assertFalse(IslandScheduledTicks.preflightIslandScheduled(
                        fixture.runtime, fixture.owner.domain, 100));
                assertFalse(IslandScheduledTicks.runIslandScheduled(
                        fixture.runtime, fixture.owner.domain, 100));
            });
            assertSame(head, pulse.peek());
            assertSame(fixture.tick, fixture.container.peek(), "whole-unit hold precedes fluid collection");
            assertEquals(0, fixture.writes.get());
        }
    }

    private static final class PulseChunkTicks
            extends net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.block.Block>
            implements FabroliaTickDomain, CausalScheduledTickIndex {
        private long id;
        private long gen;
        PulseChunkTicks(IslandRegistry.PIsland owner) { id = owner.id; gen = owner.gen; }
        @Override public long fabrolia$domainIsland() { return id; }
        @Override public long fabrolia$domainGen() { return gen; }
        @Override public void fabrolia$setDomain(long id, long gen) { this.id = id; this.gen = gen; }
        @Override public boolean fabrolia$mayContainPulseSensitiveTick() { return true; }
        @Override public void fabrolia$markPulseSensitiveTick() {}
    }


    private static final class Fixture implements AutoCloseable {
        final TickDomainsProductionPathTest.TestLevel level = TickDomainsProductionPathTest.TestLevel.alloc();
        final RegionizedServer runtime = new RegionizedServer(level, new RegionGrid(3));
        final TickDomainsProductionPathTest.TestLevelTicks<Fluid> ticks = new TickDomainsProductionPathTest.TestLevelTicks<>();
        final TickDomainsProductionPathTest.TaggedChunkTicks<Fluid> container = new TickDomainsProductionPathTest.TaggedChunkTicks<>();
        final ScheduledTick<Fluid> tick;
        final IslandRegistry.PIsland owner;
        final AtomicInteger writes = new AtomicInteger();
        final Map<ServerLevel, RegionizedServer> levels;
        final boolean debug = FabroliaConfig.fabroliaDebugRegionAccess;
        final boolean failClosed = FabroliaConfig.asyncFailClosedTripped;
        final Object orphan = field(RegionScheduler.class, "orphanFatalFailure").get(null);
        final boolean detectorLatched;
        final boolean active = field(RegionScheduler.class, "active").getBoolean(null);

        @SuppressWarnings("unchecked")
        Fixture(boolean sourceIsCore) throws Exception {
            assertNull(field(RegionScheduler.class, "pool").get(null));
            assertNull(field(RegionScheduler.class, "transitionPool").get(null));
            assertNull(orphan);
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            detectorLatched = field(RegionAccessDetector.class, "FAIL_CLOSED_LATCHED").getBoolean(null);
            level.fabricatedLevelData = new TickDomainsProductionPathTest.TestLevelData(100);
            level.fabricatedGameTime = 100;
            level.fabricatedFluidTicks = ticks;
            ticks.next.defaultReturnValue(Long.MAX_VALUE);
            long core = runtime.regionizer().grid.keyFor(7304, -3376);
            long halo = runtime.regionizer().grid.keyFor(7312, -3376);
            long target = runtime.regionizer().grid.keyFor(7320, -3376);
            runtime.islandRegistry.applyRebuildCells(List.of(LongArrayList.of(core)), 100);
            owner = runtime.islandRegistry.byCell(core);
            owner.level = level;
            owner.haloCells.add(halo);
            if (sourceIsCore) {
                owner.cells.add(halo);
                owner.haloCells.remove(halo);
                owner.haloCells.add(target);
            }
            Long2ObjectOpenHashMap<EpochDomain> domains = new Long2ObjectOpenHashMap<>();
            domains.put(core, owner.domain);
            domains.put(halo, owner.domain);
            if (sourceIsCore) domains.put(target, owner.domain);
            field(RegionizedServer.class, "cellDomainSnapshot").set(runtime, domains);
            runtime.asyncActive = true;
            runtime.regionizer().regionForGridKey(core).fullChunkCount.set(1);
            runtime.regionizer().regionForGridKey(halo).fullChunkCount.set(1);
            runtime.regionizer().regionForGridKey(target);
            levels.put(level, runtime);
            field(RegionScheduler.class, "active").setBoolean(null, true);
            FabroliaConfig.fabroliaDebugRegionAccess = true;
            BlockPos source = new BlockPos(117119, 13, -54001);
            tick = new ScheduledTick<>(Fluids.WATER, source, 100, TickPriority.NORMAL, 0);
            container.fabrolia$setDomain(owner.id, owner.gen);
            container.schedule(tick);
            long chunk = ChunkPos.pack(source);
            ticks.containers.put(chunk, container);
            ticks.next.put(chunk, 100);
            runtime.fluidTicksAccess = ticks;
            runtime.fluidTicker = (java.util.function.BiConsumer<BlockPos, Fluid>) (pos, fluid) -> {
                // The native FlowingFluid spreadTo path writes its immediate neighbour.
                // Holder readiness is intentionally true; it is not ownership authority.
                RegionAccessDetector.onBlockWrite(pos.east());
                writes.incrementAndGet();
            };
        }
        @SuppressWarnings("unchecked")
        void closeFrontier(boolean adoptMaterialized) throws Exception {
            assertFalse(owner.unitClaim.get());
            var published = (Long2ObjectOpenHashMap<EpochDomain>)
                    field(RegionizedServer.class, "cellDomainSnapshot").get(runtime);
            var next = new Long2ObjectOpenHashMap<>(published);
            long cell = runtime.regionizer().grid.keyFor(ChunkPos.pack(tick.pos()));
            runtime.regionizer().lockTopologyCreationWrite();
            try {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long key = (((int) cell + dx) & 0xFFFFFFFFL)
                                | ((((int) (cell >> 32) + dz) & 0xFFFFFFFFL) << 32);
                        next.put(key, owner.domain);
                        if (!owner.cells.contains(key)) owner.haloCells.add(key);
                        Region region = runtime.regionizer().peekByGridKey(key);
                        if (adoptMaterialized && region != null && region.domain != owner.domain) {
                            synchronized (region.admissionLock) {
                                assertTrue(region.tryBeginDomainRebaseSeal());
                                try {
                                    region.commitDomainRebaseSeal(owner.domain, owner.domain.epoch - 1);
                                } finally {
                                    region.releaseDomainRebaseSeal();
                                }
                            }
                        }
                    }
                }
                field(RegionizedServer.class, "cellDomainSnapshot").set(runtime, next);
            } finally {
                runtime.regionizer().unlockTopologyCreationWrite();
            }
        }


        void run() throws Exception {
            run(() -> assertTrue(IslandScheduledTicks.runIslandScheduled(runtime, owner.domain, 100)));
        }

        void run(Runnable body) throws Exception {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            RegionWorkerThread worker = new RegionWorkerThread(() -> {
                try {
                    assertTrue(owner.tryClaimAutonomousWork());
                    owner.noteClaim("scheduled-fluid-frontier-test");
                    body.run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    owner.clearClaim();
                    owner.unitClaim.set(false);
                }
            }, "fluid-frontier-worker");
            worker.currentDomain = owner.domain;
            worker.start();
            worker.join(10_000);
            assertFalse(worker.isAlive(), "scheduled fluid callback must finish");
            assertNull(failure.get(), () -> "scheduled fluid entered foreign live write: " + failure.get());
        }

        @Override public void close() throws Exception {
            levels.remove(level);
            FabroliaConfig.fabroliaDebugRegionAccess = debug;
            FabroliaConfig.asyncFailClosedTripped = failClosed;
            field(RegionAccessDetector.class, "FAIL_CLOSED_LATCHED").setBoolean(null, detectorLatched);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, orphan);
            field(RegionScheduler.class, "active").setBoolean(null, active);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
