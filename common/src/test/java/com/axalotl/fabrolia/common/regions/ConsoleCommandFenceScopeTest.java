package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-owner/identity model for the dedicated-console one-shot grant. */
final class ConsoleCommandFenceScopeTest {

    @Test
    void onlyTheExactOwnerAndInvocationIdentitiesCanConsumeOnce() {
        Object server = new Object();
        Object commands = new Object();
        Object source = new Object();
        Object command = new String("list");
        AtomicBoolean foreign = new AtomicBoolean(true);

        runExactConsoleCommand(server, commands, source, command, () -> {
                    foreign.set(CompletableFuture.supplyAsync(() ->
                            ConsoleCommandFenceScope
                                    .claimDedicatedConsolePrefixedCommand(
                                            commands, source, command)).join());
                    assertFalse(foreign.get(),
                            "a foreign publisher cannot borrow Main's token");
                    assertFalse(ConsoleCommandFenceScope
                                    .claimDedicatedConsolePrefixedCommand(
                                            new Object(), source, command));
                    assertFalse(ConsoleCommandFenceScope
                                    .claimDedicatedConsolePrefixedCommand(
                                            commands, new Object(), command));
                    assertFalse(ConsoleCommandFenceScope
                                    .claimDedicatedConsolePrefixedCommand(
                                            commands, source, new String("list")),
                            "equal text is not the exact console queue argument");
                    assertTrue(ConsoleCommandFenceScope
                            .claimDedicatedConsolePrefixedCommand(
                                    commands, source, command));
                    assertFalse(ConsoleCommandFenceScope
                                    .claimDedicatedConsolePrefixedCommand(
                                            commands, source, command),
                            "the exact token is one-shot even during its body");
                });

        assertFalse(ConsoleCommandFenceScope
                        .claimDedicatedConsolePrefixedCommand(
                                commands, source, command),
                "the next same-thread callback cannot borrow a closed token");
    }

    @Test
    void aNestedConsoleDrainCannotCreateAnotherTopLevelGrant() {
        Object server = new Object();
        Object commands = new Object();
        Object source = new Object();
        Object command = new Object();

        runExactConsoleCommand(server, commands, source, command, () -> {
                    assertThrows(IllegalStateException.class, () ->
                            ConsoleCommandFenceScope
                                    .runDedicatedConsolePrefixedCommand(
                                            server, new Object(), new Object(), new Object(),
                                            () -> {}));
                    assertTrue(ConsoleCommandFenceScope
                            .claimDedicatedConsolePrefixedCommand(
                                    commands, source, command));
                });
    }

    @Test
    void mandatoryCleanupRethrowsTheExactEnteredBodyFailure() {
        Object server = new Object();
        Object commands = new Object();
        Object source = new Object();
        Object command = new Object();
        AssertionError exact = new AssertionError("exact body failure");

        AssertionError observed = assertThrows(AssertionError.class, () ->
                runExactConsoleCommand(server, commands, source, command, () -> {
                            assertTrue(ConsoleCommandFenceScope
                                    .claimDedicatedConsolePrefixedCommand(
                                            commands, source, command));
                            throw exact;
                        }));
        assertSame(exact, observed);

        // Exact rethrow cleanup must have closed the grant for the next body.
        runExactConsoleCommand(server, commands, source, command, () -> assertTrue(
                        ConsoleCommandFenceScope
                                .claimDedicatedConsolePrefixedCommand(
                                        commands, source, command)));
    }

    @Test
    void returningWithoutTheExactCommandsClaimFailsStopAndCleansUp() {
        Object server = new Object();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runExactConsoleCommand(server,
                        new Object(), new Object(), new Object(), () -> {}));
        assertTrue(failure.getMessage().contains("was not claimed"));

        Object commands = new Object();
        Object source = new Object();
        Object command = new Object();
        runExactConsoleCommand(server, commands, source, command, () -> assertTrue(
                        ConsoleCommandFenceScope
                                .claimDedicatedConsolePrefixedCommand(
                                        commands, source, command)));
    }

    @Test
    void directPublicConsoleDrainHasNoCapabilityWithoutBothOuterEdges() {
        Object server = new Object();
        Object commands = new Object();
        Object source = new Object();
        Object command = new Object();
        AtomicBoolean entered = new AtomicBoolean();

        ConsoleCommandFenceScope.runDedicatedConsolePrefixedCommand(
                server, commands, source, command, () -> {
                    assertFalse(ConsoleCommandFenceScope
                            .claimDedicatedConsolePrefixedCommand(
                                    commands, source, command));
                    entered.set(true);
                });
        assertTrue(entered.get());

        ConsoleCommandFenceScope.runDedicatedTickConnection(server, () ->
                ConsoleCommandFenceScope.runDedicatedTickConnectionEntry(server, () ->
                        ConsoleCommandFenceScope.runDedicatedConsolePrefixedCommand(
                                server, commands, source, command, () -> {
                                    assertFalse(ConsoleCommandFenceScope
                                            .claimDedicatedConsolePrefixedCommand(
                                                    commands, source, command));
                                    entered.set(true);
                                })));
    }

    @Test
    void recursiveMethodEntriesCannotBorrowEitherOneShotReceipt() {
        Object server = new Object();
        Object commands = new Object();
        Object source = new Object();
        Object command = new Object();

        ConsoleCommandFenceScope.runDedicatedTickConnection(server, () ->
                ConsoleCommandFenceScope.runDedicatedTickConnectionEntry(server, () -> {
                    // A packet/mod callback recursively invoking tickConnection
                    // enters at depth two. Its own exact bytecode edge remains bounded.
                    ConsoleCommandFenceScope.runDedicatedTickConnectionEntry(server, () ->
                            ConsoleCommandFenceScope.runDedicatedConsoleInputs(server, () ->
                                    ConsoleCommandFenceScope
                                            .runDedicatedConsoleInputsEntry(server, () ->
                                                    ConsoleCommandFenceScope
                                                            .runDedicatedConsolePrefixedCommand(
                                                                    server, commands, source,
                                                                    command, () -> assertFalse(
                                                                            ConsoleCommandFenceScope
                                                                                    .claimDedicatedConsolePrefixedCommand(
                                                                                            commands,
                                                                                            source,
                                                                                            command))))));

                    ConsoleCommandFenceScope.runDedicatedConsoleInputs(server, () ->
                            ConsoleCommandFenceScope
                                    .runDedicatedConsoleInputsEntry(server, () -> {
                                        // The same rule holds for a recursive direct
                                        // public console drain at depth two.
                                        ConsoleCommandFenceScope
                                                .runDedicatedConsoleInputsEntry(server, () ->
                                                        ConsoleCommandFenceScope
                                                                .runDedicatedConsolePrefixedCommand(
                                                                        server, commands, source,
                                                                        command, () -> assertFalse(
                                                                                ConsoleCommandFenceScope
                                                                                        .claimDedicatedConsolePrefixedCommand(
                                                                                                commands,
                                                                                                source,
                                                                                                command))));
                                        ConsoleCommandFenceScope
                                                .runDedicatedConsolePrefixedCommand(
                                                        server, commands, source, command,
                                                        () -> assertTrue(
                                                                ConsoleCommandFenceScope
                                                                        .claimDedicatedConsolePrefixedCommand(
                                                                                commands, source,
                                                                                command)));
                                    }));
                }));
    }

    private static void runExactConsoleCommand(
            Object server, Object commands, Object source, Object command,
            Runnable body) {
        ConsoleCommandFenceScope.runDedicatedTickConnection(server, () ->
                ConsoleCommandFenceScope.runDedicatedTickConnectionEntry(server, () ->
                        ConsoleCommandFenceScope.runDedicatedConsoleInputs(server, () ->
                                ConsoleCommandFenceScope
                                        .runDedicatedConsoleInputsEntry(server, () ->
                                                ConsoleCommandFenceScope
                                                        .runDedicatedConsolePrefixedCommand(
                                                                server, commands, source,
                                                                command, body)))));
    }
}
