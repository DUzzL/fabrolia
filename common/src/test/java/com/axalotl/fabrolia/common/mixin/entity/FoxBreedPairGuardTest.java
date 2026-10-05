package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.mixin.entity.breed.FoxBreedGoalMixin;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the production fox double-breed guard handlers on one shared clock. */
class FoxBreedPairGuardTest {
    private static sun.misc.Unsafe unsafe;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        unsafe = (sun.misc.Unsafe) field.get(null);
    }

    static class Goal extends FoxBreedGoalMixin {
        Goal() { super(null, 1.0); }
    }

    @Test
    void secondFoxOfAPairIsGuardedAndTheEntryDoesNotOutliveTheGuard() throws Exception {
        PrimaryLevelData data = (PrimaryLevelData) unsafe.allocateInstance(PrimaryLevelData.class);
        ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        set(Level.class, level, "levelData", data);
        Fox a = fox(), b = fox(), c = fox(), d = fox();
        Map<?, ?> pairs = (Map<?, ?>) staticField("breedingPairs");
        pairs.clear();

        data.setGameTime(100L);
        assertFalse(breed(goal(level, a, b)), "first fox of the pair breeds");
        assertTrue(breed(goal(level, b, a)), "its partner in the same tick is the double breed");

        data.setGameTime(200L);
        assertFalse(breed(goal(level, c, d)), "an unrelated pair breeds");
        assertEquals(1, pairs.size(), "the first pair's guard has expired and is dropped");
        pairs.clear();
    }

    private static boolean breed(Goal goal) throws Exception {
        Method m = FoxBreedGoalMixin.class.getDeclaredMethod("preventDoubleBreed", CallbackInfo.class);
        m.setAccessible(true);
        CallbackInfo ci = new CallbackInfo("breed", true);
        m.invoke(goal, ci);
        return ci.isCancelled();
    }

    private static Goal goal(ServerLevel level, Fox self, Fox partner) throws Exception {
        Goal goal = (Goal) unsafe.allocateInstance(Goal.class);
        set(BreedGoal.class, goal, "level", level);
        set(BreedGoal.class, goal, "animal", self);
        set(BreedGoal.class, goal, "partner", partner);
        return goal;
    }

    private static Fox fox() throws Exception {
        Fox fox = (Fox) unsafe.allocateInstance(Fox.class);
        set(Entity.class, fox, "uuid", UUID.randomUUID());
        return fox;
    }

    private static Object staticField(String name) throws Exception {
        Field f = FoxBreedGoalMixin.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
