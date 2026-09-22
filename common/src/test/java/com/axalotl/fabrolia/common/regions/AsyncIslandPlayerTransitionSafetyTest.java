package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncIslandPlayerTransitionSafetyTest {
    @Test
    void disconnectMainIsTerminalIdempotentAndPreservesTheCompletedLedger() {
        IslandPlayerTurnSession<String> empty = new IslandPlayerTurnSession<>();
        assertEquals(IslandPlayerTurnSession.Phase.MAIN, empty.view().phase());
        assertTrue(empty.disconnectMain());
        assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED, empty.view().phase());
        assertNull(empty.view().owner());
        assertTrue(empty.disconnectMain(),
                "a repeated physical-Main close observes the already terminal session");

        IslandPlayerTurnSession.OwnerStamp owner = owner(1L);
        assertFalse(empty.attach(owner),
                "a connection-lifetime session cannot be recycled after Main disconnect");
        assertTrue(empty.enqueuePacket(owner, "stale").isEmpty());
        assertTrue(empty.tryReserveEarly(owner, 1L).isEmpty());

        IslandPlayerTurnSession<String> owned = new IslandPlayerTurnSession<>();
        assertTrue(owned.attach(owner));
        assertFalse(owned.disconnectMain(),
                "disconnectMain is not an escape hatch around a live island owner");
        assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY, owned.view().phase());

        IslandPlayerTurnSession.EarlyPermit<String> turn =
                owned.tryReserveEarly(owner, 7L).orElseThrow();
        assertTrue(owned.enterIslandBody(turn));
        assertTrue(owned.completeRealEarly(turn));
        assertTrue(owned.beginRealLate(turn));
        assertTrue(owned.completeRealLate(turn));
        IslandPlayerTurnSession.MainHandoff<String> handoff =
                owned.beginMainHandoff(owner).orElseThrow();
        assertTrue(owned.completeMainHandoff(handoff).orElseThrow().isEmpty());
        assertFalse(owned.disconnectMain(),
                "MAIN_DRAINING retains the exact physical-Main closer claim");
        assertTrue(owned.finishMainHandoff(handoff));
        assertTrue(owned.disconnectMain());

        IslandPlayerTurnSession.View terminal = owned.view();
        assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED, terminal.phase());
        assertNull(terminal.owner());
        assertTrue(terminal.ledger().hasTerminal());
        assertEquals(7L, terminal.ledger().lastTerminalToken(),
                "terminal disconnect must not erase the exact-once turn ledger");
        assertFalse(owned.attach(owner(2L)));
    }

    private static IslandPlayerTurnSession.OwnerStamp owner(long sessionEpoch) {
        return new IslandPlayerTurnSession.OwnerStamp(
                new Object(), new Object(), new Object(), 17L, 2L,
                new Object(), 3L, sessionEpoch,
                IslandPlayerTurnSession.PlayerKind.REAL);
    }
}
