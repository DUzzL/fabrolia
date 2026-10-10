package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/** The real 26.3 spawn search must respect existing chunk levels; raids add no tickets. */
class RaidSpawnAdmissionParityTest {
    private static final BlockPos CENTER = new BlockPos(15, 80, 15);

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void earlyWaveCanSpawnAtAChunkEdgeWhereBothOldAndRadiusEightTicketsFail() throws Exception {
        Method find = Raid.class.getDeclaredMethod("findRandomSpawnPos", ServerLevel.class, int.class);
        find.setAccessible(true);
        Raid raid = new Raid(CENTER, Difficulty.HARD);
        Field random = Raid.class.getDeclaredField("random");
        random.setAccessible(true);
        random.set(raid, Proxy.newProxyInstance(RandomSource.class.getClassLoader(),
                new Class<?>[]{RandomSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "nextFloat" -> 0f;
                    case "nextInt" -> 2;
                    default -> throw new UnsupportedOperationException(method.toString());
                }));

        for (int radius : new int[]{6, 8}) {
            assertNull(find.invoke(raid, TestLevel.create(ticketLevel(radius)), 8),
                    "negative control must reject the only platform, radius=" + radius);
        }
        assertEquals(new BlockPos(118, 80, 21),
                find.invoke(raid, TestLevel.create(ticketLevel(requiredFullRadius())), 8),
                "the real 26.3 search must accept the outer spawn ring, including jitter");
    }

    @Test
    void spawnRingAndTenBlockSafetyMarginAreCoveredAtEveryCenterChunkOffset() {
        int level = ticketLevel(requiredFullRadius());
        assertEquals(24, level);
        // 26.3: floor(32 * 3.06) with signed direction, plus [0,6] jitter.
        // Negative positions reach -98, positive positions +103.
        for (int offsetX = 0; offsetX < 16; offsetX++) {
            for (int offsetZ = 0; offsetZ < 16; offsetZ++) {
                for (int dx : new int[]{-98, 103}) {
                    for (int dz : new int[]{-98, 103}) {
                        int x = offsetX + dx;
                        int z = offsetZ + dz;
                        assertTrue(ChunkLevel.isEntityTicking(level + distance(x, z)));
                        for (int marginX : new int[]{-10, 10}) {
                            for (int marginZ : new int[]{-10, 10}) {
                                assertTrue(ChunkLevel.isLoaded(level + distance(x + marginX, z + marginZ)),
                                        "vanilla hasChunksAt(spawn +/- 10) must also pass");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void alreadyLoadedRaidFootprintFitsOneIslandWithoutAdditionalTickets() {
        RegionGrid grid = new RegionGrid(RegionGrid.DEFAULT_SHIFT);
        int cellBlocks = (1 << grid.shift) * 16;
        // Spawn jitter plus the +/-10-block hasChunksAt safety check.
        for (int centerX = 0; centerX < cellBlocks; centerX++) {
            for (int centerZ = 0; centerZ < cellBlocks; centerZ++) {
                for (int x : new int[]{centerX - 108, centerX + 113}) {
                    for (int z : new int[]{centerZ - 108, centerZ + 113}) {
                        long cell = grid.keyFor(x >> 4, z >> 4);
                        assertTrue(Math.abs(grid.regionX(cell)) <= 1);
                        assertTrue(Math.abs(grid.regionZ(cell)) <= 1);
                    }
                }
            }
        }
        // Any two populated cells in this 3x3 footprint are directly adjacent
        // under the production topology's radius-2 BFS, even with empty gaps.
        assertTrue(RegionizedServer.ISLAND_BFS_RADIUS >= 2);
    }

    private static int requiredFullRadius() {
        return 7 + ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.FULL)
                - ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING);
    }

    private static int ticketLevel(int radius) {
        TicketStorage storage = new TicketStorage();
        storage.addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(CENTER), radius);
        return storage.getTickets(ChunkPos.containing(CENTER).pack()).getFirst().getTicketLevel();
    }

    private static int distance(int x, int z) {
        return Math.max(Math.abs(x >> 4), Math.abs(z >> 4));
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static final class TestLevel extends ServerLevel {
        int ticketLevel;
        WorldBorder border;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        static TestLevel create(int ticketLevel) throws Exception {
            TestLevel level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
            level.ticketLevel = ticketLevel;
            level.border = new WorldBorder();
            return level;
        }
        private boolean platform(int x, int z) {
            return x >= CENTER.getX() + 96 && x <= CENTER.getX() + 104
                    && Math.abs(z - CENTER.getZ()) <= 8;
        }
        @Override public int getHeight(Heightmap.Types type, int x, int z) {
            return platform(x, z) ? CENTER.getY() : -60;
        }
        @Override public boolean isVillage(BlockPos pos) { return false; }
        @Override public boolean hasChunk(int x, int z) {
            return ChunkLevel.isLoaded(ticketLevel + Math.max(Math.abs(x), Math.abs(z)));
        }
        @Override public net.minecraft.server.level.ServerChunkCache getChunkSource() {
            throw new AssertionError("a parked/stopped raid must not arm a ticket");
        }
        @Override public boolean hasChunksAt(int x0, int z0, int x1, int z1) {
            return ChunkLevel.isLoaded(ticketLevel + Math.max(distance(x0, z0), distance(x1, z1)));
        }
        @Override public boolean isPositionEntityTicking(BlockPos pos) {
            return ChunkLevel.isEntityTicking(ticketLevel + distance(pos.getX(), pos.getZ()));
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            return (pos.getY() == CENTER.getY() - 1 && platform(pos.getX(), pos.getZ())
                    ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
    }
}
