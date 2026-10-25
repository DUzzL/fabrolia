package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-14: Main rejoining a loaded raider to its raid holds the raid island's claim. */
final class RaiderLoadClaimTest {

    @Test
    void aRaiderWithoutRaidLoadsInline() {
        AtomicBoolean loaded = new AtomicBoolean();
        RaiderLoadClaim.runLoad(null, OptionalInt.empty(), () -> loaded.set(true));
        assertTrue(loaded.get());
    }

    @Test
    void loadClaimsTheRaidIslandOnMain() throws Exception {
        String mixin = source("common/mixin/entity/RaiderMixin.java");
        assertTrue(mixin.contains("@WrapMethod(method = \"readAdditionalSaveData\")")
                && mixin.contains("input.getInt(\"RaidId\")")
                && mixin.contains("RaiderLoadClaim.runLoad(level,"));
        String helper = source("common/regions/RaiderLoadClaim.java");
        int owner = helper.indexOf("rs.islandOwnerOfChunk(ChunkPos.pack(raid.getCenter()))");
        int held = helper.indexOf("IslandCommands.mainHolds(owner)", owner);
        int claim = helper.indexOf("IslandCommands.mainClaim(owner);", held);
        int load = helper.indexOf("load.run();", claim);
        int release = helper.indexOf("IslandCommands.mainRelease(owner);", load);
        assertTrue(owner >= 0 && held > owner && claim > held && load > claim && release > load);
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
