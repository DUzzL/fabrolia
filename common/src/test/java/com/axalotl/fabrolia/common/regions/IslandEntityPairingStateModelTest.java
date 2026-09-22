package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MC-free model of staged desired-PRESENT/desired-ABSENT viewer membership. */
class IslandEntityPairingStateModelTest {

    @Test
    void ownerTailRefreshesBeforeSpawnAndSpawnPrecedesEveryViewerDelta() {
        Object island = new Object();
        Viewer viewer = new Viewer("viewer");
        Tracker tracker = new Tracker(island);

        assertTrue(tracker.stagePresent(viewer));
        assertEquals(List.of(Intent.ADD), tracker.pendingIntents(viewer));
        assertFalse(tracker.visible(viewer.connection));
        assertEquals(List.of(), tracker.eventsFor(viewer.connection),
                "staging updatePlayer must not expose seenBy or send a packet");

        assertTrue(tracker.islandTail(island));
        assertTrue(tracker.visible(viewer.connection));
        assertEquals(List.of(Kind.SPAWN), tracker.eventsFor(viewer.connection),
                "the refresh runs while the pending viewer is still invisible");

        assertTrue(tracker.islandTail(island));
        assertEquals(List.of(Kind.SPAWN, Kind.DELTA),
                tracker.eventsFor(viewer.connection),
                "only a later publication may address the newly spawned listener");
        assertEquals(List.of(Kind.REFRESH, Kind.SPAWN, Kind.REFRESH, Kind.DELTA),
                tracker.timelineKinds());
    }

    @Test
    void latestIntentCoalescesPerExactPlayerSession() {
        Object island = new Object();
        Viewer viewer = new Viewer("coalesced");
        Tracker tracker = new Tracker(island);

        assertTrue(tracker.stagePresent(viewer));
        assertTrue(tracker.stagePresent(viewer));
        assertEquals(List.of(Intent.ADD), tracker.pendingIntents(viewer),
                "duplicate desired-PRESENT requests remain one ADD");

        assertTrue(tracker.stageAbsent(viewer));
        assertEquals(List.of(), tracker.pendingIntents(viewer),
                "desired-ABSENT cancels an invisible ADD without manufacturing REMOVE");
        assertTrue(tracker.islandTail(island));
        assertFalse(tracker.visible(viewer.connection));

        assertTrue(tracker.stagePresent(viewer));
        assertTrue(tracker.islandTail(island));
        assertTrue(tracker.stagePresent(viewer));
        assertTrue(tracker.stagePresent(viewer));
        assertEquals(List.of(Intent.REVALIDATE), tracker.pendingIntents(viewer),
                "a listener already in seenBy coalesces to one REVALIDATE");

        assertTrue(tracker.stageAbsent(viewer));
        assertEquals(List.of(Intent.REMOVE), tracker.pendingIntents(viewer),
                "desired-ABSENT replaces an outstanding REVALIDATE");
        assertTrue(tracker.stagePresent(viewer));
        assertEquals(List.of(Intent.REVALIDATE), tracker.pendingIntents(viewer),
                "a later desired-PRESENT wins without duplicating the session");
    }

    @Test
    void capturedRemovalSurvivesDisconnectLevelAndSessionReplacement() {
        Object island = new Object();
        Viewer viewer = new Viewer("old-player");
        Tracker tracker = new Tracker(island);

        assertTrue(tracker.stagePresent(viewer));
        assertTrue(tracker.islandTail(island));
        Listener captured = viewer.connection;
        assertTrue(tracker.stageAbsent(viewer));
        assertEquals(List.of(Intent.REMOVE), tracker.pendingIntents(viewer));

        Viewer successor = new Viewer("successor");
        viewer.removed = true;
        viewer.disconnected = true;
        viewer.level = new Object();
        captured.player = successor;
        viewer.connection = successor.connection;

        assertTrue(tracker.islandTail(island));
        assertFalse(tracker.visible(captured));
        assertEquals(List.of(Kind.SPAWN, Kind.DELTA, Kind.REMOVE),
                tracker.eventsFor(captured),
                "REMOVE is validated by captured player identity and falls back to its old "
                        + "listener after the live session changes");
        assertEquals(List.of(), tracker.eventsFor(successor.connection),
                "captured-listener fallback must never remove or notify the successor session");
    }

    @Test
    void revalidateFailureRequeuesAndQuarantineAlwaysSchedulesMain() {
        Object island = new Object();
        Viewer viewer = new Viewer("retry");
        Tracker tracker = new Tracker(island);

        assertTrue(tracker.stagePresent(viewer));
        assertTrue(tracker.islandTail(island));
        assertTrue(tracker.stagePresent(viewer));
        assertEquals(List.of(Intent.REVALIDATE), tracker.pendingIntents(viewer));

        tracker.failNext(Intent.REVALIDATE);
        assertThrows(ModelFailure.class, () -> tracker.islandTail(island));
        assertEquals(List.of(Intent.REVALIDATE), tracker.pendingIntents(viewer),
                "failed REVALIDATE is recomputed and retained even though success is terminal");

        tracker.quarantineAfterIslandFailure();
        Viewer late = new Viewer("late");
        assertTrue(tracker.stagePresent(late),
                "a quarantined live tracker still accepts a membership obligation");
        assertTrue(tracker.mainScheduled(),
                "every quarantined obligation is routed to a deferred Main publication");
        assertTrue(tracker.mainRetry());
        assertEquals(List.of(), tracker.pendingIntents(viewer));
        assertTrue(tracker.visible(viewer.connection));
        assertTrue(tracker.visible(late.connection));
    }

    @Test
    void bulkRefreshIsOneBitExpandsTheCurrentPlayerSnapshotAndSurvivesFailure() {
        Object island = new Object();
        Viewer stale = new Viewer("stale-at-stage");
        Viewer current = new Viewer("current-at-commit");
        Tracker tracker = new Tracker(island);

        tracker.currentPlayers(List.of(stale));
        assertTrue(tracker.stageBulkRefresh());
        assertTrue(tracker.stageBulkRefresh(),
                "repeated VMP all-player staging coalesces into the same boolean obligation");
        tracker.currentPlayers(List.of(current));
        tracker.failNextBulkRefresh();

        assertThrows(ModelFailure.class, () -> tracker.islandTail(island));
        assertTrue(tracker.bulkRefreshPending(),
                "an exceptional transformed updatePlayers re-entry must leave its bit published");
        assertFalse(tracker.visible(stale.connection));
        assertFalse(tracker.visible(current.connection));

        tracker.quarantineAfterIslandFailure();
        assertTrue(tracker.mainRetry());
        assertFalse(tracker.bulkRefreshPending());
        assertEquals(1, tracker.bulkCommitCount());
        assertFalse(tracker.visible(stale.connection),
                "bulk commit must not retain the player list captured when the bit was staged");
        assertTrue(tracker.visible(current.connection),
                "the successful owner/Main tail expands the exact current player snapshot");
        assertEquals(List.of(Kind.SPAWN), tracker.eventsFor(current.connection));
    }

    @Test
    void retireConsumesPendingAndOnlyVisibleListenersReceiveTeardown() {
        Object island = new Object();
        Viewer pending = new Viewer("pending");
        Viewer visible = new Viewer("visible");
        Tracker tracker = new Tracker(island);

        assertTrue(tracker.stagePresent(visible));
        assertTrue(tracker.islandTail(island));
        assertTrue(tracker.stagePresent(pending));
        tracker.retire();

        assertFalse(tracker.islandTail(island));
        assertFalse(tracker.mainRetry());
        assertEquals(List.of(), tracker.eventsFor(pending.connection),
                "retirement consumes an uncommitted session without a ghost spawn/remove");
        assertEquals(List.of(Kind.SPAWN, Kind.REMOVE),
                tracker.eventsFor(visible.connection),
                "only a listener which saw spawn receives teardown");
    }

    @Test
    void portalTopologyAndLostPlayerOwnerRetainOrReroutePendingWork() {
        Object oldIsland = new Object();
        Object newIsland = new Object();
        Viewer portalViewer = new Viewer("portal");
        Tracker portalTracker = new Tracker(oldIsland);

        portalTracker.portalPending = true;
        assertTrue(portalTracker.stagePresent(portalViewer));
        assertFalse(portalTracker.islandTail(oldIsland));
        assertEquals(oldIsland, portalTracker.owner(),
                "portal routing stays attached to the source instead of escaping to Main");
        assertFalse(portalTracker.mainScheduled());

        portalTracker.topologyReroute(newIsland, true);
        assertEquals(newIsland, portalTracker.owner(),
                "quiescent topology rebuild attaches pending membership to the fresh owner");
        portalTracker.portalPending = false;
        assertTrue(portalTracker.islandTail(newIsland));
        assertEquals(List.of(Kind.SPAWN),
                portalTracker.eventsFor(portalViewer.connection));

        Viewer playerViewer = new Viewer("player-viewer");
        Tracker playerTracker = new Tracker(oldIsland);
        assertTrue(playerTracker.stagePresent(playerViewer));
        playerTracker.playerPublicationLost(true);
        assertEquals(oldIsland, playerTracker.owner(),
                "a listener-owned transition retains the pending player publication");
        assertFalse(playerTracker.mainScheduled());

        playerTracker.playerPublicationLost(false);
        assertEquals(null, playerTracker.owner());
        assertTrue(playerTracker.mainScheduled(),
                "a genuinely lost player owner detaches and schedules the pending Main retry");
        assertTrue(playerTracker.mainRetry());
        assertTrue(playerTracker.visible(playerViewer.connection));

        Tracker selfPublisher = new Tracker(oldIsland);
        selfPublisher.topologyReroutePlayer(newIsland, true, true);
        assertEquals(newIsland, selfPublisher.owner(),
                "a supported owned player keeps its self-recipient clock with no viewers/intents");
    }

    

    

    

    @Test
    void detachedTopologyGapTransfersPendingCadenceThroughOneExactMainReceipt() {
        ActivePlayerPublication publication =
                ActivePlayerPublication.attached(new Owner("old"));
        publication.topologyGap(true);

        assertEquals(ActiveOwnerRoute.INVALID, publication.route(),
                "detaching under an active A5 receipt is the concrete pre-fix crash state");

        GapHandoff receipt = publication.beginExactGapHandoff();
        assertSame(receipt, publication.beginExactGapHandoff(),
                "a repeated observer joins the accepted receipt instead of publishing a retry");
        assertEquals(PublicationPhase.MAIN_HANDOFF, publication.phase);
        assertEquals(ActiveOwnerRoute.HANDING_OFF, publication.route());
        assertFalse(publication.mainPublicationAllowed(),
                "the rebuild caller has transferred an obligation, not permission to skip it");
        assertEquals(0, publication.mainPublications);
        assertTrue(publication.publicationOwed);

        receipt.completeOnPhysicalMain();

        assertEquals(PublicationPhase.MAIN, publication.phase);
        assertEquals(ActiveOwnerRoute.NONE, publication.route());
        assertFalse(publication.publicationOwed);
        assertEquals(1, publication.mainPublications,
                "the accepted successor performs the one owed ServerEntity publication");
        assertEquals(1, publication.mainCommits,
                "pending membership commits only after that publication succeeds");
        assertEquals(List.of("drain-prefix", "publish-main", "tracker-publication",
                        "pairing-commit"), publication.handoffTimeline,
                "old listener FIFO reaches MAIN before the transferred tracker obligation");
        assertEquals(0, publication.entityBodies,
                "topology handoff must not replay the player entity ordinal");
        assertEquals(0, publication.retrySchedules,
                "the exact accepted Main successor is not a later-tick retry");
        assertThrows(ModelFailure.class, receipt::completeOnPhysicalMain,
                "one receipt cannot execute its physical-Main body twice");
    }

    @Test
    void zeroIntentSelfTrackerGapPublishesTheCapturedPersistentCadence() {
        ActivePlayerPublication publication =
                ActivePlayerPublication.attached(new Owner("old"));
        publication.topologyGap(false);

        GapHandoff receipt = publication.beginExactGapHandoff();
        receipt.completeOnPhysicalMain();

        assertEquals(PublicationPhase.MAIN, publication.phase,
                "a supported self clock cannot retain an owner after its topology vanished");
        assertEquals(1, publication.mainPublications,
                "the captured self cadence is itself one publication obligation");
        assertEquals(1, publication.mainCommits);
        assertEquals(0, publication.entityBodies);
        assertEquals(0, publication.retrySchedules);
    }

    @Test
    void observerDuringZeroIntentGapCoalescesIntoTheSameReceiptExactlyOnce() {
        ActivePlayerPublication publication =
                ActivePlayerPublication.attached(new Owner("old"));
        publication.topologyGap(false);

        GapHandoff receipt = publication.beginExactGapHandoff();
        publication.observePublicationDuringGap();
        publication.observePublicationDuringGap();

        assertSame(receipt, publication.beginExactGapHandoff(),
                "every detached observer transfers into the already accepted receipt");
        receipt.completeOnPhysicalMain();

        assertEquals(PublicationPhase.MAIN, publication.phase);
        assertFalse(publication.publicationOwed);
        assertEquals(1, publication.mainPublications,
                "one or many observers coalesce into the captured cadence publication");
        assertEquals(1, publication.mainCommits);
        assertEquals(0, publication.entityBodies);
        assertEquals(0, publication.retrySchedules);
    }

    @Test
    void topologyGapHandoffRejectsEveryInexactOrNonQuiescentBinding() {
        List<Consumer<ActivePlayerPublication>> corruptions = List.of(
                publication -> publication.trackerActive = false,
                publication -> publication.exactTrackerGeneration = false,
                publication -> publication.runtimeActive = false,
                publication -> publication.exactListenerGeneration = false,
                publication -> publication.exactTrackingBinding = false,
                publication -> publication.physicalMain = false,
                publication -> publication.allIslandClaims = false,
                publication -> publication.quiescent = false,
                publication -> publication.disconnectPending = true,
                publication -> publication.currentOwner = new Owner("live-destination"),
                publication -> publication.trackerOwner = new Owner("still-attached")
        );

        for (Consumer<ActivePlayerPublication> corruption : corruptions) {
            ActivePlayerPublication publication =
                    ActivePlayerPublication.attached(new Owner("old"));
            publication.topologyGap(true);
            corruption.accept(publication);

            assertThrows(ModelFailure.class, publication::beginExactGapHandoff,
                    "detached/pending alone is never authority to cancel an A5 owner");
            assertEquals(PublicationPhase.ISLAND_READY, publication.phase);
            assertEquals(0, publication.mainPublications);
            assertEquals(0, publication.retrySchedules);
        }
    }

    @Test
    void successfulAddDeclineIsTerminalUntilARealFutureVmpAttempt() {
        Object island = new Object();
        Viewer viewer = new Viewer("range-decline");
        Tracker tracker = new Tracker(island);

        assertTrue(tracker.stagePresent(viewer));
        tracker.topologyReroute(null, false);
        assertTrue(tracker.mainScheduled());
        tracker.declineNextAdd();

        assertTrue(tracker.mainRetry());
        assertFalse(tracker.visible(viewer.connection));
        assertEquals(List.of(), tracker.pendingIntents(viewer));
        assertFalse(tracker.mainScheduled(),
                "an exact-range ADD decline is terminal instead of feeding a coarse retry storm");
        assertFalse(tracker.nextTickScheduled());

        assertTrue(tracker.stagePresent(viewer),
                "a later VMP updatePlayer attempt is the authority that may stage ADD again");
        tracker.topologyReroute(island, true);
        assertTrue(tracker.islandTail(island));
        assertTrue(tracker.visible(viewer.connection));
        assertEquals(List.of(), tracker.pendingIntents(viewer));
    }

    @Test
    void detachedRemoveDeclineClearsTokenThenRearmsOneNextTickGeneration() {
        Object island = new Object();
        Viewer viewer = new Viewer("remove-decline");
        Tracker tracker = new Tracker(island);

        assertTrue(tracker.stagePresent(viewer));
        assertTrue(tracker.islandTail(island));
        assertTrue(tracker.stageAbsent(viewer));
        tracker.topologyReroute(null, false);
        assertTrue(tracker.mainScheduled());
        tracker.declineNextRemove();

        assertTrue(tracker.mainRetry());
        assertTrue(tracker.visible(viewer.connection));
        assertEquals(List.of(Intent.REMOVE), tracker.pendingIntents(viewer));
        assertTrue(tracker.mainScheduled(),
                "a still-present captured listener keeps the bounded REMOVE obligation");
        assertTrue(tracker.nextTickScheduled(),
                "remaining detached REMOVE work is re-armed for a later TickTask generation");

        assertTrue(tracker.mainRetry());
        assertFalse(tracker.visible(viewer.connection));
        assertEquals(List.of(), tracker.pendingIntents(viewer));
        assertFalse(tracker.mainScheduled());
    }

    @Test
    void persistentMainClockPublishesOncePerServerTickAndDefersTheDuplicate() {
        MainPublicationGate gate = new MainPublicationGate();

        assertTrue(gate.sendChanges(0));
        assertEquals(1L, gate.generation());
        assertFalse(gate.sendChanges(0),
                "a second physical-Main call in the same server tick must not advance state");
        assertEquals(1L, gate.generation(),
                "sendIslandChanges success is proven by actual original-generation movement");
        assertTrue(gate.nextTickScheduled(),
                "the duplicate retains one future generation instead of losing the clock");

        assertTrue(gate.sendChanges(1));
        assertEquals(2L, gate.generation());
    }

    private enum State { ACTIVE, RETIRED, QUARANTINED }

    private enum Intent { ADD, REVALIDATE, REMOVE }

    private enum Kind { REFRESH, SPAWN, DELTA, REMOVE }

    private enum ActiveOwnerRoute { NONE, ATTACHED, HANDING_OFF, INVALID }

    private enum PublicationPhase { ISLAND_READY, MAIN_HANDOFF, MAIN_DRAINING, MAIN }

    private static final class Owner {
        private final String name;
        private boolean retired;

        private Owner(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * MC-free state at the exact tracker monitor edge used by VMP staging and
     * ServerEntity.sendChanges.  The A5 session owner intentionally remains on the
     * predecessor until the next entity ordinal, while topology carry has already
     * moved the exact self tracker to the committed spatial owner.
     */
    private static final class ActivePlayerPublication {
        private Owner sessionOwner;
        private Owner trackerOwner;
        private Owner currentOwner;
        private final Set<Owner> cadence = Collections.newSetFromMap(
                new IdentityHashMap<>());
        private final List<String> handoffTimeline = new ArrayList<>();
        private boolean trackerActive = true;
        private boolean exactTrackerGeneration = true;
        private boolean runtimeActive = true;
        private boolean exactListenerGeneration = true;
        private boolean exactTrackingBinding = true;
        private boolean physicalMain = true;
        private boolean allIslandClaims = true;
        private boolean quiescent = true;
        private boolean disconnectPending;
        private boolean publicationOwed;
        private PublicationPhase phase = PublicationPhase.ISLAND_READY;
        private GapHandoff gapHandoff;
        private int mainPublications;
        private int mainCommits;
        private int entityBodies;
        private int retrySchedules;

        private static ActivePlayerPublication attached(Owner owner) {
            ActivePlayerPublication publication = new ActivePlayerPublication();
            publication.sessionOwner = owner;
            publication.trackerOwner = owner;
            publication.currentOwner = owner;
            publication.cadence.add(owner);
            return publication;
        }

        

        

        private void topologyGap(boolean ignoredPendingPublication) {
            cadence.clear();
            if (sessionOwner != null) sessionOwner.retired = true;
            trackerOwner = null;
            currentOwner = null;
            // Snapshotting an exact persistent self cadence is already a one-shot
            // publication obligation. Pending membership and later observers only
            // coalesce into that same receipt.
            this.publicationOwed = true;
        }

        private void observePublicationDuringGap() {
            if (gapHandoff == null || phase != PublicationPhase.MAIN_HANDOFF
                    && phase != PublicationPhase.MAIN_DRAINING) {
                throw new ModelFailure();
            }
            publicationOwed = true;
        }

        private GapHandoff beginExactGapHandoff() {
            if (phase == PublicationPhase.MAIN_HANDOFF && gapHandoff != null) {
                return gapHandoff;
            }
            boolean exactGap = phase == PublicationPhase.ISLAND_READY
                    && sessionOwner != null && trackerOwner == null
                    && currentOwner == null && cadence.isEmpty()
                    && trackerActive && exactTrackerGeneration && runtimeActive
                    && exactListenerGeneration && exactTrackingBinding
                    && physicalMain && allIslandClaims
                    && quiescent && !disconnectPending;
            if (!exactGap) throw new ModelFailure();
            gapHandoff = new GapHandoff(this);
            phase = PublicationPhase.MAIN_HANDOFF;
            return gapHandoff;
        }

        private ActiveOwnerRoute route() {
            if (phase == PublicationPhase.MAIN) return ActiveOwnerRoute.NONE;
            if (phase == PublicationPhase.MAIN_HANDOFF
                    || phase == PublicationPhase.MAIN_DRAINING) {
                return gapHandoff != null
                        ? ActiveOwnerRoute.HANDING_OFF : ActiveOwnerRoute.INVALID;
            }
            if (sessionOwner == null) return ActiveOwnerRoute.NONE;
            if (!trackerActive || !exactTrackerGeneration || !runtimeActive) {
                return ActiveOwnerRoute.INVALID;
            }
            if (trackerOwner == sessionOwner && !sessionOwner.retired
                    && cadence.contains(sessionOwner)) {
                return ActiveOwnerRoute.ATTACHED;
            }
            return ActiveOwnerRoute.INVALID;
        }

        private boolean mainPublicationAllowed() {
            return switch (route()) {
                case NONE -> true;
                case ATTACHED, HANDING_OFF -> false;
                case INVALID -> throw new ModelFailure();
            };
        }

        

        
    }

    private static final class GapHandoff {
        private final ActivePlayerPublication publication;
        private boolean completed;

        private GapHandoff(ActivePlayerPublication publication) {
            this.publication = publication;
        }

        private void completeOnPhysicalMain() {
            if (completed || publication.phase != PublicationPhase.MAIN_HANDOFF
                    || publication.gapHandoff != this) {
                throw new ModelFailure();
            }
            completed = true;
            publication.phase = PublicationPhase.MAIN_DRAINING;
            publication.handoffTimeline.add("drain-prefix");
            publication.sessionOwner = null;
            publication.exactTrackingBinding = false;
            publication.phase = PublicationPhase.MAIN;
            publication.handoffTimeline.add("publish-main");
            if (publication.publicationOwed) {
                publication.mainPublications++;
                publication.handoffTimeline.add("tracker-publication");
                publication.publicationOwed = false;
                publication.mainCommits++;
                publication.handoffTimeline.add("pairing-commit");
            }
        }
    }

    

    private static final class Viewer {
        final String name;
        Listener connection;
        boolean removed;
        boolean disconnected;
        Object level;

        private Viewer(String name) {
            this.name = name;
            this.level = ModelLevel.INSTANCE;
            this.connection = new Listener(name + "-listener", this);
        }
    }

    private static final class Listener {
        final String name;
        Viewer player;

        private Listener(String name, Viewer player) {
            this.name = name;
            this.player = player;
        }
    }

    private record Pending(Viewer player, Listener listener, Intent intent) {
        boolean matchesExactly(Viewer candidate) {
            return candidate == player && candidate != null
                    && candidate.connection == listener
                    && listener != null && listener.player == player;
        }

        boolean matchesRemovalPlayer(Viewer candidate) {
            return candidate == player && listener != null;
        }
    }

    private record Event(Kind kind, Viewer viewer, Listener listener, Object venue) {}

    private static final class ModelFailure extends RuntimeException {}

    private static final class MainPublicationGate {
        private int lastServerTick = Integer.MIN_VALUE;
        private long publicationGeneration;
        private boolean nextTickScheduled;

        boolean sendChanges(int serverTick) {
            if (lastServerTick == serverTick) {
                nextTickScheduled = true;
                return false;
            }
            lastServerTick = serverTick;
            long before = publicationGeneration;
            publicationGeneration++;
            return publicationGeneration != before;
        }

        long generation() {
            return publicationGeneration;
        }

        boolean nextTickScheduled() {
            return nextTickScheduled;
        }
    }

    private static final class Tracker {
        private State state = State.ACTIVE;
        private Object owner;
        private final List<Pending> pending = new ArrayList<>();
        private final Map<Listener, Viewer> visible = new IdentityHashMap<>();
        private final List<Event> timeline = new ArrayList<>();
        private List<Viewer> currentPlayers = List.of();
        private boolean pendingBulkRefresh;
        private boolean failNextBulkRefresh;
        private int bulkCommitCount;
        private boolean mainScheduled;
        private boolean nextTickScheduled;
        private boolean portalPending;
        private Intent failNext;
        private boolean declineNextAdd;
        private boolean declineNextRemove;

        private Tracker(Object owner) {
            this.owner = owner;
        }

        synchronized boolean stagePresent(Viewer viewer) {
            if (state == State.RETIRED || viewer == null || viewer.connection == null
                    || viewer.removed || viewer.disconnected
                    || viewer.level != ModelLevel.INSTANCE
                    || viewer.connection.player != viewer) return false;
            Intent intent = visible.containsKey(viewer.connection)
                    ? Intent.REVALIDATE : Intent.ADD;
            for (int index = 0; index < pending.size(); index++) {
                Pending queued = pending.get(index);
                if (queued.matchesExactly(viewer)) {
                    if (queued.intent() != intent) {
                        pending.set(index, new Pending(viewer, viewer.connection, intent));
                    }
                    routeMembershipObligation();
                    return true;
                }
            }
            pending.add(new Pending(viewer, viewer.connection, intent));
            routeMembershipObligation();
            return true;
        }

        synchronized boolean stageAbsent(Viewer viewer) {
            if (state == State.RETIRED || viewer == null || viewer.connection == null) {
                return false;
            }
            pending.removeIf(queued -> queued.player() == viewer);
            if (visible.containsKey(viewer.connection)) {
                pending.add(new Pending(viewer, viewer.connection, Intent.REMOVE));
            }
            routeMembershipObligation();
            return true;
        }

        synchronized boolean stageBulkRefresh() {
            if (state == State.RETIRED) return false;
            pendingBulkRefresh = true;
            routeMembershipObligation();
            return true;
        }

        private void routeMembershipObligation() {
            if (state == State.QUARANTINED) mainScheduled = true;
        }

        synchronized void retire() {
            state = State.RETIRED;
            pendingBulkRefresh = false;
            pending.clear();
            for (Map.Entry<Listener, Viewer> entry : List.copyOf(visible.entrySet())) {
                timeline.add(new Event(Kind.REMOVE, entry.getValue(), entry.getKey(), owner));
            }
            visible.clear();
            owner = null;
            mainScheduled = false;
        }

        synchronized void quarantineAfterIslandFailure() {
            if (state == State.ACTIVE) state = State.QUARANTINED;
            owner = null;
            mainScheduled = true;
        }

        synchronized void topologyReroute(Object currentOwner, boolean eligible) {
            owner = null;
            if (state != State.ACTIVE) return;
            if ((!visible.isEmpty() || hasPendingWork()) && eligible) {
                owner = currentOwner;
            } else if (hasPendingWork()) {
                mainScheduled = true;
            }
        }

        synchronized void topologyReroutePlayer(Object currentOwner,
                                                boolean hasIslandAuthority,
                                                boolean supportedType) {
            owner = null;
            if (state != State.ACTIVE) return;
            if (hasIslandAuthority
                    && (!visible.isEmpty() || hasPendingWork() || supportedType)) {
                owner = currentOwner;
            } else if (hasPendingWork()) {
                mainScheduled = true;
            }
        }

        synchronized void playerPublicationLost(boolean shouldDefer) {
            if (hasPendingWork() && shouldDefer) return;
            owner = null;
            if (hasPendingWork()) mainScheduled = true;
        }

        synchronized boolean islandTail(Object claimedOwner) {
            if (state != State.ACTIVE || owner != claimedOwner || portalPending) return false;
            refreshThenCommit(claimedOwner);
            return true;
        }

        synchronized boolean mainRetry() {
            if (state == State.RETIRED || !hasPendingWork()) return false;
            refreshThenCommit(MainVenue.INSTANCE);
            mainScheduled = hasPendingWork() && owner == null;
            nextTickScheduled = mainScheduled;
            return true;
        }

        private void refreshThenCommit(Object venue) {
            timeline.add(new Event(Kind.REFRESH, null, null, venue));
            for (Map.Entry<Listener, Viewer> entry : List.copyOf(visible.entrySet())) {
                timeline.add(new Event(Kind.DELTA, entry.getValue(), entry.getKey(), venue));
            }
            if (pendingBulkRefresh) {
                for (Viewer viewer : currentPlayers) {
                    if (viewer == null || viewer.connection == null || viewer.removed
                            || viewer.disconnected || viewer.level != ModelLevel.INSTANCE
                            || viewer.connection.player != viewer
                            || visible.containsKey(viewer.connection)) continue;
                    visible.put(viewer.connection, viewer);
                    if (failNextBulkRefresh) {
                        failNextBulkRefresh = false;
                        visible.remove(viewer.connection);
                        throw new ModelFailure();
                    }
                    timeline.add(new Event(Kind.SPAWN, viewer, viewer.connection, venue));
                }
                bulkCommitCount++;
                pendingBulkRefresh = false;
            }
            int attempts = pending.size();
            for (int attempt = 0; attempt < attempts && !pending.isEmpty(); attempt++) {
                Pending candidate = pending.removeFirst();
                boolean removal = candidate.intent() == Intent.REMOVE;
                if (removal) {
                    if (!candidate.matchesRemovalPlayer(candidate.player())) continue;
                } else if (!candidate.matchesExactly(candidate.player())
                        || candidate.player().removed || candidate.player().disconnected
                        || candidate.player().level != ModelLevel.INSTANCE) {
                    continue;
                }
                if (failNext == candidate.intent()) {
                    failNext = null;
                    requeueAfterFailure(candidate);
                    throw new ModelFailure();
                }
                switch (candidate.intent()) {
                    case ADD -> {
                        if (declineNextAdd) {
                            declineNextAdd = false;
                        } else {
                            visible.put(candidate.listener(), candidate.player());
                            timeline.add(new Event(Kind.SPAWN, candidate.player(),
                                    candidate.listener(), venue));
                        }
                    }
                    case REVALIDATE -> { }
                    case REMOVE -> {
                        if (declineNextRemove) {
                            declineNextRemove = false;
                        } else if (visible.remove(candidate.listener()) != null) {
                            timeline.add(new Event(Kind.REMOVE, candidate.player(),
                                    candidate.listener(), venue));
                        }
                    }
                }
                if (mustRetry(candidate)) pending.add(candidate);
            }
        }

        private boolean mustRetry(Pending candidate) {
            return switch (candidate.intent()) {
                case ADD, REVALIDATE -> false;
                case REMOVE -> visible.containsKey(candidate.listener());
            };
        }

        private void requeueAfterFailure(Pending failed) {
            if (state == State.RETIRED || pending.stream()
                    .anyMatch(queued -> queued.player() == failed.player())) return;
            if (failed.intent() == Intent.REMOVE) stageAbsent(failed.player());
            else if (failed.matchesExactly(failed.player())) stagePresent(failed.player());
        }

        synchronized void failNext(Intent intent) {
            this.failNext = intent;
        }

        synchronized void declineNextAdd() {
            this.declineNextAdd = true;
        }

        synchronized void declineNextRemove() {
            this.declineNextRemove = true;
        }

        synchronized void currentPlayers(List<Viewer> players) {
            this.currentPlayers = List.copyOf(players);
        }

        synchronized void failNextBulkRefresh() {
            this.failNextBulkRefresh = true;
        }

        synchronized boolean bulkRefreshPending() {
            return pendingBulkRefresh;
        }

        synchronized int bulkCommitCount() {
            return bulkCommitCount;
        }

        synchronized boolean visible(Listener listener) {
            return visible.containsKey(listener);
        }

        synchronized List<Intent> pendingIntents(Viewer viewer) {
            return pending.stream().filter(candidate -> candidate.player() == viewer)
                    .map(Pending::intent).toList();
        }

        synchronized List<Kind> eventsFor(Listener listener) {
            return timeline.stream().filter(event -> event.listener() == listener)
                    .map(Event::kind).toList();
        }

        synchronized List<Kind> timelineKinds() {
            return timeline.stream().map(Event::kind).toList();
        }

        synchronized Object owner() {
            return owner;
        }

        synchronized boolean mainScheduled() {
            return mainScheduled;
        }

        synchronized boolean nextTickScheduled() {
            return nextTickScheduled;
        }

        private boolean hasPendingWork() {
            return pendingBulkRefresh || !pending.isEmpty();
        }
    }

    private enum ModelLevel { INSTANCE }

    private enum MainVenue { INSTANCE }
}
