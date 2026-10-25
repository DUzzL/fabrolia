package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit C-09: the region path clears the islands' ServerCore random-tick snapshots. */
final class ServerCoreSnapshotClearTest {

    @Test
    void regionPathClearsTheServerCoreSnapshotBeforeReturning() throws Exception {
        String text = source("common/mixin/server/ServerChunkCacheMixin.java");
        int gate = text.indexOf("if (!fabrolia$servercoreRandomTickDistanceAffectsRandomTicks()) {");
        int clear = text.indexOf("fabrolia$publishIslandRandomTickSnapshots(List.of());", gate);
        int positions = text.indexOf("forEachEntityTickingChunk(positions::add);", clear);
        assertTrue(gate >= 0 && clear > gate && positions > clear);
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
