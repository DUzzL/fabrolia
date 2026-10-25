package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2LongConcurrentHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Population-index behavior of the tick lane. */
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
    @SuppressWarnings("unchecked")
    void indexedDiscoveryPreservesCellWalkAndTiedLoadedTickDrainOrder() throws Exception {
        RegionizedServer runtime = new RegionizedServer(
                TickDomainsProductionPathTest.TestLevel.alloc(), new RegionGrid(SHIFT));
        var discovery = IslandScheduledTicks.class.getDeclaredMethod("scheduledChunksInCell",
                RegionizedServer.class, Long2LongMap.class, long.class);
        discovery.setAccessible(true);
        var order = IslandScheduledTicks.class.getDeclaredMethod("containerOrder");
        order.setAccessible(true);
        Comparator<LevelChunkTicks<?>> comparator =
                (Comparator<LevelChunkTicks<?>>) order.invoke(null);

        for (long cell : new long[]{cellKey(1, 0), cellKey(-2, -3), cellKey(0, 0)}) {
            Long2LongConcurrentHashMap index = new Long2LongConcurrentHashMap(
                    16, IslandScheduledTicks::scheduledTickCell);
            index.defaultReturnValue(Long.MAX_VALUE);
            LongArrayList oldWalk = new LongArrayList();
            int baseX = ((int) cell) << SHIFT;
            int baseZ = ((int) (cell >> 32)) << SHIFT;
            for (int dx = 1; dx <= 2; dx++) {
                for (int dz = 1; dz <= 2; dz++) {
                    long chunk = ChunkPos.pack(baseX + dx, baseZ + dz);
                    oldWalk.add(chunk);
                    index.put(chunk, WALL_TIME);
                }
            }
            // An unrelated cell must not enter this discovery snapshot.
            index.put(ChunkPos.pack(baseX + (1 << SHIFT), baseZ), WALL_TIME);

            LongArrayList discovered = (LongArrayList) discovery.invoke(null, runtime, index, cell);
            assertEquals(oldWalk, discovered,
                    "indexed discovery must retain the previous x-then-z encounter order");
            assertEquals(drainLoaded(oldWalk, comparator), drainLoaded(discovered, comparator),
                    "equal-priority/subTickOrder loaded ticks must retain their execution order");
        }
    }

    private static List<BlockPos> drainLoaded(LongArrayList chunks,
                                                Comparator<LevelChunkTicks<?>> comparator) {
        PriorityQueue<LevelChunkTicks<?>> ready = new PriorityQueue<>(comparator);
        ScheduledTick<?> previous = null;
        for (long chunk : chunks) {
            BlockPos pos = new BlockPos(((int) chunk) << 4, 64, ((int) (chunk >> 32)) << 4);
            LevelChunkTicks<String> container = new LevelChunkTicks<>(
                    List.of(new SavedTick<>("loaded", pos, 0, TickPriority.NORMAL)));
            container.unpack(WALL_TIME);
            assertEquals(-1L, container.peek().subTickOrder());
            if (previous != null) {
                assertEquals(0, ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(previous, container.peek()),
                        "each chunk independently assigns the same loaded subTickOrder");
            }
            previous = container.peek();
            ready.add(container);
        }
        List<BlockPos> drained = new ArrayList<>();
        while (!ready.isEmpty()) drained.add(ready.poll().poll().pos());
        return drained;
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

    private static final class CountingMap extends Long2LongOpenHashMap {
        int probes;

        @Override
        public long get(long key) {
            probes++;
            return super.get(key);
        }
    }
}
