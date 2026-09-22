package com.axalotl.fabrolia.common.regions;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * HUD name colouring: Overworld green, Nether red, End light purple.
 *
 * <p>The colour is resolved from the player's CURRENT level on every HUD update
 * (once per second) rather than cached anywhere, so walking through a portal
 * recolours the name on the next line without state to invalidate. These tests exercise the
 * dimension-to-colour mapping.
 */
class TpsHudDimColorTest {

    @Test
    void overworldIsGreen() {
        assertEquals(ChatFormatting.GREEN, TpsHud.dimColor(Level.OVERWORLD));
    }

    @Test
    void netherIsRed() {
        assertEquals(ChatFormatting.RED, TpsHud.dimColor(Level.NETHER));
    }

    @Test
    void endIsLightPurple() {
        assertEquals(ChatFormatting.LIGHT_PURPLE, TpsHud.dimColor(Level.END));
    }

    /** A modded dimension must not silently borrow one of the three meanings. */
    @Test
    void moddedDimensionsFallBackToWhite() {
        ResourceKey<Level> custom = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("examplemod", "twilight"));
        assertEquals(ChatFormatting.WHITE, TpsHud.dimColor(custom));
    }

    /** The three colours must stay mutually distinguishable — that is the point. */
    @Test
    void theThreeVanillaDimensionsAreAllDifferent() {
        ChatFormatting ow = TpsHud.dimColor(Level.OVERWORLD);
        ChatFormatting ne = TpsHud.dimColor(Level.NETHER);
        ChatFormatting en = TpsHud.dimColor(Level.END);
        assertNotEquals(ow, ne);
        assertNotEquals(ow, en);
        assertNotEquals(ne, en);
    }

}
