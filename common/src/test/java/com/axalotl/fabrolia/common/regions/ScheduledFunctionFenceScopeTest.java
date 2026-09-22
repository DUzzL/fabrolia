package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for the exact-world armor_statues:tick 50 ms fence failure. */
final class ScheduledFunctionFenceScopeTest {

    @Test
    void scheduledCallbackCapabilityIsNestedThreadOwnedAndFailureSafe() throws Exception {
        assertFalse(IslandCommands.inMainScheduledFunctionCallback());
        RuntimeException exact = new RuntimeException("exact timer callback failure");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> IslandCommands.runMainScheduledFunctionCallback(() -> {
                    assertTrue(IslandCommands.inMainScheduledFunctionCallback());
                    boolean[] foreign = new boolean[1];
                    Thread thread = Thread.ofPlatform().start(() -> foreign[0] =
                            IslandCommands.inMainScheduledFunctionCallback());
                    try {
                        thread.join();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    assertFalse(foreign[0],
                            "an unrelated thread must not borrow Main's capability");
                    IslandCommands.runMainScheduledFunctionCallback(
                            () -> assertTrue(
                                    IslandCommands.inMainScheduledFunctionCallback()));
                    assertTrue(IslandCommands.inMainScheduledFunctionCallback());
                    throw exact;
                }));

        assertSame(exact, thrown, "the scope must propagate the exact vanilla failure");
        assertFalse(IslandCommands.inMainScheduledFunctionCallback(),
                "a throwing timer callback must restore the capability");
    }

    @Test
    void exactVanillaTimerCallbacksGrantTheCapabilityAndCustomCallbacksDoNot()
            throws Exception {
        String timer = source("common/mixin/commands/TimerQueueMixin.java");
        assertTrue(timer.contains("@Mixin(TimerQueue.class)"));
        assertTrue(timer.contains("method = \"tick\""));
        assertTrue(timer.contains("TimerCallback;handle("));
        assertTrue(timer.contains("callback instanceof FunctionCallback")
                && timer.contains("callback instanceof FunctionTagCallback"));
        assertTrue(timer.contains("IslandCommands.runMainScheduledFunctionCallback("));
        assertTrue(timer.indexOf("runMainScheduledFunctionCallback(")
                        < timer.lastIndexOf("original.call(callback, context, queue, triggerTime)"),
                "custom timer callbacks must call original outside the clean capability");
        assertFalse(timer.contains("catch ("));

        String commands = source("common/regions/IslandCommands.java");
        String scheduledScope = section(commands,
                "runMainScheduledFunctionCallback(Runnable body)",
                "public static boolean inMainScheduledFunctionCallback()", 0);
        assertTrue(scheduledScope.contains(
                        "RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)"),
                "a logical player actor must not borrow the clean physical-Main capability");
        assertFalse(scheduledScope.contains("server.isSameThread()"));

        String mixins = resource("fabrolia.common.mixins.json");
        assertTrue(mixins.contains("\"commands.TimerQueueMixin\""));

        String functions = source(
                "common/mixin/commands/ServerFunctionManagerMixin.java");
        String fence = section(functions, "fabrolia$fenceFunction(",
                "@org.spongepowered.asm.mixin.Unique", 0);
        assertTrue(fence.contains("IslandCommands.inMainScheduledFunctionCallback()"));
        assertTrue(fence.contains("AdvancementBatchScope.cleanMainFenceRoot()"),
                "the monitor-free deferred reward batch must use the unbounded exact fence");
        assertTrue(count(fence, "rewardFunctionActive()") == 2,
                "sticky ownership and reward metrics must require the narrow exact reward body");
        assertFalse(fence.contains("AdvancementBatchScope.active()"),
                "the broad batch owner must not grant a function-fence capability");
        assertTrue(fence.contains("cleanMainRoot\n"
                + "                ? IslandCommands.quiesceAllExcept(null)"));
        assertTrue(fence.contains(
                ": IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS)"),
                "only monitor-bearing/nested unknown callers retain the bounded fail-stop path");
        int receipt = fence.indexOf("boolean[] stickyArmed = new boolean[1]");
        int quiesce = fence.indexOf("boolean took = cleanMainRoot", receipt);
        int cleanupEnvelope = fence.indexOf("try {", quiesce);
        assertTrue(receipt >= 0 && quiesce > receipt && cleanupEnvelope > quiesce,
                "all fallible sticky receipts must exist before fence acquisition and the "
                        + "cleanup envelope must begin immediately after it");
        assertFalse(fence.contains("fabrolia$defer"));
        assertFalse(fence.contains("deferredTickFns"));
        assertFalse(fence.contains("getServer().execute"));
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate source: " + relative);
    }

    private static String resource(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/resources").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/resources").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate resource: " + relative);
    }

    private static String section(String source, String start, String end, int from) {
        int begin = source.indexOf(start, from);
        int finish = source.indexOf(end, begin + start.length());
        assertTrue(begin >= 0 && finish > begin,
                () -> "missing source section " + start + " .. " + end);
        return source.substring(begin, finish);
    }

    private static int count(String source, String needle) {
        int count = 0;
        for (int at = 0; (at = source.indexOf(needle, at)) >= 0;
                at += needle.length()) {
            count++;
        }
        return count;
    }
}
