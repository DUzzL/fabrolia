package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.entity.vehicle.minecart.MinecartSpawner;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Entity phase of an async island unit: the serial Vanilla-order pass by
 * default, ColorGate-parallel member regions on opt-in, and the serial pass
 * for every unit whose members hold TNT or a minecart whatever the mode.
 */
class IslandEntityPhaseModeTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void ordinaryEntitiesAllowTheParallelPhase() throws Exception {
        Region a = region(1L, entity(Pig.class, 1), entity(ItemEntity.class, 2));
        Region b = region(2L, entity(Pig.class, 3));
        assertFalse(RegionizedServer.holdsOrderSensitiveEntity(List.of(a, b)));
        assertFalse(RegionizedServer.holdsOrderSensitiveEntity(List.of()));
    }

    @Test
    void everyVanillaMinecartInAnyMemberKeepsTheSerialPass() throws Exception {
        List<Class<? extends AbstractMinecart>> types = List.of(
                Minecart.class, MinecartHopper.class, MinecartChest.class,
                MinecartFurnace.class, MinecartCommandBlock.class,
                MinecartSpawner.class, MinecartTNT.class);
        for (Class<? extends AbstractMinecart> type : types) {
            Region a = region(1L, entity(Pig.class, 1));
            Region b = region(2L, entity(ItemEntity.class, 2), entity(type, 3));
            assertTrue(RegionizedServer.holdsOrderSensitiveEntity(List.of(a, b)),
                    type.getSimpleName() + " pushes both carts using their current momentum");
        }
    }

    @Test
    void aModdedMinecartSubclassInheritsTheSerialPass() throws Exception {
        Region a = region(1L, entity(CustomMinecart.class, 1));
        assertTrue(RegionizedServer.holdsOrderSensitiveEntity(List.of(a)),
                "custom carts inherit AbstractMinecart's order-sensitive collision body");
    }

    @Test
    void primedTntInAnyMemberKeepsTheSerialPass() throws Exception {
        Region a = region(1L, entity(Pig.class, 1));
        Region b = region(2L, entity(ItemEntity.class, 2), entity(PrimedTnt.class, 3));
        assertTrue(RegionizedServer.holdsOrderSensitiveEntity(List.of(a, b)));
    }

    @Test
    void tntMinecartKeepsTheSerialPass() throws Exception {
        Region a = region(1L, entity(MinecartTNT.class, 7));
        assertTrue(RegionizedServer.holdsOrderSensitiveEntity(List.of(a)));
    }

    @Test
    void defaultIsTheSerialVanillaOrderPass() throws Exception {
        String config = source("common/src/main/java/com/axalotl/fabrolia/common/config/FabroliaConfig.java");
        assertTrue(config.contains("public static volatile boolean islandEntityParallel = false;"));
    }

    @Test
    void unitReadsTheModeOnceAndRunsTheSerialPassOnlyWithoutIt() throws Exception {
        String unit = source("common/src/main/java/com/axalotl/fabrolia/common/regions/IslandTickUnit.java");
        int mode = unit.indexOf("boolean regionParallel = com.axalotl.fabrolia.common.config.FabroliaConfig.islandEntityParallel");
        int sensitive = unit.indexOf("&& !RegionizedServer.holdsOrderSensitiveEntity(members);", mode);
        int dispatch = unit.indexOf("rs.dispatchEntityPhase(d, members, regionParallel);", sensitive);
        int barrier = unit.indexOf("awaitEntityBarrier(rs, island, d, members, barrier);", dispatch);
        int serial = unit.indexOf("if (!regionParallel) rs.tickIslandEntitiesInVanillaOrder(island);", barrier);
        assertTrue(mode > 0 && sensitive > mode && dispatch > sensitive && barrier > dispatch && serial > barrier,
                "one mode read, order-sensitivity check, dispatch with that mode, join, then the serial pass only in vanilla mode");
        assertEquals(1, count(unit, "rs.dispatchEntityPhase("), "the unit dispatches its entity phase once");
        assertEquals(1, count(unit, "tickIslandEntitiesInVanillaOrder("), "the serial pass has one call site");
    }

    @Test
    void fabricAndNeoForgePersistTheMode() throws Exception {
        String fabric = source("fabric/src/main/java/com/axalotl/fabrolia/fabric/config/FabroliaConfig.java");
        assertTrue(fabric.contains("bool(\"islandEntityParallel\""));
        assertTrue(fabric.contains("() -> islandEntityParallel, v -> islandEntityParallel = v"));

        String neoForge = source("neoforge/src/main/java/com/axalotl/fabrolia/neoforge/config/FabroliaConfig.java");
        assertTrue(neoForge.contains("ModConfigSpec.BooleanValue islandEntityParallel"));
        assertTrue(neoForge.contains("define(\"islandEntityParallel\""));
        assertTrue(neoForge.contains("FabroliaConfig.islandEntityParallel = islandEntityParallel.get()"));
        assertTrue(neoForge.contains("islandEntityParallel.set(com.axalotl.fabrolia.common.config.FabroliaConfig.islandEntityParallel)"));

        String command = source("common/src/main/java/com/axalotl/fabrolia/common/commands/RegionCommand.java");
        assertTrue(command.contains("literal(\"islandentities\")"));
        assertEquals(2, count(command, "FabroliaConfig.islandEntityParallel = "),
                "vanilla and parallel each set the mode");
    }

    private static Region region(long key, Entity... entities) {
        Region region = new Region(null, key);
        for (Entity entity : entities) assertTrue(region.entities.add(entity));
        return region;
    }

    private static final class CustomMinecart extends Minecart {
        private CustomMinecart() { super(null, null); }
    }

    /** A real 26.3 entity class without a level; the region set orders by id. */
    private static <T extends Entity> T entity(Class<T> type, int id) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        T entity = type.cast(((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type));
        entity.setId(id);
        return entity;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path file = root.resolve(relative);
            if (Files.isRegularFile(file)) return Files.readString(file);
        }
        Assumptions.assumeTrue(false, relative + " not found");
        return "";
    }
}
