package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pearl's retained Main gameplay lane accepts a live producer after the stop cut. */
class PearlContinuationShutdownTest {
    @Test
    void admittedWorkerPublishesItsMainBodyDuringHealthyStopBeforeSave() throws Exception {
        Field poolField = field(RegionScheduler.class, "pool");
        Field serverField = field(RegionScheduler.class, "server");
        Field orphanField = field(RegionScheduler.class, "orphanFatalFailure");
        Object previousPool = poolField.get(null);
        Object previousServer = serverField.get(null);
        Object previousOrphan = orphanField.get(null);
        RegionWorkerPool pool = new RegionWorkerPool(1);
        CountDownLatch producerEntered = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1);
        AtomicInteger teleports = new AtomicInteger();
        AtomicReference<Thread> bodyThread = new AtomicReference<>();
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        try {
            poolField.set(null, pool);
            serverField.set(null, null);
            orphanField.set(null, null);
            pool.start();
            assertTrue(pool.enqueueGlobal(() -> {
                producerThread.set(Thread.currentThread());
                producerEntered.countDown();
                try {
                    if (!stopping.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("shutdown never reached its progress boundary");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                RegionScheduler.enqueueGlobalMainOnly(() -> {
                    bodyThread.set(Thread.currentThread());
                    teleports.incrementAndGet();
                });
            }));
            assertTrue(producerEntered.await(5, TimeUnit.SECONDS));
            pool.stop(() -> {
                stopping.countDown();
                pool.drainMainSafeAll(true);
            });

            int savedTeleports = teleports.get();
            assertTrue(producerThread.get() instanceof RegionWorkerThread);
            assertEquals(1, savedTeleports,
                    "save/disconnect boundary overtook the accepted owner continuation");
            assertSame(Thread.currentThread(), bodyThread.get());
            assertTrue(pool.isTerminated());
        } finally {
            stopping.countDown();
            try {
                if (!pool.isTerminated()) pool.stop(() -> pool.drainMainSafeAll(true));
            } finally {
                poolField.set(null, previousPool);
                serverField.set(null, previousServer);
                orphanField.set(null, previousOrphan);
            }
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
