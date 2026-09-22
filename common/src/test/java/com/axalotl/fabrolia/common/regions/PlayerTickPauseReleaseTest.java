package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.MinecraftServer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Fail-closed ownership tests for exact-pool pause release at server-tick cleanup. */
class PlayerTickPauseReleaseTest {

    private static final sun.misc.Unsafe UNSAFE;

    static {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Test
    void failedReleaseWhilePhysicalFenceRemainsKeepsPhaseLatched() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            Field schedulerPool = staticField(RegionScheduler.class, "pool");
            Object previousPool = schedulerPool.get(null);
            AtomicReference<Object> current = currentPhase();
            MinecraftServer server = (MinecraftServer) UNSAFE.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            Object phase = newGlobalPhase(server);
            setBoolean(phase, "pauseHeld", true);
            assertTrue(current.compareAndSet(null, phase));
            pool.start();
            schedulerPool.set(null, pool);
            try {
                pool.pauseExactTurnAndAwait(() -> {});
                AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
                Thread foreign = Thread.ofPlatform().name("foreign-pause-release").start(() ->
                        releaseFailure.set((Throwable) invoke(phase, "releasePause")));
                foreign.join();

                assertTrue(releaseFailure.get() instanceof IllegalStateException,
                        "a non-owner release unexpectedly succeeded");
                assertTrue(pool.ownsPausedExactTurn(Thread.currentThread()),
                        "the test did not retain the real physical-main fence");
                assertTrue((boolean) invoke(phase, "pauseStillHeld"),
                        "a failed release mirrored an unsafe reopen");
                assertSame(phase, current.get(),
                        "CURRENT must remain latched while the real pool fence remains held");
            } finally {
                if (pool.ownsPausedExactTurn(Thread.currentThread())) pool.resumeExactTurn();
                setBoolean(phase, "pauseHeld", false);
                current.compareAndSet(phase, null);
                if (!pool.isTerminated()) pool.stop();
                schedulerPool.set(null, previousPool);
            }
        });
    }

    @Test
    void deferredReplayFailureAfterActualReopenIsReportedAndClearsPhase() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RegionWorkerPool pool = new RegionWorkerPool(1);
            Field schedulerPool = staticField(RegionScheduler.class, "pool");
            Object previousPool = schedulerPool.get(null);
            AtomicReference<Object> current = currentPhase();
            MinecraftServer server = (MinecraftServer) UNSAFE.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            Object phase = newGlobalPhase(server);
            setBoolean(phase, "pauseHeld", true);
            setBoolean(phase, "connectionsBarrierPassed", true);
            assertTrue(current.compareAndSet(null, phase));
            RuntimeException replayFailure = new RuntimeException("deferred replay failure");
            pool.start();
            schedulerPool.set(null, pool);
            try {
                pool.pauseExactTurnAndAwait(() -> {});
                assertTrue(CompletableFuture.supplyAsync(() ->
                        pool.publishExternalRoot(permit -> {
                            throw replayFailure;
                        })).join());

                Throwable returned = RegionalPlayerEntityTicksV2.finishServerTick();
                assertSame(replayFailure, returned,
                        "replay failure must remain the primary cleanup result");
                assertFalse(pool.ownsPausedExactTurn(Thread.currentThread()),
                        "resume reported a replay error but failed to reopen the real pool");
                assertFalse((boolean) invoke(phase, "pauseStillHeld"));
                assertNull(current.get(),
                        "an already reopened replay failure must not poison the next tick identity");
            } finally {
                if (pool.ownsPausedExactTurn(Thread.currentThread())) {
                    pool.resumeExactTurn();
                }
                current.compareAndSet(phase, null);
                Throwable stopFailure = null;
                if (!pool.isTerminated()) {
                    try {
                        pool.stop();
                    } catch (Throwable exactFailure) {
                        stopFailure = exactFailure;
                    }
                }
                schedulerPool.set(null, previousPool);
                assertSame(replayFailure, stopFailure,
                        "test teardown must observe the same fail-stop W; it may not clear it");
            }
        });
    }

    @Test
    void finishReleasesAndAbortRetainsTheRealFenceBeforeClearingCurrent() throws Exception {
        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        MethodNode finish = method(coordinator, "finishServerTick", "()Ljava/lang/Throwable;");
        int finishRelease = callIndex(finish, coordinator.name + "$GlobalPhase", "releasePause");
        int finishHeld = callIndexAfter(finish, coordinator.name + "$GlobalPhase",
                "pauseStillHeld", finishRelease);
        int finishClear = callIndexAfter(finish,
                "java/util/concurrent/atomic/AtomicReference", "compareAndSet", finishHeld);
        assertTrue(finishRelease >= 0 && finishHeld > finishRelease && finishClear > finishHeld,
                "finishServerTick must retain CURRENT until releasePause proves the fence reopened");

        MethodNode abort = method(coordinator, "abortServerTick", "()Ljava/lang/Throwable;");
        int abortRelease = callIndex(abort, coordinator.name + "$GlobalPhase", "releasePause");
        int abortHeld = callIndex(abort, coordinator.name + "$GlobalPhase", "pauseStillHeld");
        int abortClear = callIndexAfter(abort,
                "java/util/concurrent/atomic/AtomicReference", "compareAndSet", abortHeld);
        assertTrue(abortRelease < 0 && abortHeld >= 0 && abortClear > abortHeld,
                "abortServerTick must not reopen successor admission after W and may clear "
                        + "CURRENT only when the real fence is already open");

        Class<?> phaseType = Class.forName(RegionalPlayerEntityTicksV2.class.getName()
                + "$GlobalPhase");
        MethodNode release = method(readClass(phaseType), "releasePause",
                "()Ljava/lang/Throwable;");
        int resume = callIndex(release, internal(RegionScheduler.class),
                "resumeExactPlayerTurn");
        int realFence = callIndexAfter(release, internal(RegionScheduler.class),
                "isExactPlayerTurnPausedBy", resume);
        int clearMirror = fieldIndexAfter(release, Opcodes.PUTFIELD,
                internal(phaseType), "pauseHeld", realFence);
        assertTrue(resume >= 0 && realFence > resume && clearMirror > realFence,
                "releasePause may clear its mirror only after querying the authoritative pool");
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Object> currentPhase() throws Exception {
        Field field = staticField(RegionalPlayerEntityTicksV2.class, "CURRENT");
        return (AtomicReference<Object>) field.get(null);
    }

    private static Object newGlobalPhase(MinecraftServer server) throws Exception {
        Class<?> type = Class.forName(RegionalPlayerEntityTicksV2.class.getName()
                + "$GlobalPhase");
        var constructor = type.getDeclaredConstructor(long.class, boolean.class,
                MinecraftServer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(7L, true, server);
    }

    private static void setBoolean(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static Object invoke(Object target, String name) {
        try {
            var method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Field staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static ClassNode readClass(Class<?> type) throws Exception {
        try (InputStream in = type.getResourceAsStream("/" + internal(type) + ".class")) {
            assertTrue(in != null);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode type, String name, String desc) {
        return type.methods.stream().filter(method -> method.name.equals(name)
                        && method.desc.equals(desc))
                .findFirst().orElseThrow(() -> new AssertionError(type.name + "." + name + desc));
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        return callIndexAfter(method, owner, name, -1);
    }

    private static int callIndexAfter(MethodNode method, String owner, String name, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static int fieldIndexAfter(MethodNode method, int opcode, String owner,
                                       String name, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && field.owner.equals(owner)
                    && field.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
