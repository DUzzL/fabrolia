package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact failure publication and terminal results for native chunk obligations. */
final class ServerChunkCacheCompletionTest {

    private Field poolField;
    private Field orphanFatalField;
    private Object previousPool;
    private Object previousOrphanFatal;

    @BeforeEach
    void isolateFatalGate() throws ReflectiveOperationException {
        poolField = RegionScheduler.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        orphanFatalField = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        orphanFatalField.setAccessible(true);
        previousPool = poolField.get(null);
        previousOrphanFatal = orphanFatalField.get(null);
        poolField.set(null, null);
        orphanFatalField.set(null, null);
    }

    @AfterEach
    void restoreFatalGate() throws IllegalAccessException {
        poolField.set(null, previousPool);
        orphanFatalField.set(null, previousOrphanFatal);
    }


    @Test
    void regionLocalFutureSeesTheAuthoritativeWinnerOnlyAfterGlobalPublication()
            throws Exception {
        AssertionError winner = new AssertionError("pre-existing region failure");
        RuntimeException losingBody = new RuntimeException("later region body");
        orphanFatalField.set(null, winner);
        AtomicReference<Object> fatalAtTerminal = new AtomicReference<>();
        CompletableFuture<Void> local = new CompletableFuture<>() {
            @Override
            public boolean completeExceptionally(Throwable failure) {
                try {
                    fatalAtTerminal.set(orphanFatalField.get(null));
                } catch (IllegalAccessException reflectionFailure) {
                    throw new AssertionError(reflectionFailure);
                }
                return super.completeExceptionally(failure);
            }
        };

        Method complete = ServerChunkCacheMixin.class.getDeclaredMethod(
                "fabrolia$completeFutureAfterFatalPublication",
                CompletableFuture.class, Throwable.class);
        complete.setAccessible(true);
        Throwable selected = (Throwable) complete.invoke(null, local, losingBody);

        assertSame(winner, selected);
        assertSame(winner, fatalAtTerminal.get(),
                "the global first-wins slot must be armed before the local CF becomes terminal");
        CompletionException terminal = assertThrows(CompletionException.class, local::join);
        assertSame(winner, terminal.getCause());
    }

    @Test
    void chunkBridgeFailureEncodesTheEarlierGlobalWinnerNotItsLocalCause()
            throws Exception {
        AssertionError winner = new AssertionError("earlier chunk bridge peer W");
        RuntimeException laterLocal = new RuntimeException("later bridge callback failure");
        orphanFatalField.set(null, winner);
        CompletableFuture<Object> waiter = new CompletableFuture<>();

        Method complete = ServerChunkCacheMixin.class.getDeclaredMethod(
                "fabrolia$completeChunkRequestFailure",
                CompletableFuture.class, Throwable.class);
        complete.setAccessible(true);
        Throwable selected = (Throwable) complete.invoke(null, waiter, laterLocal);

        assertSame(winner, selected);
        CompletionException terminal = assertThrows(
                CompletionException.class, waiter::join);
        assertSame(winner, terminal.getCause(),
                "the causal chunk waiter must carry the exact first-wins object");
    }

    @Test
    void normalChunkCompletionRefusesAConcurrentGenerationWinnerUnchanged()
            throws Exception {
        AssertionError winner = new AssertionError("peer chunk worker won first");
        orphanFatalField.set(null, winner);
        CompletableFuture<Object> completion = new CompletableFuture<>();

        Method complete = ServerChunkCacheMixin.class.getDeclaredMethod(
                "fabrolia$completeFutureAfterHealthyCommit",
                CompletableFuture.class, Object.class, String.class);
        complete.setAccessible(true);
        InvocationTargetException escaped = assertThrows(
                InvocationTargetException.class,
                () -> complete.invoke(null, completion, new Object(), "duplicate"));

        assertSame(winner, escaped.getCause());
        CompletionException terminal = assertThrows(
                CompletionException.class, completion::join);
        assertSame(winner, terminal.getCause(),
                "no region/bridge success future may terminalize after peer W");
    }




}
