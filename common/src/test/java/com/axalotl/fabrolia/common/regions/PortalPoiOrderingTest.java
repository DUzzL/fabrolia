package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit D-19: a non-portal POI callback at a position with a queued
 * nether_portal publication is queued behind it instead of running inline.
 */
final class PortalPoiOrderingTest {

    @Test
    void nonPortalCallbackWaitsBehindAQueuedPortalPublication() throws Exception {
        String text = source("common/mixin/world/ServerLevelPortalPreparationMixin.java");
        int method = text.indexOf("private void fabrolia$trackPortalPublication(");
        int nonPortal = text.indexOf("if (!poiType.is(PoiTypes.NETHER_PORTAL)) {", method);
        int pending = text.indexOf("this.fabrolia$pendingPortalPositions.containsKey(key)", nonPortal);
        int queued = text.indexOf("server.schedule(new TickTask(server.getTickCount(), callback));", pending);
        int inline = text.indexOf("callback.run();", queued);
        int count = text.indexOf("this.fabrolia$pendingPortalPositions.merge(key, 1, Integer::sum);", inline);
        int release = text.indexOf("this.fabrolia$pendingPortalPositions.computeIfPresent(key,", count);
        int submit = text.indexOf("this.fabrolia$portalPublications.submit(counted,", release);
        assertTrue(method >= 0 && nonPortal > method && pending > nonPortal && queued > pending
                        && inline > queued && count > inline && release > count && submit > release,
                "pending check before inline execution; portal publications count their position until done");
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
