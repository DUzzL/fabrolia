package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-17: NeoForge starts, creates level runtimes and stops like Fabric. */
final class NeoForgeLifecycleParityTest {

    @Test
    void neoForgeLifecycleMatchesFabric() throws Exception {
        String mod = source("neoforge/src/main/java/com/axalotl/fabrolia/neoforge/FabroliaNeoForge.java");
        int start = mod.indexOf("public void onServerAboutToStart(ServerAboutToStartEvent event)");
        int load = mod.indexOf("loadConfig();", start);
        int scheduler = mod.indexOf("RegionScheduler.start(event.getServer());", load);
        assertTrue(start >= 0 && load > start && scheduler > load, "config and scheduler before loadLevel");
        assertFalse(mod.contains("(ServerStartingEvent event)"), "nothing waits for the post-load start event");
        assertTrue(mod.contains("public void onLevelLoad(LevelEvent.Load event)")
                && mod.contains("RegionScheduler.forLevel(sl);"), "each level runtime exists before its chunks load");
        assertFalse(mod.contains("(ServerStoppingEvent event)"), "the runtime stops at stopServer head instead");

        String stop = source("neoforge/src/main/java/com/axalotl/fabrolia/neoforge/mixin/server/ServerStopRuntimeMixin.java");
        assertTrue(stop.contains("@Inject(method = \"stopServer\", at = @At(\"HEAD\"))")
                && stop.contains("ParallelProcessor.stopServerRuntime();"));
        assertTrue(source("neoforge/src/main/resources/fabrolia.neoforge.mixins.json")
                .contains("\"server.ServerStopRuntimeMixin\""));

        String config = source("neoforge/src/main/java/com/axalotl/fabrolia/neoforge/config/FabroliaConfig.java");
        assertTrue(config.contains("FabroliaConfig.crossDimLinkedPairs = new ArrayList<>(pairEntries);")
                && config.contains("FabroliaConfig.synchronizedEntities = entities;"), "empty lists stay empty");

        String toml = source("neoforge/src/main/resources/META-INF/neoforge.mods.toml");
        int c2me = toml.indexOf("modId = \"c2me\"");
        assertTrue(c2me > 0 && toml.indexOf("versionRange = \"(,0.4.2-alpha.0.88)\"", c2me) > c2me,
                "same C2ME floor as Fabric's breaks");
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
