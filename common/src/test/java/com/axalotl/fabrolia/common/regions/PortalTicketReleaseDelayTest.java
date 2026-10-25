package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit X-06: a closed preparation keeps its tickets one more Main tick. */
final class PortalTicketReleaseDelayTest {

    @Test
    void activeReleaseWaitsOneFullBoundary() throws Exception {
        String text = source("common/regions/NetherPortalPreparation.java");
        int service = text.indexOf("public static void serviceMain(MinecraftServer server)");
        int releaseOld = text.indexOf("while ((released = RELEASE_AFTER_NEXT_TICK.poll()) != null) released.releaseTickets();", service);
        int delayNew = text.indexOf("while ((released = RELEASE.poll()) != null) RELEASE_AFTER_NEXT_TICK.add(released);", releaseOld);
        assertTrue(service >= 0 && releaseOld > service && delayNew > releaseOld,
                "release what closed one boundary ago, then delay this boundary's closures");
        int retained = text.indexOf("private static Set<Request> retainedRequests()");
        assertTrue(text.indexOf("requests.addAll(RELEASE_AFTER_NEXT_TICK);", retained) > retained,
                "a stop still releases delayed tickets");
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
