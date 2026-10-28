package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EntityTickLeaseTest {

    @Test
    void onlyTheOwningThreadMayRelease() throws Exception {
        EntityTickLease lease = new EntityTickLease();
        assertTrue(lease.tryBegin());
        assertFalse(lease.tryBegin(), "the lease is not reentrant");
        Thread foreign = Thread.ofPlatform().start(() ->
                assertThrows(IllegalStateException.class, lease::end));
        foreign.join();
        lease.end();
        assertThrows(IllegalStateException.class, lease::end, "double release fails loud");
        assertTrue(lease.tryBegin());
        lease.end();
    }

    @Test
    void simultaneousAdmissionsHaveExactlyOneWinner() throws Exception {
        for (int iteration = 0; iteration < 250; iteration++) {
            EntityTickLease lease = new EntityTickLease();
            CyclicBarrier start = new CyclicBarrier(2);
            CountDownLatch attempted = new CountDownLatch(2);
            CountDownLatch releaseWinner = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            Runnable contender = () -> {
                await(start);
                if (lease.tryBegin()) {
                    winners.incrementAndGet();
                    attempted.countDown();
                    await(releaseWinner);
                    lease.end();
                } else {
                    attempted.countDown();
                }
            };
            Thread a = Thread.ofPlatform().start(contender);
            Thread b = Thread.ofPlatform().start(contender);
            attempted.await();
            assertEquals(1, winners.get());
            releaseWinner.countDown();
            a.join();
            b.join();
            assertNull(lease.get());
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
