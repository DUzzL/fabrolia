package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A non-player whose cross-dimension portal transfer carries spectators must
 * leave the worker lane: vanilla's teleportSpectators teleports the Main-held
 * spectator and resets its camera inside the transfer.
 */
final class PortalSpectatorFenceTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearCameras() throws Exception {
        Map<?, ?> cameras = cameras();
        synchronized (cameras) { cameras.clear(); }
    }

    @Test
    void followedEndPortalUserRequiresTheGlobalFence() throws Exception {
        Entity pig = entity(Pig.class, (Portal) Blocks.END_PORTAL);
        assertFalse(ParallelProcessor.leadsSpectatorsThroughPortal(pig),
                "without a follower the End transfer keeps its worker path");

        ServerPlayer spectator = entity(ServerPlayer.class, null);
        follow(spectator, pig);
        assertTrue(ParallelProcessor.leadsSpectatorsThroughPortal(pig));

        Entity idle = entity(Pig.class, null);
        follow(entity(ServerPlayer.class, null), idle);
        assertFalse(ParallelProcessor.leadsSpectatorsThroughPortal(idle),
                "a followed entity outside a portal keeps its worker tick");

        Entity nether = entity(Pig.class, (Portal) Blocks.NETHER_PORTAL);
        follow(entity(ServerPlayer.class, null), nether);
        assertFalse(ParallelProcessor.leadsSpectatorsThroughPortal(nether),
                "Nether reservations capture their camera followers");

        ServerPlayer player = entity(ServerPlayer.class, (Portal) Blocks.END_PORTAL);
        follow(entity(ServerPlayer.class, null), player);
        assertFalse(ParallelProcessor.leadsSpectatorsThroughPortal(player),
                "players defer their own portal through their listener Main FIFO");
    }

    @Test
    void followedPassengerFencesTheWholeTree() throws Exception {
        Entity root = entity(Pig.class, (Portal) Blocks.END_PORTAL);
        Entity middle = entity(Pig.class, null);
        Entity rider = entity(Pig.class, null);
        passengers(root, middle);
        passengers(middle, rider);
        assertFalse(ParallelProcessor.leadsSpectatorsThroughPortal(root));
        follow(entity(ServerPlayer.class, null), rider);
        assertTrue(ParallelProcessor.leadsSpectatorsThroughPortal(root));
        passengers(middle);
        assertFalse(ParallelProcessor.leadsSpectatorsThroughPortal(root));
    }

    @Test
    void entityTickRoutesFollowedPortalUseBeforeTheWorkerBody() throws Exception {
        String text = source("common/regions/RegionizedServer.java");
        int tnt = text.indexOf("TntExplosionFootprint.requiresGlobalFence(this, region, tnt)");
        int portal = text.indexOf("ParallelProcessor.leadsSpectatorsThroughPortal(e)", tnt);
        int fenced = text.indexOf("RegionScheduler.queueMainSyncFencedTick(e,", portal);
        int plainMain = text.indexOf("ParallelProcessor.requiresMainThread(e)", portal);
        int workerBody = text.indexOf("ParallelProcessor.tickEntity(lvl, e, true)", portal);
        assertTrue(tnt >= 0 && portal > tnt && fenced > portal
                        && plainMain > fenced && workerBody > plainMain,
                "a followed portal transfer must take the globally fenced MAIN_SYNC lane before the worker tick");
    }

    @SuppressWarnings("unchecked")
    private static void follow(ServerPlayer spectator, Entity target) throws Exception {
        Class<?> linkType = Class.forName(NetherPortalPlayerAccess.class.getName() + "$CameraLink");
        Constructor<?> constructor = linkType.getDeclaredConstructor(ServerLevel.class, Entity.class, List.class);
        constructor.setAccessible(true);
        Object link = constructor.newInstance((ServerLevel) target.level(), target, List.of());
        Map<Object, Object> cameras = (Map<Object, Object>) cameras();
        synchronized (cameras) { cameras.put(spectator, link); }
    }

    private static Map<?, ?> cameras() throws Exception {
        Field field = NetherPortalPlayerAccess.class.getDeclaredField("CAMERAS");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Entity> T entity(Class<T> type, Portal portal) throws Exception {
        T entity = (T) unsafe().allocateInstance(type);
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
