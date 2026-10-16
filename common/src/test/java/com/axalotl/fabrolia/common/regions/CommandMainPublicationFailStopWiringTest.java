package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source pins for command work published to Minecraft's raw Main queue. */
final class CommandMainPublicationFailStopWiringTest {


    @Test
    void wedgeDebugScheduleEntryAndReleasedDiagnosticAreSeparateStages()
            throws Exception {
        String source = source("common/commands/RegionCommand.java");
        String command = section(source, "literal(\"wedgemain\")",
                "literal(\"claims\")");
        int scheduleCut = command.indexOf("RegionScheduler.runSuccessBodyIfHealthy");
        int schedule = command.indexOf("server.schedule(new net.minecraft.server.TickTask(");
        int callback = command.indexOf("fabrolia$runWedgeMainTask(ms)", schedule);
        assertTrue(scheduleCut >= 0 && schedule > scheduleCut && callback > schedule,
                "the debug TickTask publication itself must refuse peer W");

        String task = section(source,
                "private static void fabrolia$runWedgeMainTask(int ms)",
                "// ------------------------------------------------------------------\n"
                        + "    // runtime toggle");
        int entry = task.indexOf("RegionScheduler.runSuccessBodyIfHealthy(() -> {");
        int park = task.indexOf("LockSupport.parkNanos", entry);
        int terminal = task.indexOf("RegionScheduler.runSuccessBodyIfHealthy", park);
        int released = task.indexOf("[wedgemain] released", terminal);
        assertTrue(entry >= 0 && park > entry && terminal > park && released > terminal,
                "peer W selected during the park must surface before the released diagnostic");
    }

    @Test
    void recursiveStatsCallbacksGatePublicationEntryAndNormalTails()
            throws Exception {
        String source = source("common/commands/StatsCommand.java");

        String polling = section(source, "private static void pollUntilDone(",
                "private static void showGeneralStats(");
        int publicationCut = polling.indexOf("RegionScheduler.runSuccessBodyIfHealthy");
        int execute = polling.indexOf("server.execute(", publicationCut);
        int entry = polling.indexOf(
                "RegionScheduler.callSuccessBooleanIfHealthy(TickStats::isRecording)",
                execute);
        int recursive = polling.indexOf("pollUntilDone(source, topCount, ticks)", entry);
        int terminal = polling.indexOf("showEntityStats(source, topCount, true, ticks)",
                recursive);
        assertTrue(publicationCut >= 0 && execute > publicationCut && entry > execute
                        && recursive > entry && terminal > recursive,
                "each recursive callback must atomically enter and republish through a fresh cut");

        String entity = section(source, "private static void showEntityStats(", "\n}");
        int entityPublishCut = entity.indexOf("RegionScheduler.runSuccessBodyIfHealthy");
        int entityExecute = entity.indexOf("server.execute(", entityPublishCut);
        int scanEntry = entity.indexOf("private static void fabrolia$runEntityStatsTask(",
                entityExecute);
        int scanCut = entity.indexOf("RegionScheduler.runSuccessBodyIfHealthy(() -> {",
                scanEntry);
        int scan = entity.indexOf("server.getAllLevels().forEach", scanCut);
        int resetCut = entity.indexOf("RegionScheduler.runSuccessBodyIfHealthy",
                scan + 1);
        int reset = entity.indexOf("resetEntityTickStats()", resetCut);
        int replyCut = entity.indexOf("RegionScheduler.runSuccessBodyIfHealthy", reset + 1);
        // The reply is a system message: sendSuccess is dropped for players
        // while the sendCommandFeedback gamerule is off.
        int reply = entity.indexOf("source.sendSystemMessage", replyCut);
        assertTrue(entityPublishCut >= 0 && entityExecute > entityPublishCut
                        && scanEntry > entityExecute && scanCut > scanEntry && scan > scanCut
                        && resetCut > scan && reset > resetCut
                        && replyCut > reset && reply > replyCut,
                "scan, reset and reply must not collapse into one post-W continuation");
    }

    private static int count(String source, String needle) {
        int count = 0;
        int at = -1;
        while ((at = source.indexOf(needle, at + 1)) >= 0) count++;
        return count;
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, "source section not found: " + start);
        return source.substring(from, to);
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        Assumptions.assumeTrue(false, "source tree not available");
        throw new AssertionError("unreachable");
    }
}
