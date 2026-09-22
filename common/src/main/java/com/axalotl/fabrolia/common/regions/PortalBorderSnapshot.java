package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** One Main publication; moving borders use their exact zero-partial-tick bounds. */
final class PortalBorderSnapshot extends WorldBorder {
    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;
    private final VoxelShape collision;

    PortalBorderSnapshot(WorldBorder border) {
        minX = border.getMinX();
        maxX = border.getMaxX();
        minZ = border.getMinZ();
        maxZ = border.getMaxZ();
        collision = Shapes.join(Shapes.INFINITY,
                Shapes.box(Math.floor(minX), Double.NEGATIVE_INFINITY, Math.floor(minZ),
                        Math.ceil(maxX), Double.POSITIVE_INFINITY, Math.ceil(maxZ)),
                BooleanOp.ONLY_FIRST);
    }

    boolean matches(WorldBorder border) {
        return minX == border.getMinX() && maxX == border.getMaxX()
                && minZ == border.getMinZ() && maxZ == border.getMaxZ();
    }

    @Override public double getMinX() { return minX; }
    @Override public double getMaxX() { return maxX; }
    @Override public double getMinZ() { return minZ; }
    @Override public double getMaxZ() { return maxZ; }
    @Override public double getMinX(float deltaPartialTick) { return minX; }
    @Override public double getMaxX(float deltaPartialTick) { return maxX; }
    @Override public double getMinZ(float deltaPartialTick) { return minZ; }
    @Override public double getMaxZ(float deltaPartialTick) { return maxZ; }
    @Override public VoxelShape getCollisionShape() { return collision; }
}
