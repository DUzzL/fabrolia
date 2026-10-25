package com.axalotl.fabrolia.common.utils;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit D-11: a POI unpack on a loader thread outside the pool is not refused by closed pool windows. */
final class PoiForeignLoaderAdmissionTest {

    @Test
    void aPlainThreadWithoutServerIsAForeignLoader() {
        assertTrue(PoiChunkLoadFence.foreignLoaderThread());
    }

    @Test
    void foreignLoadersCheckOnlyTheSelectedFailure() throws Exception {
        String text = null;
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null && text == null; i++, root = root.getParent()) {
            Path candidate = root.resolve("common/src/main/java/com/axalotl/fabrolia/common/utils/PoiChunkLoadFence.java");
            if (Files.isRegularFile(candidate)) text = Files.readString(candidate);
        }
        int foreign = text.indexOf("if (RegionScheduler.isActive() && foreignLoaderThread()) {");
        int check = text.indexOf("RegionScheduler.throwWorkerFailureIfAny();", foreign);
        int run = text.indexOf("original.run();", check);
        int pool = text.indexOf("RegionScheduler.runSuccessBodyIfHealthy(original);", run);
        assertTrue(foreign >= 0 && check > foreign && run > check && pool > run,
                "pool threads keep the success-body cut, foreign loaders only the W check");
    }
}
