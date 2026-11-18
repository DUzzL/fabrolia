package com.axalotl.fabrolia.common.regions;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K2 (night campaign 2026-11-18): goal, navigation and behaviour throttles
 * that hold an absolute game time move with their entity's clock. Mixins are
 * not applied in unit tests, so this pins the wiring: each accessor names a
 * field the vanilla class really declares (a renamed field would only fail at
 * class load on a server), is registered, and is used by {@link TimerClocks}.
 */
final class TimerClocksGoalWiringTest {

    private record Timer(String accessor, String target, String field, Class<?> type) {}

    private static final List<Timer> TIMERS = List.of(
            new Timer("MeleeAttackGoalTimerAccessor",
                    "net.minecraft.world.entity.ai.goal.MeleeAttackGoal", "lastCanUseCheck", long.class),
            new Timer("PathNavigationTimerAccessor",
                    "net.minecraft.world.entity.ai.navigation.PathNavigation", "timeLastRecompute", long.class),
            new Timer("PathNavigationTimerAccessor",
                    "net.minecraft.world.entity.ai.navigation.PathNavigation", "lastTimeoutCheck", long.class),
            new Timer("MobTimerAccessor",
                    "net.minecraft.world.entity.Mob", "navigation",
                    net.minecraft.world.entity.ai.navigation.PathNavigation.class),
            new Timer("MobTimerAccessor",
                    "net.minecraft.world.entity.Mob", "goalSelector",
                    net.minecraft.world.entity.ai.goal.GoalSelector.class),
            new Timer("MobTimerAccessor",
                    "net.minecraft.world.entity.Mob", "targetSelector",
                    net.minecraft.world.entity.ai.goal.GoalSelector.class),
            new Timer("PatrolGoalTimerAccessor",
                    "net.minecraft.world.entity.monster.PatrollingMonster$LongDistancePatrolGoal",
                    "cooldownUntil", long.class),
            new Timer("BeeValidateFlowerGoalTimerAccessor",
                    "net.minecraft.world.entity.animal.bee.Bee$ValidateFlowerGoal", "lastValidateTick", long.class),
            new Timer("BeeValidateHiveGoalTimerAccessor",
                    "net.minecraft.world.entity.animal.bee.Bee$ValidateHiveGoal", "lastValidateTick", long.class),
            new Timer("BeePollinateGoalTimerAccessor",
                    "net.minecraft.world.entity.animal.bee.Bee$BeePollinateGoal", "unreachableFlowerCache",
                    it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap.class),
            new Timer("HarvestFarmlandTimerAccessor",
                    "net.minecraft.world.entity.ai.behavior.HarvestFarmland", "nextOkStartTime", long.class),
            new Timer("SleepInBedTimerAccessor",
                    "net.minecraft.world.entity.ai.behavior.SleepInBed", "nextOkStartTime", long.class),
            new Timer("GiveGiftToHeroTimerAccessor",
                    "net.minecraft.world.entity.ai.behavior.GiveGiftToHero", "timeSinceStart", long.class),
            new Timer("VillagerMakeLoveTimerAccessor",
                    "net.minecraft.world.entity.ai.behavior.VillagerMakeLove", "birthTimestamp", long.class),
            new Timer("AnimalMakeLoveTimerAccessor",
                    "net.minecraft.world.entity.ai.behavior.AnimalMakeLove", "spawnChildAtTime", long.class),
            new Timer("UseBonemealTimerAccessor",
                    "net.minecraft.world.entity.ai.behavior.UseBonemeal", "nextWorkCycleTime", long.class),
            new Timer("LongJumpToRandomPosTimerAccessor",
                    "net.minecraft.world.entity.ai.behavior.LongJumpToRandomPos", "prepareJumpStart", long.class),
            new Timer("PrepareRamNearestTargetTimerAccessor",
                    "net.minecraft.world.entity.ai.behavior.PrepareRamNearestTarget",
                    "reachedRamPositionTimestamp", Optional.class),
            new Timer("DoNothingTimerAccessor",
                    "net.minecraft.world.entity.ai.behavior.DoNothing", "endTimestamp", long.class),
            new Timer("LivingEntityTimerAccessor",
                    "net.minecraft.world.entity.LivingEntity", "lastKineticHitFeedbackTime", long.class),
            new Timer("LivingEntityTimerAccessor",
                    "net.minecraft.world.entity.LivingEntity", "recentKineticEnemies",
                    it.unimi.dsi.fastutil.objects.Object2LongMap.class));

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    @Test
    void everyAccessorTargetsARealVanillaField() throws Exception {
        for (Timer t : TIMERS) {
            Field f = Class.forName(t.target(), false, getClass().getClassLoader()).getDeclaredField(t.field());
            assertEquals(t.type(), f.getType(), t.target() + "." + t.field());
        }
    }

    @Test
    void everyAccessorIsRegisteredNamesItsFieldAndIsUsedByTimerClocks() throws IOException {
        String mixins = read("src/main/resources/fabrolia.common.mixins.json");
        String clocks = read("src/main/java/com/axalotl/fabrolia/common/regions/TimerClocks.java");
        for (Timer t : TIMERS) {
            assertTrue(mixins.contains("\"timer." + t.accessor() + "\""), t.accessor() + " registered");
            String accessor = read("src/main/java/com/axalotl/fabrolia/common/mixin/timer/" + t.accessor() + ".java");
            assertTrue(accessor.contains("@Accessor(\"" + t.field() + "\")"), t.accessor() + " -> " + t.field());
            String simple = t.target().substring(t.target().lastIndexOf('.') + 1);
            assertTrue(accessor.contains(simple.replace('$', '.')) || accessor.contains(t.target()),
                    t.accessor() + " targets " + t.target());
            assertTrue(clocks.contains(t.accessor()), "TimerClocks uses " + t.accessor());
        }
    }

    /** The stroll heals wrap the single {@code MutableLong.longValue()} read in
     *  {@code lambda$create$2}, whose only long argument is the brain timestamp. */
    @Test
    void strollHealTargetsTheTimestampLambdaOfEachBehaviour() throws IOException {
        for (String name : List.of("StrollToPoi", "StrollToPoiList", "StrollAroundPoi")) {
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            try (var in = getClass().getResourceAsStream("/net/minecraft/world/entity/ai/behavior/" + name + ".class")) {
                assertNotNull(in, name);
                new org.objectweb.asm.ClassReader(in).accept(node, 0);
            }
            org.objectweb.asm.tree.MethodNode lambda = node.methods.stream()
                    .filter(m -> m.name.equals("lambda$create$2")).findFirst().orElseThrow();
            org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(lambda.desc);
            assertEquals(1, java.util.Arrays.stream(args).filter(t -> t == org.objectweb.asm.Type.LONG_TYPE).count(),
                    name + " has one long argument (the timestamp)");
            assertEquals(org.objectweb.asm.Type.LONG_TYPE, args[args.length - 1], name);
            long reads = java.util.stream.StreamSupport.stream(lambda.instructions.spliterator(), false)
                    .filter(i -> i instanceof org.objectweb.asm.tree.MethodInsnNode m
                            && m.owner.equals("org/apache/commons/lang3/mutable/MutableLong")
                            && m.name.equals("longValue"))
                    .count();
            assertEquals(1L, reads, name + " reads its throttle once");
            String mixin = read("src/main/java/com/axalotl/fabrolia/common/mixin/timer/" + name + "TimerClockMixin.java");
            assertTrue(mixin.contains("method = \"lambda$create$2\"") && mixin.contains("require = 1, allow = 1"), name);
            assertTrue(read("src/main/resources/fabrolia.common.mixins.json")
                    .contains("\"timer." + name + "TimerClockMixin\""), name);
        }
    }

    @Test
    void aStrollStampFromAClockThatWasAheadIsDueNow() {
        long now = 10_000L;
        org.apache.commons.lang3.mutable.MutableLong next = new org.apache.commons.lang3.mutable.MutableLong(now + 80L);
        assertEquals(now + 80L, TimerClocks.healFutureStart(next, next.longValue(), now,
                TimerClocks.STROLL_TO_POI_MAX_LEAD), "a legal lead stays");
        next.setValue(now + 470L);
        long read = TimerClocks.healFutureStart(next, next.longValue(), now, TimerClocks.STROLL_TO_POI_MAX_LEAD);
        assertEquals(now - 1L, read);
        assertEquals(now - 1L, next.longValue());
        assertFalse(now <= read, "StrollToPoi / StrollAroundPoi: not on cooldown");
        assertTrue(now > read, "StrollToPoiList: due");
        next.setValue(now + 181L);
        assertEquals(now - 1L, TimerClocks.healFutureStart(next, next.longValue(), now,
                TimerClocks.STROLL_AROUND_POI_MAX_LEAD));
        next.setValue(now + 100L);
        assertEquals(now + 100L, TimerClocks.healFutureStart(next, next.longValue(), now,
                TimerClocks.STROLL_TO_POI_LIST_MAX_LEAD));
    }

    /** /data writes of live entities and spawners convert back at the read's clock readings. */
    @Test
    void liveReloadsGoBackToTheirClockAtOnce() throws IOException {
        String entity = read("src/main/java/com/axalotl/fabrolia/common/mixin/timer/EntityTimerClockMixin.java");
        int load = entity.indexOf("private void fabrolia$loadedTimersAreWall(");
        assertTrue(load > 0);
        String body = entity.substring(load, entity.indexOf("@WrapMethod", load));
        assertTrue(body.contains("this.levelCallback != EntityInLevelCallback.NULL"),
                "only an entity already in its level (chunk loads and dimension copies load before they are added)");
        assertTrue(body.contains("TimerClocks.reloadedInPlace(this, previous, level)"));
        String spawner = read("src/main/java/com/axalotl/fabrolia/common/mixin/timer/"
                + "TrialSpawnerBlockEntityTimerClockMixin.java");
        assertTrue(spawner.contains("@WrapMethod(method = \"loadAdditional\")"));
        assertTrue(spawner.contains("TimerClocks.reloadedInPlace(spawner, previous, level)"));
        assertTrue(spawner.indexOf("spawner.fabrolia$timerBasis()") < spawner.indexOf("original.call(input)"),
                "the basis is read before the load resets it to W");
    }

    @Test
    void mobThrottlesAreMovedFromShiftEntityOnBothSelectorsAndTheOwnNavigation() throws IOException {
        String clocks = read("src/main/java/com/axalotl/fabrolia/common/regions/TimerClocks.java");
        int shiftEntity = clocks.indexOf("public static void shiftEntity(");
        int shiftMob = clocks.indexOf("private static void shiftMob(");
        assertTrue(shiftEntity > 0 && shiftMob > shiftEntity);
        assertTrue(clocks.substring(shiftEntity, shiftMob).contains("shiftMob(mob, d, clamp)"));
        String mob = clocks.substring(shiftMob, clocks.indexOf("private static void shiftGoals("));
        assertTrue(mob.contains("fabrolia$ownNavigation()"), "getNavigation() answers the vehicle's");
        assertTrue(mob.contains("shiftGoals(m.fabrolia$goalSelector(), d, clamp)"));
        assertTrue(mob.contains("shiftGoals(m.fabrolia$targetSelector(), d, clamp)"));
    }
}
