package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.commands.CommandsMixin;
import com.axalotl.fabrolia.common.mixin.commands.ServerFunctionManagerMixin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Audit D-01: the bounded command/function fence waits seconds for a running unit, not 50 ms. */
final class CommandFenceBudgetTest {

    @Test
    void boundedFencesWaitSeconds() throws Exception {
        assertEquals(5_000_000_000L, budget(CommandsMixin.class));
        assertEquals(5_000_000_000L, budget(ServerFunctionManagerMixin.class));
    }

    private static long budget(Class<?> owner) throws Exception {
        Field field = owner.getDeclaredField("FENCE_TRY_NANOS");
        field.setAccessible(true);
        return field.getLong(null);
    }
}
