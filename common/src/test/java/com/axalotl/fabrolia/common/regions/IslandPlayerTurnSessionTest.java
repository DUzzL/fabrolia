package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IslandPlayerTurnSessionTest {

    @Test
    void ownerStampUsesExactIdentityAndAllEpochComponents() {
        EqualIdentity player = new EqualIdentity();
        EqualIdentity listener = new EqualIdentity();
        EqualIdentity island = new EqualIdentity();
        EqualIdentity domain = new EqualIdentity();
        IslandPlayerTurnSession.OwnerStamp owner = owner(
                player, listener, island, domain, 7L, 9L,
                IslandPlayerTurnSession.PlayerKind.REAL);
        IslandPlayerTurnSession.OwnerStamp exactCopy = owner(
                player, listener, island, domain, 7L, 9L,
                IslandPlayerTurnSession.PlayerKind.REAL);
        IslandPlayerTurnSession.OwnerStamp equalButDifferentPlayer = owner(
                new EqualIdentity(), listener, island, domain, 7L, 9L,
                IslandPlayerTurnSession.PlayerKind.REAL);
        IslandPlayerTurnSession.OwnerStamp differentDomain = owner(
                player, listener, island, new EqualIdentity(), 7L, 9L,
                IslandPlayerTurnSession.PlayerKind.REAL);

        assertTrue(owner.matchesExactly(exactCopy));
        assertEquals(owner, exactCopy);
        assertFalse(owner.matchesExactly(equalButDifferentPlayer));
        assertFalse(owner.matchesExactly(differentDomain));

        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        assertTrue(session.attach(owner));
        assertTrue(session.tryReserveEarly(equalButDifferentPlayer, 1L).isEmpty());
        assertTrue(session.enqueuePacket(differentDomain, "wrong-domain").isEmpty());
        assertTrue(session.enqueuePacket(exactCopy, "accepted").isPresent());
    }

    @Test
    void realTurnIsEarlyToLateAndPacketCutoffIsFifo() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        assertTrue(session.enqueuePacket(owner, "a").isPresent());
        assertTrue(session.enqueuePacket(owner, "b").isPresent());

        IslandPlayerTurnSession.EarlyPermit<String> turn =
                session.tryReserveEarly(owner, 10L).orElseThrow();
        assertTrue(session.enqueuePacket(owner, "after-cutoff").isPresent());
        List<IslandPlayerTurnSession.PacketEnvelope<String>> head =
                session.takeIslandHeadPackets(turn).orElseThrow();
        assertEquals(List.of("a", "b"), head.stream().map(
                IslandPlayerTurnSession.PacketEnvelope::packet).toList());
        assertTrue(session.takeIslandHeadPackets(turn).isEmpty());
        assertTrue(session.enterIslandPacketBody(turn));
        assertTrue(session.tryMoveToMainFallback(turn).isEmpty(),
                "an entered packet prefix never receives a Main retry");
        assertTrue(session.enterIslandBody(turn));
        assertFalse(session.isAwaitingRealLate(turn));
        assertTrue(session.completeRealEarly(turn));
        assertEquals(IslandPlayerTurnSession.Phase.EARLY_DONE, session.view().phase());
        assertTrue(session.view().ledger().hasEarly());
        assertFalse(session.view().ledger().hasTerminal());

        IslandPlayerTurnSession<String> foreign = new IslandPlayerTurnSession<>();
        assertTrue(foreign.attach(owner));
        IslandPlayerTurnSession.EarlyPermit<String> foreignTurn =
                foreign.tryReserveEarly(owner, 10L).orElseThrow();
        assertTrue(foreign.enterIslandBody(foreignTurn));
        assertTrue(foreign.completeRealEarly(foreignTurn));
        assertTrue(session.isAwaitingRealLate(turn),
                "the exact EARLY_DONE permit is the sole general-flush deferral proof");
        assertFalse(session.isAwaitingRealLate(foreignTurn),
                "an equal owner/token from another session cannot authorize publication");
        assertFalse(session.isRunningRealLate(turn),
                "post-physics publication cannot enter before the late body");
        assertTrue(session.beginRealLate(turn));
        assertFalse(session.isAwaitingRealLate(turn),
                "the proof closes before listener.tick enters the late body");
        assertTrue(session.isRunningRealLate(turn),
                "the exact LATE_RUNNING permit authorizes post-physics publication");
        assertFalse(session.isRunningRealLate(foreignTurn),
                "an equal foreign permit cannot authorize post-physics publication");
        assertTrue(session.completeRealLate(turn));
        assertFalse(session.isRunningRealLate(turn),
                "publication authority closes with the late transaction");

        assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY, session.view().phase());
        assertEquals(10L, session.view().ledger().lastEarlyToken());
        assertEquals(10L, session.view().ledger().lastLateToken());
        assertEquals(10L, session.view().ledger().lastTerminalToken());
        assertTrue(session.tryReserveEarly(owner, 10L).isEmpty());
        assertTrue(session.tryReserveEarly(owner, 9L).isEmpty());

        IslandPlayerTurnSession.EarlyPermit<String> next =
                session.tryReserveEarly(owner, 11L).orElseThrow();
        assertTrue(session.enterIslandBody(next));
        List<IslandPlayerTurnSession.PacketEnvelope<String>> nextHead =
                session.takeIslandHeadPackets(next).orElseThrow();
        assertEquals(List.of("after-cutoff"), nextHead.stream().map(
                IslandPlayerTurnSession.PacketEnvelope::packet).toList());
        assertEquals(IslandPlayerTurnSession.AfterBodyResult.CONTAINED_NO_MAIN_FALLBACK,
                session.containIslandFailure(next));
        assertTrue(session.tryMoveToMainFallback(next).isEmpty());
        assertTrue(session.tryReserveEarly(owner, 11L).isEmpty());
    }

    @Test
    void completedSourceTurnStillOwesItsPostCutoffPacketPrefix() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        var turn = session.tryReserveEarly(owner, 7L).orElseThrow();
        assertTrue(session.takeIslandHeadPackets(turn).orElseThrow().isEmpty());
        assertTrue(session.enterIslandBody(turn));
        assertTrue(session.enqueuePacket(owner, "older-than-portal-marker").isPresent());
        assertTrue(session.completeRealEarly(turn));
        assertTrue(session.beginRealLate(turn));
        assertTrue(session.completeRealLate(turn));

        assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY, session.phase());
        assertTrue(session.hasQueuedPackets(),
                "completed E/L does not authorize an empty-prefix Nether handoff over older P");
        var handoff = session.beginMainHandoff(owner).orElseThrow();
        assertEquals(List.of("older-than-portal-marker"),
                session.completeMainHandoff(handoff).orElseThrow().stream()
                        .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList());
        assertTrue(session.finishMainHandoff(handoff));
        assertFalse(session.hasQueuedPackets());
        assertEquals(7L, session.view().ledger().lastTerminalToken());
    }

    @Test
    void postPhysicsPublicationTransferOrContainedHandoffCompleteExactlyOnce() {
        assertEquals(IslandEntityTracking.PostPhysicsAuthority.HANDOFF_WITHOUT_PUBLICATION,
                IslandEntityTracking.classifyPostPhysicsAuthority(true, true, false, false),
                "a marked death may bump lifecycle epoch/retire its tracker before late tail");
        assertEquals(IslandEntityTracking.PostPhysicsAuthority.INVALID,
                IslandEntityTracking.classifyPostPhysicsAuthority(true, false, false, false),
                "the identical unavailable tracker/live-proof loss without a marker is corrupt");
        assertEquals(IslandEntityTracking.PostPhysicsAuthority.INVALID,
                IslandEntityTracking.classifyPostPhysicsAuthority(false, true, false, false),
                "a lifecycle marker may not hide replacement of the immutable late permit");
        assertEquals(IslandEntityTracking.PostPhysicsAuthority.PUBLISH_HANDOFF,
                IslandEntityTracking.classifyPostPhysicsAuthority(true, false, true, true),
                "a soft topology handoff retains the exact source publication obligation");
        assertEquals(IslandEntityTracking.PostPhysicsAuthority.LIVE,
                IslandEntityTracking.classifyPostPhysicsAuthority(true, false, true, false));

        assertTrue(AsyncIslandPlayerTicks.lifecycleEpochMatchesPendingAuthority(
                17L, 17L, false),
                "a generic topology handoff retains exact lifecycle epoch");
        assertFalse(AsyncIslandPlayerTicks.lifecycleEpochMatchesPendingAuthority(
                18L, 17L, false),
                "generic topology loss may not excuse an unexplained epoch drift");
        assertTrue(AsyncIslandPlayerTicks.lifecycleEpochMatchesPendingAuthority(
                18L, 17L, true),
                "an audited soft wrapper proves the expected epoch advance");
        assertFalse(AsyncIslandPlayerTicks.lifecycleEpochMatchesPendingAuthority(
                17L, 17L, true),
                "an audited marker without its promised epoch bump is invalid");

        for (IslandEntityTracking.PostPhysicsResult handled : List.of(
                IslandEntityTracking.PostPhysicsResult.PUBLISHED,
                IslandEntityTracking.PostPhysicsResult.TRANSFERRED,
                IslandEntityTracking.PostPhysicsResult.HANDOFF)) {
            IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
            IslandPlayerTurnSession.OwnerStamp owner = realOwner(
                    21L + handled.ordinal());
            assertTrue(session.attach(owner));
            IslandPlayerTurnSession.EarlyPermit<String> permit =
                    session.tryReserveEarly(owner, 1L).orElseThrow();
            assertTrue(session.enterIslandBody(permit));
            assertTrue(session.completeRealEarly(permit));
            assertTrue(session.beginRealLate(permit));
            if (handled == IslandEntityTracking.PostPhysicsResult.HANDOFF) {
                session.enqueuePacket(owner, "fifo-before-close-1").orElseThrow();
                session.enqueuePacket(owner, "fifo-before-close-2").orElseThrow();
            }
            assertFalse(handled == IslandEntityTracking.PostPhysicsResult.INVALID);
            assertTrue(session.isRunningRealLate(permit));
            assertTrue(session.completeRealLate(permit),
                    "a handled publication result terminalizes the one late body");
            assertEquals(1L, session.view().ledger().lastTerminalToken());
            assertTrue(session.tryMoveToMainFallback(permit).isEmpty(),
                    "an entered island body can never be replayed on Main");

            if (handled == IslandEntityTracking.PostPhysicsResult.HANDOFF) {
                IslandPlayerTurnSession.MainHandoff<String> handoff =
                        session.beginMainHandoff(owner).orElseThrow();
                assertEquals(List.of("fifo-before-close-1", "fifo-before-close-2"),
                        session.completeMainHandoff(handoff).orElseThrow().stream()
                                .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList());
                assertTrue(session.finishMainHandoff(handoff));
                assertEquals(IslandPlayerTurnSession.Phase.MAIN, session.view().phase());
                assertEquals(1L, session.view().ledger().lastTerminalToken(),
                        "the handoff preserves the completed token ledger");
            } else {
                assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY,
                        session.view().phase());
            }
        }
    }

    @Test
    void lifecycleFallbackAndActiveTurnModelsForbidLateOrCompetingBodyReplay() {
        assertTrue(AsyncIslandPlayerTicks.shouldArmLifecycleFallback(true, false, false),
                "an audited mutation during early packet replay replaces the skipped body once");
        assertFalse(AsyncIslandPlayerTicks.shouldArmLifecycleFallback(true, true, false),
                "death/removal inside listener.tick must not replay a Main entity body");
        assertFalse(AsyncIslandPlayerTicks.shouldArmLifecycleFallback(true, false, true),
                "a lifecycle mutation after early body entry must not duplicate that body");
        assertFalse(AsyncIslandPlayerTicks.shouldArmLifecycleFallback(false, false, false),
                "a competing ordinal is not authority to synthesize a body");

        for (IslandPlayerTurnSession.Phase phase : List.of(
                IslandPlayerTurnSession.Phase.EARLY_RESERVED,
                IslandPlayerTurnSession.Phase.EARLY_RUNNING,
                IslandPlayerTurnSession.Phase.EARLY_DONE,
                IslandPlayerTurnSession.Phase.LATE_RUNNING,
                IslandPlayerTurnSession.Phase.TRANSFERRING)) {
            assertTrue(AsyncIslandPlayerTicks.isActiveIslandTurnPhase(phase), phase.name());
        }
        assertFalse(AsyncIslandPlayerTicks.isActiveIslandTurnPhase(
                IslandPlayerTurnSession.Phase.ISLAND_READY));
        assertFalse(AsyncIslandPlayerTicks.isActiveIslandTurnPhase(
                IslandPlayerTurnSession.Phase.MAIN_HANDOFF));
    }

    @Test
    void lifecycleCutRestoresUntouchedSuffixBeforeConcurrentTail() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        session.enqueuePacket(owner, "applied").orElseThrow();
        session.enqueuePacket(owner, "suffix-1").orElseThrow();
        session.enqueuePacket(owner, "suffix-2").orElseThrow();

        IslandPlayerTurnSession.EarlyPermit<String> permit =
                session.tryReserveEarly(owner, 1L).orElseThrow();
        long concurrentSequence = session.enqueuePacket(owner, "concurrent-tail").orElseThrow();
        List<IslandPlayerTurnSession.PacketEnvelope<String>> head =
                session.takeIslandHeadPackets(permit).orElseThrow();
        List<IslandPlayerTurnSession.PacketEnvelope<String>> untouched =
                List.copyOf(head.subList(1, head.size()));

        assertTrue(session.enterIslandPacketBody(permit));
        assertTrue(session.tryMoveToMainFallback(permit).isEmpty(),
                "the consumed packet may not be replayed as a Main fallback");
        assertTrue(session.restoreIslandHeadSuffix(permit, untouched));
        IslandPlayerTurnSession.MainHandoff<String> handoff =
                session.preparePacketPrefixMainHandoff(permit).orElseThrow();
        assertEquals(IslandPlayerTurnSession.Phase.EARLY_RESERVED,
                session.view().phase(),
                "preallocation alone must not expose a Main owner");
        assertTrue(session.acceptMainHandoff(handoff));
        assertEquals(IslandPlayerTurnSession.Phase.MAIN_HANDOFF,
                session.view().phase());
        assertEquals(1L, session.view().ledger().lastTerminalToken(),
                "the packet-only island claim must terminalize with its Main handoff");
        List<IslandPlayerTurnSession.PacketEnvelope<String>> replay =
                session.completeMainHandoff(handoff).orElseThrow();

        assertEquals(List.of("suffix-1", "suffix-2", "concurrent-tail"), replay.stream()
                .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList());
        assertEquals(List.of(head.get(1).sequence(), head.get(2).sequence(), concurrentSequence),
                replay.stream().map(IslandPlayerTurnSession.PacketEnvelope::sequence).toList(),
                "the consumed packet stays consumed and untouched FIFO precedes the concurrent tail");
        assertTrue(session.finishMainHandoff(handoff));
        assertTrue(session.tryReserveEarly(owner, 1L).isEmpty());
    }

    @Test
    void preparedMainHandoffPublishesOnlyFromItsExactSourceFrame() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));

        IslandPlayerTurnSession.MainHandoff<String> stale =
                session.prepareMainHandoff(owner).orElseThrow();
        assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY,
                session.view().phase(),
                "preallocation is not authority to expose MAIN_HANDOFF");
        assertTrue(session.enqueuePacket(owner, "still-island-owned").isPresent());

        commitReal(session, owner, 1L);
        assertFalse(session.acceptMainHandoff(stale),
                "an intervening turn replaces the exact source frame even for the same owner");
        assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY,
                session.view().phase());

        IslandPlayerTurnSession.MainHandoff<String> fresh =
                session.prepareMainHandoff(owner).orElseThrow();
        assertTrue(session.acceptMainHandoff(fresh));
        assertFalse(session.acceptMainHandoff(fresh),
                "one prepared transaction has one acceptance edge");
        assertEquals(IslandPlayerTurnSession.Phase.MAIN_HANDOFF,
                session.view().phase());
        assertTrue(session.abortMainHandoff(fresh));
    }

    @Test
    void invalidSuffixRestoresRejectAtomicallyWithoutConsumingTheValidAttempt() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        session.enqueuePacket(owner, "applied").orElseThrow();
        session.enqueuePacket(owner, "suffix-1").orElseThrow();
        session.enqueuePacket(owner, "suffix-2").orElseThrow();
        IslandPlayerTurnSession.EarlyPermit<String> permit =
                session.tryReserveEarly(owner, 1L).orElseThrow();
        session.enqueuePacket(owner, "concurrent-tail").orElseThrow();
        assertTrue(session.enterIslandBody(permit));
        List<IslandPlayerTurnSession.PacketEnvelope<String>> head =
                session.takeIslandHeadPackets(permit).orElseThrow();
        List<IslandPlayerTurnSession.PacketEnvelope<String>> valid =
                List.copyOf(head.subList(1, head.size()));

        IslandPlayerTurnSession<String> foreign = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp foreignOwner = realOwner(2L);
        assertTrue(foreign.attach(foreignOwner));
        IslandPlayerTurnSession.EarlyPermit<String> foreignPermit =
                foreign.tryReserveEarly(foreignOwner, 1L).orElseThrow();
        assertTrue(foreign.enterIslandBody(foreignPermit));
        assertTrue(foreign.takeIslandHeadPackets(foreignPermit).isPresent());
        assertFalse(session.restoreIslandHeadSuffix(foreignPermit, valid));

        IslandPlayerTurnSession.OwnerStamp wrongOwner = realOwner(3L);
        assertFalse(session.restoreIslandHeadSuffix(permit, List.of(
                new IslandPlayerTurnSession.PacketEnvelope<>(
                        head.get(1).sequence(), wrongOwner, head.get(1).packet()),
                head.get(2))));
        assertFalse(session.restoreIslandHeadSuffix(permit,
                List.of(head.get(2), head.get(1))), "descending sequence must reject");
        assertFalse(session.restoreIslandHeadSuffix(permit, List.of(
                new IslandPlayerTurnSession.PacketEnvelope<>(
                        permit.packetCutoff() + 1L, owner, head.get(2).packet()))),
                "a sequence beyond the captured cutoff must reject");
        assertFalse(session.restoreIslandHeadSuffix(permit, List.of(
                head.get(1), new IslandPlayerTurnSession.PacketEnvelope<>(
                        head.get(1).sequence(), owner, head.get(2).packet()))),
                "duplicate sequence must reject");
        assertFalse(session.restoreIslandHeadSuffix(permit, List.of(head.get(0))),
                "an applied prefix is not the untouched suffix");

        assertTrue(session.restoreIslandHeadSuffix(permit, valid),
                "invalid calls must leave the queue and one-shot restore available");
        assertFalse(session.restoreIslandHeadSuffix(permit, valid),
                "a successful restore must reject repeat duplication");
        assertEquals(IslandPlayerTurnSession.AfterBodyResult.CONTAINED_NO_MAIN_FALLBACK,
                session.containIslandFailure(permit));
        IslandPlayerTurnSession.MainHandoff<String> handoff =
                session.beginMainHandoff(owner).orElseThrow();
        assertEquals(List.of("suffix-1", "suffix-2", "concurrent-tail"),
                session.completeMainHandoff(handoff).orElseThrow().stream()
                        .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList(),
                "every rejected restore must leave the FIFO unchanged");
        assertTrue(session.finishMainHandoff(handoff));
    }

    @Test
    void mainFallbackIsItsOwnPreEntryTransaction() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        assertTrue(session.enqueuePacket(owner, "packet").isPresent());

        IslandPlayerTurnSession.EarlyPermit<String> island =
                session.tryReserveEarly(owner, 1L).orElseThrow();
        IslandPlayerTurnSession.MainFallback<String> unentered =
                session.tryMoveToMainFallback(island).orElseThrow();
        assertFalse(session.enterIslandBody(island));
        assertTrue(session.takeIslandHeadPackets(island).isEmpty(),
                "a Main fallback claim closes the island packet prefix");
        assertTrue(session.cancelMainFallbackBeforeBody(unentered));
        assertFalse(session.cancelMainFallbackBeforeBody(unentered));
        IslandPlayerTurnSession.EarlyPermit<String> retry =
                session.tryReserveEarly(owner, 1L).orElseThrow(
                        () -> new AssertionError("an unentered claim did not consume the token"));
        assertEquals(List.of("packet"), session.takeIslandHeadPackets(retry).orElseThrow()
                .stream().map(IslandPlayerTurnSession.PacketEnvelope::packet).toList(),
                "cancelling the fallback leaves the FIFO prefix for the retry");
    }

    @Test
    void carpetFakeCommitsCombinedBodyExactlyOnce() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp carpet = carpetOwner(1L);
        assertTrue(session.attach(carpet));
        IslandPlayerTurnSession.EarlyPermit<String> turn =
                session.tryReserveEarly(carpet, 4L).orElseThrow();
        assertTrue(session.enterIslandBody(turn));
        assertFalse(session.completeRealEarly(turn));
        assertTrue(session.completeCarpetCombined(turn));
        assertFalse(session.completeCarpetCombined(turn));
        assertFalse(session.beginRealLate(turn));
        assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY, session.view().phase());
        assertEquals(4L, session.view().ledger().lastEarlyToken());
        assertEquals(4L, session.view().ledger().lastLateToken());
        assertTrue(session.tryReserveEarly(carpet, 4L).isEmpty());
    }

    @Test
    void transferInvalidatesOldStampAndCanCarryRetaggedFifo() {
        Object player = new Object();
        Object listener = new Object();
        IslandPlayerTurnSession.OwnerStamp oldOwner = owner(
                player, listener, new Object(), new Object(), 3L, 10L,
                IslandPlayerTurnSession.PlayerKind.REAL);
        IslandPlayerTurnSession.OwnerStamp nextOwner = owner(
                player, listener, new Object(), new Object(), 3L, 11L,
                IslandPlayerTurnSession.PlayerKind.REAL);
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        assertTrue(session.attach(oldOwner));
        commitReal(session, oldOwner, 50L);
        session.enqueuePacket(oldOwner, "first").orElseThrow();
        session.enqueuePacket(oldOwner, "second").orElseThrow();

        IslandPlayerTurnSession.Transfer<String> transfer =
                session.beginTransfer(oldOwner).orElseThrow();
        assertEquals(IslandPlayerTurnSession.Phase.TRANSFERRING, session.view().phase());
        assertEquals(List.of("first", "second"), transfer.pendingPackets().stream()
                .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList());
        assertTrue(session.enqueuePacket(oldOwner, "stale").isEmpty());
        assertTrue(session.tryReserveEarly(oldOwner, 51L).isEmpty());
        assertFalse(session.completeTransfer(transfer, oldOwner,
                IslandPlayerTurnSession.PacketDisposition.CARRY));
        assertTrue(session.completeTransfer(transfer, nextOwner,
                IslandPlayerTurnSession.PacketDisposition.CARRY));
        assertTrue(session.tryReserveEarly(oldOwner, 51L).isEmpty());

        IslandPlayerTurnSession.EarlyPermit<String> newTurn =
                session.tryReserveEarly(nextOwner, 1L).orElseThrow();
        assertTrue(session.enterIslandBody(newTurn));
        List<IslandPlayerTurnSession.PacketEnvelope<String>> carried =
                session.takeIslandHeadPackets(newTurn).orElseThrow();
        assertEquals(List.of("first", "second"), carried.stream()
                .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList());
        carried.forEach(packet -> assertTrue(packet.owner().matchesExactly(nextOwner)));
        assertNotSame(oldOwner.islandIdentity(), nextOwner.islandIdentity());
        assertEquals(IslandPlayerTurnSession.AfterBodyResult.CONTAINED_NO_MAIN_FALLBACK,
                session.containIslandFailure(newTurn));
    }

    @Test
    void disconnectInvalidatesPacketsAndEveryOutstandingPreBodyClaim() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        session.enqueuePacket(owner, "pending").orElseThrow();
        IslandPlayerTurnSession.EarlyPermit<String> permit =
                session.tryReserveEarly(owner, 1L).orElseThrow();

        IslandPlayerTurnSession.Disconnect<String> disconnected =
                session.disconnect(owner).orElseThrow();
        assertSame(owner.playerIdentity(), disconnected.oldOwner().playerIdentity());
        assertEquals(List.of("pending"), disconnected.pendingPackets().stream()
                .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList());
        assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED, session.view().phase());
        assertFalse(session.enterIslandBody(permit));
        assertTrue(session.enqueuePacket(owner, "late").isEmpty());
        assertTrue(session.tryReserveEarly(owner, 2L).isEmpty());
        assertFalse(session.attach(owner));
    }

    @Test
    void packetFailureAfterEntryTerminalizesWithoutMainRetry() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        session.enqueuePacket(owner, "failed-after-entry").orElseThrow();
        session.enqueuePacket(owner, "untouched").orElseThrow();
        IslandPlayerTurnSession.EarlyPermit<String> permit =
                session.tryReserveEarly(owner, 1L).orElseThrow();
        List<IslandPlayerTurnSession.PacketEnvelope<String>> head =
                session.takeIslandHeadPackets(permit).orElseThrow();

        assertTrue(session.enterIslandPacketBody(permit));
        assertTrue(session.restoreIslandHeadSuffix(permit, List.of(head.get(1))));
        assertTrue(session.tryMoveToMainFallback(permit).isEmpty(),
                "the first entered packet irrevocably closes pre-body rollback");
        assertEquals(IslandPlayerTurnSession.AfterBodyResult.CONTAINED_NO_MAIN_FALLBACK,
                session.containIslandReplayFailure(permit));
        assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY, session.view().phase());
        assertEquals(1L, session.view().ledger().lastTerminalToken());
        assertTrue(session.view().ledger().hasTerminal());
        assertTrue(session.tryReserveEarly(owner, 1L).isEmpty(),
                "a contained packet failure cannot retry the same entity ordinal");

        IslandPlayerTurnSession.MainHandoff<String> handoff =
                session.beginMainHandoff(owner).orElseThrow();
        assertEquals(List.of("untouched"),
                session.completeMainHandoff(handoff).orElseThrow().stream()
                        .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList(),
                "containment retains only the untouched packet suffix");
        assertTrue(session.finishMainHandoff(handoff));
    }

    @Test
    void disconnectAfterPacketEntryDropsConsumedPrefixButRetainsUntouchedSuffix() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        session.enqueuePacket(owner, "disconnecting").orElseThrow();
        session.enqueuePacket(owner, "untouched").orElseThrow();
        IslandPlayerTurnSession.EarlyPermit<String> permit =
                session.tryReserveEarly(owner, 1L).orElseThrow();
        List<IslandPlayerTurnSession.PacketEnvelope<String>> head =
                session.takeIslandHeadPackets(permit).orElseThrow();
        assertTrue(session.enterIslandPacketBody(permit));
        assertTrue(session.restoreIslandHeadSuffix(
                permit, List.of(head.get(1))));

        IslandPlayerTurnSession.Disconnect<String> disconnected =
                session.disconnect(owner).orElseThrow();
        assertEquals(List.of("untouched"), disconnected.pendingPackets().stream()
                .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList(),
                "disconnect terminalization must neither replay the entered packet "
                        + "nor lose its suffix");
        assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED,
                session.view().phase());
        assertEquals(IslandPlayerTurnSession.AfterBodyResult.REJECTED,
                session.containIslandReplayFailure(permit));
    }

    @Test
    void disconnectNeverCancelsAnEnteredIslandBody() {
        IslandPlayerTurnSession<String> islandSession = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp islandOwner = realOwner(1L);
        assertTrue(islandSession.attach(islandOwner));
        IslandPlayerTurnSession.EarlyPermit<String> island =
                islandSession.tryReserveEarly(islandOwner, 1L).orElseThrow();
        assertTrue(islandSession.enterIslandBody(island));
        assertTrue(islandSession.disconnect(islandOwner).isEmpty(),
                "an entered vanilla entity body is not a terminal cancellation candidate");
        assertTrue(islandSession.completeRealEarly(island));
        assertTrue(islandSession.disconnect(islandOwner).isEmpty(),
                "the owed real-player late body remains part of the entered turn");
        assertTrue(islandSession.beginRealLate(island));
        assertTrue(islandSession.disconnect(islandOwner).isEmpty());
        assertTrue(islandSession.completeRealLate(island));
    }

    @Test
    void disconnectRacingAnOwedLateCannotStealOrSuppressTheListenerBody()
            throws InterruptedException {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        IslandPlayerTurnSession.EarlyPermit<String> permit =
                session.tryReserveEarly(owner, 1L).orElseThrow();
        assertTrue(session.enterIslandBody(permit));
        assertTrue(session.completeRealEarly(permit));
        assertEquals(IslandPlayerTurnSession.Phase.EARLY_DONE,
                session.view().phase());

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch disconnectAttempted = new CountDownLatch(1);
        AtomicReference<Boolean> lateEntered = new AtomicReference<>();
        AtomicReference<Boolean> lateCompleted = new AtomicReference<>();
        AtomicReference<Boolean> disconnectWon = new AtomicReference<>();
        Thread late = new Thread(() -> {
            await(start);
            lateEntered.set(session.beginRealLate(permit));
            await(disconnectAttempted);
            lateCompleted.set(session.completeRealLate(permit));
        }, "session-owed-late");
        Thread disconnect = new Thread(() -> {
            await(start);
            disconnectWon.set(session.disconnect(owner).isPresent());
            disconnectAttempted.countDown();
        }, "session-racing-disconnect");
        late.start();
        disconnect.start();
        start.countDown();
        late.join();
        disconnect.join();

        assertEquals(Boolean.TRUE, lateEntered.get(),
                "the exact EARLY_DONE receipt must enter its one owed late body");
        assertEquals(Boolean.FALSE, disconnectWon.get(),
                "disconnect may not convert EARLY_DONE/LATE_RUNNING into cancellation");
        assertEquals(Boolean.TRUE, lateCompleted.get());
        IslandPlayerTurnSession.View view = session.view();
        assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY, view.phase());
        assertTrue(view.ledger().hasEarly());
        assertTrue(view.ledger().hasLate());
        assertTrue(view.ledger().hasTerminal());
        assertEquals(view.ledger().lastEarlyToken(), view.ledger().lastLateToken());
        assertEquals(view.ledger().lastLateToken(), view.ledger().lastTerminalToken());

        assertTrue(session.disconnect(owner).isPresent(),
                "terminal disconnect becomes legal only after the owed listener commits");
        assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED,
                session.view().phase());
    }

    @Test
    void competingReservationsProduceOneClaim() throws InterruptedException {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp owner = realOwner(1L);
        assertTrue(session.attach(owner));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger wins = new AtomicInteger();
        AtomicReference<IslandPlayerTurnSession.EarlyPermit<String>> winner =
                new AtomicReference<>();
        Runnable reserve = () -> {
            await(start);
            Optional<IslandPlayerTurnSession.EarlyPermit<String>> result =
                    session.tryReserveEarly(owner, 8L);
            result.ifPresent(permit -> {
                wins.incrementAndGet();
                winner.set(permit);
            });
            done.countDown();
        };
        Thread left = new Thread(reserve, "session-reserve-left");
        Thread right = new Thread(reserve, "session-reserve-right");
        left.start();
        right.start();
        start.countDown();
        done.await();
        left.join();
        right.join();

        assertEquals(1, wins.get());
        assertTrue(session.enterIslandBody(winner.get()));
        assertEquals(IslandPlayerTurnSession.AfterBodyResult.CONTAINED_NO_MAIN_FALLBACK,
                session.containIslandFailure(winner.get()));
        assertTrue(session.tryReserveEarly(owner, 8L).isEmpty());
    }

    private static void commitReal(IslandPlayerTurnSession<String> session,
                                   IslandPlayerTurnSession.OwnerStamp owner,
                                   long token) {
        IslandPlayerTurnSession.EarlyPermit<String> permit =
                session.tryReserveEarly(owner, token).orElseThrow();
        assertTrue(session.enterIslandBody(permit));
        assertTrue(session.completeRealEarly(permit));
        assertTrue(session.beginRealLate(permit));
        assertTrue(session.completeRealLate(permit));
    }

    private static IslandPlayerTurnSession.OwnerStamp realOwner(long sessionEpoch) {
        return owner(new Object(), new Object(), new Object(), new Object(),
                1L, sessionEpoch, IslandPlayerTurnSession.PlayerKind.REAL);
    }

    private static IslandPlayerTurnSession.OwnerStamp carpetOwner(long sessionEpoch) {
        return owner(new Object(), new Object(), new Object(), new Object(),
                1L, sessionEpoch, IslandPlayerTurnSession.PlayerKind.CARPET);
    }

    private static IslandPlayerTurnSession.OwnerStamp owner(
            Object player, Object listener, Object island, Object domain,
            long lifecycleEpoch, long sessionEpoch,
            IslandPlayerTurnSession.PlayerKind kind) {
        return new IslandPlayerTurnSession.OwnerStamp(
                player, listener, island, 42L, 5L, domain,
                lifecycleEpoch, sessionEpoch, kind);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class EqualIdentity {
        @Override
        public boolean equals(Object ignored) {
            return true;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
