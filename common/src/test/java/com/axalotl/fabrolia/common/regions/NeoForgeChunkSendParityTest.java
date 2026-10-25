package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.platform.MinecraftPlatform;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit A-12: Fabrolia's chunk sender keeps NeoForge's aux-light bundle and ChunkWatchEvent.Sent. */
final class NeoForgeChunkSendParityTest {

    @Test
    void vanillaPlatformFiresNothing() {
        MinecraftPlatform fabric = (source, node, level) -> false;
        assertFalse(fabric.firesChunkSent());
    }

    @Test
    void senderUsesTheLoaderPacketAndEvent() throws Exception {
        String delivery = source("common/src/main/java/com/axalotl/fabrolia/common/regions/ChunkDelivery.java");
        assertTrue(delivery.contains("this.packet = com.axalotl.fabrolia.common.platform.PlatformUtils.chunkPacket(chunk, packet);"),
                "the sealed packet is the loader's packet");
        int commit = delivery.indexOf("owner.batches++;");
        int queue = delivery.indexOf("for (LevelChunk sent : sentChunks) owner.pendingChunkSent.add(sent);", commit);
        assertTrue(commit >= 0 && queue > commit, "events queue only for committed sends");
        int drain = delivery.indexOf("private static void fireChunkSentEvents(");
        int tryClaim = delivery.indexOf("IslandCommands.tryMainClaim(owner, \"chunk sent event\")", drain);
        int fire = delivery.indexOf("PlatformUtils.chunkSent(p, chunk, level);", tryClaim);
        int busy = delivery.indexOf("return;", fire);
        assertTrue(drain >= 0 && tryClaim > drain && fire > tryClaim && busy > fire,
                "the event fires under the owner's claim, never waiting for it");

        String neo = source("neoforge/src/main/java/com/axalotl/fabrolia/neoforge/platform/NeoForgeMinecraftPlatform.java");
        assertTrue(neo.contains("chunk.getAuxLightManager(chunk.getPos()).sendLightDataTo(packet)")
                && neo.contains("EventHooks.fireChunkSent(player, chunk, level)"));
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
