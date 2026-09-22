package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression pins for the island entity-barrier exact-receipt watchdog. */
class IslandBarrierWatchdogTest {

    @Test
    void aNineteenSecondBlockingHealGetsOneFreshObservationWindow() {
        long t0 = 1_000L;
        EntityBarrierWatchdog watchdog = new EntityBarrierWatchdog(5, t0);
        assertEquals(EntityBarrierWatchdog.Action.HEAL,
                reachFirstHeal(watchdog, 5, t0));

        long healReturned = t0 + 19_000_000_000L;
        assertEquals(EntityBarrierWatchdog.Action.WAIT,
                watchdog.afterHeal(5, 1, healReturned, true));
        assertEquals(healReturned, watchdog.windowStartedNanos(),
                "time blocked before the accepted enqueue is not charged to its receipt");
        assertEquals(EntityBarrierWatchdog.Action.WAIT,
                watchdog.observe(5,
                        healReturned + EntityBarrierWatchdog.DEADLINE_NANOS,
                        false));
        assertEquals(EntityBarrierWatchdog.Action.FAIL,
                watchdog.observe(5,
                        healReturned + EntityBarrierWatchdog.DEADLINE_NANOS + 1,
                        false));
    }

    @Test
    void foreignWorkCannotPostponeAnUnchangedOwnBarrier() {
        long t0 = 10L;
        EntityBarrierWatchdog watchdog = new EntityBarrierWatchdog(3, t0);

        // Every call models a successful workerHelpOnce for another island.
        // There is deliberately no "worked" input: foreign work cannot reset
        // either the own-witness poll count or the own pending deadline.
        assertEquals(EntityBarrierWatchdog.Action.HEAL,
                reachFirstHeal(watchdog, 3, t0));
        assertEquals(EntityBarrierWatchdog.Action.WAIT,
                watchdog.afterHeal(3, 1, t0, true));

        for (int i = 1; i <= 1_000; i++) {
            long now = t0 + (EntityBarrierWatchdog.DEADLINE_NANOS * i / 1_000);
            assertEquals(EntityBarrierWatchdog.Action.WAIT,
                    watchdog.observe(3, now, false));
        }
        assertEquals(EntityBarrierWatchdog.Action.FAIL,
                watchdog.observe(3,
                        t0 + EntityBarrierWatchdog.DEADLINE_NANOS + 1,
                        false));
    }

    @Test
    void exactReceiptSpansPollClaimReleaseAndContributionTail() throws Exception {
        EpochDomain.EntityDispatchPublication publication =
                new EpochDomain.EntityDispatchPublication();
        ArrayBlockingQueue<Object> visibleReady = new ArrayBlockingQueue<>(1);
        CountDownLatch polled = new CountDownLatch(1);
        CountDownLatch claimReleased = new CountDownLatch(1);
        CountDownLatch allowTail = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        publication.openReadyReceipt(); // before CLQ visibility
        visibleReady.add(new Object());
        publication.accept();

        Thread consumer = new Thread(() -> {
            try {
                visibleReady.take(); // poll
                polled.countDown();
                // The claim is released while the contribution/re-arm tail is
                // deliberately held open.
                claimReleased.countDown();
                allowTail.await();
                publication.closeReadyReceipt();
                closed.countDown();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "receipt-tail-model");
        consumer.start();

        assertTrue(polled.await(2, TimeUnit.SECONDS));
        assertTrue(publication.hasReadyReceipts(),
                "poll must not retire the receipt");
        assertTrue(claimReleased.await(2, TimeUnit.SECONDS));
        assertTrue(publication.hasReadyReceipts(),
                "claim release must not expose the contribution-tail gap");
        allowTail.countDown();
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        consumer.join(2_000L);
        assertFalse(consumer.isAlive());
        assertNull(failure.get());
        assertFalse(publication.hasReadyReceipts(),
                "only the complete outcome tail retires the receipt");
    }

    @Test
    void oneHealAndOnePendingAdvancePerVersionAreBoundedByTwoN() {
        int n = 37;
        int transitions = 0;
        long now = 0L;
        EntityBarrierWatchdog watchdog = new EntityBarrierWatchdog(n, now);

        for (int pending = n; pending > 0; pending--) {
            assertEquals(EntityBarrierWatchdog.Action.HEAL,
                    reachFirstHeal(watchdog, pending, now));
            transitions++; // one accepted recovery for this pending value
            assertEquals(EntityBarrierWatchdog.Action.WAIT,
                    watchdog.afterHeal(pending, 1, now, true));

            now++;
            assertEquals(EntityBarrierWatchdog.Action.WAIT,
                    watchdog.observe(pending - 1, now, false));
            transitions++; // this domain's own pending version advanced
        }
        assertEquals(2 * n, transitions);
        assertEquals(0, watchdog.observedPending());
    }

    @Test
    void benignOldDuplicateReceiptCannotPoisonTheNextDispatch() {
        EpochDomain.EntityDispatchPublication oldPublication =
                new EpochDomain.EntityDispatchPublication();
        EpochDomain.EntityDispatchPublication nextPublication =
                new EpochDomain.EntityDispatchPublication();

        oldPublication.openReadyReceipt();
        oldPublication.accept();
        nextPublication.accept();

        assertTrue(oldPublication.hasReadyReceipts());
        assertFalse(nextPublication.hasReadyReceipts(),
                "receipt ownership is per publication, not a reused domain counter");
        oldPublication.closeReadyReceipt();
        assertFalse(nextPublication.hasReadyReceipts());
    }

    @Test
    void productionClosesReceiptsAfterBothConsumerTails()
            throws Exception {
        String pool = source("common/regions/RegionWorkerPool.java");
        int offer = pool.indexOf("ReadyEntry entry = new ReadyEntry(");
        int visible = pool.indexOf("ready.add(entry);", offer);
        assertTrue(offer >= 0 && visible > offer,
                "tag constructor must open its receipt before CLQ visibility");

        String worker = section(pool,
                "private boolean workReadyEntry(RegionWorkerThread me",
                "private boolean workReadyEntryBody(");
        assertTrue(worker.indexOf("workReadyEntryBody(me, readyEntry)")
                        < worker.indexOf("readyEntry.closeEntityReceipt()"),
                "worker receipt closes only after the complete workOnce tail");
        assertTrue(worker.indexOf("failure = publishAndSelectFatal(thrown);")
                        < worker.indexOf("readyEntry.closeEntityReceipt()"),
                "worker must select exact W before retiring its last witness");
        String main = section(pool,
                "private boolean stealReadyEntry(ReadyEntry readyEntry)",
                "private boolean stealReadyEntryBody(");
        assertTrue(main.indexOf("stealReadyEntryBody(readyEntry)")
                        < main.indexOf("readyEntry.closeEntityReceipt()"),
                "main-steal receipt closes only after its complete tail");
        assertTrue(main.indexOf("failure = publishAndSelectFatal(thrown);")
                        < main.indexOf("readyEntry.closeEntityReceipt()"),
                "main steal must select exact W before retiring its last witness");
        assertTrue(pool.contains(
                        "readyEntry.ensureEntityReceipt(readyPublication, readyEpoch);"),
                "a stale/generic entry transfers to the current exact receipt before claim");
        String direct = section(pool,
                "boolean helpAcceptedEntityRegion(",
                "private boolean workReadyEntryBody(");
        assertTrue(direct.contains(
                        "new ReadyEntry(region, true, publication, epoch)"),
                "the owner helper needs an exact receipt across deschedule/claim/tail");
        assertTrue(direct.contains("return workReadyEntry(me, direct);"),
                "owner priority must reuse the normal fail-stop claim/commit/contribute path");
        assertFalse(direct.contains("ready.poll()"),
                "owner priority must not queue behind unrelated island entries again");
        assertEquals(4,
                pool.split("detachHelpForLiveClaim\\(", -1).length - 1,
                "all three unclaimed help polls plus the shared primitive must stay wired");
        String helpDetach = section(pool,
                "private Runnable detachHelpForLiveClaim(",
                "/** Execute every accepted helper left without its original claim coordinator.");
        int admission = helpDetach.indexOf("synchronized (region.admissionLock)");
        int holder = helpDetach.indexOf("region.currentWorker()", admission);
        int eligible = helpDetach.indexOf(".eligible(region, runtime)", holder);
        int poll = helpDetach.indexOf("region.helpQueue.poll()", eligible);
        assertTrue(admission >= 0 && holder > admission && eligible > holder && poll > eligible,
                "help detach must linearize holder+epoch validation with DONE_SEAL/admission");
        assertFalse(helpDetach.contains("help.run()"),
                "the detached ExternalTask runs outside admissionLock under holder fan-in");
        String failedCancel = section(pool,
                "private Throwable cancelFailedGenerationQueues(Throwable primary)",
                "private boolean failedGenerationOwnQueuesEmpty()");
        assertTrue(failedCancel.contains("readyEntry.cancelPublication();"));
        assertFalse(failedCancel.contains("ready.clear()"),
                "bulk clear would leak tagged publication receipts");

        String color = source("common/regions/ColorGate.java");
        String doneSeal = section(color,
                "static boolean publishDoneIfQuiescent(",
                "private static boolean doneInputsValid(");
        int doneAdmission = doneSeal.indexOf("synchronized (r.admissionLock)");
        int beginSeal = doneSeal.indexOf("r.tryBeginDoneSeal(epoch)", doneAdmission);
        int secondValidation = doneSeal.indexOf("doneInputsValid(", beginSeal);
        int commitSeal = doneSeal.indexOf(
                "r.commitDoneSeal(expectedDomain, epoch)", secondValidation);
        int releaseSeal = doneSeal.indexOf("r.releaseDoneSeal()", commitSeal);
        assertTrue(doneAdmission >= 0 && beginSeal > doneAdmission
                        && secondValidation > beginSeal && commitSeal > secondValidation
                        && releaseSeal > commitSeal,
                "done must CAS-seal, revalidate, publish, then release in that order");

        String region = source("common/regions/Region.java");
        assertFalse(region.contains("boolean tryClaim(Thread worker)"),
                "an un-tokenized claim overload would let a future path bypass rebase fencing");
        String server = source("common/regions/RegionizedServer.java");
        String groupHead = section(server,
                "EpochDomain claimDomain = r.domain;",
                "// Spin-claim with backoff:");
        int groupEligibility = groupHead.indexOf("ColorGate.eligible(r, this)");
        int groupMismatch = groupHead.indexOf("r.domain != claimDomain", groupEligibility);
        int groupDefer = groupHead.indexOf("deferred.add(r)", groupMismatch);
        assertTrue(groupEligibility >= 0 && groupMismatch > groupEligibility
                        && groupDefer > groupMismatch
                        && groupHead.substring(groupMismatch, groupDefer).contains("continue;"),
                "a pre-claim domain mismatch must retire the old group before defer");
        String groupClaim = section(server,
                "while (!(claimed = r.tryClaim(me, claimDomain, claimEpoch)))",
                "if (!claimed) {");
        assertTrue(groupClaim.contains("r.domain != claimDomain")
                        && groupClaim.contains("staleGeneration = true"),
                "the group spin must terminate when its fixed claim token becomes stale");
    }

    private static EntityBarrierWatchdog.Action reachFirstHeal(
            EntityBarrierWatchdog watchdog, int pending, long now) {
        EntityBarrierWatchdog.Action action = EntityBarrierWatchdog.Action.WAIT;
        for (int i = 0;
             i < EntityBarrierWatchdog.UNWITNESSED_POLLS_BEFORE_HEAL;
             i++) {
            action = watchdog.observe(pending, now, false);
        }
        return action;
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
        Path nested = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
        Path path = Files.exists(direct) ? direct : nested;
        Assumptions.assumeTrue(Files.exists(path), "source tree is available");
        return Files.readString(path);
    }

    private static String section(String text, String from, String to) {
        int start = text.indexOf(from);
        int end = text.indexOf(to, start + from.length());
        assertTrue(start >= 0 && end > start,
                () -> "missing source section: " + from + " .. " + to);
        return text.substring(start, end);
    }
}
