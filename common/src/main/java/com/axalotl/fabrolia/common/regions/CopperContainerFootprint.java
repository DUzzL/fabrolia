package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.ChunkPos;

import java.util.function.Predicate;

/** Horizontal owners of one golem's native transport turn. Inspect only under the body's owner. */
public final class CopperContainerFootprint {
    private CopperContainerFootprint() {}

    /** Implemented by the brain and its native transport behavior. Never exposes a live container. */
    public interface RetainedTarget {
        BlockPos fabrolia$retainedCopperContainerPos();
    }

    public interface OpenChest {
        BlockPos fabrolia$openCopperContainerPos();
    }

    // A connected half is one block away. Each half's opener query inflates its unit block
    // box by the maximum vanilla player's block interaction range (64) + 4; entity section
    // lookup extends two more blocks. Include the positive face and large (scaled) users.
    private static final int TARGET_HALO = 1 + 1 + 64 + 4 + 2 + 8;

    static boolean reachesOutside(RegionizedServer rs, IslandRegistry.PIsland source, Entity entity) {
        return !covered(rs, source, entity, null, false);
    }

    static boolean needsMain(RegionizedServer rs, IslandRegistry.PIsland source, Entity entity) {
        if (!(entity instanceof CopperGolem)) return false;
        // A new golem/rider may have appeared after this unit selected parallel helpers.
        // Do not read its brain, or a neighbouring opener, from a partial region claim.
        if (source == null || !(Thread.currentThread() instanceof RegionWorkerThread worker)
                || worker.exclusiveEntityPhase == null || worker.currentDomain != source.domain
                || !worker.exclusiveEntityPhase.ownsTarget(rs.level, entity.blockPosition())) return true;
        return !covered(rs, source, entity, null, true);
    }

    /** Stop at the first owner that cannot be inspected. Null names the global remainder.
     * A visitor returning true must exclude mutation in that cell for the rest of the turn. */
    static boolean visit(RegionizedServer rs, Entity entity, Predicate<IslandRegistry.PIsland> visitor) {
        return covered(rs, null, entity, visitor, false);
    }

    private static boolean covered(RegionizedServer rs, IslandRegistry.PIsland source, Entity entity,
                                   Predicate<IslandRegistry.PIsland> visitor, boolean includeClaims) {
        if (!(entity instanceof CopperGolem golem)) return true;
        // The behavior retains this independently of WALK_TARGET, openedChestPos and the body's
        // current chunk. In particular a teleport does not discard an acquired target.
        BlockPos retained = ((RetainedTarget) golem.getBrain()).fabrolia$retainedCopperContainerPos();
        if (retained != null && !square(rs, source, visitor, retained, TARGET_HALO, includeClaims)) return false;
        BlockPos open = ((OpenChest) golem).fabrolia$openCopperContainerPos();
        if (open != null && !open.equals(retained)
                && !square(rs, source, visitor, open, TARGET_HALO, includeClaims)) return false;

        // 26.3: getTargetSearchArea inflates the body's unit block box by (32,8,32), or 1
        // for a passenger. Vanilla first visits whole BE maps in chunks +/- (distance/16+1).
        // distance + TARGET_HALO contains that entire scan as well as a candidate's second
        // half and query halo. Path validation constructs a source-centered navigation region
        // of max(copper's required path length 48, FOLLOW_RANGE) + 8 when it needs a path.
        int distance = golem.isPassenger() ? 1 : 32;
        int pathRadius = (int) (Math.max(48f, (float) golem.getAttributeValue(Attributes.FOLLOW_RANGE)) + 8f);
        return square(rs, source, visitor, golem.blockPosition(), Math.max(distance + TARGET_HALO, pathRadius), includeClaims);
    }

    private static boolean square(RegionizedServer rs, IslandRegistry.PIsland source,
                                  Predicate<IslandRegistry.PIsland> visitor, BlockPos center, int radius, boolean includeClaims) {
        int shift = rs.regionizer().grid.shift + 4;
        int minX = (center.getX() - radius) >> shift, maxX = (center.getX() + radius) >> shift;
        int minZ = (center.getZ() - radius) >> shift, maxZ = (center.getZ() + radius) >> shift;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Includes committed adjacent halo cells even without materialized Regions.
                long key = ChunkPos.pack(x, z);
                if (!coversOwner(rs.islandOwnerOfCell(key), source, visitor, includeClaims)) return false;
                // Native hasContainerOpen reads each candidate's openedChestPos block state
                // before comparing coordinates. TRACKED candidates are queryable too.
                // This is a single state read, not another transport turn or opener search.
                CopperGolem[] openers = rs.copperContainers.openers(key);
                if (openers == null) continue;
                for (CopperGolem other : openers) {
                    if (other.isRemoved() || other.level() != rs.level
                            || rs.regionizer().grid.keyFor(other.chunkPosition().pack()) != key) return false;
                    BlockPos open = ((OpenChest) other).fabrolia$openCopperContainerPos();
                    if (open != null && !coversOwner(rs.islandOwnerOfChunk(ChunkPos.pack(open)),
                            source, visitor, includeClaims)) return false;
                }
            }
        }
        return true;
    }

    private static boolean coversOwner(IslandRegistry.PIsland owner, IslandRegistry.PIsland source,
                                       Predicate<IslandRegistry.PIsland> visitor, boolean includeClaims) {
        if (visitor != null) return visitor.test(owner);
        return owner != null && (owner == source || includeClaims && CopperIslandClaims.ownsExtra(owner, source));
    }
}
