package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDeliverySuccessLinearizationWiringTest {

    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/com/axalotl/fabrolia/common/regions/ChunkDelivery.java"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, "source section not found: " + start);
        return source.substring(from, to);
    }

    @Test
    void eventLoopSuccessCannotWriteSucceededOutsideTheGlobalCut() throws Exception {
        String run = section(source(),
                "public void runPublished", "int applySuccessOnMain");
        int cut = run.indexOf("RegionScheduler.commitSuccessIfHealthy");
        int succeeded = run.indexOf("state = SUCCEEDED", cut);
        int catchFailure = run.indexOf(
                "terminalFailure = publishFatalPreserving(successFailure)", succeeded);
        int cleanup = run.indexOf(
                "terminalizeReservations(reservations, terminalFailure)", catchFailure);
        int rethrow = run.indexOf("rethrow(failure)", cleanup);

        assertTrue(cut >= 0 && succeeded > cut && catchFailure > succeeded
                        && cleanup > catchFailure && rethrow > cleanup,
                "SUCCEEDED must win the global cut or publish W, clean up, and rethrow it");
        assertFalse(run.substring(0, cut).contains("state = SUCCEEDED"));
    }

    @Test
    void mainApplyOffersBatchThenCommitsEverySuccessFieldInOneCut() throws Exception {
        String apply = section(source(),
                "int applySuccessOnMain", "void discard()");
        int prepareCut = apply.indexOf("RegionScheduler.runSuccessBodyIfHealthy");
        int offer = apply.indexOf("owner.outstanding.addLast(prepared)", prepareCut);
        int cut = apply.indexOf("RegionScheduler.commitSuccessIfHealthy", offer);
        int accept = apply.indexOf("batchCommit.acceptPublication()", cut);
        int refs = apply.indexOf("reservation.releaseReservationReference()", accept);
        int metrics = apply.indexOf("owner.enqueued += admittedCount", refs);
        int applied = apply.indexOf("state = APPLIED", metrics);
        int clear = apply.indexOf("owner.activePublication = null", applied);
        int selectW = apply.indexOf("applyFailure = publishFatalPreserving(bodyFailure)", clear);
        int cancel = apply.indexOf("completedBatch.cancelPublication()", selectW);
        int detach = apply.indexOf("owner.outstanding.removeLastOccurrence(completedBatch)", cancel);
        int terminalize = apply.indexOf(
                "terminalizeReservations(\n                            reservations, applyFailure)", detach);

        assertTrue(prepareCut >= 0 && offer > prepareCut && cut > offer
                        && accept > cut && refs > accept
                        && metrics > refs && applied > metrics && clear > applied,
                "Batch preparation must enter atomically and remain OFFERING until refs, "
                        + "metrics, APPLIED and active clear share one cut");
        assertTrue(selectW > clear && cancel > selectW && detach > cancel
                        && terminalize > detach,
                "a failed cut must select W, cancel/detach the receipt, then exhaust reservations");
    }

    @Test
    void ackCannotConsumeAnOfferingOrCancelledBatch() throws Exception {
        String all = source();
        String batch = section(all, "public static final class Batch",
                "/**\n     * One receipt-gated EventLoop transaction");
        String ack = section(all, "public static void onAck",
                "// ------------------------------------------------------------------\n    // liveness invariant");

        assertTrue(batch.contains("new AtomicInteger(OFFERING)")
                        && batch.contains("acceptPublication()")
                        && batch.contains("cancelPublication()")
                        && batch.contains("publicationAccepted()"));
        assertTrue(ack.contains("if (!b.publicationAccepted())")
                        && ack.contains("RegionScheduler.throwWorkerFailureIfAny()")
                        && ack.contains("b.cancelPublication()"));
    }
}
