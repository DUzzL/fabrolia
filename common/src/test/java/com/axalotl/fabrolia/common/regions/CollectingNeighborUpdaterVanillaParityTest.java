package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins vanilla's negative max-chained-neighbor-updates = unlimited contract. */
final class CollectingNeighborUpdaterVanillaParityTest {

    @Test
    void negativeNeighborUpdateLimitRemainsUnlimited() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/axalotl/fabrolia/common/"
                + "mixin/world/CollectingNeighborUpdaterMixin.java"));
        int limitGuard = source.indexOf("this.maxChainedNeighborUpdates >= 0");
        int countGuard = source.indexOf("f.count >= this.maxChainedNeighborUpdates", limitGuard);
        assertTrue(limitGuard >= 0 && countGuard > limitGuard,
                "the per-thread replica must keep vanilla's negative-limit bypass");
    }
}
