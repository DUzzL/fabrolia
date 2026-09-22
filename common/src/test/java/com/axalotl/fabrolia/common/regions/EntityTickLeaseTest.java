package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EntityTickLeaseTest {

    @Test
    void normalAndReservationUseTheSameOwnerSlot() {
        EntityTickLease lease = new EntityTickLease();
        Object token = new Object();

        assertTrue(lease.tryBegin());
        assertEquals(EntityTickLease.ReservationResult.BUSY, lease.tryReserve(token));
        assertTrue(lease.isOwnedByCurrentThread());
        lease.end();

        assertEquals(EntityTickLease.ReservationResult.ACQUIRED, lease.tryReserve(token));
        assertTrue(lease.hasReservation());
        assertFalse(lease.tryBegin());
        assertTrue(lease.beginReserved(token));
        assertTrue(lease.isReservedBy(token));
        assertFalse(lease.isOwnedByCurrentThread());
        assertTrue(lease.cancelReserved(token));
        assertTrue(lease.tryBegin());
        lease.end();
    }

    @Test
    void wrongIdentityCannotCancelOrTransferReservation() {
        EntityTickLease lease = new EntityTickLease();
        Object token = new Object();
        Object stale = new Object();

        assertEquals(EntityTickLease.ReservationResult.ACQUIRED, lease.tryReserve(token));
        assertEquals(EntityTickLease.CancelResult.FOREIGN,
                lease.cancelReservedExact(stale));
        assertFalse(lease.beginReserved(stale));
        assertTrue(lease.isReservedBy(token));
        assertEquals(EntityTickLease.CancelResult.RELEASED,
                lease.cancelReservedExact(token));
        assertEquals(EntityTickLease.CancelResult.FREE,
                lease.cancelReservedExact(token));
    }

    @Test
    void simultaneousNormalAndReservationHaveExactlyOneWinner() throws Exception {
        for (int iteration = 0; iteration < 250; iteration++) {
            EntityTickLease lease = new EntityTickLease();
            Object token = new Object();
            CyclicBarrier start = new CyclicBarrier(3);
            CountDownLatch attempted = new CountDownLatch(2);
            CountDownLatch releaseWinner = new CountDownLatch(1);
            AtomicBoolean normal = new AtomicBoolean();
            AtomicBoolean reserved = new AtomicBoolean();

            Thread normalThread = Thread.ofPlatform().start(() -> {
                await(start);
                if (lease.tryBegin()) {
                    normal.set(true);
                    attempted.countDown();
                    await(releaseWinner);
                    lease.end();
                } else {
                    attempted.countDown();
                }
            });
            Thread reservationThread = Thread.ofPlatform().start(() -> {
                await(start);
                if (lease.tryReserve(token) == EntityTickLease.ReservationResult.ACQUIRED) {
                    reserved.set(true);
                    attempted.countDown();
                    await(releaseWinner);
                    assertTrue(lease.cancelReserved(token));
                } else {
                    attempted.countDown();
                }
            });
            start.await();
            attempted.await();
            releaseWinner.countDown();
            normalThread.join();
            reservationThread.join();
            assertEquals(1, (normal.get() ? 1 : 0) + (reserved.get() ? 1 : 0));
        }
    }

    @Test
    void waitForActiveBodyNeverForceOpensAndPublishesPlainWrite() throws Exception {
        EntityTickLease lease = new EntityTickLease();
        int[] plain = {0};
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);

        Thread body = Thread.ofPlatform().start(() -> {
            assertTrue(lease.tryBegin());
            bodyStarted.countDown();
            await(releaseBody);
            plain[0] = 42;
            lease.end();
        });
        bodyStarted.await();
        AtomicInteger waits = new AtomicInteger();
        CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
            try {
                while (!lease.awaitFreeNanos(100_000L)) waits.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            assertEquals(42, plain[0]);
        });
        while (waits.get() == 0) Thread.onSpinWait();
        assertFalse(waiter.isDone());
        releaseBody.countDown();
        waiter.join();
        body.join();
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
