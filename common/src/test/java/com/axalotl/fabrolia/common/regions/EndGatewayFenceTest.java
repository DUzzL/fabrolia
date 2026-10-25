package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A non-player using an End gateway must leave the worker lane: vanilla's
 * handlePortal can load distant chunks and build the exit gateway outside the
 * island, and every gateway teleport moves the entity into another domain.
 */
final class EndGatewayFenceTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nonPlayerGatewayProcessRequiresTheGlobalFence() throws Exception {
        Entity idle = entity(Pig.class, null);
        assertFalse(ParallelProcessor.usesEndGateway(idle));

        Entity nether = entity(Pig.class, (Portal) Blocks.NETHER_PORTAL);
        assertFalse(ParallelProcessor.usesEndGateway(nether),
                "Nether portals keep their worker-side NetherPortalTransfers path");

        Entity gateway = entity(Pig.class, (Portal) Blocks.END_GATEWAY);
        assertTrue(ParallelProcessor.usesEndGateway(gateway));

        Entity player = entity(ServerPlayer.class, (Portal) Blocks.END_GATEWAY);
        assertFalse(ParallelProcessor.usesEndGateway(player),
                "players defer the gateway lookup through their listener Main FIFO");
    }

    @Test
    void gatewayPassengerFencesTheWholeTree() throws Exception {
        Entity root = entity(Pig.class, null);
        Entity middle = entity(Pig.class, null);
        Entity rider = entity(Pig.class, (Portal) Blocks.END_GATEWAY);
        passengers(root, middle);
        passengers(middle, rider);
        assertTrue(ParallelProcessor.usesEndGateway(root));
        passengers(middle);
        assertFalse(ParallelProcessor.usesEndGateway(root));
    }

    @Test
    void entityTickRoutesGatewayUseBeforeTheWorkerBody() throws Exception {
        String text = source("common/regions/RegionizedServer.java");
        int tnt = text.indexOf("TntExplosionFootprint.requiresGlobalFence(this, region, tnt)");
        int gateway = text.indexOf("ParallelProcessor.usesEndGateway(e)", tnt);
        int fenced = text.indexOf("RegionScheduler.queueMainSyncFencedTick(e,", gateway);
        int plainMain = text.indexOf("ParallelProcessor.requiresMainThread(e)", gateway);
        int workerBody = text.indexOf("ParallelProcessor.tickEntity(lvl, e, true)", gateway);
        assertTrue(tnt >= 0 && gateway > tnt && fenced > gateway
                        && plainMain > fenced && workerBody > plainMain,
                "gateway use must take the globally fenced MAIN_SYNC lane before the worker tick");
    }

    private static Entity entity(Class<? extends Entity> type, Portal portal) throws Exception {
        Entity entity = (Entity) unsafe().allocateInstance(type);
        passengers(entity);
        entity.portalProcess = portal == null ? null : new PortalProcessor(portal, BlockPos.ZERO);
        return entity;
    }

    private static void passengers(Entity root, Entity... passengers) throws Exception {
        Field field = Entity.class.getDeclaredField("passengers");
        field.setAccessible(true);
        field.set(root, ImmutableList.copyOf(passengers));
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        throw new AssertionError("source tree not available");
    }
}
