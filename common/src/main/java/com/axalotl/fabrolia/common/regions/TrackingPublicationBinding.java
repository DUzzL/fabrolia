package com.axalotl.fabrolia.common.regions;

import java.util.Objects;

/** Exact self-tracker lifetime and its authorized publication cadence.
 * Mutations are serialized by the containing player-state monitor. */
final class TrackingPublicationBinding<T, O> {
    private T tracker;
    private IslandPlayerTurnSession.OwnerStamp owner;
    private O cadenceOwner;

    T tracker() { return tracker; }
    long sessionEpoch() { return owner == null ? -1L : owner.sessionEpoch(); }
    O cadenceOwner() { return cadenceOwner; }

    boolean matches(T expectedTracker, IslandPlayerTurnSession.OwnerStamp expectedOwner) {
        return tracker == expectedTracker && tracker != null && owner != null
                && expectedOwner != null && owner.matchesExactly(expectedOwner);
    }

    boolean retiredGeneration(IslandPlayerTurnSession.OwnerStamp expectedOwner) {
        return tracker == null && owner != null && expectedOwner != null
                && owner.matchesExactly(expectedOwner);
    }

    void admit(T nextTracker, IslandPlayerTurnSession.OwnerStamp previousOwner,
               IslandPlayerTurnSession.OwnerStamp nextOwner, O nextCadence) {
        Objects.requireNonNull(nextTracker, "tracker");
        Objects.requireNonNull(nextOwner, "owner");
        Objects.requireNonNull(nextCadence, "cadence");
        if (nextOwner.sessionEpoch() < 0L || owner == null && previousOwner != null
                || owner != null
                && (previousOwner == null || !owner.matchesExactly(previousOwner)
                || tracker != null && tracker != nextTracker
                || nextOwner.sessionEpoch() < owner.sessionEpoch()
                || nextOwner.sessionEpoch() == owner.sessionEpoch()
                && !owner.matchesExactly(nextOwner))) {
            throw new IllegalStateException("self-tracker admission crossed its owner generation");
        }
        tracker = nextTracker;
        owner = nextOwner;
        cadenceOwner = nextCadence;
    }

    void carry(T expectedTracker, IslandPlayerTurnSession.OwnerStamp expectedOwner,
               O previousCadence, O nextCadence) {
        Objects.requireNonNull(nextCadence, "cadence");
        if (!matches(expectedTracker, expectedOwner) || cadenceOwner != previousCadence) {
            throw new IllegalStateException("self-tracker carry crossed its exact binding");
        }
        cadenceOwner = nextCadence;
    }

    boolean retire(T expectedTracker) {
        if (expectedTracker == null || tracker != expectedTracker) return false;
        tracker = null;
        cadenceOwner = null;
        // The same owner may admit one orderly successor tracker.
        return true;
    }

    void clear() {
        tracker = null;
        owner = null;
        cadenceOwner = null;
    }

    O publicationCadence(O actualCadence, boolean cadenceLive,
                         boolean member, TrackerTopologySnapshot<T, O> topology) {
        if (tracker == null || owner == null) return null;
        // Spatial ownership can move again before the source session is closed.
        // The recorded carry remains authority; retirement additionally needs
        // participation in the exact currently-published topology transaction.
        if (actualCadence != null && actualCadence == cadenceOwner && member
                && (cadenceLive || topology != null && topology.owns(tracker, actualCadence))) {
            return actualCadence;
        }
        throw new IllegalStateException("active A5 owner has no live tracker publication handoff");
    }
}
