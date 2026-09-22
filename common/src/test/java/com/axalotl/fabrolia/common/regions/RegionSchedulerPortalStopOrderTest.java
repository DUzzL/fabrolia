package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises success-body authorization at the scheduler stop boundary. */
final class RegionSchedulerPortalStopOrderTest {

    @Test
    void terminatedPoolItselfRefusesAReleasedPortalStyleSuccessBody() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            AtomicBoolean firstProgress = new AtomicBoolean(true);
            AtomicInteger bodies = new AtomicInteger();
            pool.start();
            try {
                pool.beginDraining(Thread.currentThread());
                pool.stop(() -> {
                    if (firstProgress.compareAndSet(true, false)) {
                        pool.runSuccessBodyIfHealthy(bodies::incrementAndGet);
                    }
                });

                assertEquals(1, bodies.get(),
                        "the drain owner must execute an accepted portal body while W is open");
                IllegalStateException refusal = assertThrows(IllegalStateException.class,
                        () -> pool.runSuccessBodyIfHealthy(bodies::incrementAndGet));
                assertSame(refusal, pool.fatalFailureOrNull(),
                        "post-close entry refusal must be the generation winner, not a skipped body");
                assertEquals(1, bodies.get(),
                        "the refused post-close body must never enter");
            } finally {
                if (!pool.isTerminated()) pool.stop();
            }
        });
    }

    @Test
    void stoppingLifecycleOwnerCanFinishPortalDrainAfterHealthyPoolJoin() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool terminatedPool = new RegionWorkerPool(1);
            terminatedPool.start();
            terminatedPool.stop();

            Field lockField = schedulerField("LIFECYCLE_LOCK");
            Field poolField = schedulerField("pool");
            Field transitionPoolField = schedulerField("transitionPool");
            Field lifecycleStateField = schedulerField("lifecycleState");
            Field lifecycleOwnerField = schedulerField("lifecycleOwner");
            Field orphanFailureField = schedulerField("orphanFatalFailure");
            Field activeField = schedulerField("active");
            Field acceptingField = schedulerField("accepting");
            Object lifecycleLock = lockField.get(null);

            Object oldPool;
            Object oldTransitionPool;
            Object oldLifecycleState;
            Object oldLifecycleOwner;
            Object oldOrphanFailure;
            boolean oldActive;
            boolean oldAccepting;
            synchronized (lifecycleLock) {
                oldPool = poolField.get(null);
                oldTransitionPool = transitionPoolField.get(null);
                oldLifecycleState = lifecycleStateField.get(null);
                oldLifecycleOwner = lifecycleOwnerField.get(null);
                oldOrphanFailure = orphanFailureField.get(null);
                oldActive = activeField.getBoolean(null);
                oldAccepting = acceptingField.getBoolean(null);

                poolField.set(null, terminatedPool);
                transitionPoolField.set(null, null);
                lifecycleStateField.set(null,
                        enumConstant(lifecycleStateField.getType(), "STOPPING"));
                lifecycleOwnerField.set(null, Thread.currentThread());
                orphanFailureField.set(null, null);
                activeField.setBoolean(null, false);
                acceptingField.setBoolean(null, false);
            }

            try {
                AtomicInteger ownerBodies = new AtomicInteger();
                RegionScheduler.runSuccessBodyIfHealthy(ownerBodies::incrementAndGet);
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    ownerBodies.incrementAndGet();
                    RegionScheduler.runSuccessBodyIfHealthy(
                            ownerBodies::incrementAndGet);
                });
                assertEquals(3, ownerBodies.get(),
                        "the exact STOPPING owner must finish post-join bodies, including "
                                + "the nested portal admission shape");
                assertFalse(terminatedPool.hasFatalFailure(),
                        "authorized quiesced cleanup must not invent a generation failure");

                AtomicInteger outsiderBodies = new AtomicInteger();
                AtomicReference<Throwable> outsiderFailure = new AtomicReference<>();
                Thread outsider = new Thread(() -> {
                    try {
                        RegionScheduler.runSuccessBodyIfHealthy(
                                outsiderBodies::incrementAndGet);
                    } catch (Throwable thrown) {
                        outsiderFailure.set(thrown);
                    }
                }, "fabrolia-post-join-portal-outsider");
                outsider.start();
                outsider.join(1_000L);

                assertFalse(outsider.isAlive());
                assertTrue(outsiderFailure.get() instanceof IllegalStateException,
                        "a non-owner must still be refused after the pool generation closes");
                assertEquals(0, outsiderBodies.get());
                assertSame(outsiderFailure.get(), terminatedPool.fatalFailureOrNull(),
                        "unauthorized post-close entry must select the exact refusal as W");
            } finally {
                synchronized (lifecycleLock) {
                    poolField.set(null, oldPool);
                    transitionPoolField.set(null, oldTransitionPool);
                    lifecycleStateField.set(null, oldLifecycleState);
                    lifecycleOwnerField.set(null, oldLifecycleOwner);
                    orphanFailureField.set(null, oldOrphanFailure);
                    activeField.setBoolean(null, oldActive);
                    acceptingField.setBoolean(null, oldAccepting);
                }
            }
        });
    }

    private static Field schedulerField(String name) throws ReflectiveOperationException {
        Field field = RegionScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), name);
    }
}
