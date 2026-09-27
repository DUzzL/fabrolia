package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Population-index behavior and physical-Main confinement of the tick lane. */
final class ScheduledTickGlobalCellDiscoveryTest {

    private static final int SHIFT = RegionGrid.DEFAULT_SHIFT;
    private static final int CHUNKS_PER_CELL = 1 << (SHIFT * 2);
    private static final long WALL_TIME = 4_000L;

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void populationIndexChangesOnlyAtZeroToOneAndOneToZero() {
        Region region = new Region(null, cellKey(7, -3));
        LongOpenHashSet candidates = new LongOpenHashSet();

        applyBoundary(candidates, region,
                region.applyFullChunkTransition(ChunkPos.pack(56, -24), true), true);
        assertTrue(candidates.contains(region.gridKey));

        applyBoundary(candidates, region,
                region.applyFullChunkTransition(ChunkPos.pack(57, -24), true), true);
        assertEquals(1, candidates.size(),
                "membership inside an already-live 8x8 cell cannot duplicate the index");

        applyBoundary(candidates, region,
                region.applyFullChunkTransition(ChunkPos.pack(56, -24), false), false);
        assertTrue(candidates.contains(region.gridKey),
                "a non-last unload must leave the cell discoverable");

        applyBoundary(candidates, region,
                region.applyFullChunkTransition(ChunkPos.pack(57, -24), false), false);
        assertTrue(candidates.isEmpty(),
                "the exact last unload removes the cell from Main discovery");
    }

    @Test
    void mainProbeCostDependsOnGlobalCandidateCellsNotForeignHighWater() {
        LongOpenHashSet candidates = new LongOpenHashSet();
        long globalA = cellKey(2, 5);
        long adopted = cellKey(3, 5);
        long globalB = cellKey(-8, 11);
        candidates.add(globalA);
        candidates.add(adopted);
        candidates.add(globalB);

        LongOpenHashSet ownerFrontier = new LongOpenHashSet();
        ownerFrontier.add(adopted);
        CountingMap nextTick = new CountingMap();
        nextTick.defaultReturnValue(Long.MAX_VALUE);
        long dueA = ChunkPos.pack(16, 40);
        long dueB = ChunkPos.pack(-64, 88);
        long ownedDue = ChunkPos.pack(24, 40);
        nextTick.put(dueA, WALL_TIME);
        nextTick.put(dueB, WALL_TIME - 1L);
        nextTick.put(ownedDue, WALL_TIME);
        for (int i = 0; i < 32_768; i++) {
            nextTick.put(ChunkPos.pack(200_000 + i, -200_000 - i), WALL_TIME);
        }

        LongArrayList due = discoverGlobalModel(
                candidates, ownerFrontier, nextTick, WALL_TIME);
        assertEquals(new LongOpenHashSet(new long[]{dueA, dueB}),
                new LongOpenHashSet(due));
        assertEquals(2 * CHUNKS_PER_CELL, nextTick.probes,
                "only two still-global cells may reach the scheduler map");
        assertFalse(candidates.contains(adopted),
                "verified owner adoption removes the cell from future Main slots");

        for (int i = 0; i < 32_768; i++) {
            nextTick.remove(ChunkPos.pack(200_000 + i, -200_000 - i));
        }
        nextTick.probes = 0;
        discoverGlobalModel(candidates, ownerFrontier, nextTick, WALL_TIME);
        assertEquals(2 * CHUNKS_PER_CELL, nextTick.probes,
                "retained backing capacity cannot change Main discovery cost");
    }

    @Test
    void physicalMainPublishesPopulationWhileGameplayHasALogicalWorker() throws Exception {
        LogicalServer server = LogicalServer.create();
        server.logicalThread = new Thread("Fabrolia-Region-Worker-1");
        RegionizedServer runtime = new RegionizedServer(TestLevel.create(server), new RegionGrid(SHIFT));
        long chunk = ChunkPos.pack(56, -24);
        long cell = runtime.regionizer().grid.keyFor(chunk);

        runtime.onChunkFullTransition(chunk, true, null);
        assertEquals(new LongOpenHashSet(new long[]{cell}),
                runtime.scheduledTickDiscoveryCellsMainThread("global discovery"));
        assertTrue(runtime.regionizer().peekByGridKey(cell).fullChunks.contains(chunk));

        runtime.onChunkFullTransition(chunk, false, null);
        assertTrue(runtime.scheduledTickDiscoveryCellsMainThread("global discovery").isEmpty());
        assertTrue(runtime.regionizer().peekByGridKey(cell).fullChunks.isEmpty());
    }

    @Test
    void logicalWorkerCannotMutatePhysicalMainPopulationOrReadDiscovery() throws Exception {
        LogicalServer server = LogicalServer.create();
        RegionizedServer runtime = new RegionizedServer(TestLevel.create(server), new RegionGrid(SHIFT));
        long chunk = ChunkPos.pack(56, -24);
        long cell = runtime.regionizer().grid.keyFor(chunk);
        FutureTask<Void> attempt = new FutureTask<>(() -> {
            assertThrows(IllegalStateException.class,
                    () -> runtime.onChunkFullTransition(chunk, true, null));
            assertThrows(IllegalStateException.class,
                    () -> runtime.scheduledTickDiscoveryCellsMainThread("global discovery"));
            return null;
        });
        Thread worker = new Thread(attempt, "Fabrolia-Region-Worker-1");
        server.logicalThread = worker;
        worker.start();
        try {
            attempt.get(5, TimeUnit.SECONDS);
        } finally {
            worker.join(5_000);
            assertFalse(worker.isAlive());
        }
        assertNull(runtime.regionizer().peekByGridKey(cell),
                "rejected delivery must not even create a population cell");
        assertTrue(runtime.scheduledTickDiscoveryCellsMainThread("global discovery").isEmpty());
    }

    private static void applyBoundary(LongOpenHashSet candidates, Region region,
                                      Region.FullChunkTransition transition,
                                      boolean nowFull) {
        if (transition != Region.FullChunkTransition.POPULATION_BOUNDARY) return;
        if (nowFull) candidates.add(region.gridKey);
        else candidates.remove(region.gridKey);
    }

    private static LongArrayList discoverGlobalModel(
            LongOpenHashSet candidates, LongOpenHashSet ownerFrontier,
            CountingMap nextTick, long wallTime) {
        LongArrayList due = new LongArrayList();
        var cells = candidates.iterator();
        while (cells.hasNext()) {
            long cellKey = cells.nextLong();
            if (ownerFrontier.contains(cellKey)) {
                cells.remove();
                continue;
            }
            int baseX = ((int) cellKey) << SHIFT;
            int baseZ = ((int) (cellKey >> 32)) << SHIFT;
            int side = 1 << SHIFT;
            for (int dx = 0; dx < side; dx++) {
                for (int dz = 0; dz < side; dz++) {
                    long chunk = ChunkPos.pack(baseX + dx, baseZ + dz);
                    if (nextTick.get(chunk) <= wallTime) due.add(chunk);
                }
            }
        }
        return due;
    }

    private static long cellKey(int cellX, int cellZ) {
        return (cellX & 0xFFFF_FFFFL) | ((cellZ & 0xFFFF_FFFFL) << 32);
    }

    private static final class LogicalServer extends DedicatedServer {
        Thread logicalThread;

        private LogicalServer() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        static LogicalServer create() throws Exception {
            LogicalServer server = (LogicalServer) unsafe().allocateInstance(LogicalServer.class);
            Field owner = MinecraftServer.class.getDeclaredField("serverThread");
            owner.setAccessible(true);
            owner.set(server, Thread.currentThread());
            return server;
        }

        // Reproduce the woven MinecraftServer logical-owner override without
        // changing its distinct, final physical serverThread.
        @Override public Thread getRunningThread() {
            return logicalThread == null ? super.getRunningThread() : logicalThread;
        }
    }

    private static final class TestLevel extends ServerLevel {
        private MinecraftServer owner;

        private TestLevel() {
            super(null, null, null, null, null, null, false, 0, null, false);
        }

        static TestLevel create(MinecraftServer server) throws Exception {
            TestLevel level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
            level.owner = server;
            return level;
        }

        @Override public MinecraftServer getServer() { return owner; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static final class CountingMap extends Long2LongOpenHashMap {
        int probes;

        @Override
        public long get(long key) {
            probes++;
            return super.get(key);
        }
    }
}
