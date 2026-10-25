package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-13: a crystal destroyed outside the central End island reaches the fight on Main. */
final class DragonCrystalRoutingTest {

    @Test
    void withoutARuntimeTheVanillaBodyRunsInline() {
        AtomicBoolean ran = new AtomicBoolean();
        DragonCrystalRouting.onCrystalDestroyed(null, null, () -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void fightHookRoutesThroughTheCentralOwner() throws Exception {
        String mixin = source("common/mixin/world/DragonFightTickMixin.java");
        assertTrue(mixin.contains("@WrapMethod(method = \"onCrystalDestroyed\")")
                && mixin.contains("DragonCrystalRouting.onCrystalDestroyed(")
                && mixin.contains("() -> original.call(crystal, source)"));
        String routing = source("common/regions/DragonCrystalRouting.java");
        int central = routing.indexOf("rs.islandOwnerOfChunk(ChunkPos.pack(0, 0))");
        int worker = routing.indexOf("Thread.currentThread() instanceof RegionWorkerThread", central);
        int own = routing.indexOf("rs.islandOwnerOfChunk(crystal.chunkPosition().pack()) == central", worker);
        int main = routing.indexOf("IslandCommands.mainHolds(central)", own);
        int later = routing.indexOf("RegionScheduler.enqueueMainOnlyLater(", main);
        int fence = routing.indexOf("AsyncIslandPlayerTicks.runFencedMainMutation(", later);
        assertTrue(central >= 0 && worker > central && own > worker && main > own && later > main && fence > later,
                "inline only on the central owner, otherwise later on Main under the fence");
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
