package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.common.platform.MinecraftPlatform;
import com.axalotl.fabrolia.common.platform.PearlLanding;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit A-13: the Main pearl continuation asks the loader for its landing
 * decision before the endermite roll; NeoForge fires EntityTeleportEvent.EnderPearl.
 */
final class PearlLandingHookTest {

    @Test
    void vanillaLandingNeverCancels() {
        MinecraftPlatform fabric = (source, node, level) -> false;
        Vec3 previous = new Vec3(1, 2, 3);
        PearlLanding landing = fabric.onEnderPearlLand(null, null, new Vec3(9, 9, 9), null, previous);
        assertFalse(landing.canceled());
        assertEquals(previous, landing.target(), "vanilla teleports to the pearl's previous position");
        assertEquals(5.0F, landing.damage());
        assertFalse(landing.damageInArrivalLevel(), "vanilla hurts in the player's level");
    }

    @Test
    void continuationAsksTheLoaderBeforeTheEndermite() throws Exception {
        String text = source("common/src/main/java/com/axalotl/fabrolia/common/mixin/entity/ThrownEnderpearlMixin.java");
        int allowed = text.indexOf("|| !fabrolia$ownerTeleportAllowed(player, level)) {");
        int hook = text.indexOf("PlatformUtils.onEnderPearlLand(", allowed);
        int cancel = text.indexOf("if (landing.canceled())", hook);
        int endermite = text.indexOf("if (endermiteRoll < 0.05F", cancel);
        int teleport = text.indexOf("level, landing.target(), Vec3.ZERO", endermite);
        int damage = text.indexOf("landing.damage());", teleport);
        assertTrue(allowed >= 0 && hook > allowed && cancel > hook && endermite > cancel
                        && teleport > endermite && damage > teleport,
                "event, cancel, endermite, teleport to the event target, event damage");
    }

    @Test
    void neoForgeFiresTheEnderPearlEvent() throws Exception {
        String text = source("neoforge/src/main/java/com/axalotl/fabrolia/neoforge/platform/NeoForgeMinecraftPlatform.java");
        assertTrue(text.contains("EventHooks.onEnderPearlLand(")
                && text.contains("player, hitPosition.x, hitPosition.y, hitPosition.z, pearl, 5.0F, hitResult")
                && text.contains("new PearlLanding(event.isCanceled(), event.getTarget(), event.getAttackDamage(), true)"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
        }
        throw new AssertionError("source tree not available");
    }
}
