package com.axalotl.fabrolia.common.parallelised.advancement;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-24: a fire-time NBT compare of a player this thread does not own is routed. */
final class OffOwnerPlayerNbtRouteTest {

    @Test
    void foreignPlayerNbtCompareRaisesTheRouteSignal() throws Exception {
        String text = source("common/src/main/java/com/axalotl/fabrolia/common/parallelised/advancement/AdvancementCacheContext.java");
        int method = text.indexOf("public static void checkFireTimeCompare(Entity entity, CompoundTag expected)");
        int pearls = text.indexOf("expected.contains(ENDER_PEARLS)", method);
        int owner = text.indexOf("!com.axalotl.fabrolia.common.regions.PlayerTurnOwnership.currentThreadOwns(player)", pearls);
        int signal = text.indexOf("throw RouteSignal.INSTANCE;", owner);
        assertTrue(method >= 0 && pearls > method && owner > pearls && signal > owner);
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
