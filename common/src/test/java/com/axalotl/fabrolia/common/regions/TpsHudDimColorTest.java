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
 * recolours the name on the next line with no state to invalidate. This pins the
 * mapping itself; the "recolours on dimension change" property follows from
 * there being no cache, which is pinned separately below.
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

    /**
     * Source pin: the colour must never be cached against a player or an island.
     * A cache is exactly how "the name stayed green after I walked into the
     * Nether" would come back, and it would be invisible in the mapping tests
     * above.
     */
    @Test
    void colourIsNeverCached() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("").toAbsolutePath();
        java.nio.file.Path src = null;
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            java.nio.file.Path direct = root.resolve("src/main/java/com/axalotl/fabrolia");
            java.nio.file.Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia");
            if (java.nio.file.Files.isDirectory(direct)) { src = direct; break; }
            if (java.nio.file.Files.isDirectory(viaCommon)) { src = viaCommon; break; }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(src != null, "source tree not available");
        String text = java.nio.file.Files.readString(src.resolve("common/regions/TpsHud.java"));
        int at = text.indexOf("ChatFormatting dimColor");
        org.junit.jupiter.api.Assertions.assertTrue(at > 0, "dimColor not found");
        org.junit.jupiter.api.Assertions.assertFalse(
                text.contains("colorCache") || text.contains("colourCache"),
                "the dimension colour must be resolved live, never cached — a cache is how "
                        + "a name would stay green after walking into the Nether");
    }
}
