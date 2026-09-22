package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.world.BrushableBlockEntityClockMixin;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real brush/reset bodies and owner lookup; the world surface and tick delivery are substituted. */
@Isolated
class BrushClockDomainTest {
    private static final BlockPos POS = new BlockPos(10, -60, 13);

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void mainBrushesSurviveOwnerResetsAndCompleteOnTheTenthStroke() throws Exception {
        try (Fixture f = new Fixture(1000, 1988)) {
            for (int stroke = 1; stroke <= 10; stroke++) {
                assertEquals(stroke == 10, f.brush(), "completion must require ten accepted strokes");
                if (stroke != 10) {
                    int progress = f.dusted();
                    f.advance(10);
                    assertEquals(progress, f.dusted(), "scheduled owner ticks must not retract an active brush");
                }
            }
            assertTrue(f.level.state.is(Blocks.SAND));
        }
    }

    @Test
    void rejectedStrokeRefreshesInactivityWithoutAdvancingProgress() throws Exception {
        try (Fixture f = new Fixture(3000, 1000)) {
            assertFalse(f.brush());
            f.advance(9);
            assertFalse(f.brush());
            f.advance(39);
            assertEquals(1, f.dusted(), "even a cooldown-rejected stroke refreshes the inactivity deadline");
            f.advance(1);
            assertEquals(0, f.dusted(), "the vanilla forty-tick inactivity deadline must still retract");
            for (int stroke = 1; stroke <= 10; stroke++) {
                assertEquals(stroke == 10, f.brush());
                if (stroke != 10) f.advance(10);
            }
            assertTrue(f.level.state.is(Blocks.SAND));
        }
    }

    @Test
    void speculativeRetreatKeepsItsCooldownButAnOlderOwnerCannotStallBrushing() throws Exception {
        try (Fixture f = new Fixture(1000, 2000)) {
            f.owner.domain.unitGameTime = 2001;
            assertFalse(f.brush());
            f.owner.domain.unitGameTime = 0;
            assertFalse(f.brush(), "a one-tick HOLD retreat does not make a fresh cooldown corrupt");
            f.changeOwner(500);
            // One original accepted stroke survives. The HOLD attempt above must
            // not advance it, and the old owner's future cooldown must not stall it.
            for (int stroke = 2; stroke <= 10; stroke++) {
                assertEquals(stroke == 10, f.brush(), "the new owner must resume the existing progress");
                if (stroke != 10) f.advance(10);
            }
            assertTrue(f.level.state.is(Blocks.SAND));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TestLevel level;
        final RegionizedServer runtime;
        final Region region;
        IslandRegistry.PIsland owner;
        final BrushableBlockEntity subject;
        final BrushableBlockEntityClockMixin adapter;
        // No loot table here, so brush/reset never consult the tool. Real loot
        // delivery and brush durability are exercised on the Fabric server.
        final ItemStack tool = ItemStack.EMPTY;
        final Method clockAdapter;
        final Field vanillaCooldown = field(BrushableBlockEntity.class, "coolDownEndsAtTick");
        final Field adapterCooldown = field(BrushableBlockEntityClockMixin.class, "coolDownEndsAtTick");
        final Map<ServerLevel, RegionizedServer> levels;
        final boolean previousActive;

        @SuppressWarnings("unchecked")
        Fixture(long wall, long logical) throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            level.wall = wall;
            level.rawData = new WallData(level);
            level.state = Blocks.SUSPICIOUS_SAND.defaultBlockState();
            subject = new BrushableBlockEntity(POS, level.state);
            subject.setLevel(level);
            level.subject = subject;
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            owner = new IslandRegistry.PIsland(1, logical);
            owner.level = level;
            Long2ObjectOpenHashMap<EpochDomain> domains = new Long2ObjectOpenHashMap<>();
            long key = runtime.regionizer().grid.keyFor(POS.getX() >> 4, POS.getZ() >> 4);
            domains.put(key, owner.domain);
            field(RegionizedServer.class, "cellDomainSnapshot").set(runtime, domains);
            region = runtime.regionizer().regionForGridKey(key);
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            previousActive = field(RegionScheduler.class, "active").getBoolean(null);
            levels.put(level, runtime);
            field(RegionScheduler.class, "active").setBoolean(null, true);
            adapter = new BrushableBlockEntityClockMixin(subject.getType(), POS, level.state) {};
            adapter.setLevel(level);
            clockAdapter = BrushableBlockEntityClockMixin.class.getDeclaredMethod("fabrolia$ownerBrushTime", long.class);
            clockAdapter.setAccessible(true);
        }

        boolean brush() throws Exception {
            level.now = level.wall;
            // The JUnit classloader does not weave mixins. Execute the real
            // primitive adapter on the vanilla cooldown, then the vanilla body.
            adapterCooldown.setLong(adapter, vanillaCooldown.getLong(subject));
            long now;
            try {
                now = (long) clockAdapter.invoke(adapter, level.wall);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw new AssertionError(failure.getCause());
            }
            vanillaCooldown.setLong(subject, adapterCooldown.getLong(adapter));
            return subject.brush(now, level, null, Direction.NORTH, tool);
        }

        void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                level.wall++;
                owner.logicalTick++;
                owner.domain.unitGameTime = owner.logicalTick;
                level.now = owner.logicalTick;
                // More frequent checks are also legal when a block update queues
                // a second scheduled check; vanilla alone decides whether it is due.
                subject.checkReset(level);
            }
        }

        void changeOwner(long logical) {
            owner = new IslandRegistry.PIsland(owner.id + 1, logical);
            owner.level = level;
            region.domain = owner.domain;
        }

        int dusted() { return level.state.getValue(BlockStateProperties.DUSTED); }

        @Override
        public void close() throws Exception {
            levels.remove(level);
            field(RegionScheduler.class, "active").setBoolean(null, previousActive);
        }
    }

    private static final class TestLevel extends ServerLevel {
        long wall;
        long now;
        LevelData rawData;
        BlockState state;
        BrushableBlockEntity subject;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public long getGameTime() { return now; }
        @Override public LevelData getLevelData() { return rawData; }
        @Override public MinecraftServer getServer() { return null; }
        @Override public net.minecraft.resources.ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public BlockState getBlockState(BlockPos pos) { return state; }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            this.state = state;
            if (subject.isValidBlockState(state)) subject.setBlockState(state);
            else subject.setRemoved();
            return true;
        }
        @Override public void scheduleTick(BlockPos pos, Block block, int delay) {}
        @Override public void levelEvent(Entity source, int event, BlockPos pos, int data) {}
    }

    private record WallData(TestLevel level) implements LevelData, RawGameTime {
        @Override public long fabrolia$rawGameTime() { return level.wall; }
        @Override public long getGameTime() { return level.wall; }
        @Override public RespawnData getRespawnData() { throw new UnsupportedOperationException(); }
        @Override public boolean isHardcore() { return false; }
        @Override public Difficulty getDifficulty() { return Difficulty.NORMAL; }
        @Override public boolean isDifficultyLocked() { return false; }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
