package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tick in which a player's Raid Omen expires stays on the player's island.
 * Moving that one tick to Main shifted the player by one tick against its
 * island: the self-sustaining raid farm converts Bad Omen in a one-tick village
 * window two ticks after the expiry, and the conversion then came one tick
 * after it (raid farm A/B, 2026-11-18). The fence the raid creation needs is
 * taken from the island instead ({@code RaidOwnership.runFenced}).
 */
final class RaidOmenExpiryVenueWiringTest {

    private static String source(String file) throws IOException {
        return Files.readString(Path.of("src/main/java/com/axalotl/fabrolia/common/" + file));
    }

    @Test
    void theExpiringTickKeepsItsIslandVenue() throws IOException {
        String ticks = source("regions/AsyncIslandPlayerTicks.java");
        assertFalse(ticks.contains("RaidOwnership.expiresThisTurn"),
                "an expiring Raid Omen must not make the player ineligible for its island tick");
    }

    @Test
    void theEffectTickTakesTheFenceWhereItRuns() throws IOException {
        String living = source("mixin/entity/LivingEntityMixin.java");
        assertTrue(living.contains("RaidOwnership.expiresThisTurn(player)"));
        assertTrue(living.contains("RaidOwnership.runFenced(player.level(), \"raid omen effect tick\""),
                "the island coordinator routes the expiring effect tick to fenced Main and parks");
    }
}
