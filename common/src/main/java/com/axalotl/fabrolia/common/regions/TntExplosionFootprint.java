package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.CreeperExplosionAccessor;
import com.axalotl.fabrolia.common.mixin.entity.LargeFireballExplosionAccessor;
import com.axalotl.fabrolia.common.mixin.entity.MinecartTntExplosionAccessor;
import com.axalotl.fabrolia.common.mixin.entity.PrimedTntExplosionAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.phys.Vec3;

/** Whole-tick admission for TNT whose blast can leave its exclusive island. */
public final class TntExplosionFootprint {
    private TntExplosionFootprint() {}

    static boolean requiresGlobalFence(RegionizedServer runtime, Region region, PrimedTnt tnt) {
        if (tnt.getFuse() > 1 || region.domain == null || region.domain.owner == null) return false;
        // handlePortal precedes movement and explosion in Vanilla. A possible
        // transfer cannot be certified from the current spatial footprint.
        if (tnt.portalProcess != null) return true;
        Vec3 motion = tnt.getDeltaMovement();
        float power = ((PrimedTntExplosionAccessor) tnt).fabrolia$explosionPower();
        return !ownsFootprint(runtime, region.domain.owner,
                tnt.getX(), tnt.getZ(), motion.x, motion.z, power);
    }

    /**
     * The other entities whose own tick can explode (TNT minecart: fuse, wall hit
     * or fall; creeper while swelling; large fireball on impact), and primed TNT
     * riding in the tree: their passengers tick inside the root's tick. NBT and
     * commands raise their power far past the halo (cart base and speed factor
     * 0..128 each, creeper radius and fireball power up to a byte); vanilla
     * strengths from a core cell never leave the island. The root primed TNT
     * keeps {@link #requiresGlobalFence}.
     */
    static boolean explosiveTreeLeavesIsland(RegionizedServer runtime, Region region, Entity root) {
        if (region.domain == null || region.domain.owner == null) return false;
        return treeLeavesIsland(runtime, region.domain.owner, root, true);
    }

    private static boolean treeLeavesIsland(RegionizedServer runtime, IslandRegistry.PIsland owner,
                                            Entity entity, boolean root) {
        if (!(root && entity instanceof PrimedTnt) && leavesIsland(runtime, owner, entity)) return true;
        for (Entity passenger : entity.getPassengers()) {
            if (treeLeavesIsland(runtime, owner, passenger, false)) return true;
        }
        return false;
    }

    private static boolean leavesIsland(RegionizedServer runtime, IslandRegistry.PIsland owner, Entity entity) {
        float power;
        if (entity instanceof PrimedTnt tnt) {
            if (tnt.getFuse() > 1) return false;
            if (tnt.portalProcess != null) return true;
            power = ((PrimedTntExplosionAccessor) tnt).fabrolia$explosionPower();
        } else if (entity instanceof MinecartTNT cart) {
            MinecartTntExplosionAccessor explosion = (MinecartTntExplosionAccessor) cart;
            // MinecartTNT#explode: base + factor * random * 1.5 * min(speed, 5).
            power = explosion.fabrolia$explosionPowerBase() + explosion.fabrolia$explosionSpeedFactor() * 7.5F;
        } else if (entity instanceof Creeper creeper) {
            // Only a swelling creeper reaches maxSwell in this tick's own body.
            if (!creeper.isIgnited() && creeper.getSwellDir() <= 0) return false;
            power = ((CreeperExplosionAccessor) creeper).fabrolia$explosionRadius() * (creeper.isPowered() ? 2.0F : 1.0F);
        } else if (entity instanceof LargeFireball fireball) {
            power = ((LargeFireballExplosionAccessor) fireball).fabrolia$explosionPower();
        } else {
            return false;
        }
        Vec3 motion = entity.getDeltaMovement();
        return !ownsFootprint(runtime, owner, entity.getX(), entity.getZ(), motion.x, motion.z, power);
    }

    /**
     * 26.3 clamps serialized power to 0..128. Entity queries reach 2r+1;
     * air-only block rays reach less than 1.74r. The additional margin covers
     * entity width, discretization and the pre-explosion movement/contact slot.
     */
    static boolean ownsFootprint(RegionizedServer runtime, IslandRegistry.PIsland owner,
                                 double x, double z, double vx, double vz, float power) {
        if (owner == null || owner.retired || !Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(vx) || !Double.isFinite(vz)
                || !Float.isFinite(power) || power < 0 || power > 128) return false;
        double reach = power * 2.0 + 4.0;
        double minX = x - Math.abs(vx) - reach;
        double maxX = x + Math.abs(vx) + reach;
        double minZ = z - Math.abs(vz) - reach;
        double maxZ = z + Math.abs(vz) + reach;
        if (minX < -30_000_000 || maxX > 30_000_000
                || minZ < -30_000_000 || maxZ > 30_000_000) return false;
        int blockShift = runtime.regionizer().grid.shift + 4;
        int minCellX = ((int) Math.floor(minX)) >> blockShift;
        int maxCellX = ((int) Math.floor(maxX)) >> blockShift;
        int minCellZ = ((int) Math.floor(minZ)) >> blockShift;
        int maxCellZ = ((int) Math.floor(maxZ)) >> blockShift;
        // Huge modded velocities are routed before scanning a massive area.
        if (((long) maxCellX - minCellX + 1) * ((long) maxCellZ - minCellZ + 1) > 4096) return false;
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                long key = (cellX & 0xFFFFFFFFL) | ((cellZ & 0xFFFFFFFFL) << 32);
                if (runtime.islandOwnerOfCell(key) != owner) return false;
            }
        }
        return true;
    }
}
