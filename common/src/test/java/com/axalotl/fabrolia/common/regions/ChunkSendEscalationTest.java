package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit A-11: the chunk sender's escalation never waits for a running island unit. */
final class ChunkSendEscalationTest {

    @Test
    void escalationTakesTheClaimOnlyWithoutWaiting() throws Exception {
        String text = source("common/regions/ChunkDelivery.java");
        int escalate = text.indexOf("IslandCommands.tryMainClaim(island, \"chunk-send escalation\")");
        int build = text.indexOf("new ClientboundLevelChunkWithLightPacket(live,", escalate);
        int lease = text.indexOf("island.pendingChunkSnapshots.add(key);", build);
        assertTrue(escalate >= 0 && build > escalate && lease > build,
                "a busy island falls through to the normal lease");
        assertFalse(text.contains("IslandCommands.mainClaimScoped(island)"), "no spinning claim in the sender");
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        throw new AssertionError("source tree not available");
    }
}
