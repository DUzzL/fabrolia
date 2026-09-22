package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class RedstoneTorchClockLifecycleTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void ownerCommitAndRepeatedShutdownCollapsePreserveTheBurnoutWindow() throws Exception {
        Field unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
        TestLevel level = (TestLevel) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(TestLevel.class);
        level.wall = 10000;
        RegionizedServer runtime = new RegionizedServer(level, new RegionGrid(3));
        BlockPos pos = new BlockPos(0, 64, 0);
        Region region = runtime.regionizer().regionForGridKey(0L);
        IslandRegistry.PIsland oldOwner = new IslandRegistry.PIsland(101, 100);
        oldOwner.level = level;
        region.domain = oldOwner.domain;
        @SuppressWarnings("unchecked")
        Map<ServerLevel, RegionizedServer> levels =
                (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
        Field active = field(RegionScheduler.class, "active");
        boolean previousActive = active.getBoolean(null);
        RegionizedServer previous = levels.put(level, runtime);
        active.setBoolean(null, true);
        try {
            for (int i = 0; i < 8; i++) RedstoneTorchHistory.toggled(level, pos, true);
            oldOwner.logicalTick = 120;
            IslandRegistry.PIsland next = new IslandRegistry.PIsland(102, 20);
            next.level = level;
            assertTrue(region.tryBeginDomainRebaseSeal());
            try {
                RedstoneTorchHistory.rebaseAndCommit(region, next.domain);
            } finally {
                region.releaseDomainRebaseSeal();
            }
            assertSame(next.domain, region.domain);
            next.logicalTick = 60;
            RedstoneTorchHistory.purge(level);
            assertTrue(RedstoneTorchHistory.toggled(level, pos, false));
            // Stop clears active before persistence; collapse must use the retained runtime.
            active.setBoolean(null, false);
            RedstoneTorchHistory.collapseAllToWall(List.of(runtime));
            RedstoneTorchHistory.collapseAllToWall(List.of(runtime));
            assertTrue(RedstoneTorchHistory.toggled(level, pos, false), "retry must not shift stamps twice");
            level.wall++;
            RedstoneTorchHistory.purge(level);
            assertFalse(RedstoneTorchHistory.toggled(level, pos, false));
        } finally {
            active.setBoolean(null, previousActive);
            if (previous == null) levels.remove(level);
            else levels.put(level, previous);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class TestLevel extends ServerLevel {
        long wall;
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public long getGameTime() { return wall; }
        @Override public LevelData getLevelData() { return null; }
        @Override public MinecraftServer getServer() { return null; }
        @Override public net.minecraft.resources.ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }
}
