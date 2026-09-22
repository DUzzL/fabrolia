package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact topology-cut receipts for claim-free, region-targeted main-safe work. */
class RegionMainSafeRebaseReceiptTest {

    @Test
    void runningMainSafeBodyPinsDomainCutThroughItsPermitTerminal() throws Exception {
        RegionWorkerPool pool = new RegionWorkerPool(1);
        activateWithoutWorkers(pool);
        Region region = region(31L);
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable external = reserveExternalTask(pool, () -> {
            bodyEntered.countDown();
            await(releaseBody);
        });

        synchronized (region.admissionLock) {
            assertTrue(attachMainSafeReceipt(external, region));
        }
        Thread runner = new Thread(external, "main-safe-rebase-receipt");
        runner.setUncaughtExceptionHandler((thread, thrown) -> failure.set(thrown));
        runner.start();
        assertTrue(bodyEntered.await(2, TimeUnit.SECONDS));

        synchronized (region.admissionLock) {
            assertTrue(region.tryCloseClaimTransactionsForDomainRebase());
        }
        assertEquals(1, region.activeClaimTransactions());
        assertFalse(region.claimTransactionsDrainedForDomainRebase(),
                "rebase overtook a running claim-free region body");

        releaseBody.countDown();
        runner.join(2_000L);
        assertFalse(runner.isAlive());
        assertSame(null, failure.get());
        assertTrue(region.claimTransactionsDrainedForDomainRebase(),
                "the body terminal stranded its topology receipt");
        assertEquals(0L, longField(pool, "externalObligations"),
                "permit terminal did not precede the region receipt terminal");
        synchronized (region.admissionLock) {
            region.reopenClaimTransactionsAfterDomainRebase();
        }
        pool.stop();
    }

    @Test
    void cancellationClosesQueuedMainSafeReceiptExactlyOnceWithoutBodyEntry()
            throws Exception {
        RegionWorkerPool pool = new RegionWorkerPool(1);
        activateWithoutWorkers(pool);
        Region region = region(43L);
        CancellableProbe probe = new CancellableProbe();
        Runnable external = reserveExternalTask(pool, probe);
        synchronized (region.admissionLock) {
            assertTrue(attachMainSafeReceipt(external, region));
            assertTrue(region.tryCloseClaimTransactionsForDomainRebase());
        }
        AssertionError primary = new AssertionError("main-safe-cancel-W");

        AssertionError observed = assertThrows(AssertionError.class, () ->
                ((RegionTaskFanIn.CancellableQueueEntry) external)
                        .cancelQueuedBeforeStartWithFailure(primary));
        assertSame(primary, observed);
        assertEquals(0, probe.bodyRuns.get());
        assertEquals(1, probe.cancellations.get());
        assertSame(primary, probe.cancellationPrimary.get());
        assertTrue(region.claimTransactionsDrainedForDomainRebase());
        assertEquals(0L, longField(pool, "externalObligations"));

        // A repeated terminal observer is idempotent for the region receipt.
        assertThrows(AssertionError.class, () ->
                ((RegionTaskFanIn.CancellableQueueEntry) external)
                        .cancelQueuedBeforeStartWithFailure(primary));
        assertEquals(1, probe.cancellations.get());
        assertEquals(0, region.activeClaimTransactions());
        synchronized (region.admissionLock) {
            region.reopenClaimTransactionsAfterDomainRebase();
        }
        pool.stop();
    }

    @Test
    void failedGenerationDrainsPostRebaseLaneAndItsExternalPermitExactlyOnce()
            throws Exception {
        RegionWorkerPool pool = new RegionWorkerPool(1);
        activateWithoutWorkers(pool);
        Region region = region(59L);
        CancellableProbe probe = new CancellableProbe();
        Runnable external = reserveExternalTask(pool, probe);
        synchronized (region.admissionLock) {
            assertTrue(region.tryCloseClaimTransactionsForDomainRebase());
            assertTrue(region.deferTaskForDomainRebase(external));
            assertTrue(region.deferReadyForDomainRebase());
        }
        AssertionError primary = new AssertionError("deferred-region-W");

        assertFalse(RegionScheduler.failedGenerationRegionQueuesEmpty(region));
        assertSame(primary, RegionScheduler.cancelFailedGenerationRegionQueues(
                pool, region, primary));
        assertTrue(RegionScheduler.failedGenerationRegionQueuesEmpty(region));
        assertEquals(0, probe.bodyRuns.get());
        assertEquals(1, probe.cancellations.get());
        assertSame(primary, probe.cancellationPrimary.get());
        assertEquals(0L, longField(pool, "externalObligations"));

        assertSame(primary, RegionScheduler.cancelFailedGenerationRegionQueues(
                pool, region, primary));
        assertEquals(1, probe.cancellations.get(),
                "a fixed-point resweep cancelled one accepted permit twice");
        synchronized (region.admissionLock) {
            region.reopenClaimTransactionsAfterDomainRebase();
        }
        pool.stop();
    }


    private static Region region(long epoch) {
        Region region = new Region(null, 0L);
        EpochDomain domain = new EpochDomain();
        domain.epoch = epoch;
        domain.dispatchedEpoch = epoch;
        region.domain = domain;
        region.doneEpoch = epoch - 1L;
        region.groupedEpoch = epoch - 1L;
        region.entityEpoch = epoch - 1L;
        return region;
    }

    private static boolean attachMainSafeReceipt(Runnable external, Region region) {
        try {
            Method attach = external.getClass().getDeclaredMethod(
                    "attachMainSafeBodyReceipt",
                    Region.class, EpochDomain.class, long.class);
            attach.setAccessible(true);
            return (boolean) attach.invoke(
                    external, region, region.domain, region.domain.epoch);
        } catch (InvocationTargetException invocationFailure) {
            Throwable cause = invocationFailure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static Runnable reserveExternalTask(RegionWorkerPool pool, Runnable body) {
        try {
            Method reserve = RegionWorkerPool.class.getDeclaredMethod(
                    "reserveExternalTask", Runnable.class);
            reserve.setAccessible(true);
            Runnable task = (Runnable) reserve.invoke(pool, body);
            if (task == null) throw new AssertionError("external reservation rejected");
            return task;
        } catch (InvocationTargetException invocationFailure) {
            Throwable cause = invocationFailure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static void activateWithoutWorkers(RegionWorkerPool pool) {
        setBooleanField(pool, "running", true);
        setBooleanField(pool, "accepting", true);
    }

    private static void setBooleanField(
            RegionWorkerPool pool, String fieldName, boolean value) {
        try {
            var field = RegionWorkerPool.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(pool, value);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static long longField(RegionWorkerPool pool, String fieldName) {
        try {
            var field = RegionWorkerPool.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getLong(pool);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }


    private static final class CancellableProbe
            implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        private final AtomicInteger bodyRuns = new AtomicInteger();
        private final AtomicInteger cancellations = new AtomicInteger();
        private final AtomicReference<Throwable> cancellationPrimary =
                new AtomicReference<>();

        @Override
        public void run() {
            bodyRuns.incrementAndGet();
        }

        @Override
        public void cancelQueuedBeforeStart() {
            cancelQueuedBeforeStartWithFailure(null);
        }

        @Override
        public void cancelQueuedBeforeStartWithFailure(Throwable primary) {
            cancellations.incrementAndGet();
            cancellationPrimary.compareAndSet(null, primary);
        }
    }
}
