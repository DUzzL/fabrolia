package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock-free tracker add/remove plus the viewer pass: no pairing a viewer can
 * hold is ever outside the reach of a removal (lifecycle or out-of-area), and a
 * removal that entered first can never resurrect its tracker in the index.
 */
class TrackerIndexChangesTest {

    private static final class Viewer {
        final String name;
        Viewer(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    private static final class Tracker {
        final Set<Viewer> seenBy = new LinkedHashSet<>();
        int stagedWork;
        boolean retired;
        boolean removed;
    }

    /** Index fake: removePlayer/updatePlayer apply immediately (nothing staged). */
    private static final class Index implements TrackerIndexChanges.Index<Tracker, Viewer> {
        final Set<Tracker> indexed = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Viewer, Set<Tracker>> tracked = new IdentityHashMap<>();
        final List<String> removals = new ArrayList<>();

        @Override public boolean fabrolia$indexed(Tracker tracker) { return indexed.contains(tracker); }
        @Override public void fabrolia$insert(Tracker tracker) {
            if (!fabrolia$gone(tracker)) indexed.add(tracker);
        }
        @Override public void fabrolia$remove(Tracker tracker) { indexed.remove(tracker); }
        @Override public boolean fabrolia$gone(Tracker tracker) { return tracker.removed || tracker.retired; }
        @Override public boolean fabrolia$viewersSettled(Tracker tracker, Consumer<Viewer> sink) {
            if (tracker.stagedWork != 0) return false;
            tracker.seenBy.forEach(sink);
            return true;
        }
        @Override public Set<Tracker> fabrolia$trackedBy(Viewer viewer) { return tracked.get(viewer); }
        @Override public boolean fabrolia$absentAndIdle(Tracker tracker, Viewer viewer) {
            return tracker.stagedWork == 0 && !tracker.seenBy.contains(viewer);
        }
        @Override public void fabrolia$removeViewer(Tracker tracker, Viewer viewer) {
            if (tracker.seenBy.remove(viewer)) removals.add(viewer.name);
        }

        Set<Tracker> viewer(Viewer viewer) {
            return tracked.computeIfAbsent(viewer,
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
        }
    }

    /** Pass effects with the same skip contract as the production tracker tick. */
    private static final class Effects implements TrackingViewerPass.Trackers<Tracker> {
        final Viewer viewer;
        final Set<Tracker> visible;
        final List<String> calls = new ArrayList<>();

        Effects(Viewer viewer, Set<Tracker> visible) {
            this.viewer = viewer;
            this.visible = visible;
        }

        @Override public boolean retired(Tracker tracker) { return tracker.retired; }
        @Override public boolean moved(Tracker tracker) { return false; }
        @Override public boolean claimTick(Tracker tracker) { return false; }
        @Override public void tick(Tracker tracker) { throw new AssertionError(); }
        @Override public boolean pairingCurrent(Tracker tracker) {
            return tracker.stagedWork == 0 && tracker.seenBy.contains(viewer) == visible.contains(tracker);
        }
        @Override public boolean absentAndIdle(Tracker tracker) {
            return tracker.stagedWork == 0 && !tracker.seenBy.contains(viewer);
        }
        @Override public void updatePlayer(Tracker tracker) {
            calls.add("update");
            if (visible.contains(tracker)) tracker.seenBy.add(viewer);
            else tracker.seenBy.remove(viewer);
        }
        @Override public void removePlayer(Tracker tracker) {
            calls.add("remove");
            tracker.seenBy.remove(viewer);
        }
    }

    @Test
    void lifecycleRemovalReachesABulkPairingThatNoPassRecordedYet() {
        TrackerIndexChanges<Tracker, Viewer> changes = new TrackerIndexChanges<>();
        Index index = new Index();
        Viewer viewer = new Viewer("respawning");
        Tracker tracker = new Tracker();
        changes.queueAdd(tracker);
        // The owner tail committed the add-time bulk pairing before any tick.
        tracker.seenBy.add(viewer);

        // Disconnect/respawn/dimension change: index queued adds, then cover fresh.
        changes.apply(index);
        changes.removeViewer(viewer, index);

        assertFalse(tracker.seenBy.contains(viewer),
                "a kept listener would block the successor's spawn on the same connection");
        assertEquals(List.of("respawning"), index.removals);
    }

    @Test
    void removalThatEnteredFirstNeverIndexesTheGeneration() {
        TrackerIndexChanges<Tracker, Viewer> changes = new TrackerIndexChanges<>();
        Index index = new Index();
        Tracker tracker = new Tracker();
        // Concurrent add/remove of one entity: the removal's tombstone is set
        // first, but its queue entry lands before the add's.
        tracker.removed = true;
        changes.queueRemove(tracker);
        changes.queueAdd(tracker);
        changes.apply(index);
        assertFalse(index.fabrolia$indexed(tracker), "a dead generation must not stay indexed");

        changes.graduate(index);
        changes.removeViewer(new Viewer("late"), index);
        assertTrue(index.removals.isEmpty());
    }

    @Test
    void settledFreshTrackerLetsThePassRemoveAViewerThatLeftItsArea() {
        TrackerIndexChanges<Tracker, Viewer> changes = new TrackerIndexChanges<>();
        Index index = new Index();
        Viewer departed = new Viewer("departed");
        Set<Tracker> departedTracked = index.viewer(departed);
        Tracker tracker = new Tracker();
        changes.queueAdd(tracker);
        changes.apply(index);
        tracker.seenBy.add(departed);
        tracker.stagedWork = 1;

        changes.graduate(index);
        assertFalse(departedTracked.contains(tracker),
                "staged membership is still settling; the viewer set is not final");

        tracker.stagedWork = 0;
        changes.graduate(index);
        assertTrue(departedTracked.contains(tracker));

        // The viewer is outside the tracker's area: its pass must unpair it.
        Effects effects = new Effects(departed, Set.of());
        TrackingViewerPass.run(false, Set.of(), departedTracked, effects);
        assertEquals(List.of("remove"), effects.calls);
        assertFalse(tracker.seenBy.contains(departed));
        assertTrue(departedTracked.isEmpty());

        changes.removeViewer(departed, index);
        assertTrue(index.removals.isEmpty(), "graduated trackers are no longer fresh");
    }

    @Test
    void passDropsRetiredAndIdleTrackersButRemovesARealPairing() {
        Viewer viewer = new Viewer("viewer");
        Tracker retired = new Tracker();
        retired.retired = true;
        retired.seenBy.add(viewer);
        Tracker idle = new Tracker();
        Tracker paired = new Tracker();
        paired.seenBy.add(viewer);
        Tracker staged = new Tracker();
        staged.stagedWork = 1;
        Set<Tracker> tracked = new LinkedHashSet<>(List.of(retired, idle, paired, staged));

        Effects effects = new Effects(viewer, Set.of());
        TrackingViewerPass.run(true, Set.of(), tracked, effects);

        assertEquals(List.of("remove", "remove"), effects.calls,
                "only the live pairing and the staged tracker reach removePlayer");
        assertTrue(retired.seenBy.contains(viewer),
                "broadcastRemoved already removed it; no second remove is sent");
        assertFalse(paired.seenBy.contains(viewer));
        assertTrue(tracked.isEmpty());
    }

    @Test
    void reevaluationIsSkippedOnlyWhenTheVanillaOutcomeEqualsThePairing() {
        Viewer viewer = new Viewer("viewer");
        Tracker current = new Tracker();
        current.seenBy.add(viewer);
        Tracker entering = new Tracker();
        Tracker leaving = new Tracker();
        leaving.seenBy.add(viewer);
        Tracker staged = new Tracker();
        staged.seenBy.add(viewer);
        staged.stagedWork = 1;
        Set<Tracker> inRange = new LinkedHashSet<>(List.of(current, entering, leaving, staged));
        Set<Tracker> visible = Set.of(current, entering, staged);

        Effects effects = new Effects(viewer, visible);
        Set<Tracker> tracked = new LinkedHashSet<>();
        TrackingViewerPass.run(true, inRange, tracked, effects);

        assertEquals(List.of("update", "update", "update"), effects.calls,
                "entering, leaving and the staged tracker are re-evaluated; the current pair is not");
        assertTrue(entering.seenBy.contains(viewer));
        assertFalse(leaving.seenBy.contains(viewer));
        assertEquals(inRange, tracked);

        Effects still = new Effects(viewer, visible);
        TrackingViewerPass.run(false, inRange, tracked, still);
        assertTrue(still.calls.isEmpty(), "nothing moved: no pair is re-evaluated");
    }

    @Test
    void vanillaRangeUsesTheSmallerOfTrackerRangeAndViewDistance() {
        assertTrue(TrackingViewerPass.inVanillaRange(48.0, 0.0, 48, 10));
        assertFalse(TrackingViewerPass.inVanillaRange(48.0001, 0.0, 48, 10));
        assertTrue(TrackingViewerPass.inVanillaRange(0.0, 32.0, 160, 2));
        assertFalse(TrackingViewerPass.inVanillaRange(24.0, 24.0, 160, 2));
    }
}
