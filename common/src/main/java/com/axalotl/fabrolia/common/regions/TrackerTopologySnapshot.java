package com.axalotl.fabrolia.common.regions;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Function;

/** Immutable participation proof for one topology transaction, in capture order. */
final class TrackerTopologySnapshot<T, O> {
    private final IdentityHashMap<T, O> owners;
    private final ArrayList<T> trackers;

    private TrackerTopologySnapshot(IdentityHashMap<T, O> owners, ArrayList<T> trackers) {
        this.owners = owners;
        this.trackers = trackers;
    }

    /** Caller has stopped cadence mutation; this never calls or locks a tracker. */
    static <T, O> TrackerTopologySnapshot<T, O> capture(
            Iterable<O> owners, Function<? super O, ? extends Iterable<T>> members) {
        IdentityHashMap<T, O> captured = new IdentityHashMap<>();
        ArrayList<T> ordered = new ArrayList<>();
        for (O owner : owners) {
            Objects.requireNonNull(owner, "owner");
            for (T tracker : members.apply(owner)) {
                Objects.requireNonNull(tracker, "tracker");
                O prior = captured.putIfAbsent(tracker, owner);
                if (prior == null) ordered.add(tracker);
                else if (prior != owner) {
                    throw new IllegalStateException("tracker belongs to multiple topology cadences");
                }
            }
        }
        return new TrackerTopologySnapshot<>(captured, ordered);
    }

    boolean owns(T tracker, O owner) {
        return tracker != null && owner != null && owners.get(tracker) == owner;
    }

    int size() { return trackers.size(); }
    T tracker(int index) { return trackers.get(index); }
}
