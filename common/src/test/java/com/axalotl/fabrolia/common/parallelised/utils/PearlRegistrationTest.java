package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.Entity.RemovalReason;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task #28 — pins the registered-pearl retention policy to vanilla's
 * {@code ThrownEnderpearl.onRemoval} rule, using the REAL {@code RemovalReason}
 * enum rather than a copy of it.
 */
class PearlRegistrationTest {

    @Test
    void aLivePearlAlwaysBelongs() {
        assertFalse(PearlRegistration.noLongerBelongsToOwner(null),
                "a pearl with no removal reason is alive and must stay registered");
    }

    @Test
    void unloadedWithPlayerPearlsAreNeverDropped() {
        // These belong to the player's SAVED pearl list. Dropping them would
        // delete in-flight ender pearls across a disconnect.
        assertFalse(PearlRegistration.noLongerBelongsToOwner(RemovalReason.UNLOADED_WITH_PLAYER),
                "UNLOADED_WITH_PLAYER must remain registered — vanilla onRemoval skips it");
    }

    @Test
    void everyOtherRemovalReasonDeregisters() {
        for (RemovalReason reason : RemovalReason.values()) {
            if (reason == RemovalReason.UNLOADED_WITH_PLAYER) continue;
            assertTrue(PearlRegistration.noLongerBelongsToOwner(reason),
                    reason + " is deregistered by vanilla onRemoval, so it must not stay registered");
        }
    }

    /**
     * Vanilla-drift canary. The policy above is derived from the exact set of
     * removal reasons in 26.2. If Minecraft adds, removes or renames one, this
     * fails and the policy must be re-derived from {@code ThrownEnderpearl.onRemoval}
     * rather than assumed to still hold.
     */
    @Test
    void removalReasonEnumShapeIsUnchanged() {
        assertEquals(
                EnumSet.of(RemovalReason.KILLED,
                        RemovalReason.DISCARDED,
                        RemovalReason.UNLOADED_TO_CHUNK,
                        RemovalReason.UNLOADED_WITH_PLAYER,
                        RemovalReason.CHANGED_DIMENSION),
                EnumSet.allOf(RemovalReason.class),
                "Minecraft's RemovalReason set changed — re-derive the pearl retention policy "
                        + "from ThrownEnderpearl.onRemoval before updating this expectation");
    }
}
