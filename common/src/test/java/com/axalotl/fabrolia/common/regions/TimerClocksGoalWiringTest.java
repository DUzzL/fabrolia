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
