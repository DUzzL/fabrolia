package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.Level;
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

/** Audit X-04: a non-player leaving the End through the exit portal ticks under the global fence. */
final class EndExitFenceTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void endExitPortalUseRequiresTheGlobalFence() throws Exception {
        ServerLevel end = level(Level.END);
        ServerLevel overworld = level(Level.OVERWORLD);
        assertTrue(ParallelProcessor.exitsTheEnd(entity(Pig.class, end, (Portal) Blocks.END_PORTAL)));
        assertFalse(ParallelProcessor.exitsTheEnd(entity(Pig.class, overworld, (Portal) Blocks.END_PORTAL)),
                "entering the End keeps the staged worker path");
        assertFalse(ParallelProcessor.exitsTheEnd(entity(Pig.class, end, (Portal) Blocks.END_GATEWAY)));
        assertFalse(ParallelProcessor.exitsTheEnd(entity(Pig.class, end, null)));
        assertFalse(ParallelProcessor.exitsTheEnd(entity(ServerPlayer.class, end, (Portal) Blocks.END_PORTAL)),
                "players defer their own portal through their listener Main FIFO");

        Entity cart = entity(Pig.class, end, null);
        Entity rider = entity(Pig.class, end, (Portal) Blocks.END_PORTAL);
        passengers(cart, rider);
        assertTrue(ParallelProcessor.exitsTheEnd(cart));
    }

    @Test
    void entityTickRoutesTheEndExitBeforeTheWorkerBody() throws Exception {
        String text = source("common/regions/RegionizedServer.java");
        int exit = text.indexOf("ParallelProcessor.exitsTheEnd(e)");
        int fenced = text.indexOf("RegionScheduler.queueMainSyncFencedTick(e,", exit);
        int workerBody = text.indexOf("ParallelProcessor.tickEntity(lvl, e, true)", exit);
        assertTrue(exit >= 0 && fenced > exit && workerBody > fenced);
    }

    private static ServerLevel level(ResourceKey<Level> dimension) throws Exception {
        ServerLevel level = (ServerLevel) unsafe().allocateInstance(ServerLevel.class);
        set(Level.class, level, "dimension", dimension);
        return level;
    }

    private static Entity entity(Class<? extends Entity> type, ServerLevel level, Portal portal) throws Exception {
        Entity entity = (Entity) unsafe().allocateInstance(type);
        passengers(entity);
        set(Entity.class, entity, "level", level);
        entity.portalProcess = portal == null ? null : new PortalProcessor(portal, BlockPos.ZERO);
        return entity;
    }

    private static void passengers(Entity root, Entity... passengers) throws Exception {
        set(Entity.class, root, "passengers", ImmutableList.copyOf(passengers));
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
