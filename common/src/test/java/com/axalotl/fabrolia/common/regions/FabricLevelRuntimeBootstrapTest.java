package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit D-07: on Fabric the scheduler starts before createLevels, so each level's
 * runtime is created on ServerLevelEvents.LOAD, before prepareLevels makes
 * saved-ticket chunks FULL; onChunkFullChange only looks the runtime up.
 */
final class FabricLevelRuntimeBootstrapTest {

    @Test
    void fabricCreatesTheLevelRuntimeOnLevelLoad() throws Exception {
        String fabric = source("fabric/src/main/java/com/axalotl/fabrolia/fabric/FabroliaFabric.java");
        int starting = fabric.indexOf("ServerLifecycleEvents.SERVER_STARTING.register");
        int load = fabric.indexOf("ServerLevelEvents.LOAD.register(");
        int create = fabric.indexOf("(server, world) -> RegionScheduler.forLevel(world)", load);
        assertTrue(starting >= 0 && load >= 0 && create > load,
                "the level runtime must exist before the first FULL transition of that level");
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
