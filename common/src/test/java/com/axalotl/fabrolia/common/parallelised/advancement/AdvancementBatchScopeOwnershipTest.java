package com.axalotl.fabrolia.common.parallelised.advancement;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdvancementBatchScopeOwnershipTest {


    @Test
    void cleanMainCapabilityBelongsOnlyToTheExactBatchOwner() throws Exception {
        AdvancementBatchScope.begin();
        try {
            assertTrue(AdvancementBatchScope.active());
            assertFalse(AdvancementBatchScope.rewardFunctionActive(),
                    "the broad batch owner is not itself a reward-body capability");

            AdvancementBatchScope.enterRewardBody();
            assertTrue(AdvancementBatchScope.rewardFunctionActive());
            assertTrue(AdvancementBatchScope.rewardFunctionActive());

            AtomicBoolean foreignActive = new AtomicBoolean(true);
            AtomicBoolean foreignClean = new AtomicBoolean(true);
            AtomicReference<Throwable> foreignEnd = new AtomicReference<>();
            Thread foreign = Thread.ofPlatform().start(() -> {
                foreignActive.set(AdvancementBatchScope.active());
                foreignClean.set(AdvancementBatchScope.rewardFunctionActive());
                try {
                    AdvancementBatchScope.end();
                } catch (Throwable expected) {
                    foreignEnd.set(expected);
                }
            });
            foreign.join();

            assertFalse(foreignActive.get());
            assertFalse(foreignClean.get());
            assertTrue(foreignEnd.get() instanceof IllegalStateException);
            assertTrue(AdvancementBatchScope.active(),
                    "a foreign end attempt must not retire Main's exact scope");
            assertTrue(AdvancementBatchScope.rewardFunctionActive(),
                    "a foreign end attempt must not retire Main's reward phase");

            AdvancementBatchScope.exitRewardBody();
            assertFalse(AdvancementBatchScope.rewardFunctionActive());
            assertFalse(AdvancementBatchScope.rewardFunctionActive());
        } finally {
            AdvancementBatchScope.end();
        }
        assertFalse(AdvancementBatchScope.active());
        assertFalse(AdvancementBatchScope.rewardFunctionActive());
    }

    @Test
    void nestedBeginFailsWithoutReplacingTheExactOwner() {
        AdvancementBatchScope.begin();
        try {
            assertThrows(IllegalStateException.class, AdvancementBatchScope::begin);
            assertTrue(AdvancementBatchScope.active());
            assertFalse(AdvancementBatchScope.rewardFunctionActive());
        } finally {
            AdvancementBatchScope.end();
        }
        assertFalse(AdvancementBatchScope.active());
    }

    @Test
    void serialRewardWrapperRetiresTheNarrowPhaseAndRethrowsExactPrimary()
            throws Exception {
        Method wrapper = AdvancementBuffer.class.getDeclaredMethod(
                "fabrolia$runRewardBody", Runnable.class);
        wrapper.setAccessible(true);
        RuntimeException primary = new RuntimeException("exact reward failure");
        AtomicBoolean sawNarrowCapability = new AtomicBoolean();

        AdvancementBatchScope.begin();
        try {
            InvocationTargetException thrown = assertThrows(
                    InvocationTargetException.class,
                    () -> wrapper.invoke(null, (Runnable) () -> {
                        sawNarrowCapability.set(
                                AdvancementBatchScope.rewardFunctionActive()
                                        && AdvancementBatchScope.rewardFunctionActive());
                        throw primary;
                    }));
            assertSame(primary, thrown.getCause());
            assertTrue(sawNarrowCapability.get());
            assertTrue(AdvancementBatchScope.active(),
                    "reward cleanup must retain the broad batch owner");
            assertFalse(AdvancementBatchScope.rewardFunctionActive(),
                    "reward cleanup must retire the narrow capability");
            assertFalse(AdvancementBatchScope.rewardFunctionActive());
        } finally {
            AdvancementBatchScope.end();
        }
        assertFalse(AdvancementBatchScope.active());
    }

    @Test
    void batchEndSurfacesARewardPhaseLeakAfterRetiringTheOwner() {
        AdvancementBatchScope.begin();
        AdvancementBatchScope.enterRewardBody();

        IllegalStateException leaked = assertThrows(
                IllegalStateException.class, AdvancementBatchScope::end);
        assertTrue(leaked.getMessage().contains("active reward body"));
        assertFalse(AdvancementBatchScope.active());
        assertFalse(AdvancementBatchScope.rewardFunctionActive());
        assertFalse(AdvancementBatchScope.rewardFunctionActive());

        // The invariant is fail-stop, but its mandatory teardown must leave no
        // stale owner which could poison a later test/server cleanup path.
        AdvancementBatchScope.begin();
        AdvancementBatchScope.end();
    }

}
