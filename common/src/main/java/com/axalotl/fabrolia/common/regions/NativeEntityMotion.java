package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** Pre-entry containment; an envelope never substitutes for a live island claim. */
final class NativeEntityMotion {
    private NativeEntityMotion() {}

    interface Access {
        ServerLevel level();
        boolean readyColumns(double minX, double minZ, double maxX, double maxZ);
        boolean readyNavigationColumns(double minX, double minZ, double maxX, double maxZ);
        boolean readyEntityColumns(Entity target, double margin);
        boolean hasNoGameEventListeners(Bounds footprint, int radius);
    }

    /** Reused by the enclosing native E admission, including nested admissions. */
    static final class Bounds {
        double minX, minY, minZ, maxX, maxY, maxZ;

        void set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        boolean valid() {
            return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                    && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                    && minX < maxX && minY < maxY && minZ < maxZ;
        }

        boolean contains(AABB box) {
            return box.minX >= minX && box.minY >= minY && box.minZ >= minZ
                    && box.maxX <= maxX && box.maxY <= maxY && box.maxZ <= maxZ;
        }
    }

    static boolean bounds(Entity source, Access access, Bounds out) {
        return (NativeAquaticMotion.bounds(source, access, out)
                || NativeGroundMotion.bounds(source, access, out))
                && out.valid() && out.contains(source.getBoundingBox());
    }
}
