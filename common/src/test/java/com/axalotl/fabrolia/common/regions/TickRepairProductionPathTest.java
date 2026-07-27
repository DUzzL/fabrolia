package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRODUCTION-PATH pins for the bulk scheduled-tick repair facility
 * ({@link TickRepair} / {@link TickRepairNbt},
 * {@code /fabrolia regions ticks repair-all}).
 *
 * <p>Every test drives the REAL fix-bearing production code —
 * {@code TickRepair.runLoadedPass} / {@code processChunk} and
 * {@code TickRepairNbt.classifyChunkNbt} — over REAL vanilla containers
 * ({@link LevelChunkTicks}) holding REAL block/fluid types. Following the
 * suite convention (TickDomainsProductionPathTest), the mixin-installed duck
 * interfaces are implemented by thin test subclasses of the vanilla classes:
 * the domain tag as plain fields, the packed-ticks access by reflecting the
 * SAME private vanilla fields the mixin shadows.
 *
 * <p>The single most important pin: legitimate long delays (99_999_999 and a
 * value near the int save bound) are classified NOT corrupt and survive every
 * pass BYTE-identical (asserted on vanilla's own serialized NBT bytes).
 */
class TickRepairProductionPathTest {

    private static final long WALL = FabroliaTickDomain.WALL;
    private static final long LONG_DELAY = 99_999_999L;
    private static final long NEAR_INT_BOUND_DELAY = 2_147_483_640L;   // Integer.MAX_VALUE - 7

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
    static class RepairChunkTicks<T> extends LevelChunkTicks<T> implements FabroliaTickDomain {

        private long tagId = WALL;
        private long tagGen = -1;

        RepairChunkTicks() {}

        RepairChunkTicks(List<SavedTick<T>> pending) {
            super(pending);
        }

        @Override public long fabrolia$domainIsland() { return tagId; }
        @Override public long fabrolia$domainGen() { return tagGen; }
        @Override public void fabrolia$setDomain(long islandId, long gen) {
            this.tagId = islandId;
            this.tagGen = gen;
        }
    }

    /** REAL vanilla container reshaped exactly the way Lithium's
     *  {@code mixin.world.tick_scheduler.LevelChunkTicksMixin} reshapes EVERY
     *  container at runtime (verified against the disassembly of
     *  lithium-mc26.2-0.25.0: {@code reinit}, injected at RETURN of BOTH
     *  constructors, seeds an {@code allTicks} hash set from
     *  {@code pendingTicks}, then sets {@code ticksPerPosition = null} and
     *  {@code tickQueue = null}; every public scheduler method is overwritten
     *  to use Lithium's own structures — {@code getAll}/{@code removeIf} walk
     *  only the live queue, {@code hasScheduledTick}/{@code count} answer from
     *  {@code allTicks}, {@code schedule} dedups against {@code allTicks},
     *  {@code pack} concatenates pending + queue, {@code unpack} drains
     *  pending into the queue without re-adding hashes).
     *
     *  <p>The two vanilla fields are reflectively set to null on the REAL
     *  class ({@code Field.setAccessible(true)} does allow writing non-static
     *  final instance fields of ordinary classes), so any production access
     *  to vanilla scheduler internals fails here exactly as it did on the
     *  production server ({@code ticksPerPosition} NPE). */
    static class LithiumChunkTicks<T> extends LevelChunkTicks<T> implements FabroliaTickDomain {

        static final Field PENDING;        // Lithium @Shadows and keeps using the vanilla field
        static final Field PER_POSITION;   // nulled by Lithium's reinit
        static final Field TICK_QUEUE;     // nulled by Lithium's reinit

        static {
            try {
                PENDING = LevelChunkTicks.class.getDeclaredField("pendingTicks");
                PENDING.setAccessible(true);
                PER_POSITION = LevelChunkTicks.class.getDeclaredField("ticksPerPosition");
                PER_POSITION.setAccessible(true);
                TICK_QUEUE = LevelChunkTicks.class.getDeclaredField("tickQueue");
                TICK_QUEUE.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        record Key(Object type, long pos) {}

        final Set<Key> allTicks = new java.util.HashSet<>();
        final List<ScheduledTick<T>> queue = new ArrayList<>();
        private long tagId = WALL;
        private long tagGen = -1;

        LithiumChunkTicks() {
            lithiumReinit();
        }

        LithiumChunkTicks(List<SavedTick<T>> pending) {
            super(pending);
            lithiumReinit();
        }

        @SuppressWarnings("unchecked")
        private void lithiumReinit() {
            try {
                List<SavedTick<T>> pending = (List<SavedTick<T>>) PENDING.get(this);
                if (pending != null) {
                    for (SavedTick<T> sv : pending) {
                        allTicks.add(new Key(sv.type(), sv.pos().asLong()));
                    }
                }
                PER_POSITION.set(this, null);   // Lithium: putfield ticksPerPosition = null
                TICK_QUEUE.set(this, null);     // Lithium: putfield tickQueue = null
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings("unchecked")
        List<SavedTick<T>> pendingView() {
            try {
                return (List<SavedTick<T>>) PENDING.get(this);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public void schedule(ScheduledTick<T> tick) {
            if (allTicks.add(new Key(tick.type(), tick.pos().asLong()))) {
                queue.add(tick);
            }
        }

        @Override public boolean hasScheduledTick(BlockPos pos, T type) {
            return allTicks.contains(new Key(type, pos.asLong()));
        }

        @Override public void removeIf(java.util.function.Predicate<ScheduledTick<T>> pred) {
            for (var it = queue.iterator(); it.hasNext(); ) {
                ScheduledTick<T> t = it.next();
                if (pred.test(t)) {
                    it.remove();
                    allTicks.remove(new Key(t.type(), t.pos().asLong()));
                }
            }
        }

        @Override public java.util.stream.Stream<ScheduledTick<T>> getAll() {
            return new ArrayList<>(queue).stream();   // live queue only — pending stays invisible
        }

        @Override public int count() {
            return allTicks.size();
        }

        @Override public ScheduledTick<T> peek() {
            return queue.stream().min(ScheduledTick.DRAIN_ORDER).orElse(null);
        }

        @Override public ScheduledTick<T> poll() {
            ScheduledTick<T> head = peek();
            if (head != null) {
                queue.remove(head);
                allTicks.remove(new Key(head.type(), head.pos().asLong()));
            }
            return head;
        }

        @Override public List<SavedTick<T>> pack(long gameTime) {
            List<SavedTick<T>> out = new ArrayList<>();
            List<SavedTick<T>> pending = pendingView();
            if (pending != null) {
                out.addAll(pending);
            }
            for (ScheduledTick<T> t : queue) {
                out.add(t.toSavedTick(gameTime));
            }
            return out;
        }

        @Override public void unpack(long gameTime) {
            List<SavedTick<T>> pending = pendingView();
            if (pending != null) {
                long sub = -pending.size();
                for (SavedTick<T> sv : pending) {
                    queue.add(sv.unpack(gameTime, sub++));   // hashes already in allTicks
                }
            }
            try {
                PENDING.set(this, null);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

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

        TestLevelTicks() { super(k -> true); }

        @Override public it.unimi.dsi.fastutil.longs.Long2ObjectMap<LevelChunkTicks<T>> fabrolia$allContainers() { return containers; }
        @Override public it.unimi.dsi.fastutil.longs.Long2LongMap fabrolia$nextTickForContainer() { return next; }
        @Override public java.util.function.LongPredicate fabrolia$tickCheck() { return k -> true; }
        @Override public Object fabrolia$tickLock() { return lock; }
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

    /** Constructor-bypassed ServerLevel overriding exactly the accessors the
     *  repair facility reads: clocks, lanes, dimension, world state at
     *  positions, and the schedule/chunk seams. */
    static class TestLevel extends ServerLevel {
        long fabricatedGameTime;
        LevelData fabricatedLevelData;
        TestLevelTicks<Block> blockLane;
        TestLevelTicks<Fluid> fluidLane;
        ResourceKey<Level> fabricatedDimension;
        Map<BlockPos, BlockState> states;
        List<Scheduled> scheduled;

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
        }

        @Override
        public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
            scheduled.add(new Scheduled(pos.immutable(), fluid, delay));
        }

        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
            return null;   // markDirty seam: nothing to mark in the harness
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

    private static void seedFinalClock(ServerLevel level, long islandId, long l) throws ReflectiveOperationException {
        Map<ServerLevel, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap> finals =
                getStatic(TickDomains.class, "FINAL_CLOCKS");
        finals.computeIfAbsent(level, k -> new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap())
                .put(islandId, l);
    }

    @AfterEach
    void resetGlobalState() throws ReflectiveOperationException {
        setStatic(RegionScheduler.class, "active", false);
        ((Map<?, ?>) getStatic(RegionScheduler.class, "PER_LEVEL")).clear();
        TickDomains.clearFinalClocks();
    }

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    /** A level whose one chunk [1,1] has real tagged containers registered in
     *  both lanes. */
    static final class Fixture {
        final TestLevel level;
        final RepairChunkTicks<Block> block = new RepairChunkTicks<>();
        final RepairChunkTicks<Fluid> fluid = new RepairChunkTicks<>();
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

    private static List<ScheduledTick<Block>> sortedQueue(LevelChunkTicks<Block> c) {
        return c.getAll()
                .sorted(Comparator.<ScheduledTick<Block>>comparingLong(ScheduledTick::triggerTick)
                        .thenComparingLong(ScheduledTick::subTickOrder))
                .toList();
    }

    /** Vanilla's own binary serialization of a packed tick list — the
     *  byte-identical yardstick. */
    private static byte[] nbtBytes(List<SavedTick<Block>> packed) throws Exception {
        ListTag list = (ListTag) TickRepairNbt.BLOCK_TICK_CODEC.listOf()
                .encodeStart(NbtOps.INSTANCE, packed).getOrThrow();
        CompoundTag root = new CompoundTag();
        root.put("block_ticks", list);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            NbtIo.write(root, dos);
        }
        return bos.toByteArray();
    }

    private static TickRepair.Result run(TestLevel level, TickRepair.Mode mode, Path dir) {
        return TickRepair.runLoadedPass(List.of(level), mode, dir, null);
    }

    // ------------------------------------------------------------------
    // 1. THE pin: legitimate long delays are NOT corrupt, survive
    //    byte-identical through the full confirm pass
    // ------------------------------------------------------------------

    @Test
    void legitimateLongDelaysSurviveConfirmByteIdentical() throws Exception {
        long wall = 1_000;
        Fixture fx = new Fixture(wall);
        BlockPos a = inChunk(0, 64, 0), b = inChunk(1, 64, 0);
        fx.level.states.put(a, Blocks.STONE.defaultBlockState());
        fx.level.states.put(b, Blocks.STONE.defaultBlockState());
        // tag == consumer (WALL), types match: a coherent container whose only
        // notable feature is delay magnitude — which must never be evidence.
        fx.block.schedule(new ScheduledTick<>(Blocks.STONE, a, wall + LONG_DELAY, TickPriority.NORMAL, 5));
        fx.block.schedule(new ScheduledTick<>(Blocks.STONE, b, wall + NEAR_INT_BOUND_DELAY, TickPriority.HIGH, 6));
        byte[] before = nbtBytes(fx.block.pack(wall));

        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("legit"));

        assertEquals(0, r.totals.domainSyncEntries, "no domain transition may touch a coherent container");
        assertEquals(0, r.totals.removedMismatch);
        assertEquals(0, r.totals.ambiguousEntries);
        assertEquals(0, r.totals.clearedAmbiguous);
        assertEquals(0, r.totals.rescheduled);
        assertEquals(2, r.totals.healthyEntries);
        assertTrue(r.applied.isEmpty(), "confirm must not mutate anything here");
        List<ScheduledTick<Block>> q = sortedQueue(fx.block);
        assertEquals(2, q.size());
        assertEquals(new ScheduledTick<>(Blocks.STONE, a, wall + LONG_DELAY, TickPriority.NORMAL, 5), q.get(0));
        assertEquals(new ScheduledTick<>(Blocks.STONE, b, wall + NEAR_INT_BOUND_DELAY, TickPriority.HIGH, 6), q.get(1));
        assertArrayEquals(before, nbtBytes(fx.block.pack(wall)),
                "legitimate long delays must survive the repair byte-identical");
    }

    // ------------------------------------------------------------------
    // 2. DOMAIN-SYNC (tag != consumer, both clocks resolvable): the routine
    //    pending domain transition is applied exactly, long delay preserved,
    //    idempotent — and it is NOT counted as corruption
    // ------------------------------------------------------------------

    @Test
    void domainSyncAppliedExactlyAndIdempotently() throws Exception {
        long wall = 1_000, islandL = 50_000;
        Fixture fx = new Fixture(wall);
        seedFinalClock(fx.level, 101, islandL);
        fx.block.fabrolia$setDomain(101, 3);
        BlockPos a = inChunk(0, 64, 1), b = inChunk(2, 64, 1);
        fx.level.states.put(a, Blocks.STONE.defaultBlockState());
        fx.level.states.put(b, Blocks.STONE.defaultBlockState());
        // every entry of the container is expressed on island#101's clock; the
        // consumer is WALL: the routine pending transition delta = wall - L
        fx.block.schedule(new ScheduledTick<>(Blocks.STONE, a, islandL + 5, TickPriority.NORMAL, 7));
        fx.block.schedule(new ScheduledTick<>(Blocks.STONE, b, islandL + LONG_DELAY, TickPriority.VERY_HIGH, 8));

        // DRY first: reports, never mutates
        TickRepair.Result dry = run(fx.level, TickRepair.Mode.DRY, tmp.resolve("offset-dry"));
        assertEquals(2, dry.findings.stream().filter(f -> f.action().equals(TickRepair.ACTION_DOMAIN_SYNC)).count());
        assertEquals(islandL + 5, sortedQueue(fx.block).get(0).triggerTick(), "dry run must not shift");
        assertEquals(101, fx.block.fabrolia$domainIsland(), "dry run must not retag");
        assertNotNull(dry.reportFile);
        String report = Files.readString(dry.reportFile);
        assertTrue(report.contains(TickRepair.ACTION_DOMAIN_SYNC));
        assertTrue(report.contains(TickRepair.SCOPE), "the report must lead with the honest scope statement");
        assertTrue(report.contains("minecraft:overworld"));

        // CONFIRM: exact delta, all fields preserved, tag healed
        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("offset"));
        assertEquals(2, r.totals.domainSyncEntries);
        assertEquals(1, r.totals.domainSyncContainers);
        assertEquals(0, r.totals.removedMismatch);
        List<ScheduledTick<Block>> q = sortedQueue(fx.block);
        assertEquals(new ScheduledTick<>(Blocks.STONE, a, wall + 5, TickPriority.NORMAL, 7), q.get(0),
                "relative delay +5 must land exactly at wall+5");
        assertEquals(new ScheduledTick<>(Blocks.STONE, b, wall + LONG_DELAY, TickPriority.VERY_HIGH, 8), q.get(1),
                "the legitimate long delay must be preserved exactly through the transition");
        assertEquals(WALL, fx.block.fabrolia$domainIsland(), "the tag must be brought to the consumer domain");
        assertEquals(wall + 5, fx.level.blockLane.next.get(fx.chunkKey), "next-tick index must be refreshed");

        // idempotency: the second run finds nothing and shifts nothing
        TickRepair.Result again = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("offset2"));
        assertEquals(0, again.totals.domainSyncEntries, "second run must find nothing to transition");
        assertEquals(0, again.totals.domainSyncContainers);
        assertTrue(again.applied.isEmpty());
        assertEquals(q, sortedQueue(fx.block), "second run must not shift anything again");
    }

    /** THE mislabelling pin (2026-07-27 review): a container in a routine
     *  tag != consumer state — exactly what {@code enforceConsumerDomain}
     *  heals lazily on every healthy async server after a merge/split/rebuild
     *  — must NOT be counted as corruption. The corruption counter stays 0
     *  while the transition counter increments, and the finding says so. */
    @Test
    void routineDomainTransitionIsNotCountedAsCorruption() throws Exception {
        long wall = 7_000, islandL = 7_500;
        Fixture fx = new Fixture(wall);
        seedFinalClock(fx.level, 42, islandL);
        fx.block.fabrolia$setDomain(42, 1);   // healthy container, ownership moved on
        BlockPos pos = inChunk(0, 64, 9);
        fx.level.states.put(pos, Blocks.STONE.defaultBlockState());
        fx.block.schedule(new ScheduledTick<>(Blocks.STONE, pos, islandL + 20, TickPriority.NORMAL, 1));

        TickRepair.Result dry = run(fx.level, TickRepair.Mode.DRY, tmp.resolve("routine-dry"));
        assertEquals(0, dry.totals.removedMismatch, "corruption counter must stay 0 for a routine transition");
        assertEquals(0, dry.totals.ambiguousEntries);
        assertEquals(0, dry.totals.clearedAmbiguous);
        assertEquals(1, dry.totals.domainSyncEntries, "the transition counter is the ONLY one that moves");
        assertEquals(1, dry.totals.domainSyncContainers,
                "the DRY run must report the container count too (review finding #4)");
        TickRepair.Finding f = dry.findings.get(0);
        assertEquals(TickRepair.ACTION_DOMAIN_SYNC, f.action());
        assertTrue(f.reason().contains("NOT evidence of corruption"),
                "the finding must state this is not corruption");
        assertTrue(f.reason().contains("routine pending domain transition"));
        assertTrue(dry.totals.summary().contains("corruption repaired (type-mismatch removals)=0"),
                "the summary must not present the transition as damage found");
        assertTrue(dry.totals.summary().contains("routine domain transitions applied (NOT corruption)=1"));

        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("routine"));
        assertEquals(0, r.totals.removedMismatch);
        assertEquals(1, r.totals.domainSyncEntries);
        assertEquals(new ScheduledTick<>(Blocks.STONE, pos, wall + 20, TickPriority.NORMAL, 1),
                sortedQueue(fx.block).get(0), "the transition itself is still applied exactly");
    }

    // ------------------------------------------------------------------
    // 3. Definitely-stale type mismatch: removed; coherent long delay at
    //    another position untouched; fluid recovery reschedule
    // ------------------------------------------------------------------

    @Test
    void typeMismatchRemovedAndLegitimateEntryUntouched() throws Exception {
        long wall = 2_000;
        Fixture fx = new Fixture(wall);
        BlockPos stale = inChunk(3, 64, 2), legit = inChunk(4, 64, 2);
        fx.level.states.put(stale, Blocks.STONE.defaultBlockState());       // world moved on
        fx.level.states.put(legit, Blocks.STONE.defaultBlockState());
        fx.block.schedule(new ScheduledTick<>(Blocks.REDSTONE_ORE, stale, wall + 4, TickPriority.NORMAL, 1));
        fx.block.schedule(new ScheduledTick<>(Blocks.STONE, legit, wall + LONG_DELAY, TickPriority.NORMAL, 2));

        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("mismatch"));
        assertEquals(1, r.totals.removedMismatch);
        assertEquals(0, r.totals.domainSyncEntries);
        List<ScheduledTick<Block>> q = sortedQueue(fx.block);
        assertEquals(1, q.size());
        assertEquals(new ScheduledTick<>(Blocks.STONE, legit, wall + LONG_DELAY, TickPriority.NORMAL, 2), q.get(0));
        assertFalse(fx.block.hasScheduledTick(stale, Blocks.REDSTONE_ORE),
                "dedup slot must be freed by the removal");
        assertTrue(fx.block.hasScheduledTick(legit, Blocks.STONE));

        TickRepair.Result again = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("mismatch2"));
        assertEquals(0, again.totals.removedMismatch, "idempotent: nothing left to remove");
    }

    @Test
    void staleFluidRemovalReschedulesTheCurrentFluid() throws Exception {
        long wall = 3_000;
        Fixture fx = new Fixture(wall);
        BlockPos pos = inChunk(5, 64, 3);
        fx.level.states.put(pos, Blocks.WATER.defaultBlockState());   // water source lives here now
        fx.fluid.schedule(new ScheduledTick<>(Fluids.LAVA, pos, wall + 2, TickPriority.NORMAL, 1));

        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("fluid"));
        assertEquals(1, r.totals.removedMismatch);
        assertEquals(1, r.totals.rescheduled,
                "removing the stale fluid entry orphans a live fluid — it must be rescheduled");
        assertEquals(List.of(new Scheduled(pos, Fluids.WATER, 1)), fx.level.scheduled,
                "the CURRENT fluid gets a fresh 1-tick schedule (never an immediate simulated tick)");
        assertEquals(0, fx.fluid.count());
    }

    // ------------------------------------------------------------------
    // 4. AMBIGUOUS: untouched by the normal pass, cleared ONLY by
    //    clear-ambiguous confirm (with recovery reschedule + tag heal)
    // ------------------------------------------------------------------

    @Test
    void ambiguousEntriesUntouchedByNormalPassAndClearedOnlyByClearConfirm() throws Exception {
        long wall = 4_000;
        Fixture fx = new Fixture(wall);
        fx.block.fabrolia$setDomain(777, 1);   // island 777 was never recorded anywhere
        BlockPos amb = inChunk(6, 64, 4), stale = inChunk(7, 64, 4);
        fx.level.states.put(amb, Blocks.STONE.defaultBlockState());
        fx.level.states.put(stale, Blocks.STONE.defaultBlockState());
        ScheduledTick<Block> ambiguous = new ScheduledTick<>(Blocks.STONE, amb, 123_456L, TickPriority.NORMAL, 9);
        fx.block.schedule(ambiguous);
        fx.block.schedule(new ScheduledTick<>(Blocks.REDSTONE_ORE, stale, 123_457L, TickPriority.NORMAL, 10));

        // normal confirm: the mismatch goes, the ambiguous entry stays EXACTLY
        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("amb"));
        assertEquals(1, r.totals.removedMismatch);
        assertEquals(1, r.totals.ambiguousEntries);
        assertEquals(0, r.totals.domainSyncEntries, "an unresolvable container must never be rebased");
        assertEquals(0, r.totals.clearedAmbiguous);
        assertEquals(List.of(ambiguous), sortedQueue(fx.block), "ambiguous entry must be untouched");
        assertEquals(777, fx.block.fabrolia$domainIsland(), "the tag must not be faked while entries remain");

        // clear-ambiguous DRY: still untouched
        TickRepair.Result cd = run(fx.level, TickRepair.Mode.CLEAR_DRY, tmp.resolve("amb-cd"));
        assertEquals(1, cd.totals.ambiguousEntries);
        assertEquals(0, cd.totals.clearedAmbiguous);
        assertEquals(List.of(ambiguous), sortedQueue(fx.block));

        // clear-ambiguous CONFIRM: removed, container healed, block recovered
        TickRepair.Result cc = run(fx.level, TickRepair.Mode.CLEAR_CONFIRM, tmp.resolve("amb-cc"));
        assertEquals(1, cc.totals.clearedAmbiguous);
        assertEquals(0, fx.block.count());
        assertEquals(WALL, fx.block.fabrolia$domainIsland(),
                "after clearing, the dead tag is replaced by the consumer domain (re-run = no-op)");
        assertEquals(1, cc.totals.rescheduled);
        assertEquals(List.of(new Scheduled(amb, Blocks.STONE, 1)), fx.level.scheduled,
                "the cleared entry's type still matches the world — the block is rescheduled to recover");

        // idempotency of the destructive mode
        TickRepair.Result cc2 = run(fx.level, TickRepair.Mode.CLEAR_CONFIRM, tmp.resolve("amb-cc2"));
        assertEquals(0, cc2.totals.clearedAmbiguous);
        assertEquals(0, cc2.totals.ambiguousEntries);
    }

    /** Review finding #5 pin: clearing the ambiguous entries must heal the
     *  dead tag EVEN IF proven-stale mismatch entries remain in the queue
     *  (clear mode deliberately leaves those for repair-all confirm) —
     *  otherwise later schedules at this chunk go through rebaseTo's
     *  adopt-without-shift ERROR path and packed delays anchor via
     *  unpackBasis's caller fallback, the exact bug class this facility
     *  exists to prevent. */
    @Test
    void clearConfirmHealsTagEvenWhenMismatchEntriesRemain() throws Exception {
        long wall = 8_000;
        Fixture fx = new Fixture(wall);
        fx.block.fabrolia$setDomain(999, 2);   // island 999 was never recorded anywhere
        BlockPos amb = inChunk(1, 64, 10), stale = inChunk(2, 64, 10);
        fx.level.states.put(amb, Blocks.STONE.defaultBlockState());
        fx.level.states.put(stale, Blocks.STONE.defaultBlockState());
        ScheduledTick<Block> ambiguous = new ScheduledTick<>(Blocks.STONE, amb, 55_555L, TickPriority.NORMAL, 1);
        ScheduledTick<Block> mismatch = new ScheduledTick<>(Blocks.REDSTONE_ORE, stale, 55_556L, TickPriority.NORMAL, 2);
        fx.block.schedule(ambiguous);
        fx.block.schedule(mismatch);

        TickRepair.Result cc = run(fx.level, TickRepair.Mode.CLEAR_CONFIRM, tmp.resolve("heal"));
        assertEquals(1, cc.totals.clearedAmbiguous);
        assertEquals(WALL, fx.block.fabrolia$domainIsland(),
                "the dead tag must be healed even though a mismatch entry remains");
        assertEquals(List.of(mismatch), sortedQueue(fx.block),
                "the proven-stale mismatch entry stays (clear mode touches only AMBIGUOUS entries)");

        // and the container is no longer ambiguous on re-scan
        TickRepair.Result again = run(fx.level, TickRepair.Mode.CLEAR_CONFIRM, tmp.resolve("heal2"));
        assertEquals(0, again.totals.ambiguousEntries);
        assertEquals(0, again.totals.clearedAmbiguous);
        assertEquals(List.of(mismatch), sortedQueue(fx.block));

        // the leftover is then removable by the normal pass as usual
        TickRepair.Result confirm = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("heal3"));
        assertEquals(1, confirm.totals.removedMismatch);
        assertEquals(0, fx.block.count());
    }

    // ------------------------------------------------------------------
    // 5. Multiple dimensions in one run
    // ------------------------------------------------------------------

    @Test
    void repairsAcrossMultipleDimensions() throws Exception {
        Fixture over = new Fixture(1_000);
        Fixture nether = new Fixture(9_000);
        nether.level.fabricatedDimension = Level.NETHER;
        BlockPos p1 = inChunk(0, 64, 5), p2 = inChunk(1, 64, 5);
        over.level.states.put(p1, Blocks.STONE.defaultBlockState());
        nether.level.states.put(p2, Blocks.NETHERRACK.defaultBlockState());
        over.block.schedule(new ScheduledTick<>(Blocks.REDSTONE_ORE, p1, 1_004L, TickPriority.NORMAL, 1));
        nether.block.schedule(new ScheduledTick<>(Blocks.GRAVEL, p2, 9_004L, TickPriority.NORMAL, 1));

        TickRepair.Result r = TickRepair.runLoadedPass(List.of(over.level, nether.level),
                TickRepair.Mode.CONFIRM, tmp.resolve("dims"), null);
        assertEquals(2, r.totals.dimensions);
        assertEquals(2, r.totals.removedMismatch);
        assertEquals(0, over.block.count());
        assertEquals(0, nether.block.count());
        assertTrue(r.applied.stream().anyMatch(f -> f.dim().equals("minecraft:overworld")));
        assertTrue(r.applied.stream().anyMatch(f -> f.dim().equals("minecraft:the_nether")));
        String report = Files.readString(r.reportFile);
        assertTrue(report.contains("minecraft:overworld"));
        assertTrue(report.contains("minecraft:the_nether"));
    }

    // ------------------------------------------------------------------
    // 6. Packed (FULL-but-not-ticking) containers (audit15): genuinely
    //    deserialized via the REAL LevelChunkTicks(List<SavedTick>)
    //    constructor, BOTH lanes — classified DEFERRED, NEVER mutated;
    //    repairable after the natural unpack, where the removal frees the
    //    dedup slot so a replacement schedule at that position WORKS.
    // ------------------------------------------------------------------

    @Test
    void packedContainersDeferredThenRepairableAfterNaturalUnpack_vanilla() throws Exception {
        long wall = 5_000;
        Fixture fx = new Fixture(wall);
        BlockPos stale = inChunk(2, 64, 6), legit = inChunk(3, 64, 6), fpos = inChunk(4, 64, 6);
        fx.level.states.put(stale, Blocks.STONE.defaultBlockState());
        fx.level.states.put(legit, Blocks.STONE.defaultBlockState());
        fx.level.states.put(fpos, Blocks.WATER.defaultBlockState());
        SavedTick<Block> staleTick = new SavedTick<>(Blocks.REDSTONE_ORE, stale, 3, TickPriority.NORMAL);
        SavedTick<Block> legitTick = new SavedTick<>(Blocks.STONE, legit, (int) LONG_DELAY, TickPriority.HIGH);
        SavedTick<Fluid> staleFluid = new SavedTick<>(Fluids.LAVA, fpos, 2, TickPriority.NORMAL);
        // the REAL deserialization constructor, both lanes
        RepairChunkTicks<Block> packedB = new RepairChunkTicks<>(new ArrayList<>(List.of(staleTick, legitTick)));
        RepairChunkTicks<Fluid> packedF = new RepairChunkTicks<>(new ArrayList<>(List.of(staleFluid)));
        fx.level.blockLane.containers.put(fx.chunkKey, packedB);
        fx.level.fluidLane.containers.put(fx.chunkKey, packedF);
        byte[] before = nbtBytes(packedB.pack(wall));

        // CONFIRM on the packed state: DEFERRED, nothing mutated
        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("packed"));
        assertEquals(0, r.totals.removedMismatch, "a packed container must NEVER be mutated");
        assertEquals(0, r.totals.rescheduled);
        assertEquals(2, r.totals.deferredPackedContainers, "both lanes' packed containers deferred");
        assertEquals(3, r.totals.deferredPackedEntries);
        assertTrue(r.applied.isEmpty(), "deferral is not a mutation — applied must be empty");
        assertEquals(2, r.findings.stream().filter(f -> f.action().equals(TickRepair.ACTION_DEFERRED)).count());
        assertTrue(r.findings.stream().filter(f -> f.action().equals(TickRepair.ACTION_DEFERRED))
                        .allMatch(f -> f.reason().contains("unpacks naturally")),
                "the DEFERRED finding must state when the entries become repairable");
        assertTrue(r.totals.summary().contains("deferred-packed=3"));
        assertTrue(Files.readString(r.reportFile).contains("DEFERRED-PACKED"));
        assertEquals(List.of(staleTick, legitTick), packedB.pack(wall),
                "packed entries must be preserved EXACTLY (order, type, pos, delay, priority)");
        assertArrayEquals(before, nbtBytes(packedB.pack(wall)),
                "the packed container must survive the pass byte-identical");
        assertTrue(packedB.hasScheduledTick(stale, Blocks.REDSTONE_ORE),
                "the dedup slot must be untouched while the entry is packed");
        assertEquals(List.of(staleFluid), packedF.pack(wall));

        // repeated repair on the packed state: idempotent, still deferred
        TickRepair.Result again = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("packed2"));
        assertEquals(2, again.totals.deferredPackedContainers);
        assertEquals(List.of(staleTick, legitTick), packedB.pack(wall));

        // NATURAL unpack (startTickingChunk) — never forced by the repair
        packedB.unpack(wall);
        packedF.unpack(wall);

        // now the repair works through the public API
        TickRepair.Result r2 = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("packed3"));
        assertEquals(2, r2.totals.removedMismatch, "block + fluid stale entries removed after unpack");
        assertEquals(0, r2.totals.deferredPackedContainers, "nothing packed remains");
        assertEquals(List.of(new ScheduledTick<>(Blocks.STONE, legit, wall + LONG_DELAY, TickPriority.HIGH, -1)),
                sortedQueue(packedB), "the healthy survivor anchors with its exact relative delay");
        assertEquals(1, r2.totals.rescheduled, "the orphaned live fluid gets its recovery schedule");
        assertEquals(List.of(new Scheduled(fpos, Fluids.WATER, 1)), fx.level.scheduled);

        // THE silent-corruption pin: the dedup slot must actually be FREE
        // after the removal — a replacement schedule at the same pos+type
        // must take effect (the pre-audit15 packed mutation left the slot
        // occupied, silently blocking every future schedule there).
        assertFalse(packedB.hasScheduledTick(stale, Blocks.REDSTONE_ORE),
                "dedup slot must be freed by the post-unpack removal");
        ScheduledTick<Block> replacement =
                new ScheduledTick<>(Blocks.REDSTONE_ORE, stale, wall + 10, TickPriority.NORMAL, 99);
        packedB.schedule(replacement);
        assertTrue(packedB.getAll().anyMatch(replacement::equals),
                "a replacement schedule at the repaired position must take effect");
        assertTrue(packedB.hasScheduledTick(stale, Blocks.REDSTONE_ORE));
        // clean up the replacement (its type is stale vs the world by
        // construction) and pin idempotency of the final state
        TickRepair.Result r3 = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("packed4"));
        assertEquals(1, r3.totals.removedMismatch);
        TickRepair.Result r4 = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("packed5"));
        assertEquals(0, r4.totals.removedMismatch, "idempotent after unpack too");
        assertEquals(1, sortedQueue(packedB).size());
    }

    // ------------------------------------------------------------------
    // 6b. THE PRODUCTION ENVIRONMENT (audit15): Lithium replaces the
    //     scheduler internals of EVERY container — ticksPerPosition and
    //     tickQueue are null, its own allTicks/queues carry the state, and
    //     the public API is overwritten consistently. The repair must behave
    //     identically there. LithiumChunkTicks reproduces that state on the
    //     REAL class by reflection (fields verified null below).
    // ------------------------------------------------------------------

    @Test
    void lithiumStateDeferredThenRepairedThroughPublicApi() throws Exception {
        long wall = 6_500;
        Fixture fx = new Fixture(wall);
        BlockPos stale = inChunk(1, 64, 11), legit = inChunk(2, 64, 11), fpos = inChunk(3, 64, 11);
        fx.level.states.put(stale, Blocks.STONE.defaultBlockState());
        fx.level.states.put(legit, Blocks.STONE.defaultBlockState());
        fx.level.states.put(fpos, Blocks.WATER.defaultBlockState());
        SavedTick<Block> staleTick = new SavedTick<>(Blocks.REDSTONE_ORE, stale, 3, TickPriority.NORMAL);
        SavedTick<Block> legitTick = new SavedTick<>(Blocks.STONE, legit, (int) LONG_DELAY, TickPriority.HIGH);
        SavedTick<Fluid> staleFluid = new SavedTick<>(Fluids.LAVA, fpos, 2, TickPriority.NORMAL);
        LithiumChunkTicks<Block> lithB = new LithiumChunkTicks<>(new ArrayList<>(List.of(staleTick, legitTick)));
        LithiumChunkTicks<Fluid> lithF = new LithiumChunkTicks<>(new ArrayList<>(List.of(staleFluid)));
        // the EXACT production condition that crashed audit14:
        assertEquals(null, LithiumChunkTicks.PER_POSITION.get(lithB),
                "harness must reproduce Lithium's nulled ticksPerPosition");
        assertEquals(null, LithiumChunkTicks.TICK_QUEUE.get(lithB),
                "harness must reproduce Lithium's nulled tickQueue");
        fx.level.blockLane.containers.put(fx.chunkKey, lithB);
        fx.level.fluidLane.containers.put(fx.chunkKey, lithF);

        // packed on Lithium: DEFERRED, never mutated, NO NPE
        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("lith"));
        assertEquals(0, r.totals.removedMismatch);
        assertEquals(2, r.totals.deferredPackedContainers);
        assertEquals(3, r.totals.deferredPackedEntries);
        assertEquals(List.of(staleTick, legitTick), lithB.pack(wall),
                "packed entries preserved exactly on the Lithium container");
        assertTrue(lithB.hasScheduledTick(stale, Blocks.REDSTONE_ORE));

        // natural unpack, then repair through the (Lithium-overwritten) API
        lithB.unpack(wall);
        lithF.unpack(wall);
        TickRepair.Result r2 = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("lith2"));
        assertEquals(2, r2.totals.removedMismatch);
        assertEquals(List.of(new ScheduledTick<>(Blocks.STONE, legit, wall + LONG_DELAY, TickPriority.HIGH, -1)),
                lithB.getAll().toList(), "survivor preserved exactly on Lithium");
        assertEquals(1, r2.totals.rescheduled);
        assertEquals(List.of(new Scheduled(fpos, Fluids.WATER, 1)), fx.level.scheduled);

        // THE Lithium silent-corruption pin: audit14's pendingTicks rewrite
        // left the removed entry's hash in allTicks forever, so
        // hasScheduledTick stayed true and every replacement schedule at the
        // position was silently swallowed. The public-API removal maintains
        // allTicks, so the slot is genuinely free:
        assertFalse(lithB.hasScheduledTick(stale, Blocks.REDSTONE_ORE),
                "allTicks dedup slot must be freed by the public-API removal");
        assertFalse(lithB.allTicks.contains(new LithiumChunkTicks.Key(Blocks.REDSTONE_ORE, stale.asLong())));
        ScheduledTick<Block> replacement =
                new ScheduledTick<>(Blocks.REDSTONE_ORE, stale, wall + 10, TickPriority.NORMAL, 99);
        lithB.schedule(replacement);
        assertTrue(lithB.getAll().anyMatch(replacement::equals),
                "a replacement schedule at the repaired position must take effect on Lithium");

        // idempotency on Lithium (replacement removed again — stale by
        // construction — then steady state)
        TickRepair.Result r3 = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("lith3"));
        assertEquals(1, r3.totals.removedMismatch);
        TickRepair.Result r4 = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("lith4"));
        assertEquals(0, r4.totals.removedMismatch);
        assertEquals(1, lithB.getAll().count());
    }

    /** DOMAIN-SYNC through {@code TickDomains.rebaseTo}/{@code shiftAll} on
     *  the Lithium container shape: the transition uses only
     *  getAll/removeIf/schedule, which Lithium overwrites consistently — the
     *  shift must be exact there too (this is the audit15 verification that
     *  shiftAll was already Lithium-safe). */
    @Test
    void domainSyncExactOnLithiumContainers() throws Exception {
        long wall = 1_000, islandL = 50_000;
        Fixture fx = new Fixture(wall);
        seedFinalClock(fx.level, 101, islandL);
        LithiumChunkTicks<Block> lith = new LithiumChunkTicks<>();
        lith.fabrolia$setDomain(101, 3);
        fx.level.blockLane.containers.put(fx.chunkKey, lith);
        BlockPos a = inChunk(0, 64, 12), b = inChunk(2, 64, 12);
        fx.level.states.put(a, Blocks.STONE.defaultBlockState());
        fx.level.states.put(b, Blocks.STONE.defaultBlockState());
        lith.schedule(new ScheduledTick<>(Blocks.STONE, a, islandL + 5, TickPriority.NORMAL, 7));
        lith.schedule(new ScheduledTick<>(Blocks.STONE, b, islandL + LONG_DELAY, TickPriority.VERY_HIGH, 8));

        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("lith-sync"));
        assertEquals(2, r.totals.domainSyncEntries);
        assertEquals(0, r.totals.removedMismatch);
        List<ScheduledTick<Block>> q = lith.getAll()
                .sorted(Comparator.<ScheduledTick<Block>>comparingLong(ScheduledTick::triggerTick)
                        .thenComparingLong(ScheduledTick::subTickOrder)).toList();
        assertEquals(new ScheduledTick<>(Blocks.STONE, a, wall + 5, TickPriority.NORMAL, 7), q.get(0));
        assertEquals(new ScheduledTick<>(Blocks.STONE, b, wall + LONG_DELAY, TickPriority.VERY_HIGH, 8), q.get(1));
        assertEquals(WALL, lith.fabrolia$domainIsland());
        assertTrue(lith.hasScheduledTick(a, Blocks.STONE), "dedup consistent after the drain+reschedule shift");
    }

    // ------------------------------------------------------------------
    // 6c. Per-chunk transaction (audit15): an injected failure mid-run rolls
    //     ITS chunk back exactly and the run CONTINUES; committed chunks are
    //     recorded incrementally so nothing is lost or unreported.
    // ------------------------------------------------------------------

    /** Injects one failure into the repair's own removal call AFTER the
     *  removal has already happened — a genuine PARTIAL MUTATION mid-chunk —
     *  then behaves normally, so the rollback path (which drains and
     *  re-schedules via the same API) works, exactly like a transient
     *  container fault. */
    static final class FailOnceChunkTicks<T> extends RepairChunkTicks<T> {
        int removeIfCalls;

        @Override public void removeIf(java.util.function.Predicate<ScheduledTick<T>> pred) {
            super.removeIf(pred);   // the mutation COMMITS to the container first
            if (++removeIfCalls == 1) {
                throw new IllegalStateException("injected post-mutation removeIf failure (test)");
            }
        }
    }

    @Test
    void injectedMidRunFailureRollsBackItsChunkAndRunContinues() throws Exception {
        long wall = 7_700;
        Fixture fx = new Fixture(wall);
        // three chunks, each with one stale entry; chunk [2,2] fails on its
        // first removal attempt
        long key1 = fx.chunkKey;                       // [1,1] healthy RepairChunkTicks
        long key2 = ChunkPos.pack(2, 2);
        long key3 = ChunkPos.pack(3, 3);
        FailOnceChunkTicks<Block> failing = new FailOnceChunkTicks<>();
        RepairChunkTicks<Block> third = new RepairChunkTicks<>();
        fx.level.blockLane.containers.put(key2, failing);
        fx.level.blockLane.containers.put(key3, third);
        BlockPos p1 = new BlockPos(16 + 1, 64, 16 + 1);
        BlockPos p2 = new BlockPos(32 + 1, 64, 32 + 1);
        BlockPos p3 = new BlockPos(48 + 1, 64, 48 + 1);
        fx.level.states.put(p1, Blocks.STONE.defaultBlockState());
        fx.level.states.put(p2, Blocks.STONE.defaultBlockState());
        fx.level.states.put(p3, Blocks.STONE.defaultBlockState());
        ScheduledTick<Block> stale2 = new ScheduledTick<>(Blocks.REDSTONE_ORE, p2, wall + 4, TickPriority.HIGH, 21);
        fx.block.schedule(new ScheduledTick<>(Blocks.REDSTONE_ORE, p1, wall + 4, TickPriority.NORMAL, 20));
        failing.schedule(stale2);
        third.schedule(new ScheduledTick<>(Blocks.REDSTONE_ORE, p3, wall + 4, TickPriority.NORMAL, 22));
        // simulate a live next-tick index for the failing chunk: rollback must
        // restore it
        fx.level.blockLane.next.put(key2, wall + 4);
        failing.fabrolia$setDomain(WALL, -1);

        TickRepair.Result r = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("tx"));

        // the two healthy chunks committed — the failure did NOT abort the run
        assertEquals(2, r.totals.removedMismatch,
                "chunks [1,1] and [3,3] must commit despite the [2,2] failure");
        assertEquals(0, fx.block.count());
        assertEquals(0, third.count());
        // the failing chunk rolled back EXACTLY
        assertEquals(List.of(stale2), failing.getAll().toList(),
                "the failed chunk must be restored to its exact pre-mutation state");
        assertTrue(failing.hasScheduledTick(p2, Blocks.REDSTONE_ORE),
                "the dedup probe must be restored with the entry");
        assertEquals(wall + 4, fx.level.blockLane.next.get(key2),
                "the lane's next-tick index must be restored");
        assertEquals(WALL, failing.fabrolia$domainIsland());
        // ... and is reported as FAILED-ROLLED-BACK with verified-exact restore
        String rollbackNote = r.notes.stream()
                .filter(n -> n.contains("FAILED-ROLLED-BACK")).findFirst().orElseThrow();
        assertTrue(rollbackNote.contains("chunk[2,2]"));
        assertTrue(rollbackNote.contains("injected post-mutation removeIf failure"));
        assertTrue(rollbackNote.contains("no mutation from this chunk was committed"));
        assertTrue(rollbackNote.contains("restored and verified exact"),
                "the restore must be verified exact, not assumed");
        // the report carries the committed records AND the rollback record
        String report = Files.readString(r.reportFile);
        assertTrue(report.contains("chunk[1,1]") && report.contains("chunk[3,3]"),
                "committed chunks must be identified in the report");
        assertTrue(report.contains("FAILED-ROLLED-BACK") && report.contains("chunk[2,2]"));
        assertEquals(2, r.applied.stream().filter(f -> f.action().equals(TickRepair.ACTION_REMOVE)).count());

        // a re-run repairs the (now healthy) failing chunk — transient fault
        TickRepair.Result r2 = run(fx.level, TickRepair.Mode.CONFIRM, tmp.resolve("tx2"));
        assertEquals(1, r2.totals.removedMismatch);
        assertEquals(0, failing.count());
    }

    // ------------------------------------------------------------------
    // 6d. Crash-safe record ordering (audit15): the report file exists —
    //     with the full classification — and the backup hook has fired
    //     BEFORE the first mutation; each chunk's applied record is on disk
    //     before the next chunk is touched.
    // ------------------------------------------------------------------

    @Test
    void reportAndBackupPrecedeFirstMutationAndAppliedRecordIsIncremental() throws Exception {
        long wall = 8_800;
        Fixture fx = new Fixture(wall);
        long key2 = ChunkPos.pack(2, 2);
        RepairChunkTicks<Block> second = new RepairChunkTicks<>();
        fx.level.blockLane.containers.put(key2, second);
        BlockPos p1 = new BlockPos(16 + 2, 64, 16 + 2);
        BlockPos p2 = new BlockPos(32 + 2, 64, 32 + 2);
        fx.level.states.put(p1, Blocks.STONE.defaultBlockState());
        fx.level.states.put(p2, Blocks.STONE.defaultBlockState());
        fx.block.schedule(new ScheduledTick<>(Blocks.REDSTONE_ORE, p1, wall + 4, TickPriority.NORMAL, 1));
        second.schedule(new ScheduledTick<>(Blocks.REDSTONE_ORE, p2, wall + 4, TickPriority.NORMAL, 2));

        Path dir = tmp.resolve("order");
        List<String> hookObservations = new ArrayList<>();
        TickRepair.Result r = TickRepair.runLoadedPass(List.of(fx.level), TickRepair.Mode.CONFIRM,
                dir, (level, chunkKey) -> {
                    try {
                        // the report must already exist ON DISK, classification
                        // included, before the first mutation (the hook fires
                        // pre-mutation per chunk)
                        Path reportFile;
                        try (var s = Files.list(dir)) {
                            reportFile = s.filter(p -> p.getFileName().toString().startsWith("tick-repair-"))
                                    .findFirst().orElseThrow();
                        }
                        String content = Files.readString(reportFile);
                        long appliedRemovals = content.lines()
                                .filter(l -> l.contains("action=" + TickRepair.ACTION_REMOVE)
                                        && !l.contains("== "))
                                .count() - 2;   // 2 classification lines always present
                        // both containers' classification lines present
                        hookObservations.add("classified=" + content.contains("== classification")
                                + " chunk=" + ChunkPos.getX(chunkKey) + "," + ChunkPos.getZ(chunkKey)
                                + " appliedRemovalsOnDiskBeforeThisChunk=" + appliedRemovals
                                + " thisChunkStillUnmutated="
                                + (((LevelChunkTicks<?>) fx.level.blockLane.containers.get(chunkKey)).count() == 1));
                    } catch (Exception e) {
                        hookObservations.add("hook error: " + e);
                    }
                });

        assertEquals(2, r.totals.removedMismatch);
        assertEquals(2, hookObservations.size(), "the pre-mutation hook fires once per chunk");
        assertTrue(hookObservations.get(0).contains("classified=true"),
                "the classification report must be on disk before the FIRST mutation: " + hookObservations);
        assertTrue(hookObservations.get(0).contains("appliedRemovalsOnDiskBeforeThisChunk=0"),
                "no applied record may exist before the first chunk: " + hookObservations);
        assertTrue(hookObservations.get(0).contains("thisChunkStillUnmutated=true"),
                "the hook must fire BEFORE its chunk is mutated: " + hookObservations);
        assertTrue(hookObservations.get(1).contains("appliedRemovalsOnDiskBeforeThisChunk=1"),
                "the FIRST chunk's applied record must be on disk before the SECOND chunk is "
                        + "touched (incremental, crash-safe): " + hookObservations);
        assertTrue(hookObservations.get(1).contains("thisChunkStillUnmutated=true"));
    }

    // ------------------------------------------------------------------
    // 7. Save / reload / simulated restart round trip
    // ------------------------------------------------------------------

    @Test
    void saveReloadRestartPreservesLegitimateDelaysByteIdentical() throws Exception {
        long wall = 1_000;
        Fixture fx = new Fixture(wall);
        BlockPos a = inChunk(4, 64, 7), b = inChunk(5, 64, 7);
        fx.level.states.put(a, Blocks.STONE.defaultBlockState());
        fx.level.states.put(b, Blocks.STONE.defaultBlockState());
        fx.block.schedule(new ScheduledTick<>(Blocks.STONE, a, wall + LONG_DELAY, TickPriority.NORMAL, 1));
        fx.block.schedule(new ScheduledTick<>(Blocks.STONE, b, wall + 5, TickPriority.NORMAL, 2));

        // save: vanilla pack against the WALL basis; then serialize through
        // vanilla's own codec (the exact on-disk representation)
        List<SavedTick<Block>> saved = fx.block.pack(wall);
        assertEquals((int) LONG_DELAY, saved.stream().filter(t -> t.pos().equals(a)).findFirst().orElseThrow().delay());
        byte[] savedBytes = nbtBytes(saved);

        // restart: decode the bytes back, rebuild the container, re-anchor at
        // a much later wall clock (the server ran on without this chunk)
        ListTag list = (ListTag) TickRepairNbt.BLOCK_TICK_CODEC.listOf()
                .encodeStart(NbtOps.INSTANCE, saved).getOrThrow();
        List<SavedTick<Block>> reloaded = TickRepairNbt.BLOCK_TICK_CODEC.listOf()
                .parse(NbtOps.INSTANCE, list).getOrThrow();
        long newWall = 8_000_000;
        Fixture restarted = new Fixture(newWall);
        restarted.level.states.put(a, Blocks.STONE.defaultBlockState());
        restarted.level.states.put(b, Blocks.STONE.defaultBlockState());
        RepairChunkTicks<Block> c2 = new RepairChunkTicks<>(reloaded);
        restarted.level.blockLane.containers.put(restarted.chunkKey, c2);
        c2.unpack(newWall);

        // the repair finds NOTHING to do on the restarted world
        TickRepair.Result r = run(restarted.level, TickRepair.Mode.CONFIRM, tmp.resolve("restart"));
        assertEquals(0, r.totals.domainSyncEntries);
        assertEquals(0, r.totals.removedMismatch);
        assertEquals(0, r.totals.ambiguousEntries);
        assertEquals(2, r.totals.healthyEntries);
        assertEquals(newWall + LONG_DELAY,
                sortedQueue(c2).get(1).triggerTick(), "relative delay must re-anchor exactly");

        // and the next save is byte-identical to the previous one
        assertArrayEquals(savedBytes, nbtBytes(c2.pack(newWall)),
                "a second save after reload+repair must be byte-identical");
    }

    // ------------------------------------------------------------------
    // 8. Dry run never mutates, even on a maximally broken world
    // ------------------------------------------------------------------

    @Test
    void dryRunNeverMutatesAnything() throws Exception {
        long wall = 6_000;
        Fixture fx = new Fixture(wall);
        seedFinalClock(fx.level, 55, 900_000);
        fx.block.fabrolia$setDomain(55, 1);                 // proven offset case
        fx.fluid.fabrolia$setDomain(888, 1);                // unresolvable = ambiguous
        BlockPos p1 = inChunk(6, 64, 8), p2 = inChunk(7, 64, 8);
        fx.level.states.put(p1, Blocks.STONE.defaultBlockState());
        fx.level.states.put(p2, Blocks.WATER.defaultBlockState());   // type matches -> AMBIGUOUS, not mismatch
        fx.block.schedule(new ScheduledTick<>(Blocks.REDSTONE_ORE, p1, 900_004L, TickPriority.NORMAL, 1));
        fx.block.schedule(new ScheduledTick<>(Blocks.STONE, p1, 900_005L, TickPriority.NORMAL, 2));
        fx.fluid.schedule(new ScheduledTick<>(Fluids.WATER, p2, 42L, TickPriority.NORMAL, 3));

        List<ScheduledTick<Block>> blockBefore = sortedQueue(fx.block);
        List<ScheduledTick<Fluid>> fluidBefore = fx.fluid.getAll().toList();
        long unresolvedBefore = TickDomains.UNRESOLVED_TAGS.get();

        TickRepair.Result r = run(fx.level, TickRepair.Mode.DRY, tmp.resolve("dry"));
        assertFalse(r.findings.isEmpty(), "the dry run must report what WOULD change");
        assertEquals(unresolvedBefore, TickDomains.UNRESOLVED_TAGS.get(),
                "a pure dry run must not inflate the operational UNRESOLVED_TAGS bug counter "
                        + "(non-counting probe, review finding #3a)");
        assertTrue(r.applied.isEmpty());
        assertEquals(blockBefore, sortedQueue(fx.block), "dry run must not mutate the block lane");
        assertEquals(fluidBefore, fx.fluid.getAll().toList(), "dry run must not mutate the fluid lane");
        assertEquals(55, fx.block.fabrolia$domainIsland());
        assertEquals(888, fx.fluid.fabrolia$domainIsland());
        assertTrue(fx.level.scheduled.isEmpty(), "dry run must not schedule anything");
        assertNotNull(r.reportFile);
        assertTrue(Files.readString(r.reportFile).contains("AMBIGUOUS"));
    }

    // ------------------------------------------------------------------
    // 9. Unloaded chunks: the NBT-side classifier (read-only scan)
    // ------------------------------------------------------------------

    private static CompoundTag tickNbt(String id, BlockPos pos, long delay, int priority) {
        CompoundTag t = new CompoundTag();
        t.putString("i", id);
        t.putInt("x", pos.getX());
        t.putInt("y", pos.getY());
        t.putInt("z", pos.getZ());
        t.putInt("t", (int) delay);
        t.putInt("p", priority);
        return t;
    }

    /** Hand-built saved chunk at [3,5]: one stone section at Y=4, everything
     *  else air — decoded by the PRODUCTION classifier through vanilla's own
     *  codecs. */
    private static CompoundTag chunkNbt(String status) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("xPos", 3);
        chunk.putInt("zPos", 5);
        chunk.putInt("yPos", -4);
        chunk.putString("Status", status);
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 4);
        CompoundTag blockStates = new CompoundTag();
        ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        blockStates.put("palette", palette);
        section.put("block_states", blockStates);
        sections.add(section);
        chunk.put("sections", sections);
        return chunk;
    }

    @Test
    void unloadedNbtScanFlagsOnlyProvenTypeMismatches() {
        CompoundTag chunk = chunkNbt("minecraft:full");
        BlockPos inStone = new BlockPos(3 * 16, 64, 5 * 16);        // stone section
        BlockPos inStone2 = new BlockPos(3 * 16 + 1, 65, 5 * 16);
        BlockPos inAir = new BlockPos(3 * 16, 200, 5 * 16);         // absent section = air
        ListTag blockTicks = new ListTag();
        blockTicks.add(tickNbt("minecraft:stone", inStone, LONG_DELAY, 0));       // legit LONG delay
        blockTicks.add(tickNbt("minecraft:redstone_ore", inStone2, 5, 0));        // stale
        blockTicks.add(tickNbt("minecraft:stone", inAir, 7, 0));                  // stale (air now)
        blockTicks.add(tickNbt("removedmod:machine", inStone, 3, 0));             // undecodable -> SKIP
        chunk.put("block_ticks", blockTicks);
        ListTag fluidTicks = new ListTag();
        fluidTicks.add(tickNbt("minecraft:water", inStone, NEAR_INT_BOUND_DELAY, 0)); // stale (stone holds no fluid)
        chunk.put("fluid_ticks", fluidTicks);

        TickRepairNbt.NbtChunkResult res = TickRepairNbt.classifyChunkNbt(chunk);
        assertTrue(res.fullStatus());
        assertEquals(3, res.cx());
        assertEquals(5, res.cz());
        assertEquals(5, res.entries());
        assertEquals(1, res.skippedEntries(), "undecodable entries are skipped, never classified");
        assertEquals(3, res.mismatches().size());
        assertTrue(res.mismatches().stream().noneMatch(f -> f.pos().equals(inStone) && f.lane().equals("block")),
                "the legitimate long-delay stone tick must NOT be flagged");
        assertTrue(res.mismatches().stream().anyMatch(f -> f.pos().equals(inStone2)));
        assertTrue(res.mismatches().stream().anyMatch(f -> f.pos().equals(inAir)));
        assertTrue(res.mismatches().stream().anyMatch(f -> f.lane().equals("fluid") && f.pos().equals(inStone)));
    }

    @Test
    void unloadedNbtScanNeverTouchesBelowFullChunks() {
        CompoundTag chunk = chunkNbt("minecraft:features");
        ListTag blockTicks = new ListTag();
        blockTicks.add(tickNbt("minecraft:redstone_ore", new BlockPos(48, 64, 80), 5, 0));
        chunk.put("block_ticks", blockTicks);
        TickRepairNbt.NbtChunkResult res = TickRepairNbt.classifyChunkNbt(chunk);
        assertFalse(res.fullStatus());
        assertFalse(res.actionable(), "below-full chunks are never repair candidates");
        assertTrue(res.mismatches().isEmpty(), "below-full chunks are not even classified");
    }
}
