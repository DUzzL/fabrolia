package com.axalotl.fabrolia.common.mixin.utils;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-18: the canceller seeds its mod flags from the loader, not only from mixin names. */
final class MixinCancellerModDetectionTest {

    @Test
    void withoutALoaderNoModIsReported() {
        assertFalse(MixinCanceller.modLoaded("lithium"), "the test classpath has no mod loader");
    }

    @Test
    void lithiumGatedMixinStaysWhenTheNameWasSeen() {
        MixinCanceller canceller = new MixinCanceller();
        String gated = "com.axalotl.fabrolia.common.mixin.lithium.LithiumServerLevel";
        assertTrue(canceller.shouldCancel(List.of(), gated), "no Lithium detected: gated mixin cancelled");
        canceller.shouldCancel(List.of(), "net.caffeinemc.mods.lithium.mixin.some.Mixin");
        assertFalse(canceller.shouldCancel(List.of(), gated), "a Lithium mixin name still marks Lithium present");
    }

    @Test
    void flagsAreSeededFromTheModList() throws Exception {
        String text = null;
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null && text == null; i++, root = root.getParent()) {
            Path candidate = root.resolve("common/src/main/java/com/axalotl/fabrolia/common/mixin/utils/MixinCanceller.java");
            if (Files.isRegularFile(candidate)) text = Files.readString(candidate);
        }
        assertTrue(text != null && text.contains("private boolean LITHIUM = modLoaded(\"lithium\");")
                && text.contains("private boolean VMP = modLoaded(\"vmp\");")
                && text.contains("private boolean C2ME = modLoaded(\"c2me\");"));
        assertTrue(text.contains("net.fabricmc.loader.api.FabricLoader")
                && text.contains("net.neoforged.fml.loading.LoadingModList"));
    }
}
