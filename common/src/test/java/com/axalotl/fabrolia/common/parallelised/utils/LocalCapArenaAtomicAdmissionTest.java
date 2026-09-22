package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView;
import com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView.NativeArena;
import com.axalotl.fabrolia.common.mixin.entity.spawn.LocalMobCapCalculatorMixin;
import com.axalotl.fabrolia.common.utils.LocalCapBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the real mixin entries and arena retirement; no transformed-world claim. */
@ResourceLock("LocalCapBridge.provider")
final class LocalCapArenaAtomicAdmissionTest {
    private enum Operation { CAN_SPAWN, ADD_MOB, RESERVE, RELEASE, DIAGNOSTIC }

    @Test
    void capturedArenaCannotEnterProviderAfterDetachForEveryNativeDoor() throws Exception {
        for (Operation operation : Operation.values()) {
            Provider provider = new Provider(41L);
            LocalCapBridge.Arena arena = allocate(provider);
            Calculator calculator = new Calculator();
            AcquisitionGate gate = new AcquisitionGate(arena);
            calculator.fabrolia$setCapArena(gate);
            FutureTask<Object> reader = start(() -> invokeOperation(calculator, operation));
            try {
                await(gate.captured);
                retire(arena, calculator, null);
                assertEquals(1, provider.frees.get());
            } finally {
                gate.resume.countDown();
            }
            Object result = reader.get(5, TimeUnit.SECONDS);
            assertEquals(0, provider.calls.get(), operation + " entered a freed arena");
            if (operation == Operation.CAN_SPAWN || operation == Operation.ADD_MOB) {
                assertFalse(((CallbackInfo) result).isCancelled(), "closed arena must use Java shadow");
            } else if (operation == Operation.RESERVE) {
                assertEquals(0, result);
                assertEquals(1, calculator.nearQueries);
            } else if (operation == Operation.RELEASE) {
                assertEquals(0, calculator.nearQueries, "native token must not debit Java shadow");
            } else {
                assertTrue(((String) result).contains("handle=0 provider=false"));
            }
        }
    }

    @Test
    void activeOperationsIncludingDiagnosticsKeepArenaAliveUntilFinalReaderReturns() throws Exception {
        for (Operation operation : Operation.values()) {
            Provider provider = new Provider(42L);
            LocalCapBridge.Arena arena = allocate(provider);
            Calculator calculator = new Calculator();
            calculator.fabrolia$setCapArena(arena);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            provider.beforeOperation = () -> { entered.countDown(); await(resume); };
            FutureTask<Object> reader = start(() -> invokeOperation(calculator, operation));
            try {
                await(entered);
                retire(arena, calculator, null);
                assertEquals(0, provider.frees.get(), "active " + operation + " must defer free");
                assertFalse(arena.tryAcquire());
            } finally {
                resume.countDown();
            }
            Object result = reader.get(5, TimeUnit.SECONDS);
            assertEquals(1, provider.frees.get(), "final unload needs no later build to reclaim");
            assertEquals(operation == Operation.DIAGNOSTIC ? 2 : 1, provider.calls.get());
            if (operation == Operation.RESERVE) {
                assertEquals(3 | FabroliaLocalCapView.FABROLIA_NATIVE_RESERVATION, result);
            } else if (operation == Operation.DIAGNOSTIC) {
                assertTrue(((String) result).contains("nativeCanSpawn[monster]=1"));
            }
        }
    }

    @Test
    void lastReaderBeforeDeferredHandoffIsStillReclaimed() {
        Provider provider = new Provider(43L);
        LocalCapBridge.Arena arena = allocate(provider);
        assertTrue(arena.tryAcquire());
        arena.close();
        assertFalse(arena.freeIfQuiescent());
        arena.release(null);
        assertEquals(0, provider.frees.get());
        arena.deferFree(null);
        assertEquals(1, provider.frees.get());
        arena.deferFree(null);
        assertEquals(1, provider.frees.get(), "entered native free must never repeat");
    }

    @Test
    void providerReplacementDoesNotInheritExistingAllocationReadsOrCleanup() throws Exception {
        Field registration = LocalCapBridge.class.getDeclaredField("provider");
        registration.setAccessible(true);
        Object previous = registration.get(null);
        Provider first = new Provider(51L);
        Provider second = new Provider(61L);
        try {
            LocalCapBridge.register(first);
            LocalCapBridge.Arena old = allocate(LocalCapBridge.provider());
            Calculator calculator = new Calculator();
            calculator.fabrolia$setCapArena(old);
            LocalCapBridge.register(second);
            for (Operation operation : Operation.values()) invokeOperation(calculator, operation);
            retire(old, calculator, null);
            assertEquals(6, first.calls.get());
            assertEquals(1, first.frees.get());
            assertEquals(0, second.calls.get());
            assertEquals(0, second.frees.get());
            LocalCapBridge.Arena next = allocate(LocalCapBridge.provider());
            calculator.fabrolia$setCapArena(next);
            invokeOperation(calculator, Operation.CAN_SPAWN);
            retire(next, calculator, null);
            assertEquals(1, second.calls.get());
            assertEquals(1, second.frees.get());
        } finally {
            registration.set(null, previous);
        }
    }

    @Test
    void enteredFreeFailureIsExactAndCannotBeRetried() throws Exception {
        Provider provider = new Provider(71L);
        LocalCapBridge.Arena arena = allocate(provider);
        Calculator calculator = new Calculator();
        calculator.fabrolia$setCapArena(arena);
        Error failure = new AssertionError("entered native free");
        provider.freeFailure = failure;
        assertSame(failure, assertThrows(Error.class, () -> retire(arena, calculator, null)));
        retire(arena, calculator, null);
        assertFalse(arena.tryAcquire());
        assertEquals(1, provider.frees.get());
    }

    @Test
    void unpublishedCleanupRetainsWinnerIncludingDeferredFailure() throws Exception {
        for (boolean active : new boolean[] { false, true }) {
            Provider provider = new Provider(active ? 82L : 81L);
            LocalCapBridge.Arena arena = allocate(provider);
            Calculator calculator = new Calculator();
            calculator.fabrolia$setCapArena(arena);
            if (active) assertTrue(arena.tryAcquire());
            Error winner = new AssertionError("already selected W");
            Error cleanup = new AssertionError("native free failed");
            provider.freeFailure = cleanup;
            Object owner = owner(arena, calculator);
            Method cleanupMethod = method(ParallelSpawnHelper.class, "fabrolia$freeUnpublishedArena",
                    LocalCapBridge.Provider.class, owner.getClass(), long.class, Throwable.class);
            assertSame(winner, invoke(cleanupMethod, null, provider, owner, arena.handle(), winner));
            if (active) {
                assertEquals(0, provider.frees.get());
                arena.release(null);
            }
            assertEquals(1, provider.frees.get());
            assertArrayEquals(new Throwable[] { cleanup }, winner.getSuppressed());
            arena.freeIfQuiescent();
            assertEquals(1, provider.frees.get());
        }
    }

    @Test
    void nativeOperationFailureSurvivesFinalReaderFreeFailure() throws Exception {
        Provider provider = new Provider(91L);
        LocalCapBridge.Arena arena = allocate(provider);
        Calculator calculator = new Calculator();
        calculator.fabrolia$setCapArena(arena);
        Error body = new AssertionError("native add failed");
        Error cleanup = new AssertionError("native free failed");
        provider.freeFailure = cleanup;
        provider.beforeOperation = () -> { arena.close(); arena.deferFree(null); throw body; };
        assertSame(body, assertThrows(Error.class, () -> invokeOperation(calculator, Operation.ADD_MOB)));
        assertArrayEquals(new Throwable[] { cleanup }, body.getSuppressed());
        assertEquals(1, provider.frees.get());
    }

    private static LocalCapBridge.Arena allocate(LocalCapBridge.Provider provider) {
        return new LocalCapBridge.Arena(provider, provider.buildArena(null, new long[0], new int[0], 0));
    }

    private static Object invokeOperation(Calculator calculator, Operation operation) throws Exception {
        ChunkPos pos = new ChunkPos(2, 3);
        return switch (operation) {
            case CAN_SPAWN -> {
                CallbackInfoReturnable<Boolean> callback = new CallbackInfoReturnable<>("canSpawn", true);
                invoke(method(LocalMobCapCalculatorMixin.class, "fabrolia$rustCanSpawn",
                        MobCategory.class, ChunkPos.class, CallbackInfoReturnable.class),
                        calculator, MobCategory.MONSTER, pos, callback);
                yield callback;
            }
            case ADD_MOB -> {
                CallbackInfo callback = new CallbackInfo("addMob", true);
                invoke(method(LocalMobCapCalculatorMixin.class, "fabrolia$rustAddMob",
                        ChunkPos.class, MobCategory.class, CallbackInfo.class),
                        calculator, pos, MobCategory.MONSTER, callback);
                yield callback;
            }
            case RESERVE -> calculator.fabrolia$tryReservePack(pos.pack(), MobCategory.MONSTER.ordinal(), 3);
            case RELEASE -> {
                calculator.fabrolia$releasePack(pos.pack(), MobCategory.MONSTER.ordinal(),
                        3 | FabroliaLocalCapView.FABROLIA_NATIVE_RESERVATION);
                yield null;
            }
            case DIAGNOSTIC -> calculator.fabrolia$debugReport(pos.pack());
        };
    }

    private static Object owner(LocalCapBridge.Arena arena, Calculator calculator) throws Exception {
        Class<?> type = Class.forName(ParallelSpawnHelper.class.getName() + "$CapArena");
        Constructor<?> constructor = type.getDeclaredConstructor(LocalCapBridge.Arena.class, FabroliaLocalCapView.class);
        constructor.setAccessible(true);
        return constructor.newInstance(arena, calculator);
    }

    private static void retire(LocalCapBridge.Arena arena, Calculator calculator, Throwable primary) throws Exception {
        Object owner = owner(arena, calculator);
        invoke(method(ParallelSpawnHelper.class, "fabrolia$detachAndFree", owner.getClass(), Throwable.class),
                null, owner, primary);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException encoded) {
            Throwable cause = encoded.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof Exception exception) throw exception;
            throw new AssertionError(cause);
        }
    }

    private static FutureTask<Object> start(java.util.concurrent.Callable<Object> operation) {
        FutureTask<Object> result = new FutureTask<>(operation);
        Thread.ofPlatform().daemon().start(result);
        return result;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "native operation latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Calculator extends LocalMobCapCalculatorMixin {
        int nearQueries;
        Calculator() throws Exception {
            Field counts = LocalMobCapCalculatorMixin.class.getDeclaredField("playerMobCounts");
            counts.setAccessible(true);
            counts.set(this, new ConcurrentHashMap<>());
        }
        @Override protected List<ServerPlayer> getPlayersNear(ChunkPos pos) {
            nearQueries++;
            return List.of();
        }
    }

    /** Provider boundary rejects foreign/dead handles instead of dereferencing native memory. */
    private static final class Provider implements LocalCapBridge.Provider {
        final long handle;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger frees = new AtomicInteger();
        volatile boolean live;
        Runnable beforeOperation = () -> {};
        Error freeFailure;
        Provider(long handle) { this.handle = handle; }
        @Override public long buildArena(ServerLevel level, long[] chunks, int[] categories, int count) {
            assertFalse(live);
            live = true;
            return handle;
        }
        private void enter(long handle) {
            assertEquals(this.handle, handle, "foreign-provider allocation");
            assertTrue(live, "use after native free");
            calls.incrementAndGet();
            beforeOperation.run();
            assertTrue(live, "native free overlapped an entered operation");
        }
        @Override public int canSpawn(long handle, int category, int x, int z) { enter(handle); return 1; }
        @Override public void addMob(long handle, int category, int x, int z) { enter(handle); }
        @Override public int tryReservePack(long handle, int category, int x, int z, int count) { enter(handle); return 1; }
        @Override public void releasePack(long handle, int category, int x, int z, int count) { enter(handle); }
        @Override public boolean supportsPackReservation() { return true; }
        @Override public void free(long handle) {
            assertEquals(this.handle, handle, "foreign-provider free");
            assertTrue(live, "repeated native free");
            live = false;
            frees.incrementAndGet();
            if (freeFailure != null) throw freeFailure;
        }
    }

    /** Pauses the real calculator after its arena reference load, before real CAS admission. */
    private static final class AcquisitionGate implements NativeArena {
        final LocalCapBridge.Arena delegate;
        final CountDownLatch captured = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        AcquisitionGate(LocalCapBridge.Arena delegate) { this.delegate = delegate; }
        @Override public boolean tryAcquire() { captured.countDown(); await(resume); return delegate.tryAcquire(); }
        @Override public void release(Throwable primary) { delegate.release(primary); }
        @Override public long handle() { return delegate.handle(); }
        @Override public int canSpawn(int category, int x, int z) { return delegate.canSpawn(category, x, z); }
        @Override public void addMob(int category, int x, int z) { delegate.addMob(category, x, z); }
        @Override public int tryReservePack(int category, int x, int z, int count) {
            return delegate.tryReservePack(category, x, z, count);
        }
        @Override public void releasePack(int category, int x, int z, int count) {
            delegate.releasePack(category, x, z, count);
        }
    }
}
