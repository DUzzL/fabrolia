package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.server.ReentrantBlockableEventLoopAccessor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact two-thread provenance model for the vanilla RCON command path. */
final class RconCommandFenceScopeTest {

    @Test
    void exactFullChainTransfersOneIdentityBoundTokenToTopLevelMainDrain() {
        TestServer server = new TestServer(1);
        Object commands = new Object();
        Object source = new Object();
        Object command = new String("list");
        AtomicReference<Thread> publisher = new AtomicReference<>();
        AtomicInteger bodyRuns = new AtomicInteger();

        Runnable task = publishExactTask(server, command, publisher, () ->
                RconCommandFenceScope.runDedicatedRconLambdaEntry(
                        server, command, () ->
                                RconCommandFenceScope.runDedicatedRconPrefixedCommand(
                                        server, commands, source, command, () -> {
                                            assertFalse(RconCommandFenceScope
                                                    .claimDedicatedRconPrefixedCommand(
                                                            new Object(), source, command));
                                            assertFalse(RconCommandFenceScope
                                                    .claimDedicatedRconPrefixedCommand(
                                                            commands, new Object(), command));
                                            assertFalse(RconCommandFenceScope
                                                            .claimDedicatedRconPrefixedCommand(
                                                                    commands, source,
                                                                    new String("list")),
                                                    "equal text is not the tagged command identity");
                                            assertFalse(CompletableFuture.supplyAsync(() ->
                                                    RconCommandFenceScope
                                                            .claimDedicatedRconPrefixedCommand(
                                                                    commands, source, command)).join(),
                                                    "a foreign thread cannot borrow Main's token");
                                            assertTrue(RconCommandFenceScope
                                                    .claimDedicatedRconPrefixedCommand(
                                                            commands, source, command));
                                            assertFalse(RconCommandFenceScope
                                                            .claimDedicatedRconPrefixedCommand(
                                                                    commands, source, command),
                                                    "the exact token is one-shot");
                                            bodyRuns.incrementAndGet();
                                        })));

        assertNotSame(Thread.currentThread(), publisher.get(),
                "the authenticated RCON edge must publish from another thread");
        runTopLevelMainWait(server, task);

        assertEquals(1, bodyRuns.get());
        assertFalse(RconCommandFenceScope.claimDedicatedRconPrefixedCommand(
                        commands, source, command),
                "the token must be retired after the exact command edge");
    }

    @Test
    void everyOuterReceiptIsRequiredBeforeACommandCapabilityCanExist() {
        TestServer server = new TestServer(1);
        Object command = new Object();

        assertThrows(
                IllegalStateException.class,
                () -> RconCommandFenceScope.runRconClientCommand(
                        server, command, Object::new));

        assertThrows(
                IllegalStateException.class,
                () -> RconCommandFenceScope.runRconClientCommand(
                        server, command, () ->
                                RconCommandFenceScope.runDedicatedRconCommandEntry(
                                        server, command, Object::new)));

        assertThrows(
                IllegalStateException.class,
                () -> RconCommandFenceScope.runServerLoopRoot(server, () -> {}));

        assertThrows(
                IllegalStateException.class,
                () -> RconCommandFenceScope.runServerLoopRoot(server, () ->
                        RconCommandFenceScope.runServerEntry(server, () ->
                                RconCommandFenceScope.runServerWaitUntilNextTick(
                                        server, () -> {}))));

        assertThrows(
                IllegalStateException.class,
                () -> RconCommandFenceScope.runServerLoopRoot(server, () ->
                        RconCommandFenceScope.runServerEntry(server, () ->
                                RconCommandFenceScope.runServerWaitUntilNextTick(server, () ->
                                        RconCommandFenceScope
                                                .runWaitUntilNextTickEntry(
                                                        server, () -> {})))));
    }

    @Test
    void playerTaskProofRequiresTheExactCleanDrainAndDepthOne() {
        TestServer depthOne = new TestServer(1);
        TestServer foreign = new TestServer(1);

        assertFalse(RconCommandFenceScope.exactMonitorFreeMainTask(depthOne),
                "depth alone must not authenticate a task outside the exact drain");
        Runnable assertDepthOneProof = () -> {
            assertTrue(RconCommandFenceScope.exactMonitorFreeMainTask(depthOne));
            assertFalse(RconCommandFenceScope.exactMonitorFreeMainTask(foreign),
                    "the proof is bound to the exact server identity");
            assertFalse(RconCommandFenceScope.claimDedicatedRconPrefixedCommand(
                            new Object(), new Object(), new Object()),
                    "reading the player-task proof must not grant RCON provenance");
        };
        runTopLevelMainWait(depthOne, assertDepthOneProof, assertDepthOneProof);
        assertFalse(RconCommandFenceScope.exactMonitorFreeMainTask(depthOne),
                "the proof must be retired with the drain frame");

        TestServer depthTwo = new TestServer(2);
        runTopLevelMainWait(depthTwo, () ->
                assertFalse(RconCommandFenceScope.exactMonitorFreeMainTask(depthTwo),
                        "a task pumped at nested event-loop depth stays bounded"));

        Object missingDepthAccessor = new Object();
        runTopLevelMainWait(missingDepthAccessor, () ->
                assertFalse(RconCommandFenceScope.exactMonitorFreeMainTask(
                                missingDepthAccessor),
                        "the clean drain alone cannot fabricate a depth proof"));
    }

    @Test
    void literalRunAllTasksPollClaimsOnlyOneMatchingMainEntry() {
        TestServer server = new TestServer(1);
        TestServer foreign = new TestServer(1);

        runTopLevelMainWait(server, () ->
                RconCommandFenceScope.callRunAllTasksPoll(server, () -> {
                    assertFalse(RconCommandFenceScope.exactRunAllTasksPoll(server));
                    assertFalse(CompletableFuture.supplyAsync(() ->
                            RconCommandFenceScope.callMainPoll(server, () ->
                                    RconCommandFenceScope.exactRunAllTasksPoll(server))).join());
                    assertFalse(RconCommandFenceScope.callMainPoll(foreign, () -> {
                        assertFalse(RconCommandFenceScope.exactRunAllTasksPoll(foreign));
                        assertFalse(RconCommandFenceScope.exactRunAllTasksPoll(server));
                        return RconCommandFenceScope.callMainPoll(server, () ->
                                RconCommandFenceScope.exactRunAllTasksPoll(server));
                    }));
                    assertTrue(RconCommandFenceScope.callMainPoll(server, () -> {
                        assertTrue(RconCommandFenceScope.exactRunAllTasksPoll(server));
                        assertFalse(RconCommandFenceScope.exactRunAllTasksPoll(foreign));
                        assertFalse(RconCommandFenceScope.claimDedicatedRconPrefixedCommand(
                                new Object(), new Object(), new Object()));
                        return true;
                    }));
                    assertFalse(RconCommandFenceScope.exactRunAllTasksPoll(server));
                    assertFalse(RconCommandFenceScope.callMainPoll(server, () ->
                            RconCommandFenceScope.exactRunAllTasksPoll(server)));
                    return false;
                }));
        assertFalse(RconCommandFenceScope.exactRunAllTasksPoll(server));
    }

    @Test
    void nestedMainPollingCannotBorrowOrRearmOuterReceipt() {
        TestServer server = new TestServer(1);
        ExactFailure exact = new ExactFailure("nested poll failure");

        runTopLevelMainWait(server, () ->
                RconCommandFenceScope.callRunAllTasksPoll(server, () ->
                        RconCommandFenceScope.callMainPoll(server, () -> {
                            assertTrue(RconCommandFenceScope.exactRunAllTasksPoll(server));
                            assertFalse(RconCommandFenceScope.callMainPoll(server, () ->
                                    RconCommandFenceScope.exactRunAllTasksPoll(server)));
                            assertTrue(RconCommandFenceScope.exactRunAllTasksPoll(server));
                            ExactFailure observed = assertThrows(ExactFailure.class, () ->
                                    RconCommandFenceScope.callRunAllTasksPoll(server, () ->
                                            RconCommandFenceScope.callMainPoll(server, () -> {
                                                assertFalse(RconCommandFenceScope
                                                        .exactRunAllTasksPoll(server));
                                                throw exact;
                                            })));
                            assertSame(exact, observed);
                            assertTrue(RconCommandFenceScope.exactRunAllTasksPoll(server));
                            return false;
                        })));
        assertFalse(RconCommandFenceScope.exactRunAllTasksPoll(server));
    }

    @Test
    void mainPollReceiptRequiresExactRunAllTasksDrain() {
        TestServer server = new TestServer(1);
        TestServer foreign = new TestServer(1);
        Runnable unscopedPoll = () ->
                assertFalse(RconCommandFenceScope.callRunAllTasksPoll(server, () ->
                        RconCommandFenceScope.callMainPoll(server, () ->
                                RconCommandFenceScope.exactRunAllTasksPoll(server))));

        unscopedPoll.run();
        runTopLevelMainWait(server, () -> {
            assertFalse(RconCommandFenceScope.callMainPoll(server, () ->
                    RconCommandFenceScope.exactRunAllTasksPoll(server)));
            assertFalse(RconCommandFenceScope.callRunAllTasksPoll(foreign, () ->
                    RconCommandFenceScope.callMainPoll(server, () ->
                            RconCommandFenceScope.exactRunAllTasksPoll(server))));
            assertFalse(RconCommandFenceScope.callRunAllTasksPoll(server, () -> false));
            assertFalse(RconCommandFenceScope.callMainPoll(server, () ->
                    RconCommandFenceScope.exactRunAllTasksPoll(server)));
            assertTrue(RconCommandFenceScope.callRunAllTasksPoll(server, () ->
                    RconCommandFenceScope.callMainPoll(server, () ->
                            RconCommandFenceScope.exactRunAllTasksPoll(server))));
        }, unscopedPoll);
        unscopedPoll.run();
    }

    @Test
    void rawPollFailureRetiresUnclaimedCallsiteAndClaimedMainFrames() {
        TestServer server = new TestServer(1);
        ExactFailure beforeEntry = new ExactFailure("before main poll entry");
        ExactFailure inPoll = new ExactFailure("inside main poll");
        AtomicInteger bodyRuns = new AtomicInteger();

        ExactFailure observed = assertThrows(ExactFailure.class, () ->
                runTopLevelMainWait(server, () -> {
                    assertSame(beforeEntry, assertThrows(ExactFailure.class, () ->
                            RconCommandFenceScope.callRunAllTasksPoll(server, () -> {
                                bodyRuns.incrementAndGet();
                                throw beforeEntry;
                            })));
                    assertFalse(RconCommandFenceScope.callMainPoll(server, () ->
                            RconCommandFenceScope.exactRunAllTasksPoll(server)));
                    RconCommandFenceScope.callRunAllTasksPoll(server, () ->
                            RconCommandFenceScope.callMainPoll(server, () -> {
                                assertTrue(RconCommandFenceScope.exactRunAllTasksPoll(server));
                                bodyRuns.incrementAndGet();
                                throw inPoll;
                            }));
                }));
        assertSame(inPoll, observed);
        assertEquals(2, bodyRuns.get());
        assertFalse(RconCommandFenceScope.exactRunAllTasksPoll(server));
        assertFalse(RconCommandFenceScope.callMainPoll(server, () ->
                RconCommandFenceScope.exactRunAllTasksPoll(server)));
        runTopLevelMainWait(server, () ->
                assertTrue(RconCommandFenceScope.callRunAllTasksPoll(server, () ->
                        RconCommandFenceScope.callMainPoll(server, () ->
                                RconCommandFenceScope.exactRunAllTasksPoll(server)))));
    }


    @Test
    void exactMainTaskRequiresLambdaPrefixedEdgeAndClaim() {
        TestServer server = new TestServer(1);
        Object commands = new Object();
        Object source = new Object();
        Object command = new Object();

        Runnable missingLambda = publishExactTask(
                server, command, new AtomicReference<>(), () -> {});
        assertThrows(
                IllegalStateException.class,
                () -> runTopLevelMainWait(server, missingLambda));

        Runnable missingPrefixedEdge = publishExactTask(
                server, command, new AtomicReference<>(), () ->
                        RconCommandFenceScope.runDedicatedRconLambdaEntry(
                                server, command, () -> {}));
        assertThrows(
                IllegalStateException.class,
                () -> runTopLevelMainWait(server, missingPrefixedEdge));

        Runnable missingClaim = publishExactTask(
                server, command, new AtomicReference<>(), () ->
                        RconCommandFenceScope.runDedicatedRconLambdaEntry(
                                server, command, () ->
                                        RconCommandFenceScope
                                                .runDedicatedRconPrefixedCommand(
                                                        server, commands, source, command,
                                                        () -> {})));
        assertThrows(
                IllegalStateException.class,
                () -> runTopLevelMainWait(server, missingClaim));
    }

    @Test
    void nestedManagedBlockDepthExecutesOnceWithoutAnUnboundedToken() {
        TestServer server = new TestServer(2);
        Object commands = new Object();
        Object source = new Object();
        Object command = new Object();
        AtomicInteger bodyRuns = new AtomicInteger();

        Runnable task = publishExactTask(
                server, command, new AtomicReference<>(), () ->
                        RconCommandFenceScope.runDedicatedRconLambdaEntry(
                                server, command, () ->
                                        RconCommandFenceScope
                                                .runDedicatedRconPrefixedCommand(
                                                        server, commands, source, command,
                                                        () -> {
                                                            assertFalse(RconCommandFenceScope
                                                                    .claimDedicatedRconPrefixedCommand(
                                                                            commands, source,
                                                                            command));
                                                            bodyRuns.incrementAndGet();
                                                        })));

        runTopLevelMainWait(server, task);
        assertEquals(1, bodyRuns.get(),
                "a depth-two task stays on the bounded path but is not skipped");
    }

    @Test
    void recursiveRconLambdaCannotBorrowTheDepthOneReceipt() {
        TestServer server = new TestServer(1);
        Object commands = new Object();
        Object source = new Object();
        Object command = new Object();
        AtomicInteger nestedBodyRuns = new AtomicInteger();

        Runnable task = publishExactTask(
                server, command, new AtomicReference<>(), () ->
                        RconCommandFenceScope.runDedicatedRconLambdaEntry(
                                server, command, () -> {
                                    RconCommandFenceScope.runDedicatedRconLambdaEntry(
                                            server, command, () ->
                                                    RconCommandFenceScope
                                                            .runDedicatedRconPrefixedCommand(
                                                                    server, commands, source,
                                                                    command, () -> {
                                                                        assertFalse(
                                                                                RconCommandFenceScope
                                                                                        .claimDedicatedRconPrefixedCommand(
                                                                                                commands,
                                                                                                source,
                                                                                                command));
                                                                        nestedBodyRuns
                                                                                .incrementAndGet();
                                                                    }));
                                    RconCommandFenceScope.runDedicatedRconPrefixedCommand(
                                            server, commands, source, command,
                                            () -> assertTrue(RconCommandFenceScope
                                                    .claimDedicatedRconPrefixedCommand(
                                                            commands, source, command)));
                                }));

        runTopLevelMainWait(server, task);
        assertEquals(1, nestedBodyRuns.get());
    }

    @Test
    void nestedDifferentRunCommandCannotStealTheOuterExactTaskWrapper() {
        TestServer server = new TestServer(1);
        Object commands = new Object();
        Object source = new Object();
        Object outerCommand = new String("list");
        Object nestedCommand = new String("save-all");
        AtomicReference<Runnable> publishedOuter = new AtomicReference<>();
        AtomicInteger outerBodyRuns = new AtomicInteger();
        Runnable nestedBody = () -> {};

        CompletableFuture.runAsync(() ->
                RconCommandFenceScope.runRconClientCommand(
                        server, outerCommand, () ->
                                RconCommandFenceScope.runDedicatedRconCommandEntry(
                                        server, outerCommand, () -> {
                                            Runnable observedNested =
                                                    RconCommandFenceScope
                                                            .runDedicatedRconCommandEntry(
                                                                    server, nestedCommand,
                                                                    () -> RconCommandFenceScope
                                                                            .wrapDedicatedRconExecuteTask(
                                                                                    server,
                                                                                    nestedBody));
                                            assertSame(nestedBody, observedNested,
                                                    "a depth-two different command must keep "
                                                            + "its vanilla Runnable unchanged");
                                            publishedOuter.set(RconCommandFenceScope
                                                    .wrapDedicatedRconExecuteTask(
                                                            server, () ->
                                                                    RconCommandFenceScope
                                                                            .runDedicatedRconLambdaEntry(
                                                                                    server,
                                                                                    outerCommand,
                                                                                    () -> RconCommandFenceScope
                                                                                            .runDedicatedRconPrefixedCommand(
                                                                                                    server,
                                                                                                    commands,
                                                                                                    source,
                                                                                                    outerCommand,
                                                                                                    () -> {
                                                                                                        assertTrue(RconCommandFenceScope
                                                                                                                .claimDedicatedRconPrefixedCommand(
                                                                                                                        commands,
                                                                                                                        source,
                                                                                                                        outerCommand));
                                                                                                        outerBodyRuns.incrementAndGet();
                                                                                                    }))));
                                            return null;
                                        }))).join();

        assertNotNull(publishedOuter.get());
        runTopLevelMainWait(server, publishedOuter.get());
        assertEquals(1, outerBodyRuns.get(),
                "the outer authenticated command must retain its one exact wrapper");
    }

    @Test
    void exactBodyThrowableIsRethrownOnceAndEveryFrameIsCleaned() {
        TestServer server = new TestServer(1);
        Object commands = new Object();
        Object source = new Object();
        Object command = new Object();
        AtomicInteger bodyRuns = new AtomicInteger();
        ExactFailure exact = new ExactFailure("exact command body failure");

        Runnable task = publishExactTask(
                server, command, new AtomicReference<>(), () ->
                        RconCommandFenceScope.runDedicatedRconLambdaEntry(
                                server, command, () ->
                                        RconCommandFenceScope
                                                .runDedicatedRconPrefixedCommand(
                                                        server, commands, source, command,
                                                        () -> {
                                                            assertTrue(RconCommandFenceScope
                                                                    .claimDedicatedRconPrefixedCommand(
                                                                            commands, source,
                                                                            command));
                                                            bodyRuns.incrementAndGet();
                                                            throw exact;
                                                        })));

        ExactFailure observed = assertThrows(
                ExactFailure.class, () -> runTopLevelMainWait(server, task));
        assertSame(exact, observed);
        assertEquals(1, bodyRuns.get());
        assertFalse(RconCommandFenceScope.claimDedicatedRconPrefixedCommand(
                commands, source, command));

        assertThrows(IllegalStateException.class, task::run,
                "the transferred task itself is also one-shot");
        assertEquals(1, bodyRuns.get(), "a failed vanilla body must not be replayed");

        Runnable next = publishExactTask(
                server, command, new AtomicReference<>(), () ->
                        RconCommandFenceScope.runDedicatedRconLambdaEntry(
                                server, command, () ->
                                        RconCommandFenceScope
                                                .runDedicatedRconPrefixedCommand(
                                                        server, commands, source, command,
                                                        () -> {
                                                            assertTrue(RconCommandFenceScope
                                                                    .claimDedicatedRconPrefixedCommand(
                                                                            commands, source,
                                                                            command));
                                                            bodyRuns.incrementAndGet();
                                                        })));
        runTopLevelMainWait(server, next);
        assertEquals(2, bodyRuns.get(), "failure cleanup must admit the next command");
    }

    private static Runnable publishExactTask(
            TestServer server, Object command, AtomicReference<Thread> publisher,
            Runnable mainBody) {
        AtomicReference<Runnable> published = new AtomicReference<>();
        Object exactResult = new Object();

        Object observed = CompletableFuture.supplyAsync(() -> {
            publisher.set(Thread.currentThread());
            return RconCommandFenceScope.runRconClientCommand(
                    server, command, () ->
                            RconCommandFenceScope.runDedicatedRconCommandEntry(
                                    server, command, () -> {
                                        published.set(RconCommandFenceScope
                                                .wrapDedicatedRconExecuteTask(
                                                        server, mainBody));
                                        return exactResult;
                                    }));
        }).join();

        assertSame(exactResult, observed);
        assertNotNull(published.get());
        return published.get();
    }

    private static void runTopLevelMainWait(Object server, Runnable task) {
        runTopLevelMainWait(server, task, () -> {});
    }

    private static void runTopLevelMainWait(
            Object server, Runnable runAllTask, Runnable managedBlockTask) {
        RconCommandFenceScope.runServerLoopRoot(server, () ->
                RconCommandFenceScope.runServerEntry(server, () ->
                        RconCommandFenceScope.runServerWaitUntilNextTick(server, () ->
                                RconCommandFenceScope.runWaitUntilNextTickEntry(
                                        server, () -> {
                                            RconCommandFenceScope
                                                    .runWaitUntilNextTickRunAllTasks(
                                                            server, runAllTask);
                                            RconCommandFenceScope
                                                    .runWaitUntilNextTickManagedBlock(
                                                            server, managedBlockTask);
                                        }))));
    }

    private static final class TestServer
            implements ReentrantBlockableEventLoopAccessor {
        private final int reentrantDepth;

        private TestServer(int reentrantDepth) {
            this.reentrantDepth = reentrantDepth;
        }

        @Override
        public int fabrolia$getReentrantCount() {
            return reentrantDepth;
        }
    }

    private static final class ExactFailure extends Error {
        private ExactFailure(String message) {
            super(message);
        }
    }
}
