import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

/** Executes unmodified 26.3 ticket creation and raid spawn-position selection.
 * No players or competing tickets: the only spawn surface is near x=97, y=80.
 * This isolates ticket coverage, not the owner's complete farm or throughput.
 */
public final class RaidTicketCoverageProbe {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Field configuredRadius = com.axalotl.fabrolia.common.regions.RaidIslandMerge.class
                .getDeclaredField("TICKET_RADIUS");
        configuredRadius.setAccessible(true);
        int currentRadius = configuredRadius.getInt(null);
        if (currentRadius != 6) throw new AssertionError("Update the control for changed radius");
        Method find = Raid.class.getDeclaredMethod("findRandomSpawnPos", ServerLevel.class, int.class);
        find.setAccessible(true);
        for (int radius : new int[]{currentRadius, 8, 9}) {
            TicketStorage storage = new TicketStorage();
            storage.addTicketWithRadius(TicketType.PORTAL, new ChunkPos(0, 0), radius);
            int ticketLevel = storage.getTickets(0L).getFirst().getTicketLevel();
            FlatLevel level = (FlatLevel) unsafe().allocateInstance(FlatLevel.class);
            level.ticketLevel = ticketLevel;
            level.border = new WorldBorder();
            Raid raid = new Raid(new BlockPos(0, 80, 0), Difficulty.HARD);
            Field cooldown = Raid.class.getDeclaredField("raidCooldownTicks");
            cooldown.setAccessible(true);
            cooldown.setInt(raid, 300);
            Field random = Raid.class.getDeclaredField("random");
            random.setAccessible(true);
            random.set(raid, Proxy.newProxyInstance(RandomSource.class.getClassLoader(),
                    new Class<?>[]{RandomSource.class}, (proxy, method, parameters) -> {
                        if (method.getName().equals("nextFloat")) return 0f;
                        if (method.getName().equals("nextInt")) return 0;
                        throw new UnsupportedOperationException(method.toString());
                    }));
            BlockPos spawn = (BlockPos) find.invoke(raid, level, 8);
            System.out.printf("radius=%d ticketLevel=%d entityRadius=%d earlySpawn=%s%n",
                    radius, ticketLevel, 31-ticketLevel, spawn);
            if (radius == 6 && spawn != null) throw new AssertionError("Radius 6 must reject platform");
            if (radius >= 8 && !new BlockPos(97, 80, 0).equals(spawn)) {
                throw new AssertionError("Wider ticket must admit platform: " + spawn);
            }
        }
        System.out.println("PASS: actual 26.3 spawn search rejects the platform with Fabrolia's radius-6 ticket and admits it with radius 8.");
    }

    private static final class FlatLevel extends ServerLevel {
        int ticketLevel;
        WorldBorder border;
        private FlatLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        private boolean platform(int x, int z) { return x >= 96 && x <= 104 && Math.abs(z) <= 8; }
        @Override public int getHeight(Heightmap.Types type, int x, int z) { return platform(x,z) ? 80 : -60; }
        @Override public boolean isVillage(BlockPos pos) { return false; }
        @Override public boolean hasChunksAt(int x0, int z0, int x1, int z1) {
            return Math.max(Math.max(Math.abs(x0 >> 4), Math.abs(x1 >> 4)),
                    Math.max(Math.abs(z0 >> 4), Math.abs(z1 >> 4))) + ticketLevel <= 33;
        }
        @Override public boolean isPositionEntityTicking(BlockPos pos) {
            return ChunkLevel.isEntityTicking(ticketLevel + Math.max(Math.abs(pos.getX() >> 4), Math.abs(pos.getZ() >> 4)));
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            return (pos.getY() == 79 && platform(pos.getX(),pos.getZ()) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }
}
