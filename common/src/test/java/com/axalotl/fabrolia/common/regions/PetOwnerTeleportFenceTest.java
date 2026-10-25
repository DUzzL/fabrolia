package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pet whose owner stands on another island must leave the worker lane:
 * vanilla's TamableAnimal#tryToTeleportToOwner reads the landing blocks, snaps
 * the pet there and finishes its tick in that domain.
 */
final class PetOwnerTeleportFenceTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void farOwnerOnAnotherIslandRequiresTheGlobalFence() throws Exception {
        Fixture f = new Fixture();
        Pig foreignOwner = f.at(Pig.class, new Vec3(1032.5, 64, 8.5));   // chunk (64,0): other
        Pig gapOwner = f.at(Pig.class, new Vec3(2056.5, 64, 8.5));       // chunk (128,0): no owner
        Pig nearOwner = f.at(Pig.class, new Vec3(12.5, 64, 8.5));        // chunk (0,0): home, 4 blocks away
        Pig farHomeOwner = f.at(Pig.class, new Vec3(8.5, 64, 40.5));     // chunk (0,2): home, 32 blocks away

        assertTrue(ParallelProcessor.teleportsToOwnerOutsideIsland(f.pet(foreignOwner), f.runtime, f.home));
        assertFalse(ParallelProcessor.teleportsToOwnerOutsideIsland(f.pet(foreignOwner), f.runtime, f.other),
                "the landing area is local when its owner island ticks the pet");
        assertTrue(ParallelProcessor.teleportsToOwnerOutsideIsland(f.pet(gapOwner), f.runtime, f.home),
                "an unowned (global-domain) landing area is foreign to every island");
        assertFalse(ParallelProcessor.teleportsToOwnerOutsideIsland(f.pet(nearOwner), f.runtime, f.home),
                "closer than 12 blocks vanilla walks instead of teleporting");
        assertFalse(ParallelProcessor.teleportsToOwnerOutsideIsland(f.pet(farHomeOwner), f.runtime, f.home),
                "a teleport inside the ticking island stays on the worker lane");
        assertFalse(ParallelProcessor.teleportsToOwnerOutsideIsland(f.pet(null), f.runtime, f.home),
                "no resolved owner: nothing to follow");
        assertFalse(ParallelProcessor.teleportsToOwnerOutsideIsland(f.pet(foreignOwner), null, f.home),
                "no runtime: nothing to fence");
        assertFalse(ParallelProcessor.teleportsToOwnerOutsideIsland(foreignOwner, f.runtime, f.home),
                "only tamable animals teleport to an owner");
    }

    @Test
    void landingSquareCrossingAChunkEdgeChecksTheNeighbour() throws Exception {
        Fixture f = new Fixture();
        // Owner chunk (64,0) belongs to other; 4 blocks west is unowned chunk (63,0).
        Pig edge = f.at(Pig.class, new Vec3(1025.5, 64, 8.5));
        assertTrue(ParallelProcessor.teleportsToOwnerOutsideIsland(f.pet(edge), f.runtime, f.other),
                "the +-3 landing attempts may leave the owner's chunk");
    }

    @Test
    void unableToMoveToOwnerKeepsTheWorkerLane() throws Exception {
        Fixture f = new Fixture();
        Pet sitting = f.pet(f.at(Pig.class, new Vec3(1032.5, 64, 8.5)));
        field(TamableAnimal.class, "orderedToSit").set(sitting, true);
        assertFalse(ParallelProcessor.teleportsToOwnerOutsideIsland(sitting, f.runtime, f.home));
    }

    @Test
    void entityTickRoutesPetTeleportBeforeTheWorkerBody() throws Exception {
        String text = source("common/regions/RegionizedServer.java");
        int sleeper = text.indexOf("ParallelProcessor.sleepsOutsideIsland(e, this, region.domain.owner)");
        int pet = text.indexOf("ParallelProcessor.teleportsToOwnerOutsideIsland(e, this, region.domain.owner)", sleeper);
        int fenced = text.indexOf("RegionScheduler.queueMainSyncFencedTick(e,", pet);
        int plainMain = text.indexOf("ParallelProcessor.requiresMainThread(e)", pet);
        int workerBody = text.indexOf("ParallelProcessor.tickEntity(lvl, e, true)", pet);
        assertTrue(sleeper >= 0 && pet > sleeper && fenced > pet
                        && plainMain > fenced && workerBody > plainMain,
                "a cross-island pet teleport must take the globally fenced MAIN_SYNC lane before the worker tick");
    }

    @Test
    void fencedBodyReindexesItsTreeBeforeTheFenceCloses() throws Exception {
        String scheduler = source("common/regions/RegionScheduler.java");
        int label = scheduler.indexOf("\"globally fenced MAIN_SYNC entity tick\"");
        int body = scheduler.indexOf("runMainSyncEntityTask(queued);", label);
        int reindex = scheduler.indexOf("reindexMovedTree(queued.entity());", body);
        int close = scheduler.indexOf("});", body);
        assertTrue(label >= 0 && body > label && reindex > body && close > reindex,
                "the moved tree is re-keyed inside the fenced mutation, after its body");

        String server = source("common/regions/RegionizedServer.java");
        int method = server.indexOf("void reindexMovedTree(Entity e)");
        int move = server.indexOf("indexMove(e, from, current)", method);
        int passengers = server.indexOf("reindexMovedTree(passenger)", method);
        int end = server.indexOf("private void indexMove(", method);
        assertTrue(method >= 0 && move > method && passengers > move && end > passengers,
                "the re-key uses indexMove (tick-mark carry) for the whole tree");
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

        /** A pet at block (8, 64, 8), chunk (0,0). */
        Pet pet(LivingEntity owner) throws Exception {
            Pet pet = at(Pet.class, new Vec3(8.5, 64, 8.5));
            pet.owner = owner;
            return pet;
        }

        <T extends Entity> T at(Class<T> type, Vec3 position) throws Exception {
            @SuppressWarnings("unchecked")
            T entity = (T) unsafe().allocateInstance(type);
            field(Entity.class, "passengers").set(entity, ImmutableList.of());
            field(Entity.class, "position").set(entity, position);
            field(Entity.class, "blockPosition").set(entity, BlockPos.containing(position));
            return entity;
        }
    }

    private static final class Pet extends Wolf {
        LivingEntity owner;
        private Pet() { super(null, null); }
        @Override public LivingEntity getOwner() { return owner; }
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

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        throw new AssertionError("source tree not available");
    }
}
