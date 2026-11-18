package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A region worker hands the recompute of an ownerless mob to Main as a queued tick task.
 * {@code MinecraftServer.execute} runs its task inline on the caller once the server is
 * stopped; the redispatch would then resolve the same mob again and recurse on the worker.
 */
class NavRecomputeMainHandoffWiringTest {

    @Test
    void theOwnerlessBranchQueuesATickTaskAndNeverCallsExecute() throws Exception {
        String source = source();
        int start = source.indexOf("static void dispatch(RegionizedServer rs, IslandRegistry.PIsland owner,");
        int end = source.indexOf("private static void runFailStopMainTask(Runnable body)", start);
        assertTrue(start >= 0 && end > start, "NavRecompute.dispatch not found");
        String dispatch = source.substring(start, end);
        assertTrue(dispatch.contains("srv.schedule(new net.minecraft.server.TickTask(srv.getTickCount(),"),
                "the worker-side handoff must always queue");
        assertFalse(dispatch.contains("srv.execute("),
                "MinecraftServer.execute runs inline on a stopped server");
        assertTrue(dispatch.contains("() -> runFailStopMainTask(redispatch)"),
                "the queued body keeps its fail-stop wrapper");
    }

    private static String source() throws Exception {
        Path relative = Path.of("src/main/java/com/axalotl/fabrolia/common/regions/NavRecompute.java");
        Path path = Files.exists(relative) ? relative : Path.of("common").resolve(relative);
        return Files.readString(path);
    }
}
