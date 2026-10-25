package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit C-01: random ticks of one region never run on several threads at once
 * (sync mode, ServerCore mode), and the flat path ticks them serially on Main.
 * Neighbouring chunks share LevelChunkSection counters and heightmaps.
 */
final class SerialRandomTickTest {

    @Test
    void randomTicksRunSeriallyInEveryNonIslandPath() throws Exception {
        String text = source("common/mixin/server/ServerChunkCacheMixin.java");
        int positions = text.indexOf("private CompletableFuture<Void> fabrolia$submitBlockTickPositions");
        int serial = text.indexOf("fabrolia$runChunksSerial(list, action);", positions);
        int next = text.indexOf("@Unique", positions + 1);
        assertTrue(positions >= 0 && serial > positions && serial < next,
                "a region's random-tick positions run serially");
        assertTrue(text.contains("fabrolia$submitPerRegion(tickChunks, tickingChunkConsumer, true)"),
                "the ServerCore random-tick path submits serial regions");
        int flat = text.indexOf("Random ticks of the flat (region threading off) path run serially");
        int loop = text.indexOf("for (LevelChunk c : fabrolia$mainOwnedOnly(collected))", flat);
        assertTrue(flat >= 0 && loop > flat, "the flat path ticks random-tick chunks on Main");
        assertFalse(text.contains("() -> tickingChunkConsumer.accept(tickChunks.get(chunkIndex))"),
                "the flat random-tick pool fan-out is gone");
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
