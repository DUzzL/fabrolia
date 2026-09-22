package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuccessTailFailStopWiringTest {

    private static String source(String file) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/axalotl/fabrolia/common/regions/" + file));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, "source section not found: " + start);
        return source.substring(from, to);
    }

    @Test
    void regionalPlayerClaimUsesFixedReceiptBeforeTerminalRetirementAndNotification()
            throws Exception {
        String all = source("RegionalPlayerEntityTicksV2.java");
        String completion = section(all,
                "private static Throwable completeAndRetire(",
                "public static void finishLevel(");

        int offer = completion.indexOf("context.batch.offerCompletion(context.claim)");
        int cut = completion.indexOf("RegionScheduler.commitSuccessIfHealthy");
        int accept = completion.indexOf("acceptedReceipt::acceptSuccess", cut);
        int consume = completion.indexOf(
                "context.batch.consumeAcceptedCompletionAndRetire(acceptedReceipt)", accept);
        int poison = completion.indexOf(
                "context.batch.poisonCompletionAndRetire(receipt, result)", consume);
        int signal = completion.indexOf("context.batch.signalCompletion(receipt)", poison);

        assertTrue(offer >= 0 && cut > offer && accept > cut && consume > accept
                        && poison > consume && signal > poison,
                "the cut may only accept a fixed receipt; ledger consume, W poison, retirement, "
                        + "and waiter notification must remain outside it");
        assertFalse(completion.substring(cut, consume).contains("retireCandidate"));
        assertFalse(completion.substring(cut, consume).contains("ledger.complete"));

        String globalFail = section(all, "Throwable fail(Throwable thrown)",
                "synchronized Throwable firstFailure()");
        assertTrue(globalFail.indexOf("publishFatalAndSelectWinner(thrown)")
                        < globalFail.indexOf("recordFailureLocked(winner)"),
                "the exact scheduler W must win before local ledger publication");
    }



    @Test
    void fanInPublishesNormalTerminalOnlyInsideTheHealthyCut() throws Exception {
        String all = source("RegionTaskFanIn.java");
        String run = section(all, "public final class RegisteredTask",
                "public boolean cancelBeforeStart(Throwable failure)");
        int body = run.indexOf("RegionScheduler.runSuccessBodyIfHealthy(body)");
        int catchRecord = run.indexOf("recordFailure(thrown)", body);
        int cut = run.indexOf("RegionScheduler.commitSuccessIfHealthy", catchRecord);
        int terminal = run.indexOf("state.set(TERMINAL)", cut);
        int pending = run.indexOf("pending.decrementAndGet()", terminal);
        int normalReturn = run.indexOf("return", pending);
        int selectedRecord = run.indexOf("recordFailure(selectedFailure)", normalReturn);
        int cleanupTerminal = run.indexOf("state.set(TERMINAL)", selectedRecord);
        int cleanupPending = run.indexOf("pending.decrementAndGet()", cleanupTerminal);

        assertTrue(body >= 0 && catchRecord > body,
                "helper body entry must be atomic with the generation W cut");
        assertTrue(cut > catchRecord && terminal > cut && pending > terminal
                        && normalReturn > pending,
                "normal fan-in terminal and pending edge must share the healthy cut");
        assertTrue(selectedRecord > normalReturn && cleanupTerminal > selectedRecord
                        && cleanupPending > cleanupTerminal,
                "a failed cut must select W before mandatory obligation retirement");
        assertFalse(run.substring(body, cut).contains("pending.decrementAndGet()"));
    }
}
