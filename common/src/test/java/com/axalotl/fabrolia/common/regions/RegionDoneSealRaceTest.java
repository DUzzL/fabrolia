package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic interleavings for claim/queue admission versus ColorGate done. */
class RegionDoneSealRaceTest {

    @Test
    void claimWinnerOwnsTheOldEpochAndDoneWaitsForItsRelease() {
        Fixture f = fixture(41L);
        Thread claimant = Thread.currentThread();

        assertTrue(f.region.tryClaim(claimant, f.domain, f.epoch));
        assertFalse(ColorGate.publishDoneIfQuiescent(
                f.region, f.domain, f.epoch, true, false));
        assertFalse(f.region.hasSealedDone(f.epoch));

        f.region.release(claimant);
        assertTrue(ColorGate.publishDoneIfQuiescent(
                f.region, f.domain, f.epoch, true, false));
        f.region.finishClaimTransaction();
        assertTrue(f.region.hasSealedDone(f.epoch));
    }

    @Test
    void stalePrecheckCannotClaimAfterSealButNextEpochCan() {
        Fixture f = fixture(77L);

        // Models eligibility read=true before this exact publication.
        boolean staleEligibility = f.region.doneEpoch < f.epoch;
        assertTrue(staleEligibility);
        assertTrue(ColorGate.publishDoneIfQuiescent(
                f.region, f.domain, f.epoch, true, false));

        assertFalse(f.region.tryClaim(Thread.currentThread(), f.domain, f.epoch),
                "post-CAS sealedDoneEpoch check admitted a stale old-epoch claimant");
        f.domain.epoch++;
        assertTrue(f.region.tryClaim(
                        Thread.currentThread(), f.domain, f.domain.epoch),
                "the seal leaked into the next epoch instead of fencing only its owner epoch");
        f.region.release(Thread.currentThread());
        f.region.finishClaimTransaction();
    }

    @Test
    void doneSealCommitsOldEpochAfterAutonomousAdvanceWithoutRegression() {
        Fixture f = fixture(701L);

        synchronized (f.region.admissionLock) {
            assertTrue(f.region.tryBeginDoneSeal(f.epoch));
            try {
                // IslandTickUnit advances this clock without the region's
                // admissionLock. A next-epoch direct writer may win as well.
                f.domain.epoch++;
                f.region.doneEpoch = f.domain.epoch;
                f.region.commitDoneSeal(f.domain, f.epoch);
            } finally {
                f.region.releaseDoneSeal();
            }
        }

        assertEquals(f.domain.epoch, f.region.doneEpoch,
                "an old done tail regressed a next-epoch direct stamp");
        assertEquals(f.epoch, f.region.sealedDoneEpoch);
        assertTrue(f.region.hasSealedDone(f.epoch));
        assertFalse(f.region.hasSealedDone(f.domain.epoch),
                "the old seal closed the autonomous next epoch");
        assertTrue(f.region.tryClaim(
                        Thread.currentThread(), f.domain, f.domain.epoch),
                "the old seal fenced a fresh-epoch claimant");
        f.region.release(Thread.currentThread());
        f.region.finishClaimTransaction();
    }

    @Test
    void globalDuplicateBetweenEntityCommitAndDoneOwnsTheQuiescenceTail()
            throws Exception {
        Fixture f = fixture(89L);
        CountDownLatch syntheticReleased = new CountDownLatch(1);
        CountDownLatch duplicateClaimed = new CountDownLatch(1);
        CountDownLatch allowDuplicateRelease = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread synthetic = Thread.currentThread();
        assertTrue(f.region.tryClaim(synthetic, f.domain, f.epoch));
        f.region.entityEpoch = f.epoch; // synthetic phase commit
        f.region.release(synthetic);
        syntheticReleased.countDown();

        Thread duplicate = new Thread(() -> {
            try {
                syntheticReleased.await();
                if (!f.region.tryClaim(
                        Thread.currentThread(), f.domain, f.epoch)) {
                    throw new AssertionError("global duplicate lost arranged claim window");
                }
                duplicateClaimed.countDown();
                allowDuplicateRelease.await();
                f.region.release(Thread.currentThread());
                if (!ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, false)) {
                    throw new AssertionError("last duplicate did not publish sealed done");
                }
                f.region.finishClaimTransaction();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "global-ready-duplicate");
        duplicate.start();
        assertTrue(duplicateClaimed.await(2, TimeUnit.SECONDS));

        assertFalse(ColorGate.publishDoneIfQuiescent(
                f.region, f.domain, f.epoch, true, false),
                "synthetic tail published done across the duplicate's held claim");
        assertFalse(f.region.hasSealedDone(f.epoch),
                "barrier contribution would have been authorized before quiescence");
        assertEquals(2, f.region.activeClaimTransactions(),
                "release-to-done tails stopped owning their claim transactions");
        f.region.finishClaimTransaction();

        allowDuplicateRelease.countDown();
        duplicate.join(2_000L);
        assertFalse(duplicate.isAlive());
        assertNull(failure.get());
        assertTrue(f.region.hasSealedDone(f.epoch),
                "the last no-op/drain claimant failed to inherit the completion tail");
    }

    @Test
    void groupedPreEntityQueueOfferPreventsDoneUntilBodyAndEntityCommit() throws Exception {
        Fixture f = fixture(103L);
        f.region.entityEpoch = f.epoch - 1;
        AtomicInteger bodies = new AtomicInteger();
        CountDownLatch offerOwnsAdmission = new CountDownLatch(1);
        CountDownLatch allowOfferCommit = new CountDownLatch(1);
        CountDownLatch sealerStarted = new CountDownLatch(1);
        AtomicReference<Boolean> published = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread offer = new Thread(() -> {
            try {
                synchronized (f.region.admissionLock) {
                    offerOwnsAdmission.countDown();
                    allowOfferCommit.await();
                    f.region.queue().submit(bodies::incrementAndGet);
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "done-seal-offer-winner");
        Thread sealer = new Thread(() -> {
            try {
                sealerStarted.countDown();
                published.set(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, false));
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "done-seal-after-offer");

        offer.start();
        assertTrue(offerOwnsAdmission.await(2, TimeUnit.SECONDS));
        sealer.start();
        assertTrue(sealerStarted.await(2, TimeUnit.SECONDS));
        allowOfferCommit.countDown();
        offer.join(2_000L);
        sealer.join(2_000L);
        assertFalse(offer.isAlive());
        assertFalse(sealer.isAlive());
        assertNull(failure.get());
        assertEquals(Boolean.FALSE, published.get());
        assertFalse(f.region.hasSealedDone(f.epoch));
        assertEquals(1, f.region.queue().drain(Integer.MAX_VALUE));
        assertEquals(1, bodies.get());

        assertFalse(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, false),
                "a grouped participant sealed before its entity body committed");
        f.region.entityEpoch = f.epoch;
        assertTrue(ColorGate.publishDoneIfQuiescent(
                f.region, f.domain, f.epoch, true, false));
        assertTrue(f.region.hasSealedDone(f.epoch));
    }

    @Test
    void ungroupedPostSnapshotQueuesSealCurrentEpochAndRunExactlyOnceNextEpoch() {
        Fixture f = islandFixture(157L);
        f.region.groupedEpoch = f.epoch - 1;
        f.region.entityEpoch = f.epoch - 1;
        AtomicInteger queueBodies = new AtomicInteger();
        AtomicInteger helpBodies = new AtomicInteger();
        f.region.queue().submit(queueBodies::incrementAndGet);
        f.region.helpQueue.add(helpBodies::incrementAndGet);

        assertFalse(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, false, false),
                "retirement/unconditional done sealed over accepted work");
        assertFalse(f.region.hasSealedDone(f.epoch));
        assertTrue(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, false),
                "post-snapshot work made an ungrouped lower colour block E forever");
        assertTrue(f.region.hasSealedDone(f.epoch));
        assertEquals(1, f.region.queue().size(),
                "current-E cutoff consumed next-epoch region work");
        assertEquals(1, f.region.helpQueue.size(),
                "current-E cutoff consumed next-epoch helper work");
        assertEquals(0, queueBodies.get());
        assertEquals(0, helpBodies.get());
        assertTrue(RegionizedServer.hasEntityDispatchWork(f.region, true),
                "retained queue/help work was not eligible to seed E+1");
        assertFalse(f.region.tryClaim(
                        Thread.currentThread(), f.domain, f.epoch),
                "sealed current epoch admitted its preserved next-epoch queue");

        f.domain.epoch++;
        long nextEpoch = f.domain.epoch;
        assertTrue(f.region.tryClaim(
                        Thread.currentThread(), f.domain, nextEpoch),
                "preserved post-snapshot work stayed sealed in the next epoch");
        assertEquals(1, f.region.queue().drain(Integer.MAX_VALUE));
        Runnable helper = f.region.helpQueue.poll();
        assertTrue(helper != null);
        helper.run();
        f.region.release(Thread.currentThread());
        f.region.finishClaimTransaction();

        assertEquals(1, queueBodies.get());
        assertEquals(1, helpBodies.get());
        assertTrue(f.region.queue().isEmpty());
        assertTrue(f.region.helpQueue.isEmpty());
    }

    @Test
    void globalUngroupedPostSnapshotQueuesRemainStrictCurrentEpochWork() {
        Fixture f = fixture(167L);
        f.region.groupedEpoch = f.epoch - 1;
        f.region.entityEpoch = f.epoch - 1;
        AtomicInteger queueBodies = new AtomicInteger();
        AtomicInteger helpBodies = new AtomicInteger();
        f.region.queue().submit(queueBodies::incrementAndGet);
        f.region.helpQueue.add(helpBodies::incrementAndGet);

        assertFalse(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, false),
                "global done cut escaped over work Main may await this tick");
        assertFalse(f.region.hasSealedDone(f.epoch));
        assertEquals(0, queueBodies.get());
        assertEquals(0, helpBodies.get());

        assertEquals(1, f.region.queue().drain(Integer.MAX_VALUE));
        Runnable helper = f.region.helpQueue.poll();
        assertTrue(helper != null);
        helper.run();
        assertTrue(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, false),
                "empty global ungrouped work failed to close current E");
        assertEquals(1, queueBodies.get());
        assertEquals(1, helpBodies.get());
    }

    @Test
    void queueOnlyGroupedCompletionRequiresExactTokenAndPhysicalEmptyQueues() {
        Fixture f = fixture(173L);
        f.region.entityEpoch = f.epoch - 1;
        AtomicInteger queueBodies = new AtomicInteger();
        AtomicInteger helpBodies = new AtomicInteger();
        f.region.queue().submit(queueBodies::incrementAndGet);
        f.region.helpQueue.add(helpBodies::incrementAndGet);

        assertFalse(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, true),
                "queue-only dispatch sealed over undrained current-E work");
        assertFalse(f.region.hasSealedDone(f.epoch));

        assertEquals(1, f.region.queue().drain(Integer.MAX_VALUE));
        Runnable helper = f.region.helpQueue.poll();
        assertTrue(helper != null);
        helper.run();
        assertFalse(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, false),
                "ordinary grouped completion skipped its entity body");
        assertTrue(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, true),
                "exact queue-only empty phase failed to close ColorGate");
        assertTrue(f.region.hasSealedDone(f.epoch));
        assertEquals(1, queueBodies.get());
        assertEquals(1, helpBodies.get());
    }

    @Test
    void queueOnlyLegacyGroupDrainsRetainedHelpBeforeCompletion() throws Exception {
        Fixture f = fixture(179L);
        f.region.entityEpoch = f.epoch - 1;
        CountDownLatch helperEntered = new CountDownLatch(1);
        CountDownLatch allowHelperTerminal = new CountDownLatch(1);
        AtomicInteger helperBodies = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        f.region.helpQueue.add(() -> {
            helperEntered.countDown();
            try {
                allowHelperTerminal.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
            helperBodies.incrementAndGet();
        });

        RegionizedServer runtime = allocateRuntimeWithoutConstructor();
        CompletableFuture<Void> groupFuture = new CompletableFuture<>();
        Method processGroup = RegionizedServer.class.getDeclaredMethod(
                "processGroup", ArrayList.class, int.class,
                CompletableFuture.class, boolean.class);
        processGroup.setAccessible(true);
        ArrayList<Region> group = new ArrayList<>();
        group.add(f.region);
        RegionWorkerThread worker = new RegionWorkerThread(() -> {
            try {
                processGroup.invoke(runtime, group, 0, groupFuture, false);
            } catch (Throwable thrown) {
                Throwable cause = thrown.getCause();
                failure.compareAndSet(null, cause == null ? thrown : cause);
            }
        }, "queue-only-help-group");
        worker.start();

        assertTrue(helperEntered.await(2, TimeUnit.SECONDS));
        assertFalse(groupFuture.isDone(),
                "queue-only group future completed before helper terminal");
        allowHelperTerminal.countDown();
        worker.join(2_000L);

        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertTrue(groupFuture.isDone());
        assertFalse(groupFuture.isCompletedExceptionally());
        assertEquals(1, helperBodies.get());
        assertTrue(f.region.helpQueue.isEmpty());
        assertTrue(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, true),
                "queue-only legacy group did not close after its helper terminal");
    }

    @Test
    void groupedPostEntityQueuesSealAndRemainForExactlyOneNextDispatch() throws Exception {
        Fixture f = fixture(181L);
        AtomicInteger queueBodies = new AtomicInteger();
        AtomicInteger helpBodies = new AtomicInteger();
        f.region.queue().submit(queueBodies::incrementAndGet);
        f.region.helpQueue.add(helpBodies::incrementAndGet);

        RegionizedServer runtime = allocateRuntimeWithoutConstructor();
        assertFalse(ColorGate.eligible(f.region, runtime),
                "a committed entity phase was reclaimable through a late ready duplicate");
        assertTrue(ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, false),
                "post-entity queue work prevented the current phase cutoff");
        assertEquals(1, f.region.queue().size());
        assertEquals(1, f.region.helpQueue.size());
        assertEquals(0, queueBodies.get());
        assertEquals(0, helpBodies.get());
        assertTrue(RegionizedServer.hasEntityDispatchWork(f.region, true),
                "post-entity retained work was not eligible to seed E+1");
        assertFalse(f.region.tryClaim(Thread.currentThread(), f.domain, f.epoch),
                "the current epoch reclaimed post-entity retained work");

        f.domain.epoch++;
        assertTrue(f.region.tryClaim(
                Thread.currentThread(), f.domain, f.domain.epoch));
        assertEquals(1, f.region.queue().drain(Integer.MAX_VALUE));
        Runnable helper = f.region.helpQueue.poll();
        assertTrue(helper != null);
        helper.run();
        f.region.release(Thread.currentThread());
        f.region.finishClaimTransaction();

        assertEquals(1, queueBodies.get());
        assertEquals(1, helpBodies.get());
        assertFalse(RegionizedServer.hasEntityDispatchWork(f.region, false));
    }

    @Test
    void staleClaimRefusalCannotDetachHelpAdmittedAfterSeal() throws Exception {
        Fixture f = fixture(211L);
        assertTrue(ColorGate.publishDoneIfQuiescent(
                f.region, f.domain, f.epoch, true, false));

        AtomicInteger nextEpochBodies = new AtomicInteger();
        synchronized (f.region.admissionLock) {
            f.region.helpQueue.add(nextEpochBodies::incrementAndGet);
        }

        // The stale consumer's CAS self-refuses because the seal won. Its
        // unclaimed-help path must not detach the later admission.
        assertFalse(f.region.tryClaim(
                Thread.currentThread(), f.domain, f.epoch));
        RegionWorkerPool pool = new RegionWorkerPool(1);
        Method detach = RegionWorkerPool.class.getDeclaredMethod(
                "detachHelpForLiveClaim", Region.class,
                RegionizedServer.class, boolean.class,
                EpochDomain.class, long.class);
        detach.setAccessible(true);
        assertNull(detach.invoke(pool, f.region, null, false,
                f.domain, f.epoch));
        assertEquals(1, f.region.helpQueue.size());
        assertEquals(0, nextEpochBodies.get());
    }

    @Test
    void topologyCutWaitsPastNullForClaimTailAndDefersPostCutOffer()
            throws Exception {
        Fixture f = fixture(263L);
        EpochDomain next = new EpochDomain();
        next.epoch = 19L;
        next.dispatchedEpoch = 18L;
        Thread claimant = Thread.currentThread();
        assertTrue(f.region.tryClaim(claimant, f.domain, f.epoch));

        AtomicInteger deferredBodies = new AtomicInteger();
        synchronized (f.region.admissionLock) {
            assertTrue(f.region.tryCloseClaimTransactionsForDomainRebase());
            assertTrue(f.region.deferTaskForDomainRebase(
                    deferredBodies::incrementAndGet));
        }
        assertTrue(f.region.queue().isEmpty(),
                "post-cut task entered the finite old-domain FIFO");
        assertFalse(f.region.tryClaim(
                        new Thread(), f.domain, f.epoch),
                "fresh claimant crossed CLOSED_FOR_REBASE");

        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread rebaser = new Thread(() -> {
            try {
                waiterStarted.countDown();
                RegionizedServer.awaitClaimTransactionsForDomainRebase(f.region);
                synchronized (f.region.admissionLock) {
                    if (!f.region.tryBeginDomainRebaseSeal()) {
                        throw new AssertionError("drained cut did not own rebase seal");
                    }
                    f.region.commitDomainRebaseSeal(next, next.epoch - 1L);
                    f.region.releaseDomainRebaseSeal();
                    if (!f.region.flushDomainRebaseDeferredAdmissions()) {
                        throw new AssertionError("post-cut task lost its ready obligation");
                    }
                    f.region.reopenClaimTransactionsAfterDomainRebase();
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            } finally {
                waiterDone.countDown();
            }
        }, "domain-rebase-tail-waiter");
        rebaser.start();
        assertTrue(waiterStarted.await(2, TimeUnit.SECONDS));

        // workOnce releases currentWorker BEFORE its ColorGate/barrier/receipt
        // tail. Rebase must still wait on the transaction receipt at null.
        f.region.release(claimant);
        assertNull(f.region.currentWorker());
        assertEquals(1, f.region.activeClaimTransactions());
        assertFalse(waiterDone.await(100, TimeUnit.MILLISECONDS),
                "rebase overtook the release-to-success-tail window");
        assertEquals(f.domain, f.region.domain);

        // The waiter must not hold admissionLock while awaiting the tail.
        CountDownLatch tailOwnedAdmission = new CountDownLatch(1);
        Thread tail = new Thread(() -> {
            try {
                synchronized (f.region.admissionLock) {
                    tailOwnedAdmission.countDown();
                }
                if (!ColorGate.publishDoneIfQuiescent(
                        f.region, f.domain, f.epoch, true, false)) {
                    throw new AssertionError("old claim tail failed to publish old done");
                }
                f.region.finishClaimTransaction();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "old-domain-claim-tail");
        tail.start();
        assertTrue(tailOwnedAdmission.await(2, TimeUnit.SECONDS),
                "rebase waited while retaining admissionLock");
        tail.join(2_000L);
        rebaser.join(2_000L);

        assertFalse(tail.isAlive());
        assertFalse(rebaser.isAlive());
        assertNull(failure.get());
        assertEquals(next, f.region.domain);
        assertEquals(next.epoch - 1L, f.region.doneEpoch);
        assertFalse(f.region.claimRebaseClosed());
        assertEquals(1, f.region.queue().drain(Integer.MAX_VALUE));
        assertEquals(1, deferredBodies.get());
        assertTrue(f.region.queue().isEmpty());
        assertFalse(f.region.tryClaim(claimant, f.domain, f.epoch),
                "delayed old token entered the replacement domain");
        assertTrue(f.region.tryClaim(claimant, next, next.epoch));
        f.region.release(claimant);
        f.region.finishClaimTransaction();
    }

    @Test
    void topologySafePointFailsInsteadOfStealingAnInFlightDoneSeal() {
        Fixture f = fixture(307L);
        synchronized (f.region.admissionLock) {
            assertTrue(f.region.tryBeginDoneSeal(f.epoch));
            assertFalse(f.region.tryBeginDomainRebaseSeal(),
                    "domain rebase stole an in-flight done seal");
            f.region.releaseDoneSeal();
        }
    }

    @Test
    void domainRebasePublishesAllBaselinesBeforeDomainAndRefusesOldClaimers() {
        Fixture f = fixture(401L);
        EpochDomain next = new EpochDomain();
        next.epoch = 17L;
        next.dispatchedEpoch = 16L;

        RegionizedServer.rebaseDomainEpochAtSafePoint(f.region, next);

        assertEquals(next, f.region.domain);
        assertEquals(16L, f.region.sealedDoneEpoch);
        assertEquals(16L, f.region.doneEpoch);
        assertEquals(16L, f.region.groupedEpoch);
        assertEquals(16L, f.region.entityEpoch);
        assertEquals(16L, f.region.entityEnqueuedEpoch.get());
        assertEquals(16L, f.region.entityBarrierEpoch.get());
        assertTrue(f.region.tryClaim(
                Thread.currentThread(), next, next.epoch));
        // The CAS is legal in the new epoch, but the worker's required
        // ColorGate post-check refuses body entry until dispatch stamps E.
        assertFalse(f.region.groupedEpoch == next.epoch);
        f.region.release(Thread.currentThread());
        f.region.finishClaimTransaction();
    }

    @Test
    void domainRebaseFailsStopOnLiveClaimWithoutChangingAnyStamp() {
        Fixture f = fixture(509L);
        EpochDomain next = new EpochDomain();
        next.epoch = 3L;
        assertTrue(f.region.tryClaim(
                Thread.currentThread(), f.domain, f.epoch));

        assertThrows(IllegalStateException.class,
                () -> RegionizedServer.rebaseDomainEpochAtSafePoint(f.region, next));
        assertEquals(f.domain, f.region.domain);
        assertEquals(f.epoch - 1, f.region.doneEpoch);
        assertEquals(Long.MIN_VALUE, f.region.sealedDoneEpoch);
        f.region.release(Thread.currentThread());
        f.region.finishClaimTransaction();
    }

    @Test
    void delayedOldTokenCannotClaimAfterRebaseToGlobalButFreshTokenCan() {
        Fixture f = fixture(557L);
        EpochDomain oldDomain = f.region.domain;
        long oldEpoch = oldDomain.epoch;
        assertTrue(f.region.doneEpoch < oldEpoch, "arranged old eligibility");

        EpochDomain global = new EpochDomain();
        global.epoch = 23L;
        global.dispatchedEpoch = 22L;
        RegionizedServer.rebaseDomainEpochAtSafePoint(f.region, global);

        assertFalse(f.region.tryClaim(Thread.currentThread(), oldDomain, oldEpoch),
                "delayed old-domain claimant entered the replacement global timeline");
        assertTrue(f.region.tryClaim(Thread.currentThread(), global, global.epoch),
                "a fresh replacement-domain token was rejected");
        f.region.release(Thread.currentThread());
        f.region.finishClaimTransaction();
    }

    @Test
    void barrierContributionRequiresSealAndConcurrentTailsDecrementExactlyOnce()
            throws Exception {
        Fixture f = fixture(613L);
        f.region.entityBarrierEpoch.set(f.epoch - 1);
        f.domain.entityBarrierArmedEpoch = f.epoch;
        f.domain.entityPending.set(1);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        AtomicInteger terminals = new AtomicInteger();
        barrier.whenComplete((ignored, failure) -> terminals.incrementAndGet());
        f.domain.entityBarrier = barrier;
        RegionizedServer runtime = allocateRuntimeWithoutConstructor();

        runtime.contributeEntityBarrier(f.region);
        assertEquals(1, f.domain.entityPending.get(),
                "an unsealed entity commit escaped into the barrier");
        assertFalse(barrier.isDone());

        assertTrue(ColorGate.publishDoneIfQuiescent(
                f.region, f.domain, f.epoch, true, false));
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = contributionThread(runtime, f.region, start, failure, "contribute-1");
        Thread second = contributionThread(runtime, f.region, start, failure, "contribute-2");
        first.start();
        second.start();
        start.countDown();
        first.join(2_000L);
        second.join(2_000L);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(failure.get());
        assertEquals(0, f.domain.entityPending.get());
        assertTrue(barrier.isDone());
        assertFalse(barrier.isCompletedExceptionally());
        assertEquals(1, terminals.get());
        runtime.contributeEntityBarrier(f.region);
        assertEquals(0, f.domain.entityPending.get());
        assertEquals(1, terminals.get());
    }

    private static Thread contributionThread(
            RegionizedServer runtime, Region region, CountDownLatch start,
            AtomicReference<Throwable> failure, String name) {
        return new Thread(() -> {
            try {
                start.await();
                runtime.contributeEntityBarrier(region);
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, name);
    }

    private static RegionizedServer allocateRuntimeWithoutConstructor() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        return (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
    }

    private static Fixture fixture(long epoch) {
        return fixture(new EpochDomain(), epoch);
    }

    private static Fixture islandFixture(long epoch) {
        IslandRegistry.PIsland owner = new IslandRegistry.PIsland(1L, epoch);
        return fixture(owner.domain, epoch);
    }

    private static Fixture fixture(EpochDomain domain, long epoch) {
        domain.epoch = epoch;
        domain.dispatchedEpoch = epoch;
        Region region = new Region(null, 0L);
        region.domain = domain;
        region.doneEpoch = epoch - 1;
        region.groupedEpoch = epoch;
        region.entityEpoch = epoch;
        return new Fixture(region, domain, epoch);
    }

    private record Fixture(Region region, EpochDomain domain, long epoch) {}
}
