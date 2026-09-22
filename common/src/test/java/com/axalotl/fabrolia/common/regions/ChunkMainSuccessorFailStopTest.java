package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.server.ServerChunkCacheMixin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Exact-W runtime and wiring pins for level-wide chunk-Main queue pumps. */
final class ChunkMainSuccessorFailStopTest {

    @Test
    void queuedChunkRequestRefusalTerminalizesItsReceiptWithExactW() throws Exception {
        Field poolField = field(RegionScheduler.class, "pool");
        Field orphanField = field(RegionScheduler.class, "orphanFatalFailure");
        Object previousPool = poolField.get(null);
        Object previousOrphan = orphanField.get(null);
        Method queuedBody = chunkRequestBody();
        AssertionError winner = new AssertionError("queued chunk-Main refusal W");
        AtomicBoolean entered = new AtomicBoolean();
        CompletableFuture<Object> receipt = new CompletableFuture<>();
        try {
            poolField.set(null, null);
            orphanField.set(null, winner);

            InvocationTargetException refused = assertThrows(
                    InvocationTargetException.class,
                    () -> queuedBody.invoke(null, receipt,
                            (Runnable) () -> entered.set(true)));
            assertSame(winner, refused.getCause());
            assertFalse(entered.get(), "known W must refuse the queued vanilla body");
            assertExceptionalCause(receipt, winner);
        } finally {
            poolField.set(null, previousPool);
            orphanField.set(null, previousOrphan);
        }
    }

    @Test
    void enqueueThenThrowReleasesDetachedLambdaOnlyAfterExactReceiptTerminal() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            Field poolField = field(RegionScheduler.class, "pool");
            Field orphanField = field(RegionScheduler.class, "orphanFatalFailure");
            Object previousPool = poolField.get(null);
            Object previousOrphan = orphanField.get(null);
            Class<?> publicationType = Class.forName(
                    ServerChunkCacheMixin.class.getName()
                            + "$FabroliaChunkMainPublication");
            var constructor = publicationType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object publication = constructor.newInstance();
            Method cancel = publicationType.getDeclaredMethod("cancel");
            cancel.setAccessible(true);
            Method publishedBody = ServerChunkCacheMixin.class.getDeclaredMethod(
                    "fabrolia$runPublishedChunkMainRequest", publicationType,
                    CompletableFuture.class, Runnable.class);
            publishedBody.setAccessible(true);
            Method completeFailure = ServerChunkCacheMixin.class.getDeclaredMethod(
                    "fabrolia$completeChunkRequestFailure",
                    CompletableFuture.class, Throwable.class);
            completeFailure.setAccessible(true);
            AssertionError winner = new AssertionError("enqueue-then-throw W");
            CompletableFuture<Object> receipt = new CompletableFuture<>();
            CountDownLatch detached = new CountDownLatch(1);
            AtomicBoolean entered = new AtomicBoolean();
            AtomicReference<Throwable> surfaced = new AtomicReference<>();
            try {
                poolField.set(null, null);
                orphanField.set(null, null);
                Thread mainTask = new Thread(() -> {
                    detached.countDown();
                    try {
                        publishedBody.invoke(null, publication, receipt,
                                (Runnable) () -> entered.set(true));
                    } catch (InvocationTargetException wrapped) {
                        surfaced.set(wrapped.getCause());
                    } catch (Throwable reflectionFailure) {
                        surfaced.set(reflectionFailure);
                    }
                }, "fabrolia-enqueue-then-throw-race");
                mainTask.start();
                assertTrue(detached.await(1, TimeUnit.SECONDS));

                assertSame(winner, completeFailure.invoke(null, receipt, winner));
                // Model the old scheduler generation being detached before a
                // cancelled queue node is eventually observed.  Its typed
                // future receipt, not mutable global state, owns exact W.
                orphanField.set(null, null);
                cancel.invoke(publication);
                mainTask.join(1_000L);

                assertFalse(mainTask.isAlive());
                assertFalse(entered.get(),
                        "an enqueue-then-throw lambda may not enter the request body");
                assertSame(winner, surfaced.get());
                assertExceptionalCause(receipt, winner);
            } finally {
                poolField.set(null, previousPool);
                orphanField.set(null, previousOrphan);
            }
        });
    }

    @Test
    void peerWinnerDuringEnteredChunkRequestTerminalizesWithoutDeadlock() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            Field poolField = field(RegionScheduler.class, "pool");
            Field orphanField = field(RegionScheduler.class, "orphanFatalFailure");
            Object previousPool = poolField.get(null);
            Object previousOrphan = orphanField.get(null);
            RegionWorkerPool generation = new RegionWorkerPool(1);
            Method queuedBody = chunkRequestBody();
            AssertionError winner = new AssertionError("entered chunk-Main peer W");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> surfaced = new AtomicReference<>();
            CompletableFuture<Object> receipt = new CompletableFuture<>();
            generation.start();
            try {
                poolField.set(null, generation);
                orphanField.set(null, null);
                Thread mainTask = new Thread(() -> {
                    try {
                        queuedBody.invoke(null, receipt, (Runnable) () -> {
                            entered.countDown();
                            await(release);
                        });
                    } catch (InvocationTargetException wrapped) {
                        surfaced.set(wrapped.getCause());
                    } catch (Throwable reflectionFailure) {
                        surfaced.set(reflectionFailure);
                    }
                }, "fabrolia-chunk-main-W-race");
                mainTask.start();
                assertTrue(entered.await(1, TimeUnit.SECONDS));

                generation.publishFatalFailure(winner);
                release.countDown();
                mainTask.join(1_000L);

                assertFalse(mainTask.isAlive(),
                        "an entered request must finish its permit/receipt after peer W");
                assertSame(winner, surfaced.get());
                assertExceptionalCause(receipt, winner);
            } finally {
                release.countDown();
                if (!generation.isTerminated()) {
                    try {
                        generation.stop();
                    } catch (Throwable expectedWinner) {
                        assertSame(winner, expectedWinner);
                    }
                }
                poolField.set(null, previousPool);
                orphanField.set(null, previousOrphan);
            }
        });
    }

    @Test
    void compiledChunkRequestWrapperAdmitsThenTerminalizesBeforeRethrow()
            throws Exception {
        MethodNode wrapper = method(readClass(ServerChunkCacheMixin.class),
                "fabrolia$runChunkMainRequest",
                "(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Runnable;)V");
        int admission = callIndex(wrapper, RegionScheduler.class,
                "runSuccessBodyIfHealthy");
        int terminal = callIndex(wrapper, ServerChunkCacheMixin.class,
                "fabrolia$completeChunkRequestFailure");
        int rethrow = callIndex(wrapper, ServerChunkCacheMixin.class,
                "fabrolia$rethrow");
        assertTrue(admission >= 0 && terminal > admission && rethrow > terminal,
                "refusal/body failure must select W and terminalize the receipt before escape");
        assertEquals(1, callCount(wrapper, RegionScheduler.class,
                "runSuccessBodyIfHealthy"));

        MethodNode published = method(readClass(ServerChunkCacheMixin.class),
                "fabrolia$runPublishedChunkMainRequest",
                "(Lcom/axalotl/fabrolia/common/mixin/server/"
                        + "ServerChunkCacheMixin$FabroliaChunkMainPublication;"
                        + "Ljava/util/concurrent/CompletableFuture;Ljava/lang/Runnable;)V");
        int receipt = callIndexByName(published, "awaitAccepted");
        int body = callIndex(published, ServerChunkCacheMixin.class,
                "fabrolia$runChunkMainRequest");
        assertTrue(receipt >= 0 && body > receipt,
                "a detached lambda cannot enter before execute commits its receipt");
    }


    private static Method chunkRequestBody() throws ReflectiveOperationException {
        Method method = ServerChunkCacheMixin.class.getDeclaredMethod(
                "fabrolia$runChunkMainRequest", CompletableFuture.class, Runnable.class);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void assertExceptionalCause(
            CompletableFuture<?> future, Throwable expected) {
        CompletionException terminal = assertThrows(CompletionException.class, future::join);
        assertSame(expected, terminal.getCause());
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                if (!latch.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting for race handoff");
                }
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }


    private static int callIndex(MethodNode method, Class<?> owner, String name) {
        String internalOwner = Type.getInternalName(owner);
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && internalOwner.equals(call.owner) && name.equals(call.name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int callCount(MethodNode method, Class<?> owner, String name) {
        String internalOwner = Type.getInternalName(owner);
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && internalOwner.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static int callIndexByName(MethodNode method, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && name.equals(call.name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name)
                        && descriptor.equals(method.desc))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        owner.name + " is missing " + name + descriptor));
    }

    private static ClassNode readClass(Class<?> type) throws IOException {
        String resource = "/" + Type.getInternalName(type) + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
