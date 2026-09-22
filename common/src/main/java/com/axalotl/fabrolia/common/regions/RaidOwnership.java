package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/** Raid bodies belong to the island phase; POI reads additionally need its spatial read footprint. */
public final class RaidOwnership {
    private RaidOwnership() {}
    public static final class ForeignOwner extends RuntimeException {
        public ForeignOwner() { super(null, null, false, false); }
    }

    /** Physical-Main compatibility execution, retaining the canonical first-W fence exit. */
    public static void runMainFenced(Runnable body) {
        boolean acquired = false;
        Throwable failure = null;
        try {
            acquired = IslandCommands.quiesceAllExcept(null);
            RegionScheduler.runSuccessBodyIfHealthy(body);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        if (acquired) {
            IslandCommands.releaseAllExceptAndPropagate(failure);
        } else if (failure != null) {
            IslandCommands.publishAndPropagate(failure);
        } else {
            RegionScheduler.commitSuccessIfHealthy(() -> {});
        }
    }

    public static boolean canCreate(ServerPlayer player, BlockPos position) {
        ServerLevel level = player.level();
        if (RegionalPlayerEntityTicksV2.inExactPlayerActor()
                && RegionScheduler.ownsCurrentContext(level, position)) return true;
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) return false;
        if (rs.currentThreadHoldsAllClaims()) return true;
        if (!AsyncIslandPlayerTicks.ownsLifecycleMutation(player)) return false;
        // PoiManager.getInSquare(64) visits floorDiv(64,16)+1 chunks each way.
        int cx = position.getX() >> 4;
        int cz = position.getZ() >> 4;
        RegionGrid grid = rs.regionizer().grid;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        // A grid cell is one ownership identity; inspect it once even when
        // several of the exact 11x11 POI columns intersect it.
        for (int x = (cx - 5) >> grid.shift; x <= ((cx + 5) >> grid.shift); x++) {
            for (int z = (cz - 5) >> grid.shift; z <= ((cz + 5) >> grid.shift); z++) {
                int chunkX = x << grid.shift;
                int chunkZ = z << grid.shift;
                probe.set(chunkX << 4, 0, chunkZ << 4);
                if (!ownsPoiRead(rs, grid.keyFor(chunkX, chunkZ), probe)) return false;
            }
        }
        return true;
    }

    private static boolean ownsPoiRead(RegionizedServer rs, long gridKey, BlockPos pos) {
        if (RegionScheduler.ownsCurrentContext(rs.level, pos)) return true;
        if (!(Thread.currentThread() instanceof RegionWorkerThread worker)) return false;
        Region source = worker.currentRegion;
        Region target = rs.regionizer().peekByGridKey(gridKey);
        if (source == null || target == null || source.currentWorker() != worker
                || !rs.ownsRegion(source) || !rs.ownsRegion(target)
                || source.domain != target.domain
                || Math.abs((long) (int) source.gridKey - (int) target.gridKey) > 1
                || Math.abs((long) (int) (source.gridKey >>> 32) - (int) (target.gridKey >>> 32)) > 1) {
            return false;
        }
        EpochDomain domain = source.domain;
        long epoch = domain.epoch;
        return FabroliaConfig.useRegionColorGate()
                && domain.entityDispatchPublication.isAccepted()
                && domain.entityDispatchCutEpoch == epoch && domain.dispatchedEpoch == epoch
                && source.groupedEpoch == epoch && ColorGate.eligible(source, rs);
    }

    /** Entity-phase callers may share a raid, but cannot overlap its coordinator tick. */
    public static boolean ownsRaid(ServerLevel level, BlockPos center) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (RegionScheduler.ownsCurrentContext(level, center)) return true;
        if (rs == null || !rs.asyncActive) {
            return RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer());
        }
        if (rs.currentThreadHoldsAllClaims()) return true;
        if (!(Thread.currentThread() instanceof RegionWorkerThread worker)) return false;
        Region source = worker.currentRegion;
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(ChunkPos.pack(center));
        if (source == null || source.currentWorker() != worker || !rs.ownsRegion(source)
                || owner == null || owner.retired || source.domain != owner.domain) return false;
        EpochDomain domain = owner.domain;
        return domain.entityDispatchPublication.isAccepted()
                && domain.entityDispatchCutEpoch == domain.epoch
                && domain.dispatchedEpoch == domain.epoch && source.groupedEpoch == domain.epoch;
    }
}
