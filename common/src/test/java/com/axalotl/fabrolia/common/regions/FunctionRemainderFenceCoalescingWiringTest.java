package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Main remainder passes of partitioned function roots share one all-island fence
 * per {@code #tick} tag and per server TimerQueue tick instead of quiescing every
 * island once per root or loop.
 */
final class FunctionRemainderFenceCoalescingWiringTest {

    @Test
    void tickTagRemaindersShareTheStickyTickHeadFence() throws Exception {
        String head = source("common/regions/IslandFunctionHead.java");
        String pass = section(head, "private static void runMainPass(", "\n    }\n");
        int needsFence = pass.indexOf("partition.hasAsyncLevelMember(pass)");
        int window = pass.indexOf("AdvancementBatchScope.active()", needsFence);
        int sticky = pass.indexOf(".ensureFunctionRemainderFence()", window);
        int timer = pass.indexOf("if (timerTickOpen)", sticky);
        int ownFence = pass.indexOf("IslandCommands.quiesceAllExcept(null)", timer);
        assertTrue(needsFence >= 0 && window > needsFence && sticky > window
                        && timer > sticky && ownFence > timer,
                "a remainder that needs the fence first borrows the tick-head window, then the"
                        + " timer tick, and only outside both takes its own scoped fence");

        String scope = source("common/parallelised/advancement/AdvancementBatchScope.java");
        String ensure = section(scope, "public static void ensureFunctionRemainderFence()", "\n    }\n");
        assertTrue(ensure.contains("ensureEvaluationFence(AdvancementDiag.Site.HEAD_FUNCTION_REMAINDER)"),
                "the remainder uses the window's sticky fence, released by AdvancementBatchScope.end");
    }

    @Test
    void timerTickRemainderFenceIsArmedBeforeItsBodyAndReleasedOnceAtTheTickEnd() throws Exception {
        String head = source("common/regions/IslandFunctionHead.java");
        String pass = section(head, "private static void runMainPass(", "\n    }\n");
        String timer = section(pass, "if (timerTickOpen)", "return;\n        }\n        if (!IslandCommands");
        int quiesce = timer.indexOf("IslandCommands.quiesceAllExcept(null)");
        int armed = timer.indexOf("timerStickyFence = true;", quiesce);
        int body = timer.indexOf("runSuccessBodyIfHealthy(() -> runMainPassMembers(", armed);
        assertTrue(quiesce >= 0 && armed > quiesce && body > armed,
                "the sticky marker owns the release before any fallible body runs");

        String mixin = source("common/mixin/commands/TimerQueueMixin.java");
        String wrap = section(mixin, "fabrolia$fenceDueFunctionsOnce(", "\n    }\n");
        int begin = wrap.indexOf("boolean opened = IslandFunctionHead.beginTimerTick();");
        int tick = wrap.indexOf("fabrolia$tickServerQueue(", begin);
        int end = wrap.indexOf("IslandFunctionHead.endTimerTick(server);", tick);
        int close = wrap.indexOf("opened && IslandFunctionHead.closeTimerTickFence()", end);
        int release = wrap.indexOf("IslandCommands.releaseAllExceptAndPropagate(", close);
        assertTrue(begin >= 0 && tick > begin && end > tick && close > end && release > close,
                "the opener releases a kept remainder fence exactly once after the queue tick");
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate source: " + relative);
    }

    private static String section(String source, String start, String end) {
        int begin = source.indexOf(start);
        int finish = source.indexOf(end, begin + start.length());
        assertTrue(begin >= 0 && finish > begin, () -> "missing source section " + start + " .. " + end);
        return source.substring(begin, finish);
    }
}
