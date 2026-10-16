package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Island TPS is a reader-side window. Before 3.1.3 only a reader advanced it, so
 * the first {@code /fabrolia regions top} after an island was created (or after
 * nobody had looked at it) read the empty window as 0 TPS and called the island
 * "Idle". The HUD tick now samples every island once per second, independent of
 * subscribers, and an empty window is NaN ("measuring"), never 0.
 */
final class IslandTpsSamplingWiringTest {

    @Test
    void theOncePerSecondMainSampleAlsoSamplesEveryIslandBeforeTheSubscriberCheck() throws Exception {
        String hud = source("TpsHud.java");
        int mainSample = hud.indexOf("boolean mainSampleUpdated = MAIN_TPS_WINDOW.update(gt, now);");
        int notUpdated = hud.indexOf("if (!mainSampleUpdated) {", mainSample);
        int islands = hud.indexOf("IslandMetrics.sampleAll();", notUpdated);
        int subscribers = hud.indexOf("if (SUBSCRIBERS.isEmpty()) {", notUpdated);
        assertTrue(mainSample >= 0 && notUpdated > mainSample && islands > notUpdated
                        && subscribers > islands,
                "islands must be sampled once per second even with no HUD subscriber");

        String metrics = source("IslandMetrics.java");
        int sampleAll = metrics.indexOf("static void sampleAll()");
        int asyncOnly = metrics.indexOf("if (!rs.asyncActive) continue;", sampleAll);
        int read = metrics.indexOf("actualTps(p);", asyncOnly);
        assertTrue(sampleAll >= 0 && asyncOnly > sampleAll && read > asyncOnly);
    }

    @Test
    void anUnmeasuredIslandWindowIsNaNNotZero() throws Exception {
        assertTrue(source("IslandRegistry.java").contains(
                "final TpsWindow tpsWindow = new TpsWindow(TpsWindow.ONE_SECOND_NANOS, Double.NaN);"));
        String metrics = source("IslandMetrics.java");
        int classify = metrics.indexOf("static State classify(");
        int measuring = metrics.indexOf("if (Double.isNaN(tps)) return State.MEASURING;", classify);
        int idle = metrics.indexOf("State.IDLE", measuring);
        assertTrue(classify >= 0 && measuring > classify && idle > measuring,
                "an unmeasured island may not be classified Idle");
    }

    private static String source(String file) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        String relative = "com/axalotl/fabrolia/common/regions/" + file;
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        Assumptions.assumeTrue(false, "source tree not available");
        throw new AssertionError("unreachable");
    }
}
