package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual 26.3 raid logic; missing villages get no custom grace or reach. */
class RaidVillageParityTest {
    private static final BlockPos CENTER = new BlockPos(8, 88, 8);

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void runtimeDoesNotEnableCustomVillageRulesOrCreateRaidChunkTickets() throws Exception {
        try (var input = getClass().getResourceAsStream("/fabrolia.common.mixins.json")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(config.contains("RaidVillageDebounceMixin"));
            assertFalse(config.contains("RaidRelocationColumnMixin"));
        }
        ClassNode node = new ClassNode();
        new ClassReader(RaidIslandDispatch.class.getName()).accept(node, 0);
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    assertNotEquals("addTicketWithRadius", call.name);
                    assertFalse(call.owner.endsWith("/RaidIslandMerge"));
                }
            }
        }
    }

    @Test
    void missingVillageStopsBeforeTheFirstWaveOnTheFirstTick() throws Exception {
        TestLevel level = TestLevel.create();
        Raid raid = freshRaid();
        raid.tick(level);
        assertTrue(raid.isStopped());
        assertFalse(raid.isActive());
        assertEquals(125, level.sectionsRead.size());
        // Reclaiming the original village on the next tick cannot revive a stopped raid.
        level.villages.add(SectionPos.of(CENTER));
        raid.tick(level);
        assertTrue(raid.isStopped());
    }

    @Test
    void missingVillageAfterAWaveLosesImmediately() throws Exception {
        TestLevel level = TestLevel.create();
        Raid raid = freshRaid();
        set(raid, "groupsSpawned", 1);
        raid.tick(level);
        assertTrue(raid.isLoss());
        assertFalse(raid.isStopped());
    }

    @Test
    void villageSectionAtVanillasVerticalBoundaryIsAValidRelocation() throws Exception {
        TestLevel level = TestLevel.create();
        SectionPos target = SectionPos.of(CENTER).offset(0, 2, 0);
        level.villages.add(target);
        Raid raid = freshRaid();
        raid.tick(level);
        assertEquals(target.center(), raid.getCenter());
        assertFalse(raid.isStopped());
        assertFalse(raid.isLoss());
    }

    @Test
    void villageJustOutsideVanillasCubeCannotRescueTheRaid() throws Exception {
        TestLevel level = TestLevel.create();
        level.villages.add(SectionPos.of(CENTER).offset(0, 3, 0));
        Raid raid = freshRaid();
        raid.tick(level);
        assertEquals(CENTER, raid.getCenter());
        assertTrue(raid.isStopped());
        assertEquals(125, level.sectionsRead.size());
        assertTrue(level.sectionsRead.stream().allMatch(s ->
                Math.abs(s.y() - SectionPos.of(CENTER).y()) <= 2));
    }

    private static Raid freshRaid() throws Exception {
        Raid raid = new Raid(CENTER, Difficulty.HARD);
        // Avoid spawn/player updates, which are unrelated to this first village decision.
        set(raid, "raidCooldownTicks", 299);
        return raid;
    }

    private static void set(Raid raid, String name, Object value) throws Exception {
        Field field = Raid.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(raid, value);
    }

    private static final class TestLevel extends ServerLevel {
        // Effective isVillage answers, including vanilla's one-section POI buffer.
        Set<SectionPos> villages;
        Set<SectionPos> sectionsRead;
        Raids raids;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        static TestLevel create() throws Exception {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            TestLevel level = (TestLevel) ((sun.misc.Unsafe) field.get(null)).allocateInstance(TestLevel.class);
            level.villages = new HashSet<>();
            level.sectionsRead = new HashSet<>();
            level.raids = new Raids();
            return level;
        }
        @Override public Difficulty getDifficulty() { return Difficulty.HARD; }
        @Override public boolean hasChunk(int x, int z) { return true; }
        @Override public boolean isVillage(BlockPos pos) { return villages.contains(SectionPos.of(pos)); }
        @Override public boolean isVillage(SectionPos section) {
            sectionsRead.add(section);
            return villages.contains(section);
        }
        @Override public Raids getRaids() { return raids; }
    }
}
