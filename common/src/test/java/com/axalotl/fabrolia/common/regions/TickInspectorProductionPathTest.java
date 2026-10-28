package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRODUCTION-PATH pins for the per-position scheduled-tick operator tools
 * ({@link TickInspector}, {@code /fabrolia regions ticks clear-stale|reschedule}).
 *
 * <p>Every test drives the REAL production code over REAL vanilla containers
 * ({@link LevelChunkTicks}) holding REAL block/fluid types. Following the
 * suite convention (TickDomainsProductionPathTest), the mixin-installed duck
 * interfaces are implemented by thin test subclasses of the vanilla classes.
 * The pins assert that a confirmed mutation marks its FULL chunk unsaved and
 * that a dry run or a no-op never does.
 */
class TickInspectorProductionPathTest {

    private static final long WALL = FabroliaTickDomain.WALL;

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static final sun.misc.Unsafe UNSAFE;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** REAL vanilla container + the mixin's domain-tag duck. As of audit15
     *  there is no packed-ticks duck anywhere — the production code touches
     *  scheduled-tick containers through the public API only. */
    static class InspectorChunkTicks<T> extends LevelChunkTicks<T> implements FabroliaTickDomain {

        private long tagId = WALL;
        private long tagGen = -1;

        InspectorChunkTicks() {}

        @Override public long fabrolia$domainIsland() { return tagId; }
        @Override public long fabrolia$domainGen() { return tagGen; }
        @Override public void fabrolia$setDomain(long islandId, long gen) {
            this.tagId = islandId;
            this.tagGen = gen;
        }
    }

    /** REAL vanilla scheduler + the mixin's island-access duck. */
    static final class TestLevelTicks<T> extends LevelTicks<T> implements LevelTicksIslandAccess<T> {
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LevelChunkTicks<T>> containers =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap next =
                new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        final Object lock = new Object();
        final IslandScheduledTicks.SliceRegistry sliceRegistry =
                new IslandScheduledTicks.SliceRegistry();

        TestLevelTicks() { super(k -> true); }

        @Override public it.unimi.dsi.fastutil.longs.Long2ObjectMap<LevelChunkTicks<T>> fabrolia$allContainers() { return containers; }
        @Override public it.unimi.dsi.fastutil.longs.Long2LongMap fabrolia$nextTickForContainer() { return next; }
        @Override public java.util.function.LongPredicate fabrolia$tickCheck() { return k -> true; }
        @Override public Object fabrolia$tickLock() { return lock; }
        @Override public IslandScheduledTicks.SliceRegistry fabrolia$sliceRegistry() { return sliceRegistry; }
        @Override public java.util.Queue<ScheduledTick<T>> fabrolia$vanillaToRunThisTick() { return new java.util.ArrayDeque<>(); }
        @Override public java.util.List<ScheduledTick<T>> fabrolia$vanillaAlreadyRunThisTick() { return new java.util.ArrayList<>(); }
    }

    /** LevelData carrying the raw-wall-counter duck. */
    record TestLevelData(long raw) implements LevelData, RawGameTime {
        @Override public long fabrolia$rawGameTime() { return raw; }
        @Override public LevelData.RespawnData getRespawnData() { throw new UnsupportedOperationException(); }
        @Override public long getGameTime() { return raw; }
        @Override public boolean isHardcore() { return false; }
        @Override public Difficulty getDifficulty() { return Difficulty.NORMAL; }
        @Override public boolean isDifficultyLocked() { return false; }
    }

    record Scheduled(BlockPos pos, Object type, int delay) {}

    /** Minimal FULL-chunk seam for the production dirty-mark contract. The
     *  constructor is never entered: like {@link TestLevel}, the instance is
     *  allocated through Unsafe so this fixture cannot accidentally exercise
     *  unrelated LevelChunk construction. */
    static final class TestFullChunk extends LevelChunk {
        boolean markedUnsaved;

        private TestFullChunk() {
            super(null, new ChunkPos(1, 1));
            throw new AssertionError("never constructed — allocated via Unsafe");
        }

        static TestFullChunk alloc() {
            try {
                return (TestFullChunk) UNSAFE.allocateInstance(TestFullChunk.class);
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void markUnsaved() {
            markedUnsaved = true;
        }
    }

    /** Constructor-bypassed ServerLevel overriding exactly the accessors the
     *  inspector reads: clocks, lanes, dimension, world state at positions,
     *  and the schedule/chunk seams. */
    static class TestLevel extends ServerLevel {
        long fabricatedGameTime;
        LevelData fabricatedLevelData;
        TestLevelTicks<Block> blockLane;
        TestLevelTicks<Fluid> fluidLane;
        ResourceKey<Level> fabricatedDimension;
        Map<BlockPos, BlockState> states;
        List<Scheduled> scheduled;
        Map<Long, TestFullChunk> fullChunks;
        boolean scheduleCanonicalTicks;

        private TestLevel() {
            super(null, null, null, null, null, null, false, 0L, null, false);
            throw new AssertionError("never constructed — allocated via Unsafe");
        }

        static TestLevel alloc() {
            try {
                // Unsafe allocation skips field initializers — wire them here.
                TestLevel l = (TestLevel) UNSAFE.allocateInstance(TestLevel.class);
                l.fabricatedDimension = Level.OVERWORLD;
                l.states = new HashMap<>();
                l.scheduled = new ArrayList<>();
                l.fullChunks = new HashMap<>();
                return l;
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public long getGameTime() { return fabricatedGameTime; }
        @Override public LevelData getLevelData() { return fabricatedLevelData; }
        @Override public MinecraftServer getServer() { return null; }
        @Override public ResourceKey<Level> dimension() { return fabricatedDimension; }
        @Override public LevelTicks<Block> getBlockTicks() { return blockLane; }
        @Override public LevelTicks<Fluid> getFluidTicks() { return fluidLane; }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public void scheduleTick(BlockPos pos, Block block, int delay) {
            scheduled.add(new Scheduled(pos.immutable(), block, delay));
            if (scheduleCanonicalTicks) {
                blockLane.schedule(new ScheduledTick<>(block, pos, fabricatedGameTime + delay, TickPriority.NORMAL, 0L));
            }
        }

        @Override
        public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
            scheduled.add(new Scheduled(pos.immutable(), fluid, delay));
            if (scheduleCanonicalTicks) {
                fluidLane.schedule(new ScheduledTick<>(fluid, pos, fabricatedGameTime + delay, TickPriority.NORMAL, 0L));
            }
        }

        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
            if (status != ChunkStatus.FULL || create) {
                return null;
            }
            // Every container installed by this harness represents an already
            // loaded chunk. Materialize only its minimal dirty-mark seam.
            return fullChunks.computeIfAbsent(ChunkPos.pack(x, z), ignored -> TestFullChunk.alloc());
        }
    }

    // ---- global-state plumbing (test-only setup; the code under test reads
    //      this state through its normal accessors) ----

    @SuppressWarnings("unchecked")
    private static <T> T getStatic(Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(null);
    }

    private static void setStatic(Class<?> owner, String name, Object value) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @AfterEach
    void resetGlobalState() throws ReflectiveOperationException {
        setStatic(RegionScheduler.class, "active", false);
        ((Map<?, ?>) getStatic(RegionScheduler.class, "PER_LEVEL")).clear();
        setStatic(RegionScheduler.class, "orphanFatalFailure", null);
        TickDomains.clearFinalClocks();
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    /** A level whose one chunk [1,1] has real tagged containers registered in
     *  both lanes. */
    static final class Fixture {
        final TestLevel level;
        final InspectorChunkTicks<Block> block = new InspectorChunkTicks<>();
        final InspectorChunkTicks<Fluid> fluid = new InspectorChunkTicks<>();
        final long chunkKey = ChunkPos.pack(1, 1);

        Fixture(long wall) {
            level = TestLevel.alloc();
            level.fabricatedGameTime = wall;
            level.fabricatedLevelData = new TestLevelData(wall);
            level.blockLane = new TestLevelTicks<>();
            level.fluidLane = new TestLevelTicks<>();
            level.blockLane.containers.put(chunkKey, block);
            level.fluidLane.containers.put(chunkKey, fluid);
        }
    }

    private static BlockPos inChunk(int dx, int y, int dz) {
        return new BlockPos(16 + dx, y, 16 + dz);
    }

    @Test
    void inspectorPositionClearPersistsOnlyConfirmedMutation() {
        Fixture f = new Fixture(100L);
        BlockPos pos = inChunk(0, 64, 0);
        f.block.schedule(new ScheduledTick<>(Blocks.STONE, pos, 100L + TickInspector.SUSPECT_FUTURE + 1, TickPriority.NORMAL, 0L));
        TestFullChunk chunk = (TestFullChunk) f.level.getChunk(1, 1, ChunkStatus.FULL, false);
        TickInspector.clearStale(f.level, pos, false);
        assertTrue(f.block.hasScheduledTick(pos, Blocks.STONE));
        assertFalse(chunk.markedUnsaved);
        TickInspector.clearStale(f.level, pos, true);
        assertFalse(f.block.hasScheduledTick(pos, Blocks.STONE));
        assertTrue(chunk.markedUnsaved, "cleared ticks must be eligible for chunk persistence");
        chunk.markedUnsaved = false;
        TickInspector.clearStale(f.level, pos, true);
        assertFalse(chunk.markedUnsaved, "an already-clean container is not a mutation");
    }

    @Test
    void inspectorChunkClearPersistsBothLanesWithoutDirtyingDryRun() {
        Fixture f = new Fixture(100L);
        BlockPos pos = inChunk(0, 64, 0);
        long stale = 100L + TickInspector.SUSPECT_FUTURE + 1;
        f.block.schedule(new ScheduledTick<>(Blocks.STONE, pos, stale, TickPriority.NORMAL, 0L));
        f.fluid.schedule(new ScheduledTick<>(Fluids.WATER, pos, stale, TickPriority.NORMAL, 1L));
        TestFullChunk chunk = (TestFullChunk) f.level.getChunk(1, 1, ChunkStatus.FULL, false);
        TickInspector.clearStaleChunk(f.level, 1, 1, false, true);
        assertEquals(1, f.block.count());
        assertEquals(1, f.fluid.count());
        assertFalse(chunk.markedUnsaved);
        TickInspector.clearStaleChunk(f.level, 1, 1, false, false);
        assertEquals(0, f.block.count());
        assertEquals(0, f.fluid.count());
        assertTrue(chunk.markedUnsaved);
        chunk.markedUnsaved = false;
        TickInspector.clearStaleChunk(f.level, 1, 1, false, false);
        assertFalse(chunk.markedUnsaved);
    }

    @Test
    void inspectorReschedulePersistsAcceptedTickButNotDedupRefusal() {
        Fixture f = new Fixture(100L);
        BlockPos pos = inChunk(0, 64, 0);
        f.level.states.put(pos, Blocks.WATER.defaultBlockState());
        f.level.scheduleCanonicalTicks = true;
        f.level.blockLane.addContainer(new ChunkPos(1, 1), f.block);
        f.level.fluidLane.addContainer(new ChunkPos(1, 1), f.fluid);
        TestFullChunk chunk = (TestFullChunk) f.level.getChunk(1, 1, ChunkStatus.FULL, false);
        TickInspector.reschedule(f.level, pos, 5);
        assertTrue(f.block.hasScheduledTick(pos, Blocks.WATER));
        assertTrue(f.fluid.hasScheduledTick(pos, Fluids.WATER));
        assertTrue(chunk.markedUnsaved);
        chunk.markedUnsaved = false;
        TickInspector.reschedule(f.level, pos, 1);
        assertEquals(105L, f.block.peek().triggerTick());
        assertEquals(105L, f.fluid.peek().triggerTick());
        assertFalse(chunk.markedUnsaved, "vanilla dedup retained the original ticks unchanged");
    }
}
