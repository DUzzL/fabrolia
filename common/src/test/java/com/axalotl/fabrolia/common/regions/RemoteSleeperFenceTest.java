package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sleeper whose remembered bed lies in another island's domain must leave the
 * worker lane: vanilla's tick reads and writes that remote bed block.
 */
final class RemoteSleeperFenceTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sleeperInForeignBedChunkRequiresTheGlobalFence() throws Exception {
        Fixture f = new Fixture();
        BlockPos homeBed = new BlockPos(5, 64, 5);      // chunk (0,0): owned by home
        BlockPos foreignBed = new BlockPos(1029, 64, 5); // chunk (64,0): owned by other
        BlockPos gapBed = new BlockPos(2053, 64, 5);     // chunk (128,0): no owner

        Sleeper awake = f.sleeper(null);
        assertFalse(ParallelProcessor.sleepsOutsideIsland(awake, f.runtime, f.home));

        Sleeper local = f.sleeper(homeBed);
        assertFalse(ParallelProcessor.sleepsOutsideIsland(local, f.runtime, f.home),
                "a bed inside the ticking island stays on the worker lane");

        Sleeper remote = f.sleeper(foreignBed);
        assertTrue(ParallelProcessor.sleepsOutsideIsland(remote, f.runtime, f.home));
        assertFalse(ParallelProcessor.sleepsOutsideIsland(remote, f.runtime, f.other),
                "the same bed is local when the bed's owner ticks the sleeper");

        Sleeper gap = f.sleeper(gapBed);
        assertTrue(ParallelProcessor.sleepsOutsideIsland(gap, f.runtime, f.home),
                "an unowned (global-domain) bed chunk is foreign to every island");
        assertFalse(ParallelProcessor.sleepsOutsideIsland(gap, f.runtime, null),
                "a global-domain unit owns global-domain beds");

        assertFalse(ParallelProcessor.sleepsOutsideIsland(remote, null, f.home),
                "no runtime: nothing to fence");
    }

    @Test
    void remoteSleepingPassengerFencesTheWholeTree() throws Exception {
        Fixture f = new Fixture();
        Sleeper root = f.sleeper(null);
        Sleeper middle = f.sleeper(null);
        Sleeper remote = f.sleeper(new BlockPos(1029, 64, 5));
        passengers(root, middle);
        passengers(middle, remote);
        assertTrue(ParallelProcessor.sleepsOutsideIsland(root, f.runtime, f.home));
        passengers(middle);
        assertFalse(ParallelProcessor.sleepsOutsideIsland(root, f.runtime, f.home));
    }

    private static final class Fixture {
        final RegionizedServer runtime;
        final ThreadedRegionizer regionizer;
        final IslandRegistry.PIsland home;
        final IslandRegistry.PIsland other;

        Fixture() throws Exception {
            ServerLevel level = (ServerLevel) unsafe().allocateInstance(ServerLevel.class);
            runtime = (RegionizedServer) unsafe().allocateInstance(RegionizedServer.class);
            regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
            regionizer.owner = runtime;
            set(RegionizedServer.class, runtime, "level", level);
            set(RegionizedServer.class, runtime, "regionizer", regionizer);
            runtime.asyncActive = true;
            home = island(1L, level);
            other = island(2L, level);
            Long2ObjectOpenHashMap<EpochDomain> cells = new Long2ObjectOpenHashMap<>();
            cells.put(regionizer.grid.keyFor(new ChunkPos(0, 0).pack()), home.domain);
            cells.put(regionizer.grid.keyFor(new ChunkPos(64, 0).pack()), other.domain);
            set(RegionizedServer.class, runtime, "cellDomainSnapshot", cells);
        }

        private static IslandRegistry.PIsland island(long id, ServerLevel level) {
            IslandRegistry.PIsland island = new IslandRegistry.PIsland(id, 100L);
            island.level = level;
            return island;
        }

        Sleeper sleeper(BlockPos bed) throws Exception {
            Sleeper sleeper = (Sleeper) unsafe().allocateInstance(Sleeper.class);
            sleeper.bed = Optional.ofNullable(bed);
            passengers(sleeper);
            return sleeper;
        }
    }

    private static final class Sleeper extends Pig {
        Optional<BlockPos> bed;
        private Sleeper() { super(null, null); }
        @Override public Optional<BlockPos> getSleepingPos() { return bed; }
    }

    private static void passengers(Entity root, Entity... passengers) throws Exception {
        field(Entity.class, "passengers").set(root, ImmutableList.copyOf(passengers));
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        field(owner, name).set(target, value);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        return (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
    }
}
