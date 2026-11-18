package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Vanilla's insertion-ordered goal set stays in place unless the flat path without regions is active. */
class GoalSelectorGoalSetTest {
    private boolean disabled;
    private boolean regions;

    @BeforeEach
    void remember() {
        disabled = FabroliaConfig.disabled;
        regions = FabroliaConfig.enableRegionThreading;
    }

    @AfterEach
    void restore() {
        FabroliaConfig.disabled = disabled;
        FabroliaConfig.enableRegionThreading = regions;
    }

    @Test
    void regionThreadingLeavesVanillasSetUntouched() throws Exception {
        FabroliaConfig.disabled = false;
        FabroliaConfig.enableRegionThreading = true;
        assertNull(goalsAfterConstructorTail());
    }

    @Test
    void aDisabledModLeavesVanillasSetUntouched() throws Exception {
        FabroliaConfig.disabled = true;
        FabroliaConfig.enableRegionThreading = false;
        assertNull(goalsAfterConstructorTail());
    }

    @Test
    void theFlatPathWithoutRegionsKeepsTheConcurrentSet() throws Exception {
        FabroliaConfig.disabled = false;
        FabroliaConfig.enableRegionThreading = false;
        Object goals = goalsAfterConstructorTail();
        assertNotNull(goals);
        // The set the flat path used before: ConcurrentCollections.newHashSet().
        assertEquals(ConcurrentCollections.newHashSet().getClass(), goals.getClass());
    }

    /** The mixin's field after its constructor-tail handler; null means "vanilla's own set was kept". */
    private static Object goalsAfterConstructorTail() throws Exception {
        GoalSelectorMixin mixin = new GoalSelectorMixin();
        Method tail = GoalSelectorMixin.class.getDeclaredMethod("fabrolia$concurrentGoalsOnlyWithoutRegions",
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        tail.setAccessible(true);
        tail.invoke(mixin, (Object) null);
        Field goals = GoalSelectorMixin.class.getDeclaredField("availableGoals");
        goals.setAccessible(true);
        return goals.get(mixin);
    }
}
