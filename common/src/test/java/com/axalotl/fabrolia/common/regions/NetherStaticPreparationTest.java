package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Loaded static footprints need no island topology; async footprints still do. */
@Isolated
class NetherStaticPreparationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void staticFootprintRemainsReadyWithoutRequestingAnImpossibleIsland() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            assertTrue(fixture.request.readyToAttempt());
            fixture.request.footprint();
            assertTrue(fixture.request.readyToAttempt(),
                    "a paused compatibility transfer cannot wait for disabled island topology");
            fixture.request.footprint();
            assertTrue(fixture.request.readyToAttempt(), "rechecking the footprint must preserve progress");
        }
    }

    @Test
    void asyncFootprintStillRequiresOwnerPopulationBeforeAdmission() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.request.footprint();
            assertFalse(fixture.request.readyToAttempt(),
                    "normal worker transfer may not treat an ownerless cell as exclusive");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Object oldActive = field(RegionScheduler.class, "active").get(null);
        final Object oldGrid = field(RegionScheduler.class, "grid").get(null);
        final ServerLevel level;
        final NetherPortalPreparation.Request request;
        final Map<ServerLevel, RegionizedServer> runtimes;
        final Map<ServerLevel, Object> borders;

        @SuppressWarnings("unchecked")
        Fixture(boolean async) throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            level = (ServerLevel) unsafe.allocateInstance(TestLevel.class);
            RegionizedServer runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = async;
            runtimes = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            borders = (Map<ServerLevel, Object>) field(NetherPortalPreparation.class, "BORDERS").get(null);
            request = (NetherPortalPreparation.Request) unsafe.allocateInstance(NetherPortalPreparation.Request.class);
            Object border = new PortalBorderSnapshot(new WorldBorder());
            field(NetherPortalPreparation.Request.class, "source").set(request, level);
            field(NetherPortalPreparation.Request.class, "destination").set(request, level);
            field(NetherPortalPreparation.Request.class, "sourcePosition").set(request, Vec3.ZERO);
            field(NetherPortalPreparation.Request.class, "center").set(request, BlockPos.ZERO);
            field(NetherPortalPreparation.Request.class, "border").set(request, border);
            var chunk = new NetherPortalPreparation.ChunkRef(level, 0L);
            field(NetherPortalPreparation.Request.class, "chunks").set(request, new LinkedHashSet<>(Set.of(chunk)));
            field(NetherPortalPreparation.Request.class, "required").set(request, new LinkedHashMap<>(Map.of(chunk, ChunkStatus.FULL)));
            field(NetherPortalPreparation.Request.class, "loading").set(request, new LinkedHashMap<>());
            runtimes.put(level, runtime);
            borders.put(level, border);
            field(RegionScheduler.class, "grid").set(null, new RegionGrid(3));
            field(RegionScheduler.class, "active").setBoolean(null, true);
        }

        @Override public void close() throws ReflectiveOperationException {
            runtimes.remove(level);
            borders.remove(level);
            field(RegionScheduler.class, "active").set(null, oldActive);
            field(RegionScheduler.class, "grid").set(null, oldGrid);
        }
    }

    private static final class TestLevel extends ServerLevel {
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
