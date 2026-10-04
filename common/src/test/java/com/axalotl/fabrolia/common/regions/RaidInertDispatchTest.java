package com.axalotl.fabrolia.common.regions;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A raid no island owns is ticked by Main under the all-island fence. When vanilla's
 * level step for it is a no-op (ongoing, already inactive, center chunk unloaded),
 * Main skips the fence: production kept such raids forever and paid ~10 ms/tick.
 */
class RaidInertDispatchTest {
    private static final BlockPos CENTER = new BlockPos(1000, 64, -2000);

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void anInactiveRaidWithAnUnloadedCenterIsInertAndItsVanillaTickChangesNothing() throws Exception {
        TestLevel level = TestLevel.create(false, Difficulty.NORMAL, true);
        Raid raid = new Raid(CENTER, Difficulty.NORMAL);
        // A new raid starts active; vanilla's first tick with an unloaded center makes
        // it inactive (and hides its bar). That tick is not inert.
        assertTrue(raid.isActive());
        assertFalse(RaidIslandDispatch.isInertWhileCenterUnloaded(level, raid));
        raid.tick(level);
        assertFalse(raid.isActive());

        assertInertAndUnchangedByVanillaTicks(level, raid);
    }

    @Test
    void aRaidLoadedFromDiskInactiveIsInertFromItsFirstTick() throws Exception {
        // Production: raids.dat keeps raids of unloaded farms with active=false.
        TestLevel level = TestLevel.create(false, Difficulty.NORMAL, true);
        Raid fresh = new Raid(CENTER, Difficulty.NORMAL);
        fresh.tick(level);
        Raid loaded = Raid.MAP_CODEC.codec().parse(JsonOps.INSTANCE,
                Raid.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE, fresh).getOrThrow()).getOrThrow();
        assertFalse(loaded.isActive());
        assertInertAndUnchangedByVanillaTicks(level, loaded);
    }

    private static void assertInertAndUnchangedByVanillaTicks(TestLevel level, Raid raid) throws Exception {
        assertTrue(RaidIslandDispatch.isInertWhileCenterUnloaded(level, raid));
        String before = snapshot(raid);
        for (int i = 0; i < 50; i++) raid.tick(level);
        assertEquals(before, snapshot(raid), "vanilla ticks of an inert raid must not change it");
        assertTrue(RaidIslandDispatch.isInertWhileCenterUnloaded(level, raid));
    }

    @Test
    void everyStateWhoseVanillaTickActsTakesTheFencedPath() throws Exception {
        Raid inactive = new Raid(CENTER, Difficulty.NORMAL);
        inactive.tick(TestLevel.create(false, Difficulty.NORMAL, true));
        assertFalse(inactive.isActive());

        assertFalse(RaidIslandDispatch.isInertWhileCenterUnloaded(
                TestLevel.create(true, Difficulty.NORMAL, true), inactive),
                "a loaded center activates the raid");
        assertFalse(RaidIslandDispatch.isInertWhileCenterUnloaded(
                TestLevel.create(false, Difficulty.PEACEFUL, true), inactive),
                "peaceful stops the raid");
        assertFalse(RaidIslandDispatch.isInertWhileCenterUnloaded(
                TestLevel.create(false, Difficulty.NORMAL, false), inactive),
                "raids=false stops the raid");

        TestLevel unloaded = TestLevel.create(false, Difficulty.NORMAL, true);
        Raid over = new Raid(CENTER, Difficulty.NORMAL);
        over.tick(unloaded);
        setStatus(over, "LOSS");
        assertFalse(RaidIslandDispatch.isInertWhileCenterUnloaded(unloaded, over),
                "a finished raid counts its celebration ticks");

        Raid stopped = new Raid(CENTER, Difficulty.NORMAL);
        stopped.tick(unloaded);
        stopped.stop();
        assertFalse(RaidIslandDispatch.isInertWhileCenterUnloaded(unloaded, stopped),
                "a stopped raid is removed");
    }

    /** Saved form plus every unsaved field a tick could touch, incl. the boss bar. */
    private static String snapshot(Raid raid) throws Exception {
        StringBuilder out = new StringBuilder(
                Raid.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE, raid).getOrThrow().toString());
        for (Field field : Raid.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || field.getType() == net.minecraft.util.RandomSource.class) continue;
            field.setAccessible(true);
            Object value = field.get(raid);
            if (value instanceof net.minecraft.server.level.ServerBossEvent bar) {
                value = "visible=" + bar.isVisible() + " players=" + bar.getPlayers().size()
                        + " progress=" + bar.getProgress() + " name=" + bar.getName().getString();
            }
            out.append('\n').append(field.getName()).append('=').append(value);
        }
        return out.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setStatus(Raid raid, String name) throws Exception {
        Field status = Raid.class.getDeclaredField("status");
        status.setAccessible(true);
        status.set(raid, Enum.valueOf((Class) status.getType(), name));
    }

    private static final class TestLevel extends ServerLevel {
        boolean centerLoaded;
        Difficulty difficulty;
        GameRules rules;

        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }

        static TestLevel create(boolean centerLoaded, Difficulty difficulty, boolean raids) throws Exception {
            TestLevel level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
            level.centerLoaded = centerLoaded;
            level.difficulty = difficulty;
            level.rules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
            level.rules.set(GameRules.RAIDS, raids, null);
            return level;
        }

        @Override public boolean hasChunk(int x, int z) {
            assertEquals(CENTER.getX() >> 4, x);
            assertEquals(CENTER.getZ() >> 4, z);
            return centerLoaded;
        }
        @Override public Difficulty getDifficulty() { return difficulty; }
        @Override public GameRules getGameRules() { return rules; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
