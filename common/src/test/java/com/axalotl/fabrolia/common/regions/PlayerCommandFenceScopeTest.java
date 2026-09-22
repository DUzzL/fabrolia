package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the identity-bound capability carried by one exact tagged Main task. */
class PlayerCommandFenceScopeTest {

    @Test
    void exactListenerEntryAndParsedIdentitiesCanClaimOnce() {
        Object server = new Object();
        Object listener = new Object();
        Object packet = new Object();
        Object commands = new Object();
        Object parsed = new Object();
        Object command = new Object();

        PlayerCommandFenceScope.runCleanMainTask(server, listener, packet, () -> {
            assertTrue(PlayerCommandFenceScope.cleanRootActive(listener, packet));
            assertFalse(PlayerCommandFenceScope.cleanRootActive(
                    listener, new Object()));
            PlayerCommandFenceScope.runPlayerCommandEntry(listener, () ->
                    PlayerCommandFenceScope.runParsedCommandEdge(
                            listener, commands, parsed, command, () -> {
                                assertFalse(PlayerCommandFenceScope.claimParsedCommand(
                                        new Object(), parsed, command));
                                assertFalse(PlayerCommandFenceScope.claimParsedCommand(
                                        commands, new Object(), command));
                                assertFalse(PlayerCommandFenceScope.claimParsedCommand(
                                        commands, parsed, new Object()));
                                assertTrue(PlayerCommandFenceScope.claimParsedCommand(
                                        commands, parsed, command));
                                assertFalse(PlayerCommandFenceScope.claimParsedCommand(
                                        commands, parsed, command));
                            }));
        });

        assertFalse(PlayerCommandFenceScope.claimParsedCommand(
                commands, parsed, command));
    }

    @Test
    void nestedAndLaterEntriesCannotBorrowTheExactTaggedTask() {
        Object server = new Object();
        Object listener = new Object();
        Object packet = new Object();
        Object nestedCommands = new Object();
        Object nestedParsed = new Object();
        Object nestedCommand = new Object();
        Object exactCommands = new Object();
        Object exactParsed = new Object();
        Object exactCommand = new Object();

        PlayerCommandFenceScope.runCleanMainTask(server, listener, packet, () -> {
            PlayerCommandFenceScope.runPlayerCommandEntry(listener, () -> {
                PlayerCommandFenceScope.runPlayerCommandEntry(listener, () ->
                        PlayerCommandFenceScope.runParsedCommandEdge(
                                listener, nestedCommands, nestedParsed, nestedCommand,
                                () -> assertFalse(
                                        PlayerCommandFenceScope.claimParsedCommand(
                                                nestedCommands, nestedParsed, nestedCommand))));
                PlayerCommandFenceScope.runParsedCommandEdge(
                        listener, exactCommands, exactParsed, exactCommand,
                        () -> assertTrue(PlayerCommandFenceScope.claimParsedCommand(
                                exactCommands, exactParsed, exactCommand)));
            });

            PlayerCommandFenceScope.runPlayerCommandEntry(listener, () ->
                    PlayerCommandFenceScope.runParsedCommandEdge(
                            listener, nestedCommands, nestedParsed, nestedCommand,
                            () -> assertFalse(PlayerCommandFenceScope.claimParsedCommand(
                                    nestedCommands, nestedParsed, nestedCommand))));
        });
    }

    @Test
    void foreignListenerCannotOfferAParsedCapability() {
        Object server = new Object();
        Object listener = new Object();
        Object packet = new Object();
        Object foreignListener = new Object();
        Object commands = new Object();
        Object parsed = new Object();
        Object command = new Object();

        PlayerCommandFenceScope.runCleanMainTask(server, listener, packet, () ->
                {
                    PlayerCommandFenceScope.runPlayerCommandEntry(foreignListener, () ->
                        PlayerCommandFenceScope.runParsedCommandEdge(
                                foreignListener, commands, parsed, command,
                                () -> assertFalse(PlayerCommandFenceScope.claimParsedCommand(
                                        commands, parsed, command))));
                    // The authenticated vanilla private entry still occurs. A
                    // secure-profile/signature refusal may legally return before
                    // it reaches Commands.performCommand.
                    PlayerCommandFenceScope.runPlayerCommandEntry(listener, () -> {});
                });
    }

    @Test
    void exactEntryMayReturnBeforeParsedDispatch() {
        Object server = new Object();
        Object listener = new Object();
        Object packet = new Object();

        PlayerCommandFenceScope.runCleanMainTask(server, listener, packet, () ->
                PlayerCommandFenceScope.runPlayerCommandEntry(listener, () -> {}));

        assertFalse(PlayerCommandFenceScope.cleanRootActive(listener, packet),
                "the exact task frame must retire after a legal early return");
    }

    @Test
    void exactBodyFailureIsRethrownByIdentityAndScopeIsRetired() {
        Object server = new Object();
        Object listener = new Object();
        Object packet = new Object();
        Object commands = new Object();
        Object parsed = new Object();
        Object command = new Object();
        RuntimeException marker = new RuntimeException("marker");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                PlayerCommandFenceScope.runCleanMainTask(server, listener, packet, () ->
                        PlayerCommandFenceScope.runPlayerCommandEntry(listener, () ->
                                PlayerCommandFenceScope.runParsedCommandEdge(
                                        listener, commands, parsed, command, () -> {
                                            assertTrue(
                                                    PlayerCommandFenceScope.claimParsedCommand(
                                                            commands, parsed, command));
                                            throw marker;
                                        }))));

        assertSame(marker, thrown);
        assertFalse(PlayerCommandFenceScope.claimParsedCommand(
                commands, parsed, command));
    }

    @Test
    void exactParsedEdgeMustBeClaimedBeforeItReturns() {
        Object server = new Object();
        Object listener = new Object();
        Object packet = new Object();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                PlayerCommandFenceScope.runCleanMainTask(server, listener, packet, () ->
                        PlayerCommandFenceScope.runPlayerCommandEntry(listener, () ->
                                PlayerCommandFenceScope.runParsedCommandEdge(
                                        listener, new Object(), new Object(), new Object(),
                                        () -> {}))));

        assertTrue(thrown.getMessage().contains("was not claimed"));
    }
}
