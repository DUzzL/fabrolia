package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-08: NeoForge's POI unload hook runs inside the POI column fence. */
final class NeoForgePoiUnloadFenceTest {

    @Test
    void commonHooksUnloadIsRoutedThroughTheColumnFence() throws Exception {
        String mixin = source("neoforge/src/main/java/com/axalotl/fabrolia/neoforge/mixin/world/CommonHooksPoiUnloadMixin.java");
        assertTrue(mixin.contains("@Mixin(CommonHooks.class)"));
        assertTrue(mixin.contains("@WrapMethod(method = \"onChunkUnload(Lnet/minecraft/world/entity/ai/village/poi/PoiManager;\""));
        assertTrue(mixin.contains("((FabroliaPoiLoadGate) poiManager).fabrolia$unloadPoiColumn(")
                && mixin.contains("() -> original.call(poiManager, chunk)"),
                "flush and remove both run as the fenced unload body");
        String config = source("neoforge/src/main/resources/fabrolia.neoforge.mixins.json");
        assertTrue(config.contains("\"world.CommonHooksPoiUnloadMixin\"") && config.contains("\"defaultRequire\": 1"),
                "registered with require 1, so a renamed hook fails loud");
        String hooks = neoForgeHooks();
        if (hooks != null) {
            assertTrue(hooks.contains("public static void onChunkUnload(PoiManager poiManager, ChunkAccess chunkAccess)"),
                    "the wrapped NeoForge hook must exist with this signature");
        }
    }

    /** CommonHooks source from the NeoForge sources jar, if Gradle has it cached. */
    private static String neoForgeHooks() throws Exception {
        Path cache = Paths.get(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1",
                "net.neoforged", "neoforge");
        if (!Files.isDirectory(cache)) return null;
        try (var jars = Files.walk(cache)) {
            Path jar = jars.filter(p -> p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(p -> p.toString().contains("26.3.0.38")).findFirst().orElse(null);
            if (jar == null) return null;
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                var entry = zip.getEntry("net/neoforged/neoforge/common/CommonHooks.java");
                return entry == null ? null : new String(zip.getInputStream(entry).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
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
