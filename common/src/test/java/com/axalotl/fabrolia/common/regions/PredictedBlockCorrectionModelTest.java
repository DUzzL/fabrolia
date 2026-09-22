package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** MC-free protocol model for the per-listener prediction-correction journal. */
class PredictedBlockCorrectionModelTest {

    @Test
    void currentOwnerReceivesEachFinalBlockStateBeforeItsAck() {
        Object island = new Object();
        Owner owner = new Owner(island, 41L);
        Journal journal = new Journal();
        journal.record(7L, owner);
        journal.record(7L, owner);
        journal.record(9L, owner);

        List<String> connectionOrder = new ArrayList<>();
        journal.drain(owner, position -> connectionOrder.add("block:" + position));
        connectionOrder.add("ack");

        assertEquals(List.of("block:7", "block:9", "ack"), connectionOrder,
                "all authoritative actor states must be queued before listener.tick's ACK, "
                        + "with duplicate mutations collapsed to their final state");
    }

    @Test
    void respawnOrTransferSuccessorNeverInheritsAnOldOwnersCorrection() {
        Object island = new Object();
        Owner oldOwner = new Owner(island, 10L);
        Owner successor = new Owner(island, 11L);
        Journal journal = new Journal();
        journal.record(23L, oldOwner);
        journal.record(29L, successor);

        List<Long> successorPackets = new ArrayList<>();
        journal.drain(successor, successorPackets::add);
        assertEquals(List.of(29L), successorPackets,
                "the successor may consume its own state but must discard the stale envelope");

        List<Long> oldPackets = new ArrayList<>();
        journal.drain(oldOwner, oldPackets::add);
        assertEquals(List.of(), oldPackets,
                "a stale envelope is consumed once, not retained until its retired owner returns");
    }

    private record Owner(Object islandIdentity, long sessionEpoch) {
        boolean matchesExactly(Owner other) {
            return other != null && islandIdentity == other.islandIdentity
                    && sessionEpoch == other.sessionEpoch;
        }
    }

    private record Correction(long position, Owner owner) {}

    private static final class Journal {
        private final ConcurrentLinkedQueue<Correction> queue =
                new ConcurrentLinkedQueue<>();

        void record(long position, Owner owner) {
            queue.add(new Correction(position, owner));
        }

        void drain(Owner current, LongConsumer send) {
            Set<Long> sent = new HashSet<>();
            Correction correction;
            while ((correction = queue.poll()) != null) {
                if (correction.owner.matchesExactly(current)
                        && sent.add(correction.position)) {
                    send.accept(correction.position);
                }
            }
        }
    }
}
