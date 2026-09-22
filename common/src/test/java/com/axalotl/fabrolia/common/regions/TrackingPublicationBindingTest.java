package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackingPublicationBindingTest {
    private static final class Owner {
        final long id;
        final List<Object> trackers = new ArrayList<>();
        boolean retired;
        Owner(long id) { this.id = id; }
    }

    private static final class Fixture {
        final Object player = new Object();
        final Object listener = new Object();
        final Object tracker = new Object();
        final Owner source = new Owner(1);
        final TrackingPublicationBinding<Object, Owner> binding = new TrackingPublicationBinding<>();
        final IslandPlayerTurnSession.OwnerStamp owner = stamp(source, 1);

        Fixture() {
            source.trackers.add(tracker);
            binding.admit(tracker, null, owner, source);
        }

        IslandPlayerTurnSession.OwnerStamp stamp(Owner cadence, long epoch) {
            return new IslandPlayerTurnSession.OwnerStamp(player, listener, cadence,
                    cadence.id, 1, cadence, 0, epoch, IslandPlayerTurnSession.PlayerKind.REAL);
        }

        void carry(Owner destination) {
            Owner prior = binding.cadenceOwner();
            prior.trackers.remove(tracker);
            destination.trackers.add(tracker);
            binding.carry(tracker, owner, prior, destination);
        }
    }

    @Test
    void secondTopologyRetirementKeepsTheAlreadyCarriedPublicationOwned() {
        Fixture f = new Fixture();
        Owner firstDestination = new Owner(2);
        Owner secondDestination = new Owner(3);
        f.source.retired = true;
        f.carry(firstDestination);
        TrackerTopologySnapshot<Object, Owner> secondRebuild = TrackerTopologySnapshot.capture(
                List.of(firstDestination, secondDestination), owner -> owner.trackers);
        firstDestination.retired = true;

        assertSame(firstDestination, f.binding.publicationCadence(firstDestination, false, true, secondRebuild));
        f.carry(secondDestination);
        assertSame(secondDestination, f.binding.publicationCadence(secondDestination, true, true, null));
    }

    @Test
    void completedCarryRemainsOwnedThroughItsExactSuccessorAdmission() {
        Fixture f = new Fixture();
        Owner destination = new Owner(2);
        f.carry(destination);

        assertSame(destination, f.binding.publicationCadence(destination, true, true, null));
        var successor = f.stamp(destination, 2);
        f.binding.admit(f.tracker, f.owner, successor, destination);
        assertFalse(f.binding.matches(f.tracker, f.owner));
        assertTrue(f.binding.matches(f.tracker, successor));
        assertSame(destination, f.binding.publicationCadence(destination, true, true, null));
    }

    @Test
    void packetOnlyMainHandoffRetainsCadenceUntilItsOrderedClose() {
        Fixture f = new Fixture();
        IslandPlayerTurnSession<String> session = new IslandPlayerTurnSession<>();
        assertTrue(session.attach(f.owner));
        session.enqueuePacket(f.owner, "movement").orElseThrow();
        session.enqueuePacket(f.owner, "interaction").orElseThrow();
        var permit = session.tryReserveEarly(f.owner, 1).orElseThrow();
        var head = session.takeIslandHeadPackets(permit).orElseThrow();
        assertTrue(session.enterIslandPacketBody(permit));
        assertTrue(session.restoreIslandHeadSuffix(permit, List.of(head.get(1))));
        var handoff = session.preparePacketPrefixMainHandoff(permit).orElseThrow();
        assertTrue(session.acceptMainHandoff(handoff));
        assertEquals(IslandPlayerTurnSession.Phase.MAIN_HANDOFF, session.view().phase());
        assertSame(f.source, f.binding.publicationCadence(f.source, true, true, null));
        var remaining = session.completeMainHandoff(handoff).orElseThrow();
        assertEquals(List.of("interaction"), remaining.stream()
                .map(IslandPlayerTurnSession.PacketEnvelope::packet).toList());
        assertTrue(session.finishMainHandoff(handoff));
        f.binding.clear();
        assertNull(f.binding.publicationCadence(f.source, true, true, null));
    }

    @Test
    void aRetiredSourceNeedsParticipationInThisExactTopologySnapshot() {
        Fixture f = new Fixture();
        Owner unrelated = new Owner(2);
        unrelated.trackers.add(new Object());
        TrackerTopologySnapshot<Object, Owner> foreign = TrackerTopologySnapshot.capture(
                List.of(unrelated), owner -> owner.trackers);
        f.source.retired = true;

        assertThrows(IllegalStateException.class, () -> f.binding.publicationCadence(f.source, false, true, foreign));
        TrackerTopologySnapshot<Object, Owner> exact = TrackerTopologySnapshot.capture(
                List.of(f.source), owner -> owner.trackers);
        assertSame(f.source, f.binding.publicationCadence(f.source, false, true, exact));
    }

    @Test
    void spatialCoincidenceCannotAuthorizeAnUnrecordedCadence() {
        Fixture f = new Fixture();
        Owner foreign = new Owner(2);
        foreign.trackers.add(f.tracker);
        assertThrows(IllegalStateException.class, () -> f.binding.publicationCadence(foreign, true, true, null));
        assertThrows(IllegalStateException.class, () -> f.binding.publicationCadence(f.source, true, false, null));
        assertThrows(IllegalStateException.class, () -> f.binding.publicationCadence(null, false, false, null));
    }

    @Test
    void admissionChecksThePreviousBindingBeforeReplacingItsEpoch() {
        Fixture f = new Fixture();
        Owner destination = new Owner(2);
        var next = f.stamp(destination, 2);
        assertThrows(IllegalStateException.class,
                () -> f.binding.admit(new Object(), f.owner, next, destination));
        assertThrows(IllegalStateException.class,
                () -> f.binding.admit(f.tracker, f.stamp(f.source, 9), next, destination));
        assertSame(f.source, f.binding.publicationCadence(f.source, true, true, null));
        f.binding.admit(f.tracker, f.owner, next, destination);
        assertTrue(f.binding.matches(f.tracker, next));
        assertFalse(f.binding.matches(f.tracker, f.owner));
    }

    @Test
    void exactRetirementAdmitsASuccessorButStaleCleanupCannotEraseIt() {
        Fixture f = new Fixture();
        assertTrue(f.binding.retire(f.tracker));
        assertTrue(f.binding.retiredGeneration(f.owner));
        assertNull(f.binding.publicationCadence(f.source, true, true, null));
        Object successor = new Object();
        f.binding.admit(successor, f.owner, f.owner, f.source);
        assertFalse(f.binding.retire(f.tracker));
        assertSame(f.source, f.binding.publicationCadence(f.source, true, true, null));
        f.binding.clear();
        assertNull(f.binding.publicationCadence(f.source, true, true, null));
        assertFalse(f.binding.retiredGeneration(f.owner));
        assertEquals(-1, f.binding.sessionEpoch());
    }

    @Test
    void topologySnapshotKeepsIdentityAndCaptureOrderAfterMembershipChanges() {
        Owner one = new Owner(1);
        Owner two = new Owner(2);
        Object first = new String("equal tracker");
        Object second = new String("equal tracker");
        one.trackers.add(first);
        two.trackers.add(second);
        TrackerTopologySnapshot<Object, Owner> snapshot = TrackerTopologySnapshot.capture(
                List.of(one, two), owner -> owner.trackers);
        one.trackers.clear();
        two.trackers.clear();
        assertTrue(snapshot.owns(first, one));
        assertTrue(snapshot.owns(second, two));
        assertFalse(snapshot.owns(first, two));
        assertFalse(snapshot.owns(new String("equal tracker"), one));
        assertSame(first, snapshot.tracker(0));
        assertSame(second, snapshot.tracker(1));
        one.trackers.add(first);
        two.trackers.add(first);
        assertThrows(IllegalStateException.class, () -> TrackerTopologySnapshot.capture(
                List.of(one, two), owner -> owner.trackers));
    }
}
