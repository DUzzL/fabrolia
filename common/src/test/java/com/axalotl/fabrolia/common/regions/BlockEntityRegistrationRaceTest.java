package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BlockEntityRegistrationRaceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void registrationDuringWarmFoldSurvivesCarryRetirementAndColdReconstruction() throws Exception {
        registrationDuringFold(false);
    }

    @Test
    void registrationDuringColdFoldSurvivesCarryRetirementAndColdReconstruction() throws Exception {
        registrationDuringFold(true);
    }

    private static void registrationDuringFold(boolean cold) throws Exception {
        try (Fixture f = new Fixture()) {
            CounterTicker early = new CounterTicker(new BlockPos(1, 64, 1), f.level.lock);
            CounterTicker late = new CounterTicker(new BlockPos(2, 64, 1), f.level.lock);
            IslandBlockEntityTicks.setTickingBlockEntities(f.level, true);
            f.register(early);
            if (!cold) IslandBlockEntityTicks.preUnitMaintenance(f.level, f.runtime);
            SnapshotList pending = new SnapshotList();
            set(f.level, Level.class, "pendingBlockEntityTickers", pending);
            // Ensure addAll obtains a nonempty snapshot in both warm and cold cases.
            if (cold) pending.add(early);
            else f.register(new CounterTicker(new BlockPos(3, 64, 1), f.level.lock));
            pending.armed = true;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread maintenance = thread(() -> IslandBlockEntityTicks.preUnitMaintenance(
                    f.level, f.runtime), failure);
            RegionWorkerThread registration = new RegionWorkerThread(() -> {
                try { f.register(late); } catch (Throwable t) { failure.set(t); }
            }, "be-registration-race");
            registration.currentDomain = f.island.domain;
            try {
                maintenance.start();
                await(pending.captured);
                registration.start();
                awaitBlocked(registration);
            } finally {
                pending.release.countDown();
                maintenance.join(3_000);
                registration.join(3_000);
            }
            assertFalse(maintenance.isAlive());
            assertFalse(registration.isAlive());
            assertNull(failure.get());
            assertTrue(f.level.blockEntityTickers.contains(late)
                    || f.level.pendingBlockEntityTickers.contains(late));

            // The same-island registration ticks before Main has folded it.
            IslandBlockEntityTicks.tickIslandCells(f.level, f.runtime, f.island);
            assertEquals(1, late.ticks);
            assertTrue(f.island.carryBes.contains(late));
            IslandBlockEntityTicks.preUnitMaintenance(f.level, f.runtime);
            IslandBlockEntityTicks.tickIslandCells(f.level, f.runtime, f.island);
            assertEquals(2, late.ticks, "folded and carried ticker must not tick twice");
            assertFalse(f.island.carryBes.contains(late));
            assertTrue(f.level.blockEntityTickers.contains(late));
            IslandBlockEntityTicks.setTickingBlockEntities(f.level, false);
            f.level.tickBlockEntities();
            assertEquals(3, late.ticks, "vanilla fallback lost or duplicated the registration");
            synchronized (f.level.lock) { f.runtime.releaseBeIndexForVanillaFallback(); }

            // Fallback discards the derived state, not the accepted registration.
            IslandBlockEntityTicks.preUnitMaintenance(f.level, f.runtime);
            IslandBlockEntityTicks.tickIslandCells(f.level, f.runtime, f.island);
            assertEquals(4, late.ticks, "cold reconstruction lost the authoritative ticker");
        }
    }

    @Test
    void coldRebuildNeverPublishesAnEmptyOrPartialIndexToAnEnteredUnit() throws Exception {
        try (Fixture f = new Fixture()) {
            CounterTicker first = new CounterTicker(new BlockPos(1, 64, 1), f.level.lock);
            CounterTicker second = new CounterTicker(new BlockPos(2, 64, 1), f.level.lock);
            f.register(first);
            f.register(second);
            IslandBlockEntityTicks.preUnitMaintenance(f.level, f.runtime);
            f.runtime.beCellsValid = false;
            first.pauseRebuild = true;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread rebuilding = thread(() -> IslandBlockEntityTicks.preUnitMaintenance(
                    f.level, f.runtime), failure);
            first.rebuildThread = rebuilding;
            try {
                rebuilding.start();
                await(first.rebuilding);
                IslandBlockEntityTicks.tickIslandCells(f.level, f.runtime, f.island);
                assertEquals(1, first.ticks);
                assertEquals(1, second.ticks);
            } finally {
                first.release.countDown();
                rebuilding.join(3_000);
            }
            assertFalse(rebuilding.isAlive());
            assertNull(failure.get());
            IslandBlockEntityTicks.tickIslandCells(f.level, f.runtime, f.island);
            assertEquals(2, first.ticks);
            assertEquals(2, second.ticks);
        }
    }

    @Test
    void capturedIndexPreservesCrossCellOrderThroughSleepRemovalAndColdRebuild() throws Exception {
        try (Fixture f = new Fixture()) {
            ArrayList<Integer> order = new ArrayList<>();
            CounterTicker first = new CounterTicker(new BlockPos(1, 64, 1), f.level.lock);
            CounterTicker sleeping = new CounterTicker(new BlockPos(65, 64, 1), f.level.lock);
            CounterTicker removed = new CounterTicker(new BlockPos(2, 64, 1), f.level.lock);
            CounterTicker last = new CounterTicker(new BlockPos(66, 64, 1), f.level.lock);
            CounterTicker[] tickers = {first, sleeping, removed, last};
            for (int i = 0; i < tickers.length; i++) {
                int id = i;
                tickers[i].body = () -> order.add(id);
                f.register(tickers[i]);
            }
            IslandBlockEntityTicks.preUnitMaintenance(f.level, f.runtime);
            ArrayList<BlockEntityTickerList> captured = new ArrayList<>(f.runtime.beCells.values());
            sleeping.sleeping = true;
            removed.removed = true;
            java.util.Queue<TickingBlockEntity> dead = new java.util.ArrayDeque<>();
            var gates = IslandBlockEntityTicks.ChunkTickGate.forLevel(f.level);
            IslandBlockEntityTicks.tickMerged(f.runtime, captured, dead, gates, null, null);
            assertEquals(java.util.List.of(0, 3), order);
            assertEquals(java.util.List.of(removed), new ArrayList<>(dead));
            assertEquals(2, f.level.gateReads);

            // A stale unit keeps its ticker/sequence pairs after metadata release.
            synchronized (f.level.lock) { f.runtime.releaseBeIndexForVanillaFallback(); }
            f.level.blockEntityTickers.remove(removed);
            IslandBlockEntityTicks.preUnitMaintenance(f.level, f.runtime);
            sleeping.sleeping = false; // same registered wrapper, rebound to its live ticker
            order.clear();
            gates = IslandBlockEntityTicks.ChunkTickGate.forLevel(f.level);
            f.level.gateReads = 0;
            IslandBlockEntityTicks.tickMerged(f.runtime, captured, dead, gates, null, null);
            assertEquals(java.util.List.of(0, 1, 3), order);
            assertEquals(2, f.level.gateReads, "one gate read per chunk, not per wrapper");
            order.clear();
            IslandBlockEntityTicks.tickMerged(f.runtime,
                    new ArrayList<>(f.runtime.beCells.values()), dead, gates, null, null);
            assertEquals(java.util.List.of(0, 1, 3), order);
        }
    }

    @Test
    void indexedFoldMarksCarriedTickerSeenWithoutEnteringItTwice() throws Exception {
        try (Fixture f = new Fixture()) {
            CounterTicker carried = new CounterTicker(new BlockPos(1, 64, 1), f.level.lock);
            CounterTicker ordinary = new CounterTicker(new BlockPos(65, 64, 1), f.level.lock);
            f.register(carried);
            f.register(ordinary);
            IslandBlockEntityTicks.preUnitMaintenance(f.level, f.runtime);
            java.util.Set<TickingBlockEntity> skip =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            java.util.Set<TickingBlockEntity> seen =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            skip.add(carried);
            IslandBlockEntityTicks.tickMerged(f.runtime,
                    new ArrayList<>(f.runtime.beCells.values()), new java.util.ArrayDeque<>(),
                    IslandBlockEntityTicks.ChunkTickGate.forLevel(f.level), skip, seen);
            assertEquals(0, carried.ticks);
            assertEquals(1, ordinary.ticks);
            assertEquals(skip, seen);
        }
    }

    @Test
    void unresolvedWorkerRegistrationStaysMainPendingUntilItWakes() throws Exception {
        try (Fixture f = new Fixture()) {
            CounterTicker sleeper = new CounterTicker(new BlockPos(1, 64, 1), f.level.lock);
            sleeper.sleeping = true;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            RegionWorkerThread worker = new RegionWorkerThread(() -> {
                try {
                    f.runtime.stashPendingBeAdd(sleeper);
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "be-posless-registration");
            worker.currentDomain = f.island.domain;
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertTrue(f.island.pendingUnitBes.isEmpty(),
                    "no position means no proof for a same-unit island mutation");
            f.runtime.drainPendingBeAdds();
            sleeper.sleeping = false;
            IslandBlockEntityTicks.tickMerged(f.runtime,
                    java.util.List.of(f.runtime.beCells.get(Long.MIN_VALUE)),
                    new java.util.ArrayDeque<>(),
                    IslandBlockEntityTicks.ChunkTickGate.forLevel(f.level), null, null);
            assertEquals(1, sleeper.ticks, "the unresolved registration must survive for Main");
        }
    }

    private static final class SnapshotList extends ArrayList<TickingBlockEntity> {
        final CountDownLatch captured = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        boolean armed;
        @Override public Object[] toArray() {
            Object[] snapshot = super.toArray();
            if (armed) {
                armed = false;
                captured.countDown();
                await(release);
            }
            return snapshot;
        }
    }

    private static final class CounterTicker implements TickingBlockEntity {
        final BlockPos pos;
        final Object registryLock;
        final CountDownLatch rebuilding = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread rebuildThread;
        volatile boolean pauseRebuild;
        int ticks;
        boolean sleeping;
        boolean removed;
        Runnable body;
        CounterTicker(BlockPos pos, Object registryLock) {
            this.pos = pos;
            this.registryLock = registryLock;
        }
        @Override public void tick() {
            assertFalse(Thread.holdsLock(registryLock), "ticker callback under registry exclusion");
            ticks++;
            if (body != null) body.run();
        }
        @Override public boolean isRemoved() { return removed; }
        @Override public BlockPos getPos() {
            if (pauseRebuild && Thread.currentThread() == rebuildThread) {
                rebuilding.countDown();
                await(release);
            }
            return sleeping ? null : pos;
        }
        @Override public String getType() { return "test:registry"; }
    }

    private static final class TestLevel extends ServerLevel
            implements BlockEntityTickerAccess, BlockTickingPassAccess {
        Object lock;
        ServerTickRateManager rate;
        int gateReads;
        private TestLevel() {
            super(null, null, null, null, null, null, false, 0L, null, false);
            throw new AssertionError("constructor bypassed");
        }
        @Override public Object fabrolia$blockEntityTickerLock() { return lock; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public ServerTickRateManager tickRateManager() { return rate; }
        @Override public boolean shouldTickBlocksAt(BlockPos pos) { gateReads++; return true; }
        @Override public java.util.function.LongPredicate fabrolia$blockTickingPass() {
            return chunkKey -> { gateReads++; return true; };
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TestLevel level;
        final RegionizedServer runtime;
        final IslandRegistry.PIsland island = new IslandRegistry.PIsland(101, 0);
        final Map<ServerLevel, RegionizedServer> runtimes;
        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            level.lock = new Object();
            level.rate = new ServerTickRateManager(null);
            set(level, Level.class, "blockEntityTickers", new ArrayList<TickingBlockEntity>());
            set(level, Level.class, "pendingBlockEntityTickers", new ArrayList<TickingBlockEntity>());
            runtime = new RegionizedServer(level, new RegionGrid(2));
            runtime.asyncActive = true;
            Region region = runtime.regionizer().regionForChunk(0L);
            island.domain.owner = island;
            region.domain = island.domain;
            island.cells.add(region.gridKey);
            Field mapField = RegionScheduler.class.getDeclaredField("PER_LEVEL");
            mapField.setAccessible(true);
            runtimes = (Map<ServerLevel, RegionizedServer>) mapField.get(null);
            runtimes.put(level, runtime);
        }
        void register(TickingBlockEntity ticker) {
            IslandBlockEntityTicks.registerTicker(level, ticker, () -> level.addBlockEntityTicker(ticker));
        }
        @Override public void close() { runtimes.remove(level); }
    }

    private static Thread thread(Runnable body, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try { body.run(); } catch (Throwable t) { failure.set(t); }
        }, "be-maintenance-race");
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "timed out at registry race boundary");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(Thread.State.BLOCKED, thread.getState(), "registration escaped the pending flush");
    }
}
