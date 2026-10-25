package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickContainerAccess;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRODUCTION-PATH pins for the 2026-07-27 scheduled-tick clock-domain fixes.
 *
 * <p>Unlike {@link ScheduledTickClockDomainTest} (which pins the SPEC through
 * statement-for-statement mirrors), every test here calls the REAL fix-bearing
 * production code — {@link TickDomains#unpackBasis},
 * {@link TickDomains#saveBasis}, and
 * {@code IslandCommands.rebaseScheduledTick}'s ctx-identity resolution — so
 * each test FAILS if its fix is reverted (verified by reverting each fix in a
 * working copy and observing the failure).
 *
 * <p>Harness notes: the mixin-installed duck interfaces
 * ({@link FabroliaTickDomain}, {@link LevelTicksIslandAccess}) are implemented
 * by thin test subclasses of the REAL vanilla containers — the logic under
 * test lives entirely in main-source classes and consumes only these
 * interfaces. {@code ServerLevel}/{@code ChunkAccess} instances are allocated
 * without constructors (Unsafe) and override exactly the accessors the tested
 * seams read; clock resolution runs through the genuine
 * {@code TickDomains.resolveNow} order (live registry → islandLastL →
 * FINAL_CLOCKS retained snapshot).
 */
class TickDomainsProductionPathTest {

    private static final long WALL = FabroliaTickDomain.WALL;

    /** {@code Level}'s clinit reaches the builtin registries (particle codecs);
     *  the vanilla bootstrap must have run before any test touches it. */
    @org.junit.jupiter.api.BeforeAll
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

    /** REAL vanilla container + the mixin's tag duck. */
    static final class TaggedChunkTicks<T> extends LevelChunkTicks<T> implements FabroliaTickDomain {
        private long tagId = WALL;
        private long tagGen = -1;

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
        /** The production map: keys indexed by region-grid cell (LevelTicksMixin). */
        final com.axalotl.fabrolia.api.fastutil.Long2LongConcurrentHashMap next =
                new com.axalotl.fabrolia.api.fastutil.Long2LongConcurrentHashMap(
                        64, IslandScheduledTicks::scheduledTickCell);
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

    /** LevelData carrying the mod's raw-wall-counter duck (PrimaryLevelData
     *  shape): lets the test give the RAW wall clock a different value from
     *  {@code getGameTime()} — exactly the production divergence when a worker
     *  context pumps a promotion and the redirected getGameTime answers L. */
    record TestLevelData(long raw) implements LevelData, RawGameTime {
        @Override public long fabrolia$rawGameTime() { return raw; }
        @Override public LevelData.RespawnData getRespawnData() { throw new UnsupportedOperationException(); }
        @Override public long getGameTime() { return raw; }
        @Override public boolean isHardcore() { return false; }
        @Override public Difficulty getDifficulty() { return Difficulty.NORMAL; }
        @Override public boolean isDifficultyLocked() { return false; }
    }

    /** Constructor-bypassed ServerLevel overriding only what the tested seams
     *  read. Fields are set post-allocation. */
    static class TestLevel extends ServerLevel {
        long fabricatedGameTime;
        LevelData fabricatedLevelData;
        LevelTicks<Block> fabricatedBlockTicks;
        LevelTicks<Fluid> fabricatedFluidTicks;

        private TestLevel() {
            super(null, null, null, null, null, null, false, 0L, null, false);
            throw new AssertionError("never constructed — allocated via Unsafe");
        }

        static TestLevel alloc() {
            try {
                return (TestLevel) UNSAFE.allocateInstance(TestLevel.class);
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public long getGameTime() { return fabricatedGameTime; }
        @Override public LevelData getLevelData() { return fabricatedLevelData; }
        @Override public MinecraftServer getServer() { return null; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public LevelTicks<Block> getBlockTicks() { return fabricatedBlockTicks; }
        @Override public LevelTicks<Fluid> getFluidTicks() { return fabricatedFluidTicks; }
    }

    /** Constructor-bypassed ChunkAccess exposing pos + the two REAL vanilla
     *  tick containers ({@code TickDomains.saveBasis} reads nothing else). */
    static class TestChunk extends ChunkAccess {
        ChunkPos fabricatedPos;
        TickContainerAccess<Block> fabricatedBlockTicks;
        TickContainerAccess<Fluid> fabricatedFluidTicks;

        private TestChunk() {
            super(null, null, null, null, 0L, null, null);
            throw new AssertionError("never constructed — allocated via Unsafe");
        }

        static TestChunk alloc() {
            try {
                return (TestChunk) UNSAFE.allocateInstance(TestChunk.class);
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public ChunkPos getPos() { return fabricatedPos; }
        @Override public TickContainerAccess<Block> getBlockTicks() { return fabricatedBlockTicks; }
        @Override public TickContainerAccess<Fluid> getFluidTicks() { return fabricatedFluidTicks; }

        @Override public BlockState setBlockState(BlockPos pos, BlockState state, int flags) { throw new UnsupportedOperationException(); }
        @Override public void setBlockEntity(BlockEntity be) { throw new UnsupportedOperationException(); }
        @Override public void addEntity(Entity entity) { throw new UnsupportedOperationException(); }
        @Override public ChunkStatus getPersistedStatus() { throw new UnsupportedOperationException(); }
        @Override public void removeBlockEntity(BlockPos pos) { throw new UnsupportedOperationException(); }
        @Override public CompoundTag getBlockEntityNbtForSaving(BlockPos pos, HolderLookup.Provider registries) { throw new UnsupportedOperationException(); }
        @Override public ChunkAccess.PackedTicks getTicksForSerialization(long gameTime) { throw new UnsupportedOperationException(); }
        @Override public ChunkSkyLightSources getSkyLightSources() { throw new UnsupportedOperationException(); }
        @Override public StructureStart getStartForStructure(Structure structure) { throw new UnsupportedOperationException(); }
        @Override public void setStartForStructure(Structure structure, StructureStart start) { throw new UnsupportedOperationException(); }
        @Override public it.unimi.dsi.fastutil.longs.LongSet getReferencesForStructure(Structure structure) { throw new UnsupportedOperationException(); }
        @Override public void addReferenceForStructure(Structure structure, long reference) { throw new UnsupportedOperationException(); }
        @Override public Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> getAllReferences() { throw new UnsupportedOperationException(); }
        @Override public void setAllReferences(Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> references) { throw new UnsupportedOperationException(); }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) { throw new UnsupportedOperationException(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { throw new UnsupportedOperationException(); }
        @Override public BlockState getBlockState(BlockPos pos) { throw new UnsupportedOperationException(); }
        @Override public FluidState getFluidState(BlockPos pos) { throw new UnsupportedOperationException(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
    }

    // ---- private-static plumbing (test-only setup; the code under test
    //      reads this state through its normal accessors) ----

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

    /** Seed the retained final-clock snapshot ({@code TickDomains.FINAL_CLOCKS})
     *  for {@code level} — the production authority of last resort resolveNow
     *  consults when the scheduler is dark. */
    static void seedFinalClock(ServerLevel level, long islandId, long l) throws ReflectiveOperationException {
        Map<ServerLevel, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap> finals =
                getStatic(TickDomains.class, "FINAL_CLOCKS");
        finals.computeIfAbsent(level, k -> new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap())
                .put(islandId, l);
    }

    private static void setSchedulerActive(boolean active) throws ReflectiveOperationException {
        setStatic(RegionScheduler.class, "active", active);
    }

    @SuppressWarnings("unchecked")
    private static Map<ServerLevel, RegionizedServer> perLevel() throws ReflectiveOperationException {
        return (Map<ServerLevel, RegionizedServer>) (Map<?, ?>) getStatic(RegionScheduler.class, "PER_LEVEL");
    }

    @AfterEach
    void resetGlobalState() throws ReflectiveOperationException {
        setSchedulerActive(false);
        perLevel().clear();
        TickDomains.clearFinalClocks();
    }

    /** Run {@code body} on a real {@link RegionWorkerThread} with the given
     *  context, returning its result (worker identity + currentRegion /
     *  currentDomain are exactly what the ctx-identity resolution reads). */
    private static <T> T onWorker(Region region, EpochDomain domain,
                                  java.util.concurrent.Callable<T> body) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        RegionWorkerThread t = new RegionWorkerThread(() -> {
            try {
                out.set(body.call());
            } catch (Throwable e) {
                out.set(e);
            }
        }, "fabrolia-test-worker");
        t.currentRegion = region;
        t.currentDomain = domain;
        t.start();
        t.join(10_000);
        Object o = out.get();
        if (o instanceof Throwable e) {
            throw new AssertionError("worker body threw", e);
        }
        return (T) o;
    }

    /** Erased-lane cast: {@code saveBasis} consumes containers through the
     *  erased {@code LevelChunkTicks<?>}, so test lanes may carry String types
     *  exactly like the spec suite's containers do. */
    @SuppressWarnings("unchecked")
    private static <T> TickContainerAccess<T> cast(LevelChunkTicks<?> c) {
        return (TickContainerAccess<T>) c;
    }

    private static Region regionWithDomain(EpochDomain d) throws InstantiationException {
        Region r = (Region) UNSAFE.allocateInstance(Region.class);
        r.domain = d;
        return r;
    }

    private static EpochDomain islandDomain(long islandId, long unitGameTime) {
        EpochDomain d = new EpochDomain();
        d.owner = new IslandRegistry.PIsland(islandId, 0);
        d.unitGameTime = unitGameTime;
        return d;
    }

    // ------------------------------------------------------------------
    // (a) TickDomains.unpackBasis — the REAL method
    // ------------------------------------------------------------------

    @Test
    void productionUnpackBasisAnchorsIslandTagOnTaggedClockNotCallerClock() throws Exception {
        TestLevel level = TestLevel.alloc();
        level.fabricatedGameTime = 200_000;              // promoting thread's clock
        seedFinalClock(level, 101, 110_000);             // island 101's L
        TaggedChunkTicks<String> c = new TaggedChunkTicks<>();
        c.fabrolia$setDomain(101, 1);
        // vanilla would anchor at the caller's 200_000; the fix must answer
        // the TAGGED domain's clock, exactly.
        assertEquals(110_000L, TickDomains.unpackBasis(level, c, 200_000L),
                "island-tagged container must anchor on the tag's resolved clock");
    }

    @Test
    void productionUnpackBasisAnchorsWallTagOnRawWallCounterNotCallerClock() throws Exception {
        TestLevel level = TestLevel.alloc();
        level.fabricatedLevelData = new TestLevelData(42_000);  // RAW wall counter
        level.fabricatedGameTime = 9_999;                // redirected worker clock
        TaggedChunkTicks<String> c = new TaggedChunkTicks<>(); // WALL tag (default)
        // callerNow simulates a worker context having pumped the promotion
        // task (getGameTime answered some island's L): the WALL anchor must be
        // the raw wall counter, never the caller's value.
        assertEquals(42_000L, TickDomains.unpackBasis(level, c, 55_555L),
                "WALL-tagged container must anchor on the raw wall counter");
    }

    @Test
    void productionUnpackBasisFailsBeforeUsingCallerClockOnUnresolvableTag() throws Exception {
        TestLevel level = TestLevel.alloc();
        level.fabricatedGameTime = 1_000;
        TaggedChunkTicks<String> c = new TaggedChunkTicks<>();
        c.fabrolia$setDomain(999, 1);                    // never recorded anywhere
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TickDomains.unpackBasis(level, c, 77_777L));
        assertTrue(failure.getMessage().contains("action=FAIL-STOP"),
                "unresolvable tag must stop before caller-clock fallback");
    }

    // ------------------------------------------------------------------
    // (c) TickDomains.saveBasis — the REAL method
    // ------------------------------------------------------------------

    @Test
    void productionSaveBasisPacksAgainstOwnerDomainClockNotWall() throws Exception {
        TestLevel level = TestLevel.alloc();
        seedFinalClock(level, 101, 110_000);             // owner island's L
        TaggedChunkTicks<String> block = new TaggedChunkTicks<>();
        block.fabrolia$setDomain(101, 1);
        TaggedChunkTicks<String> fluid = new TaggedChunkTicks<>();
        fluid.fabrolia$setDomain(101, 1);
        block.schedule(new ScheduledTick<>("repeater", new BlockPos(3, 64, 3),
                110_070L, TickPriority.NORMAL, 0));      // 70 ticks remaining on L
        TestChunk chunk = TestChunk.alloc();
        chunk.fabricatedPos = new ChunkPos(0, 0);
        chunk.fabricatedBlockTicks = cast(block);
        chunk.fabricatedFluidTicks = cast(fluid);
        long wall = 200_000;                             // what vanilla would pack against
        long basis = TickDomains.saveBasis(level, chunk, wall);
        assertEquals(110_000L, basis,
                "save basis must be the OWNER domain's clock, never the wall clock");
        // and the delay vanilla's pack derives from that basis is the TRUE
        // remaining delay in the owner's domain:
        assertEquals(70, block.pack(basis).get(0).delay(),
                "packed delay must be the remaining delay in the owner's domain");
    }

    // ------------------------------------------------------------------
    // (b) IslandCommands.rebaseScheduledTick — the REAL method, REAL
    //     RegionWorkerThread context (ctx-identity resolution, ~line 432)
    // ------------------------------------------------------------------

    /** Full production wiring for rebaseScheduledTick: an active scheduler,
     *  a RegionizedServer whose block lane IS the test scheduler, and island
     *  clocks resolvable through islandLastL. */
    private TestLevelTicks<String> scheduler;
    private TestLevel bLevel;
    private TaggedChunkTicks<String> bContainer;
    private long bChunkKey;

    private void wireRebaseFixture(long containerTagId) throws Exception {
        bLevel = TestLevel.alloc();
        bLevel.fabricatedGameTime = 1_000;               // caller/wall clock
        scheduler = new TestLevelTicks<>();
        bLevel.fabricatedBlockTicks = (LevelTicks<Block>) (LevelTicks<?>) scheduler;
        bLevel.fabricatedFluidTicks = null;
        RegionizedServer rs = new RegionizedServer(bLevel, new RegionGrid(2));
        rs.asyncActive = true;
        rs.blockTicksAccess = scheduler;
        synchronized (rs.islandLastL) {
            rs.islandLastL.put(101L, 50_000L);           // island A's clock
            rs.islandLastL.put(202L, 70_000L);           // island B's clock
        }
        setSchedulerActive(true);
        perLevel().put(bLevel, rs);
        BlockPos pos = new BlockPos(16, 64, 16);
        bChunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        bContainer = new TaggedChunkTicks<>();
        bContainer.fabrolia$setDomain(containerTagId, 1);
        scheduler.containers.put(bChunkKey, bContainer);
    }

    @Test
    void ctxIdentityPrefersClaimedRegionDomainOverWorkerCurrentDomain() throws Exception {
        wireRebaseFixture(101);
        // Worker mid-unit: claimed region belongs to island A (101), but the
        // thread's currentDomain still points at island B (202). contextTime
        // prefers the region and never falls through past it — the identity
        // feeding the same-domain short-circuit must name the SAME clock.
        Region claimed = regionWithDomain(islandDomain(101, 50_001));
        EpochDomain straggler = islandDomain(202, 70_001);
        ScheduledTick<String> tick = new ScheduledTick<>("repeater",
                new BlockPos(16, 64, 16), 50_005L, TickPriority.NORMAL, 7);
        ScheduledTick<String> out = onWorker(claimed, straggler,
                () -> IslandCommands.rebaseScheduledTick(scheduler, tick));
        // same-domain call: returned UNTOUCHED. A resolution preferring
        // currentDomain would compute ctx=202 and shift by (50_000 − 1_000).
        assertSame(tick, out, "same-domain schedule must short-circuit untouched");
        assertEquals(50_005L, out.triggerTick());
    }

    @Test
    void ctxIdentityTreatsUnitGameTimeZeroAsWallContext() throws Exception {
        wireRebaseFixture(101);
        // Worker whose claimed region names island A but whose domain has NOT
        // begun a unit (unitGameTime == 0): contextTime answers WALL there, so
        // the identity must be WALL too — the wall-computed trigger MUST be
        // rebased onto the island clock. Ignoring the sentinel would read
        // ctx=101==tag and skip the rebase the trigger actually needs.
        Region claimed = regionWithDomain(islandDomain(101, 0));
        ScheduledTick<String> tick = new ScheduledTick<>("repeater",
                new BlockPos(16, 64, 16), 1_007L, TickPriority.NORMAL, 9);  // wall + 7
        ScheduledTick<String> out = onWorker(claimed, null,
                () -> IslandCommands.rebaseScheduledTick(scheduler, tick));
        // delta = tagNow(50_000) − ctxNow(1_000): trigger re-expressed exactly.
        assertEquals(50_007L, out.triggerTick(),
                "wall-context trigger must be rebased onto the island clock");
        assertEquals("repeater", out.type());
        assertEquals(new BlockPos(16, 64, 16), out.pos());
        assertEquals(TickPriority.NORMAL, out.priority());
        assertEquals(9L, out.subTickOrder());
    }

    @Test
    void ctxIdentityIsWallOnNonWorkerThreads() throws Exception {
        wireRebaseFixture(101);
        // Plain (main-shaped) thread scheduling into island territory: ctx is
        // WALL by definition; trigger computed on the wall clock is rebased.
        ScheduledTick<String> tick = new ScheduledTick<>("observer",
                new BlockPos(16, 64, 16), 1_009L, TickPriority.NORMAL, 11);  // wall + 9
        ScheduledTick<String> out = IslandCommands.rebaseScheduledTick(scheduler, tick);
        assertEquals(50_009L, out.triggerTick(),
                "main-thread trigger must be re-expressed on the container's island clock");
    }

    @Test
    void consumerHealPublishesBothRefreshedIndexesInsideThePairLock() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/axalotl/fabrolia/"
                + "common/regions/TickDomains.java"));
        int method = source.indexOf("public static long enforceConsumerDomain");
        int end = source.indexOf("public static void collapseAllToWall()", method);
        String body = source.substring(method, end);
        int pairLock = body.indexOf("synchronized (blockLane.fabrolia$tickLock())");
        int siblingRemove = body.indexOf(
                "sibling.fabrolia$nextTickForContainer().remove(chunkKey)", pairLock);
        int scannedPeek = body.indexOf("ScheduledTick<?> head = c.peek();", siblingRemove);
        int scannedPut = body.indexOf(
                "access.fabrolia$nextTickForContainer().put(chunkKey, newNext)", scannedPeek);
        int pairLockEnd = body.indexOf("\n            }\n        }\n        if (moved > 0)", scannedPut);
        assertTrue(pairLock >= 0 && siblingRemove > pairLock && scannedPeek > siblingRemove
                        && scannedPut > scannedPeek && pairLockEnd > scannedPut,
                "head/index publication must stay inside the block->fluid lock pair");
    }
}
