package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debug visualization of region-grid boundaries: END_ROD curtains along the grid lines
 * near each opted-in player, FLAME columns on cell corners. Toggled per player via
 * {@code /fabrolia regions show}; drawn every ~10 server ticks, only to the toggled player
 * (private particles via the per-player sendParticles overload), only within {@link
 * #RADIUS} blocks. Costs nothing while the toggle set is empty (one isEmpty check per
 * batch end).
 */
public final class RegionViz {

    private RegionViz() {}

    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();
    private static int callCounter;

    private static final int RADIUS = 96;          // draw distance from player, blocks
    private static final int LINE_STEP = 2;        // particle spacing along a line, blocks
    private static final int WALL_HEIGHT_STEP = 2; // vertical spacing
    private static final int WALL_LEVELS = 12;     // particle rows per wall (~24 blocks tall)
    private static final int WALL_BELOW = 6;       // start this far below the player's feet,
                                                   // so the lines stay visible looking down

    /** Big bright red dust — far more visible than END_ROD against any backdrop. */
    private static final DustParticleOptions BOUNDARY_DUST =
            new DustParticleOptions(DustParticleOptions.REDSTONE_PARTICLE_COLOR, 2.0F);

    public static boolean toggle(ServerPlayer player) {
        UUID id = player.getUUID();
        if (ENABLED.remove(id)) return false;
        ENABLED.add(id);
        return true;
    }

    public static boolean isEmpty() {
        return ENABLED.isEmpty();
    }

    /**
     * Called from ParallelProcessor.onEntityTickBatchEnd (per level per tick — the call
     * counter, not tick parity, sets the draw cadence). Main thread.
     */
    public static void tick() {
        if (ENABLED.isEmpty()) return;
        if (++callCounter % 15 != 0) return; // ~every 5 server ticks at 3 levels
        MinecraftServer server = ParallelProcessor.getServer();
        if (server == null) return;

        RegionGrid grid = RegionScheduler.grid();
        int shift = grid != null ? grid.shift : RegionGrid.DEFAULT_SHIFT;
        int cellBlocks = 1 << (shift + 4); // cell size in blocks (chunks << 4)

        for (UUID id : ENABLED) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                ENABLED.remove(id); // logged off — drop
                continue;
            }
            draw(player.level() instanceof ServerLevel sl ? sl : null, player, cellBlocks);
        }
    }

    private static void draw(ServerLevel level, ServerPlayer player, int cell) {
        if (level == null) return;
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        int minX = px - RADIUS, maxX = px + RADIUS;
        int minZ = pz - RADIUS, maxZ = pz + RADIUS;

        // Vertical curtains along every grid line within radius. Grid lines sit at
        // block coords that are multiples of the cell size (cell origin boundaries).
        for (int bx = Math.floorDiv(minX, cell) * cell + cell; bx <= maxX; bx += cell) {
            for (int z = minZ - (minZ % LINE_STEP); z <= maxZ; z += LINE_STEP) {
                wall(level, player, bx + 0.0D, py, z + 0.5D);
            }
        }
        for (int bz = Math.floorDiv(minZ, cell) * cell + cell; bz <= maxZ; bz += cell) {
            for (int x = minX - (minX % LINE_STEP); x <= maxX; x += LINE_STEP) {
                wall(level, player, x + 0.5D, py, bz + 0.0D);
            }
        }
        // Corners (cell intersections): flame columns, taller — easy to spot from afar.
        for (int bx = Math.floorDiv(minX, cell) * cell + cell; bx <= maxX; bx += cell) {
            for (int bz = Math.floorDiv(minZ, cell) * cell + cell; bz <= maxZ; bz += cell) {
                for (int dy = -WALL_BELOW; dy <= WALL_LEVELS * WALL_HEIGHT_STEP + 6; dy += 2) {
                    level.sendParticles(player, ParticleTypes.FLAME, true, true,
                            bx + 0.0D, py + dy, bz + 0.0D, 1, 0, 0, 0, 0);
                }
            }
        }
    }

    private static void wall(ServerLevel level, ServerPlayer player, double x, int baseY, double z) {
        for (int i = 0; i < WALL_LEVELS; i++) {
            level.sendParticles(player, BOUNDARY_DUST, true, true,
                    x, baseY - WALL_BELOW + (long) i * WALL_HEIGHT_STEP, z, 1, 0, 0, 0, 0);
        }
    }
}
