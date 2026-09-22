package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDimensionTransitionTest {

    @Test
    void onlyChangedDimensionBypassesTheTransientRemovedResult() {
        AtomicInteger vanillaChecks = new AtomicInteger();

        assertTrue(PlayerDimensionTransition.transitionCanInteractWithLevel(
                20.0F, false, Entity.RemovalReason.CHANGED_DIMENSION,
                () -> {
                    vanillaChecks.incrementAndGet();
                    return false;
                }));
        assertFalse(PlayerDimensionTransition.transitionCanInteractWithLevel(
                20.0F, false, Entity.RemovalReason.DISCARDED,
                () -> {
                    vanillaChecks.incrementAndGet();
                    return true;
                }));
        assertEquals(0, vanillaChecks.get(),
                "definitive removal reasons must not consult a stale vanilla result");
    }

    @Test
    void unsetRemovalRechecksTheCurrentVanillaState() {
        assertTrue(PlayerDimensionTransition.transitionCanInteractWithLevel(
                20.0F, false, null, () -> true));
        assertFalse(PlayerDimensionTransition.transitionCanInteractWithLevel(
                20.0F, false, null, () -> false));
    }

    @Test
    void deathAndSpectatorNeverGainTransitionInteraction() {
        assertFalse(PlayerDimensionTransition.transitionCanInteractWithLevel(
                0.0F, false, Entity.RemovalReason.CHANGED_DIMENSION, () -> true));
        assertFalse(PlayerDimensionTransition.transitionCanInteractWithLevel(
                20.0F, true, Entity.RemovalReason.CHANGED_DIMENSION, () -> true));
    }
}
