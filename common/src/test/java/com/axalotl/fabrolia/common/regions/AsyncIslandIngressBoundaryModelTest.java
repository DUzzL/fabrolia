package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MC-free state-machine model for A5's retained listener-boundary FIFO. */
final class AsyncIslandIngressBoundaryModelTest {

    @Test
    void scanAllBoundaryPreservesIoldMainSafeAndBothTwoEntryOrders() {
        BoundaryModel oldMainSafe = new BoundaryModel();
        oldMainSafe.accept(safe("Iold"));
        oldMainSafe.accept(main("M"));
        oldMainSafe.accept(safe("S"));
        assertEquals(1, oldMainSafe.closerPublications);
        assertEquals(0, oldMainSafe.mainTaskPublications,
                "enqueue/prepend may not publish a drain ahead of the handoff closer");
        oldMainSafe.finishHandoff();
        oldMainSafe.drainMain();
        assertEquals(List.of("Iold@M", "M@M"), oldMainSafe.commits);
        assertEquals(List.of("S"), oldMainSafe.retainedIds());
        assertFalse(oldMainSafe.scheduled);
        assertEquals(2, oldMainSafe.polls);
        assertEquals(1, oldMainSafe.releasesWithoutPoll);
        oldMainSafe.reattachAndRunIsland();
        assertEquals(List.of("Iold@M", "M@M", "S@I"), oldMainSafe.commits);

        BoundaryModel mainSafe = new BoundaryModel();
        mainSafe.accept(main("M"));
        mainSafe.accept(safe("S"));
        mainSafe.finishHandoff();
        mainSafe.drainMain();
        assertEquals(List.of("M@M"), mainSafe.commits);
        assertEquals(List.of("S"), mainSafe.retainedIds());
        mainSafe.reattachAndRunIsland();
        assertEquals(List.of("M@M", "S@I"), mainSafe.commits);

        BoundaryModel safeMain = new BoundaryModel();
        safeMain.accept(safe("S"));
        safeMain.accept(main("M"));
        safeMain.finishHandoff();
        safeMain.drainMain();
        assertEquals(List.of("S@M", "M@M"), safeMain.commits,
                "the player body may not overtake an already-accepted M behind a safe head");
        assertTrue(safeMain.retained.isEmpty());
        assertFalse(safeMain.scheduled);
    }

    @Test
    void causalRoutedEntryCommitsBeforeSafePrefixThenReleasesItToIsland() {
        BoundaryModel model = new BoundaryModel();
        model.accept(safe("S"));
        model.acceptCausal(routed("R"));
        model.finishHandoff();
        model.drainMain();

        assertEquals(List.of("R@M"), model.commits);
        assertEquals(List.of("S"), model.retainedIds());
        assertFalse(model.causalCutoff,
                "the cutoff is retired only after no covered routed candidate remains");
        model.reattachAndRunIsland();
        assertEquals(List.of("R@M", "S@I"), model.commits);
    }

    @Test
    void fallbackDrainsEverythingOnMainAndDisconnectTerminalizesWithoutReplay() {
        BoundaryModel fallback = new BoundaryModel();
        fallback.accept(safe("S"));
        fallback.beginLifecycleHandoff();
        fallback.acceptEntityOrdinalMarker();
        fallback.finishHandoff();
        fallback.drainMain();
        assertEquals(List.of("S@M", "fallback-1@M"), fallback.commits);
        assertFalse(fallback.scheduled);
        assertEquals(Phase.MAIN, fallback.phase);

        BoundaryModel disconnect = new BoundaryModel();
        disconnect.accept(safe("Iold"));
        disconnect.accept(main("M"));
        disconnect.disconnectPending = true;
        disconnect.finishHandoff();
        assertEquals(Phase.DISCONNECTED, disconnect.phase);
        assertTrue(disconnect.retained.isEmpty());
        assertTrue(disconnect.oldIsland.isEmpty());
        assertTrue(disconnect.commits.isEmpty());
        assertFalse(disconnect.scheduled);
    }

    @Test
    void everyOrdinalBeforeFirstJoinFallbackCompletionKeepsItsOrderedVanillaBody() {
        BoundaryModel model = BoundaryModel.detachedMain();
        model.accept(main("P0-player-loaded"));
        assertTrue(model.scheduled,
                "the owner-null login packet already owns the Main listener chain");

        model.acceptDetachedEntityOrdinal();
        model.drainMainUntilBlocked();
        assertEquals(List.of("P0-player-loaded@M"), model.commits,
                "the login packet must commit before the first vanilla entity body");
        assertEquals(List.of(1), model.fallbackPublications);
        assertEquals(1, model.fallbackBodyInFlight,
                "the first ordinal must retain the listener-chain token until body completion");

        model.accept(main("P1"));
        model.acceptDetachedEntityOrdinal();
        model.accept(main("P2"));
        assertEquals(List.of("P1", "fallback-2", "P2"), model.retainedIds(),
                "packet/entity arrivals must share one listener-lifetime FIFO");

        model.completeFallbackBody();
        assertEquals(List.of(1, 2), model.fallbackPublications,
                "completion must run P1 and then publish exactly the next entity ordinal");
        model.completeFallbackBody();
        assertEquals(List.of(1, 2), model.fallbackPublications,
                "two region ordinals must publish two Main bodies in FIFO order");
        assertEquals(List.of("P0-player-loaded@M", "fallback-1@M", "P1@M",
                "fallback-2@M", "P2@M"), model.commits,
                "P0,E1,P1,E2,P2 must remain exact: no coalescing, skip, or reordering");
        assertFalse(model.scheduled);
        assertEquals(0, model.fallbackBodyInFlight);
        assertTrue(model.retained.isEmpty());
    }

    @Test
    void onlyExactReentrantMainBodyPublicationMayCrossAnOlderPacket() {
        BoundaryModel foreign = BoundaryModel.detachedMain();
        foreign.acceptEntityOrdinalMarker();
        foreign.arm();
        foreign.drainMainUntilBlocked();
        foreign.accept(main("P1"));
        foreign.acceptRouted(routed("foreign-R1"), false);
        foreign.completeFallbackBody();
        assertEquals(List.of("fallback-1@M", "P1@M", "foreign-R1@M"),
                foreign.commits,
                "a foreign publisher which merely overlaps E1 must retain FIFO order");

        BoundaryModel reentrant = BoundaryModel.detachedMain();
        reentrant.acceptEntityOrdinalMarker();
        reentrant.arm();
        reentrant.drainMainUntilBlocked();
        reentrant.accept(main("P1"));
        reentrant.acceptRouted(routed("reentrant-R1"), true);
        reentrant.completeFallbackBody();
        assertEquals(List.of("fallback-1@M", "reentrant-R1@M", "P1@M"),
                reentrant.commits,
                "an exact reentrant body effect must commit before its next envelope");
    }

    @Test
    void physicalMainRecoversParkedSuffixAfterAsyncTurnsOff() {
        BoundaryModel model = new BoundaryModel();
        model.accept(main("M"));
        model.accept(safe("S"));
        model.finishHandoff();
        model.drainMain();
        assertEquals(List.of("M@M"), model.commits);
        assertEquals(List.of("S"), model.retainedIds());
        assertFalse(model.scheduled);

        model.asyncEnabled = false;
        model.beginMainListenerTick();
        assertTrue(model.scheduled,
                "OFF recovery must arm the same retained head from physical Main");
        model.drainMain();
        assertEquals(List.of("M@M", "S@M"), model.commits);
        assertTrue(model.retained.isEmpty());
    }

    @Test
    void rejectedScheduleCentrallyResetsMarkerAndLeavesExactEntryRetryable() {
        BoundaryModel model = new BoundaryModel();
        model.accept(main("M"));
        model.rejectNextSchedule = true;

        assertThrows(ScheduleRejected.class, model::finishHandoff);
        assertEquals(Phase.MAIN, model.phase,
                "finish publishes MAIN before making its one arm decision");
        assertFalse(model.scheduled,
                "the centralized schedule failure edge must reopen its marker");
        assertEquals(List.of("M"), model.retainedIds());
        assertEquals(0, model.polls);

        model.beginMainListenerTick();
        assertTrue(model.scheduled);
        model.drainMain();
        assertEquals(List.of("M@M"), model.commits);
        assertEquals(2, model.polls,
                "one committed entry plus one terminal empty observation opens the marker");
    }

    private static Entry safe(String id) {
        return new Entry(id, Lane.SAFE, 0L);
    }

    private static Entry main(String id) {
        return new Entry(id, Lane.MAIN, 0L);
    }

    private static Entry routed(String id) {
        return new Entry(id, Lane.ROUTED, 1L);
    }

    private enum Lane { SAFE, MAIN, ROUTED, ENTITY }

    private enum Phase { ISLAND, HANDOFF, MAIN, DISCONNECTED }

    private record Entry(String id, Lane lane, long routedSequence) {
        boolean requiresMain() {
            return lane != Lane.SAFE;
        }
    }

    private static final class ScheduleRejected extends RuntimeException {
    }

    private static final class BoundaryModel {
        final ArrayDeque<Entry> oldIsland = new ArrayDeque<>();
        final ArrayDeque<Entry> retained = new ArrayDeque<>();
        final ArrayList<String> commits = new ArrayList<>();
        Phase phase = Phase.ISLAND;
        boolean scheduled;
        boolean asyncEnabled = true;
        boolean causalCutoff;
        final ArrayList<Integer> fallbackPublications = new ArrayList<>();
        int nextFallbackOrdinal = 1;
        int fallbackBodyInFlight;
        boolean disconnectPending;
        boolean rejectNextSchedule;
        int closerPublications;
        int mainTaskPublications;
        int polls;
        int releasesWithoutPoll;

        static BoundaryModel detachedMain() {
            BoundaryModel model = new BoundaryModel();
            model.phase = Phase.MAIN;
            return model;
        }

        void accept(Entry entry) {
            if (phase == Phase.DISCONNECTED) return;
            if (phase == Phase.ISLAND && entry.lane == Lane.SAFE && retained.isEmpty()) {
                oldIsland.addLast(entry);
                return;
            }
            retained.addLast(entry);
            if (phase == Phase.ISLAND) {
                phase = Phase.HANDOFF;
                closerPublications++;
                return;
            }
            if (phase == Phase.MAIN) arm();
        }

        void acceptCausal(Entry entry) {
            acceptRouted(entry, true);
        }

        void acceptRouted(Entry entry, boolean exactReentrantBody) {
            if (entry.lane != Lane.ROUTED) throw new IllegalArgumentException();
            accept(entry);
            if (exactReentrantBody) causalCutoff = true;
        }

        void beginLifecycleHandoff() {
            if (phase != Phase.ISLAND) throw new IllegalStateException();
            phase = Phase.HANDOFF;
            closerPublications++;
        }

        void acceptDetachedEntityOrdinal() {
            if (phase != Phase.MAIN || !scheduled) throw new IllegalStateException();
            acceptEntityOrdinalMarker();
        }

        void acceptEntityOrdinalMarker() {
            retained.addLast(new Entry(
                    "fallback-" + nextFallbackOrdinal, Lane.ENTITY,
                    nextFallbackOrdinal++));
        }

        void finishHandoff() {
            if (phase != Phase.HANDOFF) throw new IllegalStateException();
            ArrayList<Entry> prefix = new ArrayList<>(oldIsland);
            oldIsland.clear();
            for (int index = prefix.size() - 1; index >= 0; index--) {
                retained.addFirst(prefix.get(index));
            }
            phase = Phase.MAIN;
            if (disconnectPending) {
                retained.clear();
                causalCutoff = false;
                fallbackBodyInFlight = 0;
                scheduled = false;
                phase = Phase.DISCONNECTED;
                return;
            }
            arm();
        }

        void beginMainListenerTick() {
            if (phase == Phase.MAIN && !scheduled && !retained.isEmpty()) arm();
        }

        void arm() {
            if (scheduled || retained.isEmpty() || phase != Phase.MAIN || !shouldRun()) return;
            scheduled = true;
            try {
                scheduleMainTask();
            } catch (RuntimeException failure) {
                scheduled = false;
                throw failure;
            }
        }

        void scheduleMainTask() {
            if (rejectNextSchedule) {
                rejectNextSchedule = false;
                throw new ScheduleRejected();
            }
            mainTaskPublications++;
        }

        boolean shouldRun() {
            Entry head = retained.peekFirst();
            if (head == null) return false;
            if (hasCausalCandidate() || head.requiresMain()) return true;
            return !wholeQueueMayResume();
        }

        boolean wholeQueueMayResume() {
            if (phase != Phase.MAIN || retained.isEmpty() || !asyncEnabled
                    || fallbackBodyInFlight != 0
                    || disconnectPending || causalCutoff) {
                return false;
            }
            for (Entry entry : retained) {
                if (entry.requiresMain()) return false;
            }
            return true;
        }

        boolean hasCausalCandidate() {
            if (!causalCutoff) return false;
            for (Entry entry : retained) {
                if (entry.lane == Lane.ROUTED && entry.routedSequence <= 1L) return true;
            }
            causalCutoff = false;
            return false;
        }

        Entry poll() {
            polls++;
            if (causalCutoff) {
                Iterator<Entry> iterator = retained.iterator();
                while (iterator.hasNext()) {
                    Entry candidate = iterator.next();
                    if (candidate.lane == Lane.ROUTED && candidate.routedSequence <= 1L) {
                        iterator.remove();
                        return candidate;
                    }
                }
                causalCutoff = false;
            }
            return retained.pollFirst();
        }

        void drainMain() {
            do {
                drainMainUntilBlocked();
                if (fallbackBodyInFlight != 0) completeFallbackBody();
            } while (scheduled);
        }

        void drainMainUntilBlocked() {
            while (scheduled) {
                if (fallbackBodyInFlight != 0) return;
                if (!retained.isEmpty() && !shouldRun()) {
                    scheduled = false;
                    releasesWithoutPoll++;
                    return;
                }
                Entry entry = poll();
                if (entry != null) {
                    if (entry.lane == Lane.ENTITY) {
                        fallbackBodyInFlight = Math.toIntExact(entry.routedSequence);
                        fallbackPublications.add(fallbackBodyInFlight);
                        return;
                    }
                    commits.add(entry.id + "@M");
                    continue;
                }
                scheduled = false;
            }
        }

        void completeFallbackBody() {
            if (!scheduled || fallbackBodyInFlight == 0) throw new IllegalStateException();
            commits.add("fallback-" + fallbackBodyInFlight + "@M");
            fallbackBodyInFlight = 0;
            drainMainUntilBlocked();
        }

        void reattachAndRunIsland() {
            if (scheduled || !wholeQueueMayResume()) throw new IllegalStateException();
            phase = Phase.ISLAND;
            while (!retained.isEmpty()) commits.add(retained.removeFirst().id + "@I");
        }

        List<String> retainedIds() {
            return retained.stream().map(Entry::id).toList();
        }
    }
}
