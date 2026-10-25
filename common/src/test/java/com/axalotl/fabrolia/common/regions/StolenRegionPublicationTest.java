package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit X-01: a stolen region body publishes currentRegion like workOnce:
 * before the claim, cleared after the release, never nested.
 */
final class StolenRegionPublicationTest {

    @Test
    void stealPublishesTheRegionAroundItsClaim() throws Exception {
        String text = source("common/regions/RegionWorkerPool.java");
        int body = text.indexOf("private boolean stealReadyEntryBody(ReadyEntry readyEntry)");
        int nested = text.indexOf("if (stealer != null && stealer.currentRegion != null) {", body);
        int publish = text.indexOf("if (stealer != null) stealer.currentRegion = r;", nested);
        int claim = text.indexOf("if (!r.tryClaim(Thread.currentThread(), claimDomain, claimEpoch)) {", publish);
        int lost = text.indexOf("if (stealer != null) stealer.currentRegion = null;", claim);
        int drain = text.indexOf("r.queue().drain(Integer.MAX_VALUE);", lost);
        int release = text.indexOf("r.release(Thread.currentThread());", drain);
        int unpublish = text.indexOf("if (stealer != null) stealer.currentRegion = null;", release);
        assertTrue(body >= 0 && nested > body && publish > nested && claim > publish
                        && lost > claim && drain > lost && release > drain && unpublish > release,
                "publish before claim, clear on a lost claim, drain published, unpublish after release");
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
