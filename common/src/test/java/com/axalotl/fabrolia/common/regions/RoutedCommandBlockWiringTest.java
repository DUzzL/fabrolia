package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source pins for routed island command blocks (night campaign 2026-11-18).
 *
 * <p>R1: the routed batch has exactly one execution lane, under the all-island
 * fence. A selector-free {@code scoreboard} line once ran fenceless on Main
 * while island workers iterated the same objective lists.
 */
class RoutedCommandBlockWiringTest {

    @Test
    void routedBatchExecutesNoBodyBeforeTheAllIslandFence() throws Exception {
        String commands = source("common/regions/IslandCommands.java");
        assertFalse(commands.contains("isGlobalNoFence"),
                "no routed command may be classified as fenceless");
        assertFalse(commands.contains("boolean fenceless") || commands.contains(".fenceless"),
                "the routed batch has no fenceless lane");
        String drain = section(commands, "private static void fabrolia$drainRoutedBatchEntered",
                "private static void execute");
        int fence = drain.indexOf("quiesceAllExcept(null)");
        int firstExecute = drain.indexOf("execute(");
        assertTrue(fence > 0, "the routed batch takes the all-island fence");
        assertTrue(firstExecute > fence,
                "every routed body executes after the all-island fence was taken");
        assertEquals(1, count(drain, "execute("),
                "the fenced loop is the only routed execution site");
    }

    @Test
    void routedDrainStaysAScheduledTickTaskSkippedOnceDone() throws Exception {
        // f068a2e: MinecraftServer.execute runs inline on a stopped server, which
        // drained on the routing region worker during shutdown.
        String commands = source("common/regions/IslandCommands.java");
        String park = section(commands, "private static boolean routeAndPark",
                "private static boolean parkCommandWaiterForGlobalFence");
        assertTrue(park.contains("sv.schedule(new net.minecraft.server.TickTask(sv.getTickCount(), () -> {")
                        && park.contains("if (!fut.isDone()) drainRoutedBatch();"),
                "the routed drain nudge is a tick task skipped once its receipt is done");
        assertFalse(park.contains("sv.execute("), "never execute the routed drain inline");
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, Math.max(0, start + startNeedle.length()));
        assertTrue(start >= 0 && end > start,
                () -> "source section not found: " + startNeedle + " .. " + endNeedle);
        return text.substring(start, end);
    }

    private static int count(String text, String needle) {
        int matches = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            matches++;
        }
        return matches;
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path common = root.resolve("common/src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate source: " + relative);
    }
}
