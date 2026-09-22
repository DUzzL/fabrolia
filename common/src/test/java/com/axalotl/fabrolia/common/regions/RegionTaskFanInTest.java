package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

class RegionTaskFanInTest {
    private static final String FAN_IN =
            "com/axalotl/fabrolia/common/regions/RegionTaskFanIn";
    private static final String REGION_SERVER =
            "com/axalotl/fabrolia/common/regions/RegionizedServer";
    private static final String EXTERNAL_TASK =
            "com/axalotl/fabrolia/common/regions/RegionWorkerPool$ExternalTask";
    private static final String EXTERNAL_PERMIT =
            "com/axalotl/fabrolia/common/regions/RegionWorkerPool$ExternalPermit";
    private static final String CANCELLABLE_ENTRY =
            FAN_IN + "$CancellableQueueEntry";
    private static final String CHUNK_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/ServerChunkCacheMixin";
    private static final String REGION_SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private Field activeField;
    private Field orphanFatalField;
    private boolean previousActive;
    private Object previousOrphanFatal;

    @BeforeEach
    void isolateStandaloneFanInModel() throws Exception {
        activeField = RegionScheduler.class.getDeclaredField("active");
        orphanFatalField = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        activeField.setAccessible(true);
        orphanFatalField.setAccessible(true);
        previousActive = activeField.getBoolean(null);
        previousOrphanFatal = orphanFatalField.get(null);
        activeField.setBoolean(null, false);
        orphanFatalField.set(null, null);
    }

    @AfterEach
    void restoreSchedulerState() throws Exception {
        orphanFatalField.set(null, previousOrphanFatal);
        activeField.setBoolean(null, previousActive);
    }

    @Test
    void healthyRegisteredTaskPublishesOneTerminalAndUnblocksItsCoordinator() {
        RegionTaskFanIn fanIn = new RegionTaskFanIn();
        AtomicInteger bodies = new AtomicInteger();
        RegionTaskFanIn.RegisteredTask task = fanIn.register(bodies::incrementAndGet);

        task.run();
        fanIn.awaitAndRethrow();
        task.run();

        assertEquals(1, bodies.get(), "a terminal helper body must never run twice");
        assertThrows(IllegalStateException.class,
                () -> fanIn.drainAndAwait(new ConcurrentLinkedQueue<>()),
                "duplicate delivery must remain a fan-in invariant failure");
    }

    @Test
    void throwingDrainedHelperCancelsQueuedBodiesAndAwaitsOnlyAlreadyStartedWork() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionTaskFanIn fanIn = new RegionTaskFanIn();
            Queue<Runnable> published = new ConcurrentLinkedQueue<>();
            Error primary = new AssertionError("outer helper failure");
            Error secondary = new AssertionError("registered body failure");
            CountDownLatch remoteEntered = new CountDownLatch(1);
            CountDownLatch releaseRemote = new CountDownLatch(1);
            AtomicInteger completedBodies = new AtomicInteger();
            AtomicInteger queuedBodies = new AtomicInteger();
            AtomicInteger queuedWrappersRan = new AtomicInteger();
            AtomicInteger queuedWrappersCancelled = new AtomicInteger();

            RegionTaskFanIn.RegisteredTask remote = fanIn.register(() -> {
                remoteEntered.countDown();
                await(releaseRemote);
                completedBodies.incrementAndGet();
                throw secondary;
            });
            Thread remoteThread = Thread.ofPlatform().daemon(true).start(remote);
            try {
                assertTrue(remoteEntered.await(1, TimeUnit.SECONDS));

                // Simulate an outer RegionWorkerPool ExternalTask failure. The remote
                // helper is already RUNNING and remains an obligation; both wrappers
                // still queued behind the failure must be cancelled without run().
                published.add(() -> {
                    throw primary;
                });
                published.add(new QueuedWrapper(
                        fanIn.register(queuedBodies::incrementAndGet),
                        queuedWrappersRan, queuedWrappersCancelled, null));
                CountDownLatch queueDrained = new CountDownLatch(1);
                published.add(new QueuedWrapper(
                        fanIn.register(queuedBodies::incrementAndGet),
                        queuedWrappersRan, queuedWrappersCancelled, queueDrained));

                CompletableFuture<Void> coordinator = CompletableFuture.runAsync(() ->
                        fanIn.drainAndAwait(published));
                assertTrue(queueDrained.await(1, TimeUnit.SECONDS),
                        "queued wrapper obligations were not cancelled");
                assertFalse(coordinator.isDone(),
                        "primary failure escaped while a registered sibling was still running");
                assertTrue(published.isEmpty());
                assertEquals(0, queuedWrappersRan.get(),
                        "a queued pool wrapper was entered after fail-stop");
                assertEquals(2, queuedWrappersCancelled.get());
                assertEquals(0, queuedBodies.get(),
                        "a queued helper body started after the first failure");

                releaseRemote.countDown();
                ExecutionException wrapped = assertThrows(ExecutionException.class,
                        () -> coordinator.get(1, TimeUnit.SECONDS));
                assertSame(primary, wrapped.getCause(), "the exact first failure must win");
                assertEquals(1, primary.getSuppressed().length);
                assertSame(secondary, primary.getSuppressed()[0]);
                assertEquals(1, completedBodies.get(),
                        "only the helper already running at failure may finish before rethrow");
            } finally {
                releaseRemote.countDown();
                remoteThread.join(1_000L);
            }
        });
    }

    @Test
    void publicationFailureCancelsUnpublishedWorkButAwaitsAlreadyPublishedBatch() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionTaskFanIn fanIn = new RegionTaskFanIn();
            Error publicationFailure = new OutOfMemoryError("publish rejected after registration");
            CountDownLatch publishedEntered = new CountDownLatch(1);
            CountDownLatch releasePublished = new CountDownLatch(1);
            CountDownLatch queuedCancelled = new CountDownLatch(1);
            AtomicInteger publishedBodies = new AtomicInteger();
            AtomicInteger rejectedBodies = new AtomicInteger();
            AtomicInteger coordinatorBodies = new AtomicInteger();
            AtomicInteger queuedRan = new AtomicInteger();
            AtomicInteger queuedCancellationCount = new AtomicInteger();
            AtomicReference<Runnable> lateRejectedDelivery = new AtomicReference<>();

            RegionTaskFanIn.RegisteredTask alreadyPublished = fanIn.register(() -> {
                publishedEntered.countDown();
                await(releasePublished);
                publishedBodies.incrementAndGet();
            });
            Thread helperThread = Thread.ofPlatform().daemon(true).start(alreadyPublished);
            try {
                assertTrue(publishedEntered.await(1, TimeUnit.SECONDS));
                assertFalse(fanIn.submit(rejectedBodies::incrementAndGet, rejected -> {
                    lateRejectedDelivery.set(rejected);
                    throw publicationFailure;
                }, "unreachable rejection"));
                RegionTaskFanIn.RegisteredTask coordinator =
                        fanIn.register(coordinatorBodies::incrementAndGet);
                assertFalse(coordinator.cancelBeforeStart(null),
                        "the first failure must have closed and cancelled the start gate");

                Queue<Runnable> coordinatorQueue = new ConcurrentLinkedQueue<>();
                coordinatorQueue.add(new QueuedWrapper(coordinator, queuedRan,
                        queuedCancellationCount, queuedCancelled));
                CompletableFuture<Void> result = CompletableFuture.runAsync(() ->
                        fanIn.drainAndAwait(coordinatorQueue));
                assertTrue(queuedCancelled.await(1, TimeUnit.SECONDS));
                assertFalse(result.isDone(),
                        "publication failure bypassed an already-published running batch");
                assertEquals(0, queuedRan.get(),
                        "failure-start gate entered a queued wrapper body");
                assertEquals(1, queuedCancellationCount.get());

                releasePublished.countDown();
                ExecutionException wrapped = assertThrows(ExecutionException.class,
                        () -> result.get(1, TimeUnit.SECONDS));
                assertSame(publicationFailure, wrapped.getCause());
                assertEquals(1, publishedBodies.get());

                // A late queue delivery after cancellation may invoke the wrapper, but
                // neither the rejected batch nor the coordinator body may execute.
                assertNotNull(lateRejectedDelivery.get());
                lateRejectedDelivery.get().run();
                coordinator.run();
                assertEquals(0, rejectedBodies.get());
                assertEquals(0, coordinatorBodies.get());
            } finally {
                releasePublished.countDown();
                helperThread.join(1_000L);
            }
        });
    }

    @Test
    void explicitRejectionAndDuplicateDeliveryNeverRunABodyTwice() {
        RegionTaskFanIn rejectedFanIn = new RegionTaskFanIn();
        AtomicInteger rejectedBodies = new AtomicInteger();
        AtomicReference<Runnable> rejectedDelivery = new AtomicReference<>();
        assertFalse(rejectedFanIn.submit(rejectedBodies::incrementAndGet, task -> {
            rejectedDelivery.set(task);
            return false;
        }, "explicit rejection"));
        assertNotNull(rejectedDelivery.get());
        rejectedDelivery.get().run();
        assertEquals(0, rejectedBodies.get());
        assertThrows(IllegalStateException.class,
                () -> rejectedFanIn.drainAndAwait(new ConcurrentLinkedQueue<>()));

        RegionTaskFanIn duplicateFanIn = new RegionTaskFanIn();
        AtomicInteger duplicateBodies = new AtomicInteger();
        RegionTaskFanIn.RegisteredTask duplicate =
                duplicateFanIn.register(duplicateBodies::incrementAndGet);
        duplicate.run();
        duplicate.run();
        assertEquals(1, duplicateBodies.get());
        assertThrows(IllegalStateException.class,
                () -> duplicateFanIn.drainAndAwait(new ConcurrentLinkedQueue<>()));
    }

    @Test
    void productionPoolWrapperCancelsNestedFanInBeforeClosingItsPermit() throws Exception {
        ClassNode wrapper = readClass(EXTERNAL_TASK);
        assertTrue(wrapper.interfaces.contains(CANCELLABLE_ENTRY),
                "pool wrapper must expose cancellation without entering run()");
        MethodNode cancel = method(wrapper, "cancelQueuedBeforeStart");
        int nested = callIndex(cancel, CANCELLABLE_ENTRY, "cancelQueuedBeforeStart");
        int permit = callIndex(cancel, EXTERNAL_PERMIT, "cancelBeforeRun");
        assertTrue(nested >= 0 && permit > nested,
                "queued cancellation must close nested fan-in then the pool permit");
    }

    


    

    private static void assertCoordinatorWiring(MethodNode method) {
        int register = callIndex(method, FAN_IN, "register");
        int submit = callIndex(method, FAN_IN, "submit");
        int drain = callIndex(method, FAN_IN, "drainAndAwait");
        assertEquals(1, callCount(method, FAN_IN, "register"),
                method.name + " must register exactly its direct coordinator");
        assertEquals(1, callCount(method, FAN_IN, "submit"),
                method.name + " must use the exception-safe helper publication API");
        assertTrue(register >= 0 && submit > register && drain > submit,
                method.name + " lost coordinator/register-safe-submit/fan-in ordering");
        assertEquals(1, callCount(method, FAN_IN, "drainAndAwait"));
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream in = RegionTaskFanInTest.class.getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(in, internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        ArrayList<MethodNode> matches = new ArrayList<>();
        for (MethodNode candidate : node.methods) {
            if (candidate.name.equals(name)) matches.add(candidate);
        }
        if (matches.size() != 1) {
            fail(node.name + "." + name + " expected one method, found " + matches.size());
        }
        return matches.getFirst();
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int lastCallIndex(MethodNode method) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) found = index;
            index++;
        }
        return found;
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static final class QueuedWrapper
            implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        private final RegionTaskFanIn.RegisteredTask nested;
        private final AtomicInteger runs;
        private final AtomicInteger cancellations;
        private final CountDownLatch cancelled;

        private QueuedWrapper(RegionTaskFanIn.RegisteredTask nested,
                              AtomicInteger runs,
                              AtomicInteger cancellations,
                              CountDownLatch cancelled) {
            this.nested = nested;
            this.runs = runs;
            this.cancellations = cancellations;
            this.cancelled = cancelled;
        }

        @Override
        public void run() {
            runs.incrementAndGet();
            nested.run();
        }

        @Override
        public void cancelQueuedBeforeStart() {
            cancellations.incrementAndGet();
            nested.cancelQueuedBeforeStart();
            if (cancelled != null) cancelled.countDown();
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
}
