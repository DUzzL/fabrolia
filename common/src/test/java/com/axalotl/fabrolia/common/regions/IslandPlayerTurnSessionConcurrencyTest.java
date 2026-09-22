package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adversarial packet-cutoff/session-replacement races for the Minecraft-free A5 core. */
class IslandPlayerTurnSessionConcurrencyTest {

    @Test
    void concurrentOfferIsOnExactlyOneSideOfTheCapturedCutoff() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int attempt = 0; attempt < 128; attempt++) {
                IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
                IslandPlayerTurnSession.OwnerStamp owner = owner(
                        new Object(), new Object(), 1L);
                assertTrue(session.attach(owner));

                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<OptionalLong> offered = new AtomicReference<>();
                AtomicReference<Optional<IslandPlayerTurnSession.EarlyPermit<String>>> reserved =
                        new AtomicReference<>();
                Thread producer = new Thread(() -> {
                    await(start);
                    offered.set(session.enqueuePacket(owner, "packet"));
                }, "a5-cutoff-producer");
                Thread coordinator = new Thread(() -> {
                    await(start);
                    reserved.set(session.tryReserveEarly(owner, 1L));
                }, "a5-cutoff-coordinator");
                producer.start();
                coordinator.start();
                start.countDown();
                producer.join();
                coordinator.join();

                long sequence = offered.get().orElseThrow();
                IslandPlayerTurnSession.EarlyPermit<String> permit =
                        reserved.get().orElseThrow();
                boolean belongsToThisTurn = sequence <= permit.packetCutoff();
                assertTrue(session.enterIslandBody(permit));
                List<String> first = packets(session.takeIslandHeadPackets(permit).orElseThrow());
                assertEquals(belongsToThisTurn ? List.of("packet") : List.of(), first,
                        "offer and cutoff use one linearization lock");
                completeReal(session, permit);

                IslandPlayerTurnSession.EarlyPermit<String> next =
                        session.tryReserveEarly(owner, 2L).orElseThrow();
                assertTrue(session.enterIslandBody(next));
                List<String> second = packets(session.takeIslandHeadPackets(next).orElseThrow());
                assertEquals(belongsToThisTurn ? List.of() : List.of("packet"), second,
                        "an after-cutoff packet must remain FIFO for exactly the next turn");
                assertEquals(1, first.size() + second.size(), "packet was lost or duplicated");
                completeReal(session, next);
            }
        });
    }

    @Test
    void transferRaceEitherCarriesTheOldOfferOrRejectsItWithoutSessionLeakage() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int attempt = 0; attempt < 128; attempt++) {
                Object player = new Object();
                Object listener = new Object();
                IslandPlayerTurnSession.OwnerStamp oldOwner = owner(player, listener, 1L);
                IslandPlayerTurnSession.OwnerStamp newOwner = owner(player, listener, 2L);
                IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
                assertTrue(session.attach(oldOwner));

                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<OptionalLong> offered = new AtomicReference<>();
                AtomicReference<Optional<IslandPlayerTurnSession.Transfer<String>>> begun =
                        new AtomicReference<>();
                Thread producer = new Thread(() -> {
                    await(start);
                    offered.set(session.enqueuePacket(oldOwner, "racing-old"));
                }, "a5-transfer-producer");
                Thread transfer = new Thread(() -> {
                    await(start);
                    begun.set(session.beginTransfer(oldOwner));
                }, "a5-transfer-owner");
                producer.start();
                transfer.start();
                start.countDown();
                producer.join();
                transfer.join();

                IslandPlayerTurnSession.Transfer<String> claim = begun.get().orElseThrow();
                assertTrue(session.completeTransfer(claim, newOwner,
                        IslandPlayerTurnSession.PacketDisposition.CARRY));
                assertTrue(session.enqueuePacket(oldOwner, "stale").isEmpty());
                session.enqueuePacket(newOwner, "new").orElseThrow();

                IslandPlayerTurnSession.EarlyPermit<String> permit =
                        session.tryReserveEarly(newOwner, 1L).orElseThrow();
                assertTrue(session.enterIslandBody(permit));
                List<IslandPlayerTurnSession.PacketEnvelope<String>> drained =
                        session.takeIslandHeadPackets(permit).orElseThrow();
                List<String> values = packets(drained);
                assertEquals(offered.get().isPresent()
                                ? List.of("racing-old", "new") : List.of("new"),
                        values,
                        "a pre-transfer offer is carried; a post-linearization old offer is rejected");
                assertTrue(drained.stream().allMatch(packet ->
                                packet.owner().matchesExactly(newOwner)),
                        "carried packets must be retagged to the replacement session");
                assertFalse(values.contains("stale"));
                completeReal(session, permit);
            }
        });
    }

    @Test
    void mainHandoffKeepsTheOldFifoOpenUntilItsPhysicalMainCloseEdge() {
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        Object player = new Object();
        Object listener = new Object();
        IslandPlayerTurnSession.OwnerStamp oldOwner = owner(player, listener, 1L);
        assertTrue(session.attach(oldOwner));
        session.enqueuePacket(oldOwner, "before-handoff").orElseThrow();

        IslandPlayerTurnSession.MainHandoff<String> handoff =
                session.beginMainHandoff(oldOwner).orElseThrow();
        assertEquals(IslandPlayerTurnSession.Phase.MAIN_HANDOFF, session.view().phase());
        assertTrue(session.disconnect(oldOwner).isEmpty(),
                "an accepted Main closer must not be orphaned by disconnect");
        session.enqueuePacket(oldOwner, "while-main-runnable-is-queued").orElseThrow();
        assertTrue(session.tryReserveEarly(oldOwner, 1L).isEmpty(),
                "an island body may not enter behind a pending Main handoff");

        List<String> mainPrefix = packets(
                session.completeMainHandoff(handoff).orElseThrow());
        assertEquals(List.of("before-handoff", "while-main-runnable-is-queued"), mainPrefix,
                "physical Main must atomically close and take the complete old-session FIFO");
        assertEquals(IslandPlayerTurnSession.Phase.MAIN_DRAINING, session.view().phase());
        assertTrue(session.view().owner().matchesExactly(oldOwner));
        assertTrue(session.disconnect(oldOwner).isEmpty(),
                "physical-Main FIFO replay must retain its exclusive closer claim");
        assertTrue(session.enqueuePacket(oldOwner, "after-close-uses-vanilla-path").isEmpty());
        assertTrue(session.completeMainHandoff(handoff).isEmpty(),
                "one Main closer may consume the handoff only once");

        IslandPlayerTurnSession.OwnerStamp replacement = owner(player, listener, 2L);
        assertFalse(session.attach(replacement),
                "a replacement island may not overtake Main's detached FIFO replay");
        assertTrue(session.finishMainHandoff(handoff));
        assertFalse(session.finishMainHandoff(handoff),
                "the draining claim must publish MAIN exactly once");
        assertEquals(IslandPlayerTurnSession.Phase.MAIN, session.view().phase());
        assertTrue(session.view().owner() == null);
        assertTrue(session.attach(replacement),
                "a later safe island admission must be able to replace a MAIN owner");
        IslandPlayerTurnSession.EarlyPermit<String> next =
                session.tryReserveEarly(replacement, 1L).orElseThrow();
        assertTrue(session.enterIslandBody(next));
        assertTrue(session.takeIslandHeadPackets(next).orElseThrow().isEmpty(),
                "no packet accepted by the old session may remain stranded after handoff");
        completeReal(session, next);
    }

    @Test
    void stalledPhysicalMainHandoffDoesNotBlockAnotherIslandPacketTurn() {
        IslandPlayerTurnSession<String> stalled = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp stalledOwner = owner(
                new Object(), new Object(), 1L);
        assertTrue(stalled.attach(stalledOwner));
        IslandPlayerTurnSession.MainHandoff<String> pendingMain =
                stalled.beginMainHandoff(stalledOwner).orElseThrow();
        assertEquals(IslandPlayerTurnSession.Phase.MAIN_HANDOFF,
                stalled.view().phase());

        IslandPlayerTurnSession<String> local = new IslandPlayerTurnSession<>();
        IslandPlayerTurnSession.OwnerStamp localOwner = owner(
                new Object(), new Object(), 1L);
        assertTrue(local.attach(localOwner));
        local.enqueuePacket(localOwner, "attack").orElseThrow();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            IslandPlayerTurnSession.EarlyPermit<String> permit =
                    local.tryReserveEarly(localOwner, 1L).orElseThrow();
            assertEquals(List.of("attack"),
                    packets(local.takeIslandHeadPackets(permit).orElseThrow()));
            assertTrue(local.enterIslandPacketBody(permit));
            assertTrue(local.enterIslandBody(permit));
            completeReal(local, permit);
        });

        assertEquals(IslandPlayerTurnSession.Phase.ISLAND_READY,
                local.view().phase());
        assertEquals(1L, local.view().ledger().lastTerminalToken());
        assertEquals(IslandPlayerTurnSession.Phase.MAIN_HANDOFF,
                stalled.view().phase(),
                "the local turn completed without advancing the stalled Main closer");
        assertTrue(stalled.abortMainHandoff(pendingMain));
    }

    @Test
    void offerRacingTheMainCloseIsEitherReturnedOrRejectedNeverAcceptedAndStranded() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int attempt = 0; attempt < 128; attempt++) {
                IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
                IslandPlayerTurnSession.OwnerStamp owner = owner(
                        new Object(), new Object(), 1L);
                assertTrue(session.attach(owner));
                IslandPlayerTurnSession.MainHandoff<String> handoff =
                        session.beginMainHandoff(owner).orElseThrow();

                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<OptionalLong> offered = new AtomicReference<>();
                AtomicReference<List<IslandPlayerTurnSession.PacketEnvelope<String>>> closed =
                        new AtomicReference<>();
                Thread producer = new Thread(() -> {
                    await(start);
                    offered.set(session.enqueuePacket(owner, "racing"));
                }, "a5-main-handoff-producer");
                Thread mainCloser = new Thread(() -> {
                    await(start);
                    closed.set(session.completeMainHandoff(handoff).orElseThrow());
                    assertTrue(session.finishMainHandoff(handoff));
                }, "a5-main-handoff-closer");
                producer.start();
                mainCloser.start();
                start.countDown();
                producer.join();
                mainCloser.join();

                assertEquals(offered.get().isPresent() ? List.of("racing") : List.of(),
                        packets(closed.get()),
                        "an offer before the close belongs to Main's returned FIFO; an offer "
                                + "after it is rejected to Connection's vanilla path");
                assertEquals(IslandPlayerTurnSession.Phase.MAIN, session.view().phase());
                assertTrue(session.enqueuePacket(owner, "stale").isEmpty());
            }
        });
    }

    private static List<String> packets(
            List<IslandPlayerTurnSession.PacketEnvelope<String>> envelopes) {
        return envelopes.stream().map(IslandPlayerTurnSession.PacketEnvelope::packet).toList();
    }

    private static void completeReal(IslandPlayerTurnSession<String> session,
                                     IslandPlayerTurnSession.EarlyPermit<String> permit) {
        assertTrue(session.completeRealEarly(permit));
        assertTrue(session.beginRealLate(permit));
        assertTrue(session.completeRealLate(permit));
    }

    private static IslandPlayerTurnSession.OwnerStamp owner(
            Object player, Object listener, long sessionEpoch) {
        return new IslandPlayerTurnSession.OwnerStamp(
                player, listener, new Object(), 7L, 3L, new Object(),
                11L, sessionEpoch, IslandPlayerTurnSession.PlayerKind.REAL);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
