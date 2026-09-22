package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ItemFrame;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Splits entity tracking into a Main-owned pairing pass and an island-owned
 * state-publication clock. Every ServerEntity state machine and its pairing
 * mutations share the exact ChunkMap.TrackedEntity intrinsic monitor.
 */
public final class IslandEntityTracking {

    public enum Route { MAIN, ISLAND, DROP }
    /**
     * Result of the one post-physics publication attempt owned by a real-player
     * {@code LATE_RUNNING} permit.
     *
     * <p>{@link #HANDOFF} is a contained topology/lifecycle outcome, not a failed
     * player turn.  The player body has already run and therefore must never be
     * retried on Main; its caller terminalizes the permit and starts the ordered
     * listener-lifetime handoff instead. {@link #INVALID} is reserved for a true
     * token/listener/tracker invariant violation and remains fail-stop.
     */
    enum PostPhysicsResult { PUBLISHED, TRANSFERRED, HANDOFF, INVALID }
    enum PostPhysicsAuthority {
        LIVE,
        PUBLISH_HANDOFF,
        HANDOFF_WITHOUT_PUBLICATION,
        INVALID
    }

    private static final ThreadLocal<IslandTrackedEntityAccess> PUBLISHING =
            new ThreadLocal<>();
    /** Narrow override for one authenticated LATE_RUNNING spatial crossing. */
    private static final ThreadLocal<IslandTrackedEntityAccess>
            POST_PHYSICS_CROSSING_PUBLISHING = new ThreadLocal<>();
    private static final ThreadLocal<PairingCommit> PAIRING_COMMIT =
            new ThreadLocal<>();
    private static final ThreadLocal<IslandTrackedEntityAccess> BULK_PAIRING_COMMIT =
            new ThreadLocal<>();
    /** Exact live ChunkMap tracker for a player's self-recipient publication. */
    private static final ConcurrentHashMap<PlayerIdentity, IslandTrackedEntityAccess>
            PLAYER_TRACKERS = new ConcurrentHashMap<>();
    /** Tracker -> registry only; release the registry before entering arrival custody. */
    private static final IdentityHashMap<IslandTrackedEntityAccess,
            IdentityHashMap<ServerPlayer, ArrivalPairingWait>> ARRIVAL_PAIRINGS =
            new IdentityHashMap<>();

    /** Entity.equals/hashCode use the mutable numeric id; lifecycle keys cannot. */
    private static final class PlayerIdentity {
        private final ServerPlayer player;
        private final int hash;

        private PlayerIdentity(ServerPlayer player) {
            this.player = player;
            this.hash = System.identityHashCode(player);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PlayerIdentity key && player == key.player;
        }
    }

    private record PairingCommit(IslandTrackedEntityAccess tracker,
                                 IslandPendingPairing pending) {}

    private IslandEntityTracking() {}

    /** Called with the exact tracker monitor held by the ServerEntity mixin. */
    public static Route route(Object synchronizer, Entity entity) {
        if (!(synchronizer instanceof IslandTrackedEntityAccess tracker)) {
            return Route.MAIN;
        }
        if (!Thread.holdsLock(tracker)) {
            throw new IllegalStateException(
                    "tracker routing entered without exact tracker monitor");
        }
        int state = tracker.fabrolia$islandTrackingState();
        if (state == IslandTrackedEntityAccess.TRACKING_RETIRED) {
            detach(tracker);
            return Route.DROP;
        }
        // A staged updatePlayer commit runs only after the exact tracker cache
        // was refreshed above. Installed VMP invokes sendChanges once more
        // before seenBy.add; suppress only that exact redundant call.
        if (PAIRING_COMMIT.get() instanceof PairingCommit commit
                && commit.tracker() == tracker
                && commit.pending().intent()
                != IslandPendingPairing.Intent.REMOVE) return Route.DROP;
        if (BULK_PAIRING_COMMIT.get() == tracker) return Route.DROP;
        if (entity == null || entity.isRemoved()) return Route.DROP;
        if (deferPendingArrival(tracker, entity)) return Route.DROP;
        if (PUBLISHING.get() == tracker) return Route.MAIN;
        RegionizedServer runtime = runtime(entity);
        if (runtime != null) runtime.enterTrackingTopologyRouting();
        try {
            // A spatial crossing can make the current chunk owner differ while the
            // exact A5 source turn is still EARLY/LATE/HANDOFF-owned. Ordinary Main
            // tracking must neither publish nor detach/schedule from that transient
            // observation. This fence deliberately precedes runtime-OFF and
            // quarantine routing too: MAIN_HANDOFF remains an attached transaction,
            // and its terminal tail is the only actor allowed to release it.
            if (handoffMainPublicationToActiveOwnerLocked(tracker, entity)) {
                return Route.DROP;
            }

            if (runtime == null) {
                detach(tracker);
                return Route.MAIN;
            }
            if (runtime.trackingTopologyTransitionReceipt() != null) {
                // This exact foreign call began after the topology write-side cut.
                // It cannot attach behind the old snapshot. Transfer one deduped
                // publication successor to physical Main; no entity/player body is
                // executed or retried by this routing decision.
                detach(tracker);
                scheduleMainPublication(tracker);
                return Route.DROP;
            }
            if (!runtime.asyncActive) {
                detach(tracker);
                return Route.MAIN;
            }
            if (state == IslandTrackedEntityAccess.TRACKING_QUARANTINED) {
                detach(tracker);
                return mainRouteOrSchedule(tracker, entity, runtime);
            }
            if (requiresPersistentMainPublisher(entity)) {
                IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(
                        entity.chunkPosition().pack());
                if (owner != null && !owner.retired
                        && (tracker.fabrolia$hasPendingIslandPairings()
                        || tracker.fabrolia$hasIslandTrackingViewers()
                        && persistentMainClockEligible(entity, runtime))) {
                    // The owner tail is only the cadence source. Publication stays
                    // physical-Main and claims this owner in the scheduled task.
                    attach(tracker, owner);
                } else {
                    detach(tracker);
                }
                return mainRouteOrSchedule(tracker, entity, runtime);
            }
            IslandRegistry.PIsland island =
                    runtime.islandOwnerOfChunk(entity.chunkPosition().pack());
            if (entity instanceof ServerPlayer player) {
                if (!AsyncIslandPlayerTicks.shouldDeferTrackingPublication(player, island)) {
                    detach(tracker);
                    return mainRouteOrSchedule(tracker, entity, runtime);
                }
                // EARLY/LATE/HANDOFF are still one attached owner lifetime.  A
                // zero-view player needs this exact tracker for self motion (Lunge).
                attach(tracker, island);
                return Route.ISLAND;
            }
            if ((portalDeparturePending(entity.getRootVehicle())
                    || portalDeparturePending(entity))
                    && islandCompatibleNonPlayer(entity, island)) {
                // A staged portal departure has not committed to either venue.
                // Keep its tracker obligation on the source owner until the
                // transfer retires/reroutes it; a claimed Main refresh could run
                // before the deferred teleport task and expose stale source state.
                attach(tracker, island);
                return Route.DROP;
            }
            if (!eligibleNonPlayer(entity, island)) {
                detach(tracker);
                return mainRouteOrSchedule(tracker, entity, runtime);
            }
            // VMP refreshes a zero-view tracker immediately before updatePlayer.
            // Suppress that Main read too: one island-tail publication refreshes
            // the codec/metadata cache, then detaches again if no pairing arrived.
            attach(tracker, island);
            return Route.ISLAND;
        } finally {
            if (runtime != null) runtime.exitTrackingTopologyRouting();
        }
    }

    /**
     * Intercepts an unseen viewer before the exact transformed updatePlayer
     * reads entity state. The listener remains invisible to delta broadcasts
     * until the island tail refreshes ServerEntity's cache and re-enters that
     * same vanilla/VMP method to send its spawn bundle.
     */
    public static boolean stagePairingIfOwned(
            IslandTrackedEntityAccess tracker, ServerPlayer viewer) {
        if (tracker == null || viewer == null
                || tracker.fabrolia$islandTrackingState()
                == IslandTrackedEntityAccess.TRACKING_RETIRED) return false;
        Entity entity = tracker.fabrolia$trackedEntity();
        if (entity == null || entity == viewer || entity.isRemoved() || viewer.isRemoved()
                || viewer.hasDisconnected() || viewer.connection == null
                || viewer.connection.player != viewer
                || viewer.level() != entity.level()) return false;
        RegionizedServer runtime = runtime(entity);
        if ((runtime == null || !runtime.asyncActive) && !arrivalPending(entity)
                || !tracker.fabrolia$queueIslandPairing(viewer)) return false;
        routeMembershipObligation(tracker, entity, runtime);
        return true;
    }

    /**
     * Retains only this viewer's membership until its arrival metadata settles.
     * State publication and other recipients remain on their existing cadence.
     * Caller has validated the viewer and owns the exact tracker monitor.
     */
    public static boolean deferArrivalPairing(
            IslandTrackedEntityAccess tracker, ServerPlayer viewer) {
        // The ordinary pairing path does not allocate or consult the wait index.
        if (!arrivalPending(viewer)) return false;
        if (tracker == null || !Thread.holdsLock(tracker)) {
            throw new IllegalStateException(
                    "arrival pairing without exact tracker authority");
        }
        ArrivalPairingWait waiting = null;
        try {
            RegionScheduler.throwWorkerFailureIfAny();
            // Bulk/per-viewer commit may already have detached this intent.
            // A retained wait is not proof that the pending queue still owns it.
            if (!tracker.fabrolia$queueIslandPairing(viewer)) {
                throw new IllegalStateException(
                        "validated arrival viewer lost its pairing obligation");
            }
            Entity entity = tracker.fabrolia$trackedEntity();
            synchronized (ARRIVAL_PAIRINGS) {
                IdentityHashMap<ServerPlayer, ArrivalPairingWait> viewers =
                        ARRIVAL_PAIRINGS.get(tracker);
                ArrivalPairingWait prior = viewers == null ? null : viewers.get(viewer);
                if (prior != null && prior.listener == viewer.connection) return true;
                waiting = new ArrivalPairingWait(tracker, entity, viewer);
                if (viewers == null) {
                    viewers = new IdentityHashMap<>();
                    viewers.put(viewer, waiting);
                    ARRIVAL_PAIRINGS.put(tracker, viewers);
                } else {
                    viewers.put(viewer, waiting);
                }
            }
            if (!NetherPortalPlayerAccess.deferArrivalPublication(viewer, waiting)) {
                // Settlement won the registration race. The queued intent still
                // owns the attempt; never fall through to an inline native body.
                waiting.publish();
            }
            return true;
        } catch (Throwable thrown) {
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            if (waiting != null) {
                try {
                    waiting.cancel();
                } catch (Throwable cleanup) {
                    failure = mergeFailure(failure, cleanup);
                }
            }
            IslandEntityTracking.<RuntimeException>rethrow(failure);
            return true;
        }
    }

    /** One current exact-session wait, independent of the tracker's publication token. */
    private static final class ArrivalPairingWait
            implements NetherPortalPlayerAccess.ArrivalPublication {
        private final IslandTrackedEntityAccess tracker;
        private final Entity entity;
        private final ServerPlayer viewer;
        private final ServerGamePacketListenerImpl listener;

        private ArrivalPairingWait(
                IslandTrackedEntityAccess tracker, Entity entity, ServerPlayer viewer) {
            this.tracker = tracker;
            this.entity = entity;
            this.viewer = viewer;
            this.listener = viewer.connection;
        }

        @Override
        public void publish() {
            try {
                synchronized (tracker) {
                    if (!currentLocked()) return;
                    RegionScheduler.throwWorkerFailureIfAny();
                    boolean liveTracker = tracker.fabrolia$trackedEntity() == entity
                            && entity != null && !entity.isRemoved()
                            && tracker.fabrolia$islandTrackingState()
                            != IslandTrackedEntityAccess.TRACKING_RETIRED;
                    if (liveTracker && viewer.connection == listener
                            && listener != null && listener.player == viewer
                            && !viewer.isRemoved() && !viewer.hasDisconnected()
                            && viewer.level() == entity.level()
                            && arrivalPending(viewer)
                            && NetherPortalPlayerAccess.deferArrivalPublication(viewer, this)) {
                        // A newer arrival owns the same handle, not another
                        // pending intent or a whole-tracker publication token.
                        return;
                    }
                    // Invalid viewers are consumed by ordinary pending-intent
                    // validation. Never cancel by player: a newer REMOVE may own
                    // the queue. Keep this wait until scheduling succeeds so an
                    // offer failure selects W before exact-handle cleanup.
                    if (liveTracker && tracker.fabrolia$hasPendingIslandPairings())
                        scheduleMainPublication(tracker);
                    removeLocked();
                }
            } catch (Throwable thrown) {
                Throwable failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
                try {
                    cancel();
                } catch (Throwable cleanup) {
                    failure = mergeFailure(failure, cleanup);
                }
                IslandEntityTracking.<RuntimeException>rethrow(failure);
            }
        }

        @Override
        public void cancel() {
            synchronized (tracker) {
                removeLocked();
            }
        }

        private boolean currentLocked() {
            synchronized (ARRIVAL_PAIRINGS) {
                IdentityHashMap<ServerPlayer, ArrivalPairingWait> viewers =
                        ARRIVAL_PAIRINGS.get(tracker);
                return viewers != null && viewers.get(viewer) == this;
            }
        }

        private void removeLocked() {
            synchronized (ARRIVAL_PAIRINGS) {
                IdentityHashMap<ServerPlayer, ArrivalPairingWait> viewers =
                        ARRIVAL_PAIRINGS.get(tracker);
                if (viewers == null || viewers.get(viewer) != this) return;
                viewers.remove(viewer);
                if (viewers.isEmpty()) ARRIVAL_PAIRINGS.remove(tracker);
            }
        }
    }

    /**
     * Collapses VMP tickStaging's updatePlayers(level.players()) fan-out into
     * one tracker-local obligation. Expanding it into per-viewer pending
     * records is both unnecessary and catastrophic at 12k entities / 100
     * players; exact vanilla range/session checks run when this token commits.
     */
    public static boolean stageBulkPairingRefreshIfOwned(
            IslandTrackedEntityAccess tracker) {
        if (tracker == null) return false;
        // Installed VMP's bulk staging seam is not entered through the
        // synchronized TrackedEntity update/remove wrappers. Keep publication
        // of the exact bulk bit and selection of its successor in one tracker
        // transaction: an island tail must not consume the bit between those
        // two operations, and a topology writer may cut only at the nested read
        // admission below. routeMembershipObligation takes this monitor too;
        // that acquisition is deliberately reentrant.
        synchronized (tracker) {
            if (tracker.fabrolia$islandTrackingState()
                    != IslandTrackedEntityAccess.TRACKING_ACTIVE) return false;
            Entity entity = tracker.fabrolia$trackedEntity();
            if (entity == null || entity.isRemoved()) return false;
            RegionizedServer runtime = runtime(entity);
            if ((runtime == null || !runtime.asyncActive) && !arrivalPending(entity)
                    || !tracker.fabrolia$queueIslandBulkPairingRefresh()) return false;
            routeMembershipObligation(tracker, entity, runtime);
            return true;
        }
    }

    public static boolean isBulkPairingCommit(IslandTrackedEntityAccess tracker) {
        return tracker != null && BULK_PAIRING_COMMIT.get() == tracker;
    }

    /** Caller owns the exact tracker monitor and has refreshed its cache. */
    public static void commitBulkPairingRefresh(
            IslandTrackedEntityAccess tracker, Runnable body) {
        if (tracker == null || body == null || !Thread.holdsLock(tracker)) {
            throw new IllegalStateException(
                    "bulk pairing commit without exact tracker authority");
        }
        if (BULK_PAIRING_COMMIT.get() != null || PAIRING_COMMIT.get() != null) {
            throw new IllegalStateException("nested bulk island pairing commit");
        }
        BULK_PAIRING_COMMIT.set(tracker);
        Throwable failure = null;
        try {
            RegionScheduler.runSuccessBodyIfHealthy(body);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            BULK_PAIRING_COMMIT.remove();
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    mergeFailure(failure, cleanup));
        }
        if (failure != null) IslandEntityTracking.<RuntimeException>rethrow(failure);
    }

    /** Stages the exact transformed removePlayer for the same owner protocol. */
    public static boolean stageRemovalIfOwned(
            IslandTrackedEntityAccess tracker, ServerPlayer viewer) {
        if (tracker == null || viewer == null
                || tracker.fabrolia$islandTrackingState()
                == IslandTrackedEntityAccess.TRACKING_RETIRED) return false;
        Entity entity = tracker.fabrolia$trackedEntity();
        if (entity == null || viewer.connection == null) return false;
        RegionizedServer runtime = runtime(entity);
        if ((runtime == null || !runtime.asyncActive) && !arrivalPending(entity)
                || !tracker.fabrolia$queueIslandRemoval(viewer)) return false;
        routeMembershipObligation(tracker, entity, runtime);
        return true;
    }

    private static void routeMembershipObligation(
            IslandTrackedEntityAccess tracker, Entity entity,
            RegionizedServer runtime) {
        // NearbyEntityTracking.tickStaging invokes the bulk seam directly, while
        // transformed updatePlayer/removePlayer already own this monitor.  Take it
        // here in both cases (reentrant for the latter), then enter the shared
        // topology read side.  Every membership caller therefore has the one lock
        // order tracker -> topology-read; no read-holder can wait for this tracker
        // while a queued topology writer waits for that read-holder.
        synchronized (tracker) {
            if (deferPendingArrival(tracker, entity)) return;
            if (runtime == null || !runtime.asyncActive) {
                scheduleMainPublication(tracker);
                return;
            }
            runtime.enterTrackingTopologyRouting();
            try {
                // Preserve staged membership on a live tracker already owned by the
                // exact A5 session.  Its attached island-tail entry is the structural
                // continuation: publishing another Main task would overlap that owner,
                // while a detached/quarantined tracker is an invariant failure rather
                // than work which can be made safe by trying the body on a later tick.
                if (handoffMainPublicationToActiveOwnerLocked(tracker, entity)) {
                    return;
                }
                if (runtime.trackingTopologyTransitionReceipt() != null) {
                    detach(tracker);
                    scheduleMainPublication(tracker);
                    return;
                }
                if (tracker.fabrolia$islandTrackingState()
                        == IslandTrackedEntityAccess.TRACKING_QUARANTINED) {
                    scheduleMainPublication(tracker);
                    return;
                }
                IslandRegistry.PIsland island = runtime.islandOwnerOfChunk(
                        entity.chunkPosition().pack());
                boolean portal = portalDeparturePending(entity.getRootVehicle())
                        || portalDeparturePending(entity);
                boolean islandOwned = entity instanceof ServerPlayer player
                        ? AsyncIslandPlayerTicks.shouldDeferTrackingPublication(player, island)
                        : eligibleNonPlayer(entity, island);
                boolean portalOwned = !(entity instanceof ServerPlayer)
                        && portal && islandCompatibleNonPlayer(entity, island);
                if (island != null && !island.retired && (portalOwned || islandOwned)) {
                    attach(tracker, island);
                } else {
                    scheduleMainPublication(tracker);
                }
            } finally {
                runtime.exitTrackingTopologyRouting();
            }
        }
    }

    /** Exact re-entry marker for the staged transformed updatePlayer call. */
    public static boolean isPairingCommit(
            IslandTrackedEntityAccess tracker, ServerPlayer player) {
        PairingCommit commit = PAIRING_COMMIT.get();
        return commit != null && commit.tracker() == tracker
                && commit.pending().intent() != IslandPendingPairing.Intent.REMOVE
                && commit.pending().player() == player;
    }

    /** Rechecks the captured listener immediately before vanilla/VMP re-entry. */
    public static boolean pairingCommitStillCurrent(
            IslandTrackedEntityAccess tracker, ServerPlayer player) {
        PairingCommit commit = PAIRING_COMMIT.get();
        return commit != null && commit.tracker() == tracker
                && commit.pending().intent() != IslandPendingPairing.Intent.REMOVE
                && commit.pending().matchesExactly(player);
    }

    public static boolean isRemovalCommit(
            IslandTrackedEntityAccess tracker, ServerPlayer player) {
        PairingCommit commit = PAIRING_COMMIT.get();
        return commit != null && commit.tracker() == tracker
                && commit.pending().intent() == IslandPendingPairing.Intent.REMOVE
                && commit.pending().matchesRemovalPlayer(player);
    }

    /** Caller owns the exact tracker monitor and has refreshed its cache. */
    public static void commitPairing(IslandTrackedEntityAccess tracker,
                                     IslandPendingPairing pending,
                                     Runnable body) {
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> commitPairingInAdmittedBody(tracker, pending, body));
    }

    /**
     * Exact pairing body for a caller which already atomically detached the
     * corresponding pending intent under a scheduler admission.  A second
     * admission here could be refused by a peer W after the list removal and
     * would leave that accepted intent with neither a queue owner nor a body.
     */
    public static void commitPairingInAdmittedBody(
            IslandTrackedEntityAccess tracker,
            IslandPendingPairing pending,
            Runnable body) {
        if (tracker == null || pending == null || body == null
                || !Thread.holdsLock(tracker)) {
            throw new IllegalStateException(
                    "pairing commit without exact tracker authority");
        }
        if (PAIRING_COMMIT.get() != null) {
            throw new IllegalStateException("nested island pairing commit");
        }
        PAIRING_COMMIT.set(new PairingCommit(tracker, pending));
        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            PAIRING_COMMIT.remove();
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    mergeFailure(failure, cleanup));
        }
        if (failure != null) IslandEntityTracking.<RuntimeException>rethrow(failure);
    }

    /** Pure-vanilla first pairing does not call ServerEntity.sendChanges. */
    public static void registerFromPairing(IslandTrackedEntityAccess tracker) {
        if (tracker == null
                || tracker.fabrolia$islandTrackingState()
                != IslandTrackedEntityAccess.TRACKING_ACTIVE) return;
        Entity entity = tracker.fabrolia$trackedEntity();
        RegionizedServer runtime = runtime(entity);
        if (runtime == null || !runtime.asyncActive
                || requiresPersistentMainPublisher(entity)) return;
        synchronized (tracker) {
            runtime.enterTrackingTopologyRouting();
            try {
                if (tracker.fabrolia$islandTrackingState()
                        != IslandTrackedEntityAccess.TRACKING_ACTIVE
                        || tracker.fabrolia$trackedEntity() != entity) return;
                if (handoffMainPublicationToActiveOwnerLocked(tracker, entity)) return;
                if (runtime.trackingTopologyTransitionReceipt() != null) {
                    detach(tracker);
                    scheduleMainPublication(tracker);
                    return;
                }
                IslandRegistry.PIsland island =
                        runtime.islandOwnerOfChunk(entity.chunkPosition().pack());
                if (entity instanceof ServerPlayer player) {
                    if (AsyncIslandPlayerTicks.shouldDeferTrackingPublication(
                            player, island)) {
                        attach(tracker, island);
                    }
                    return;
                }
                if ((!tracker.fabrolia$hasIslandTrackingViewers()
                        && !tracker.fabrolia$hasPendingIslandPairings())) return;
                if (eligibleNonPlayer(entity, island)) {
                    attach(tracker, island);
                }
            } finally {
                runtime.exitTrackingTopologyRouting();
            }
        }
    }

    /** Stop the persistent L-clock when the final viewer leaves. */
    public static void unregisterIfIdle(IslandTrackedEntityAccess tracker) {
        if (tracker == null) return;
        synchronized (tracker) {
            if (tracker.fabrolia$hasIslandTrackingViewers()
                    || tracker.fabrolia$hasPendingIslandPairings()) return;
            Entity entity = tracker.fabrolia$trackedEntity();
            RegionizedServer runtime = runtime(entity);
            if (runtime != null) runtime.enterTrackingTopologyRouting();
            try {
                if (handoffMainPublicationToActiveOwnerLocked(tracker, entity)) return;
                if (runtime != null
                        && runtime.trackingTopologyTransitionReceipt() != null) {
                    detach(tracker);
                    return;
                }
                if (entity instanceof ServerPlayer player) {
                    IslandRegistry.PIsland island = runtime == null ? null
                            : runtime.islandOwnerOfChunk(entity.chunkPosition().pack());
                    boolean deferred = AsyncIslandPlayerTicks
                            .shouldDeferTrackingPublication(player, island);
                    if (AsyncIslandPlayerTicks.supportsIslandPlayerType(player)
                            && (AsyncIslandPlayerTicks.hasAttachedOwner(player)
                            || deferred)) {
                        if (deferred) attach(tracker, island);
                        return;
                    }
                }
                detach(tracker);
            } finally {
                if (runtime != null) runtime.exitTrackingTopologyRouting();
            }
        }
    }

    /** Registers the exact self tracker created for one ServerPlayer lifecycle. */
    public static void registerPlayerTracker(IslandTrackedEntityAccess tracker) {
        if (tracker == null) return;
        synchronized (tracker) {
            Entity entity = tracker.fabrolia$trackedEntity();
            if (entity instanceof ServerPlayer player) {
                IslandTrackedEntityAccess existing = PLAYER_TRACKERS.putIfAbsent(
                        new PlayerIdentity(player), tracker);
                if (existing != null && existing != tracker) {
                    IllegalStateException failure = new IllegalStateException(
                            "player self-tracker generation replaced before retirement");
                    IslandEntityTracking.<RuntimeException>rethrow(
                            RegionScheduler.publishFatalAndSelectWinner(failure));
                }
            }
        }
    }

    /** Exact compare-and-remove; retiring an old generation cannot erase its successor. */
    public static void unregisterPlayerTracker(IslandTrackedEntityAccess tracker) {
        if (tracker == null) return;
        synchronized (tracker) {
            Entity entity = tracker.fabrolia$trackedEntity();
            if (entity instanceof ServerPlayer player
                    && PLAYER_TRACKERS.remove(new PlayerIdentity(player), tracker)) {
                AsyncIslandPlayerTicks.clearTrackingPublicationTracker(player, tracker);
            }
        }
    }

    /** Lock-free candidate lookup; admission must revalidate after taking its monitor. */
    static IslandTrackedEntityAccess currentPlayerTracker(ServerPlayer player) {
        return player == null ? null : PLAYER_TRACKERS.get(new PlayerIdentity(player));
    }

    /** Exact self-tracker proof used on the canonical tracker -> PlayerState edge. */
    static boolean currentLivePlayerTrackerLocked(
            IslandTrackedEntityAccess tracker, ServerPlayer player) {
        if (tracker == null || player == null || !Thread.holdsLock(tracker)) {
            throw new IllegalStateException(
                    "A5 self-tracker admission proof without tracker lock");
        }
        return tracker.fabrolia$islandTrackingState()
                == IslandTrackedEntityAccess.TRACKING_ACTIVE
                && tracker.fabrolia$trackedEntity() == player
                && PLAYER_TRACKERS.get(new PlayerIdentity(player)) == tracker;
    }

    /**
     * Completes owner admission before the tracker monitor can expose that owner to
     * sendChanges/VMP. Failure after session installation is structural and fatal;
     * no accepted player body or membership token is deferred to a later tick.
     */
    static void attachPlayerTrackerAtAdmissionLocked(
            IslandTrackedEntityAccess tracker, ServerPlayer player,
            AsyncIslandPlayerTicks.PlayerState playerState,
            RegionizedServer runtime, IslandRegistry.PIsland island,
            IslandPlayerTurnSession.OwnerStamp owner,
            IslandPlayerTurnSession.OwnerStamp priorOwner) {
        if (tracker == null || playerState == null || !Thread.holdsLock(tracker)
                || !Thread.holdsLock(playerState)) {
            throw new IllegalStateException(
                    "A5 self-tracker attachment without tracker/state lock");
        }
        boolean exactOwner = player != null && island != null && owner != null
                && owner.playerIdentity() == player
                && owner.listenerIdentity() == player.connection
                && owner.islandIdentity() == island
                && owner.islandId() == island.id
                && owner.islandGeneration() == island.gen
                && owner.domainIdentity() == island.domain
                && owner.kind() == AsyncIslandPlayerTicks.classifyPlayer(player);
        if (!currentLivePlayerTrackerLocked(tracker, player)
                || runtime == null || runtime != runtime(player)
                || !runtime.asyncActive || runtime.level != player.level()
                || island == null || island.retired || !exactOwner) {
            IllegalStateException failure = new IllegalStateException(
                    "A5 owner admission has no exact live self tracker");
            IslandEntityTracking.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
            return;
        }
        IslandPlayerTurnSession.View ownerView = playerState.session.view();
        boolean exactBinding = playerState.trackingPublication.matches(tracker, owner);
        boolean retiredGenerationHandoff =
                playerState.trackingPublication.retiredGeneration(priorOwner);
        if (ownerView.owner() != owner
                || priorOwner == null && playerState.trackingPublication.sessionEpoch() != -1L
                || priorOwner != null
                && !playerState.trackingPublication.matches(tracker, priorOwner)
                && !retiredGenerationHandoff) {
            IllegalStateException failure = new IllegalStateException(
                    "A5 owner admission conflicts with its self tracker generation");
            IslandEntityTracking.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
            return;
        }
        if (owner != priorOwner || retiredGenerationHandoff) {
            attachCadence(tracker, island);
        } else if (!exactBinding) {
            IllegalStateException failure = new IllegalStateException(
                    "A5 reused owner has no exact self tracker binding");
            IslandEntityTracking.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
            return;
        }
        if (!currentLivePlayerTrackerLocked(tracker, player)
                || tracker.fabrolia$islandTrackingOwner() != island
                || !island.pendingEntityUpdates.contains(tracker)) {
            IllegalStateException failure = new IllegalStateException(
                    "A5 owner admission could not publish its self tracker cadence");
            IslandEntityTracking.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
            return;
        }
        playerState.trackingPublication.admit(tracker, priorOwner, owner, island);
    }

    /** Called under the island unit claim before the real-player listener tail. */
    public static void flush(RegionizedServer runtime, IslandRegistry.PIsland island) {
        if (runtime == null || island == null) return;
        // A ConcurrentHashMap iterator is deliberately only weakly consistent.
        // Snapshot and attach exact PendingLate self trackers independently, but
        // do NOT publish them in this general generation. listener.tick/doTick
        // must first consume the replayed impulse and apply vanilla physics;
        // runLate publishes each exact tracker while its LATE_RUNNING permit is
        // still live. The identity set also excludes a weak-set duplicate.
        Set<IslandTrackedEntityAccess> deferredPlayers =
                snapshotPendingRealPlayerTrackers(island);
        for (IslandTrackedEntityAccess tracker : island.pendingEntityUpdates) {
            if (!deferredPlayers.contains(tracker)) {
                publishCurrent(runtime, island, tracker);
            }
        }
    }

    /** Carry persistent tracker clocks across merge/split/topology retirement. */
    static void reroute(RegionizedServer runtime, IslandTrackedEntityAccess tracker) {
        if (tracker == null) return;
        // The tracker monitor is the publication cut.  No VMP/Main caller may
        // observe the temporary detached shape between retirement of the old
        // topology and either attachment to the fresh owner or the exact A5 close.
        synchronized (tracker) {
            if (runtime == null || tracker.fabrolia$islandTrackingState()
                    != IslandTrackedEntityAccess.TRACKING_ACTIVE) {
                detach(tracker);
                return;
            }
            Entity entity = tracker.fabrolia$trackedEntity();
            if (entity == null || entity.isRemoved()) {
                detach(tracker);
                return;
            }
            boolean hasPending = tracker.fabrolia$hasPendingIslandPairings();
            boolean hasViewers = tracker.fabrolia$hasIslandTrackingViewers();
            if (entity instanceof ServerPlayer player
                    && AsyncIslandPlayerTicks.supportsIslandPlayerType(player)
                    && AsyncIslandPlayerTicks.ownsTrackingTopologyGap(
                    player, tracker)) {
                // A prior gap may still be draining its ordered Main prefix while
                // another topology commits. Its exact receipt keeps this tracker
                // detached until that prefix and publication both terminalize.
                return;
            }
            // Validate the existing binding before any detached gap is created.
            // This rebuild's BEGIN snapshot, not the mutable spatial map, owns carry.
            handoffMainPublicationToActiveOwnerLocked(tracker, entity);
            if (arrivalPending(entity)) {
                detach(tracker);
                scheduleMainPublication(tracker);
                return;
            }
            IslandRegistry.PIsland current =
                    runtime.islandOwnerOfChunk(entity.chunkPosition().pack());
            if (current == null && entity instanceof ServerPlayer player
                    && AsyncIslandPlayerTicks.supportsIslandPlayerType(player)) {
                detach(tracker);
                if (AsyncIslandPlayerTicks.handoffTrackingTopologyGapLocked(player, tracker)) return;
            }
            if (!runtime.asyncActive) {
                detach(tracker);
                fallbackPendingPairingsOnMain(tracker);
                return;
            }
            if (requiresPersistentMainPublisher(entity)) {
                if (hasPending || hasViewers
                        && persistentMainClockEligible(entity, runtime)) {
                    IslandRegistry.PIsland owner = currentOwner(runtime, entity);
                    if (owner != null && !owner.retired) attach(tracker, owner);
                    else detach(tracker);
                    scheduleMainPublication(tracker);
                } else {
                    detach(tracker);
                }
                return;
            }
            if (current == null) {
                detach(tracker);
                fallbackPendingPairingsOnMain(tracker);
                return;
            }
            if (entity instanceof ServerPlayer player) {
                boolean islandPlayer = AsyncIslandPlayerTicks.hasAttachedOwner(player)
                        || AsyncIslandPlayerTicks.shouldDeferTrackingPublication(
                        player, current)
                        || AsyncIslandPlayerTicks.ownsTrackingPublication(player, current);
                if (islandPlayer && (hasViewers || hasPending
                        || AsyncIslandPlayerTicks.supportsIslandPlayerType(player))) {
                    // Rebuild is quiescent. The A5 session may still name the
                    // retired island; keep the tracker on the exact fresh owner so
                    // the next player turn can transfer then revalidate at tail.
                    // A supported player's self clock remains persistent without
                    // seenBy viewers: motion/impulse packets use
                    // sendToTrackingPlayersAndSelf (notably spear Lunge).
                    attach(tracker, current);
                } else {
                    detach(tracker);
                    if (hasPending) scheduleMainPublication(tracker);
                }
                return;
            }
            boolean portal = portalDeparturePending(entity.getRootVehicle())
                    || portalDeparturePending(entity);
            if ((hasViewers || hasPending)
                    && (eligibleNonPlayer(entity, current)
                    || portal && islandCompatibleNonPlayer(entity, current))) {
                attach(tracker, current);
            } else {
                detach(tracker);
                if (hasPending) scheduleMainPublication(tracker);
            }
        }
    }

    /** Revalidates every authority while holding the exact tracker monitor. */
    private static boolean publishCurrent(RegionizedServer runtime,
                                          IslandRegistry.PIsland island,
                                          IslandTrackedEntityAccess tracker) {
        synchronized (tracker) {
            if (tracker.fabrolia$islandTrackingState()
                    != IslandTrackedEntityAccess.TRACKING_ACTIVE) {
                detach(tracker);
                return false;
            }
            if (tracker.fabrolia$islandTrackingOwner() != island) {
                // A weak iterator from the prior identity must not detach a
                // tracker which has already atomically attached elsewhere.
                island.pendingEntityUpdates.remove(tracker);
                return false;
            }
            Entity entity = tracker.fabrolia$trackedEntity();
            if (entity == null || entity.isRemoved()) {
                detach(tracker);
                return false;
            }
            if (deferPendingArrival(tracker, entity)) return false;
            IslandRegistry.PIsland current =
                    runtime.islandOwnerOfChunk(entity.chunkPosition().pack());
            if (current != island) {
                runtime.enterTrackingTopologyRouting();
                try {
                    if (handoffMainPublicationToActiveOwnerLocked(tracker, entity)) {
                        // Source turns and their ordered Main handoffs retain this
                        // cadence, including real players without a PendingLate.
                        // Only admission, topology, or the post-physics tail may
                        // carry it; a foreign cadence pass cannot detach or move it.
                        return false;
                    }
                } finally {
                    runtime.exitTrackingTopologyRouting();
                }
                // Keep a staged viewer live across an owner move. The old
                // weak-set entry is removed before the exact current owner is
                // selected; no spawn bundle is exposed in between.
                detach(tracker);
                if (tracker.fabrolia$hasPendingIslandPairings()) {
                    if (current != null && !current.retired) attach(tracker, current);
                    else scheduleMainPublication(tracker);
                }
                return false;
            }
            if (requiresPersistentMainPublisher(entity)) {
                if (tracker.fabrolia$hasPendingIslandPairings()
                        || tracker.fabrolia$hasIslandTrackingViewers()
                        && persistentMainClockEligible(entity, runtime)) {
                    // A bounded one-token bridge from autonomous L cadence to
                    // a claimed physical-Main ServerEntity state machine.
                    scheduleMainPublication(tracker);
                } else {
                    detach(tracker);
                }
                return false;
            }
            if (entity instanceof ServerPlayer player) {
                if (PLAYER_TRACKERS.get(new PlayerIdentity(player)) != tracker) {
                    // A replacement tracker generation won registration.  The
                    // old one may still appear in a weak owner-set iterator,
                    // but it can never publish the same player's state again.
                    detach(tracker);
                    return false;
                }
                // A real PendingLate tracker is an obligation of runLate, not
                // this general generation: publishing the replayed raw Lunge
                // impulse here sends the pre-friction value. READY remains the
                // clock for Carpet and turns without a real late token.
                if (hasPendingRealPlayerBeforeLate(player, island)) return false;
                boolean published = AsyncIslandPlayerTicks.runTrackingPublicationIfOwned(
                        player, island, () -> publishAndPairUnderTrackerLock(tracker));
                if (!published) {
                    if (AsyncIslandPlayerTicks.supportsIslandPlayerType(player)
                            && (AsyncIslandPlayerTicks.hasAttachedOwner(player)
                            || AsyncIslandPlayerTicks.shouldDeferTrackingPublication(
                            player, island))) return false;
                    detach(tracker);
                    if (tracker.fabrolia$hasPendingIslandPairings()) {
                        scheduleMainPublication(tracker);
                    }
                }
                return published;
            }
            if (!eligibleNonPlayer(entity, island)) {
                // A portal/departure or temporary ticking-range cut must not
                // expose a stale pending viewer from Main. Portal transfer is
                // the only cut whose mutation has not reached either venue;
                // every other false result means Main can refresh once now.
                if (portalDeparturePending(entity.getRootVehicle())
                        || portalDeparturePending(entity)) return false;
                if (tracker.fabrolia$hasPendingIslandPairings()) {
                    detach(tracker);
                    scheduleMainPublication(tracker);
                    return false;
                }
                detach(tracker);
                return false;
            }
            boolean published = publishAndPairUnderTrackerLock(tracker);
            if (published && !tracker.fabrolia$hasIslandTrackingViewers()
                    && !tracker.fabrolia$hasPendingIslandPairings()) {
                // One-shot zero-view cache refresh (VMP tickAlways parity).
                detach(tracker);
            }
            return published;
        }
    }

    /** Bounded deferral snapshot; one entry per exact current tracker identity. */
    private static Set<IslandTrackedEntityAccess> snapshotPendingRealPlayerTrackers(
            IslandRegistry.PIsland island) {
        Set<IslandTrackedEntityAccess> snapshot = Collections.newSetFromMap(
                new IdentityHashMap<>());
        for (AsyncIslandPlayerTicks.PendingLate pending : island.pendingPlayerLate) {
            boolean current;
            synchronized (pending.state) {
                current = pendingRealPlayerBeforeLateCurrentLocked(pending, island);
            }
            if (!current) continue;
            IslandTrackedEntityAccess tracker = PLAYER_TRACKERS.get(
                    new PlayerIdentity(pending.player));
            if (tracker == null) continue;
            synchronized (tracker) {
                synchronized (pending.state) {
                    if (!pendingRealPlayerBeforeLateCurrentLocked(pending, island)) continue;
                    if (!currentLivePlayerTrackerLocked(tracker, pending.player)
                            || !pending.state.trackingPublication.matches(tracker, pending.owner)
                            || pending.state.trackingPublication.cadenceOwner() != island
                            || tracker.fabrolia$islandTrackingOwner() != island
                            || !island.pendingEntityUpdates.contains(tracker)) {
                        IslandEntityTracking.<RuntimeException>rethrow(
                                RegionScheduler.publishFatalAndSelectWinner(new IllegalStateException(
                                        "real-player late publication lost its admitted cadence")));
                    }
                    snapshot.add(tracker);
                }
            }
        }
        return snapshot;
    }

    /** True only for the exact queued EARLY_DONE token; caller owns its tracker. */
    private static boolean hasPendingRealPlayerBeforeLate(
            ServerPlayer player, IslandRegistry.PIsland island) {
        for (AsyncIslandPlayerTicks.PendingLate pending : island.pendingPlayerLate) {
            if (pending.player != player) continue;
            synchronized (pending.state) {
                if (pendingRealPlayerBeforeLateCurrentLocked(pending, island)) return true;
            }
        }
        return false;
    }

    /**
     * Publishes the exact real-player self tracker after listener physics, but
     * before completeRealLate closes its permit. A normal physics boundary
     * crossing uses that still-live source permit to refresh self/existing
     * viewers without committing cross-owner staged pairings, then transfers
     * the persistent tracker obligation and lets the late turn complete.
     * An explicitly marked lifecycle cut is a contained handoff; unexplained
     * missing/replaced authority remains INVALID. The already-entered player body
     * is never retried on Main. If the exact live source tracker still exists, an
     * ownerless destination receives its one post-friction state refresh before
     * that tracker is detached.
     */
    static PostPhysicsResult publishPendingRealPlayerAfterPhysics(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island) {
        if (pending == null || island == null) return PostPhysicsResult.INVALID;
        IslandTrackedEntityAccess tracker = PLAYER_TRACKERS.get(
                new PlayerIdentity(pending.player));
        if (tracker == null || tracker.fabrolia$trackedEntity() != pending.player) {
            return unavailablePostPhysicsTrackerResult(pending, island);
        }
        synchronized (tracker) {
            if (PLAYER_TRACKERS.get(new PlayerIdentity(pending.player)) != tracker
                    || tracker.fabrolia$trackedEntity() != pending.player) {
                return unavailablePostPhysicsTrackerResult(pending, island);
            }
            synchronized (pending.state) {
                boolean immutableAuthority =
                        pendingRealPlayerAfterPhysicsInvariantLocked(pending, island);
                boolean hardLifecycleCut = immutableAuthority
                        && explicitPostPhysicsLifecycleCutLocked(pending);
                boolean handoffRequested = immutableAuthority && !hardLifecycleCut
                        && pending.state.lifecycleInvalidated;
                PostPhysicsAuthority authority = classifyPostPhysicsAuthority(
                        immutableAuthority, hardLifecycleCut,
                        !hardLifecycleCut && (handoffRequested
                                ? pendingRealPlayerAfterPhysicsPublicationCurrentLocked(
                                pending, island)
                                : pendingRealPlayerAfterPhysicsCurrentLocked(pending, island)),
                        handoffRequested);
                int trackerState = tracker.fabrolia$islandTrackingState();
                IslandRegistry.PIsland trackerOwner =
                        tracker.fabrolia$islandTrackingOwner();
                if (authority == PostPhysicsAuthority.HANDOFF_WITHOUT_PUBLICATION) {
                    // Death/removal may already have retired, replaced or moved the
                    // tracker and bumped the player lifecycle epoch.  The immutable
                    // PendingLate/session proof above is the authority to contain the
                    // already-run body; mutate only an exact still-live source tracker.
                    return trackerState == IslandTrackedEntityAccess.TRACKING_ACTIVE
                            && trackerOwner == island
                            ? detachForPostPhysicsHandoffLocked(pending, tracker, true)
                            : PostPhysicsResult.HANDOFF;
                }
                if (authority == PostPhysicsAuthority.INVALID) {
                    return PostPhysicsResult.INVALID;
                }
                if (trackerState != IslandTrackedEntityAccess.TRACKING_ACTIVE
                        || trackerOwner != island) {
                    return PostPhysicsResult.INVALID;
                }
                RegionizedServer runtime = runtime(pending.player);
                if (runtime == null || !runtime.asyncActive) {
                    return PostPhysicsResult.INVALID;
                }
                if (authority == PostPhysicsAuthority.PUBLISH_HANDOFF) {
                    // A topology/owner fallback requested after this exact body
                    // entered is not a hard lifecycle exit. Publish the one
                    // post-friction state under the still-live source permit, then
                    // detach and hand off without replaying listener/player physics.
                    if (!publishCrossOwnerStateUnderTrackerLock(tracker)) {
                        return PostPhysicsResult.INVALID;
                    }
                    RegionScheduler.runSuccessBodyIfHealthy(() ->
                            detachForPostPhysicsHandoffLocked(pending, tracker, false));
                    return PostPhysicsResult.HANDOFF;
                }
                IslandRegistry.PIsland current = runtime.islandOwnerOfChunk(
                        pending.player.chunkPosition().pack());
                if (current != island) {
                    // The exact source LATE_RUNNING permit still owns this
                    // player state after physics. Refresh self and already
                    // committed viewers now, before another doTick can apply
                    // friction again. Do not expose cross-owner staged pairing
                    // mutations: those travel with the tracker obligation and
                    // commit only from a valid destination generation.  A null
                    // destination is the ordinary fresh-terrain/topology-gap case:
                    // publish once from this source permit, detach, and let the
                    // listener FIFO cross to Main without another player body.
                    if (!publishCrossOwnerStateUnderTrackerLock(tracker)) {
                        return PostPhysicsResult.INVALID;
                    }
                    if (current == null || current.retired) {
                        RegionScheduler.runSuccessBodyIfHealthy(() ->
                                detachForPostPhysicsHandoffLocked(pending, tracker, false));
                        return PostPhysicsResult.HANDOFF;
                    }
                    // Detach+attach is one ownership transfer body.  A W which
                    // wins before admission prevents it; once admitted, leaving
                    // only the detached half visible would be invalid cleanup.
                    RegionScheduler.runSuccessBodyIfHealthy(() -> {
                        attach(tracker, current);
                    });
                    return PostPhysicsResult.TRANSFERRED;
                }
                return publishAndPairUnderTrackerLock(tracker)
                        ? PostPhysicsResult.PUBLISHED
                        : PostPhysicsResult.INVALID;
            }
        }
    }

    /**
     * Completes the original real-player publication after an audited same-level
     * soft move. The old permit identifies the obligation, not its spatial writer:
     * only the typed Main-L writer may refresh the player at its live destination.
     * Pairing work stays on the exact tracker until the ordered listener handoff
     * reaches its existing Main publication tail.
     */
    static PostPhysicsResult publishPendingRealPlayerAfterSoftLifecycleOnMain(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island) {
        if (pending == null || island == null) return PostPhysicsResult.INVALID;
        boolean hardLifecycleCut;
        synchronized (pending.state) {
            if (!pendingRealPlayerAfterPhysicsInvariantLocked(pending, island)) {
                return PostPhysicsResult.INVALID;
            }
            hardLifecycleCut = explicitPostPhysicsLifecycleCutLocked(pending);
            if (!hardLifecycleCut
                    && !AsyncIslandPlayerTicks.ownsSoftLifecycleLateOnMain(pending, island)) {
                return PostPhysicsResult.INVALID;
            }
        }
        // The original listener may itself die/disconnect/change level. Its
        // existing hard-exit path owns retirement, including an absent tracker;
        // the earlier soft receipt must not turn that into live publication.
        if (hardLifecycleCut) return publishPendingRealPlayerAfterPhysics(pending, island);
        IslandTrackedEntityAccess tracker = currentPlayerTracker(pending.player);
        if (tracker == null) return PostPhysicsResult.INVALID;
        synchronized (tracker) {
            synchronized (pending.state) {
                if (!AsyncIslandPlayerTicks.ownsSoftLifecycleLateOnMain(pending, island)
                        || !currentLivePlayerTrackerLocked(tracker, pending.player)
                        || !pending.state.trackingPublication.matches(tracker, pending.owner)
                        || pending.state.trackingTopologyGap != null
                        || arrivalPending(pending.player)) {
                    return PostPhysicsResult.INVALID;
                }
                IslandRegistry.PIsland cadence = tracker.fabrolia$islandTrackingOwner();
                if (cadence == null || cadence.retired
                        || pending.state.trackingPublication.cadenceOwner() != cadence
                        || !cadence.pendingEntityUpdates.contains(tracker)) {
                    return PostPhysicsResult.INVALID;
                }
                // Do not use POST_PHYSICS_CROSSING_PUBLISHING here: the source
                // generation no longer supplies publication authority. This is
                // the ordinary native Main publisher, including its hard-clock
                // duplicate rule; a duplicate is terminal, never a replay.
                publishUnderTrackerLock(tracker);
                RegionScheduler.runSuccessBodyIfHealthy(() ->
                        detachForPostPhysicsHandoffLocked(pending, tracker, false));
                return PostPhysicsResult.HANDOFF;
            }
        }
    }

    /** Missing/replaced trackers are containable only behind an explicit lifecycle cut. */
    private static PostPhysicsResult unavailablePostPhysicsTrackerResult(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island) {
        synchronized (pending.state) {
            boolean immutableAuthority =
                    pendingRealPlayerAfterPhysicsInvariantLocked(pending, island);
            boolean hardLifecycleCut = immutableAuthority
                    && explicitPostPhysicsLifecycleCutLocked(pending);
            PostPhysicsAuthority authority = classifyPostPhysicsAuthority(
                    immutableAuthority, hardLifecycleCut, false,
                    immutableAuthority && !hardLifecycleCut
                            && pending.state.lifecycleInvalidated);
            return authority == PostPhysicsAuthority.HANDOFF_WITHOUT_PUBLICATION
                    ? PostPhysicsResult.HANDOFF : PostPhysicsResult.INVALID;
        }
    }

    /**
     * Minecraft-free ordering model: an immutable token violation always fails;
     * an explicit lifecycle cut wins before any mutable post-death observation;
     * without that cut every mutable live-authority check must pass.
     */
    static PostPhysicsAuthority classifyPostPhysicsAuthority(
            boolean immutableAuthority,
            boolean hardLifecycleCut,
            boolean mutableLiveAuthority,
            boolean handoffRequested) {
        if (!immutableAuthority) return PostPhysicsAuthority.INVALID;
        if (hardLifecycleCut) {
            return PostPhysicsAuthority.HANDOFF_WITHOUT_PUBLICATION;
        }
        if (!mutableLiveAuthority) return PostPhysicsAuthority.INVALID;
        return handoffRequested
                ? PostPhysicsAuthority.PUBLISH_HANDOFF
                : PostPhysicsAuthority.LIVE;
    }

    /**
     * Caller owns the exact source tracker and PlayerState. Publish the route
     * shield before releasing tracker cadence to the ordered Main handoff.
     */
    private static PostPhysicsResult detachForPostPhysicsHandoffLocked(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandTrackedEntityAccess tracker, boolean suppressPublication) {
        if (!Thread.holdsLock(tracker) || !Thread.holdsLock(pending.state)) {
            throw new IllegalStateException(
                    "post-physics player containment without tracker -> state lock");
        }
        AsyncIslandPlayerTicks.handoffPostPhysicsTrackingGapLocked(
                pending, tracker, suppressPublication);
        detach(tracker);
        return PostPhysicsResult.HANDOFF;
    }

    /** Exact queued EARLY_DONE proof; caller owns pending.state. */
    private static boolean pendingRealPlayerBeforeLateCurrentLocked(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island) {
        return AsyncIslandPlayerTicks.pendingRealPlayerBeforeLateInvariantLocked(
                pending, island)
                && island.pendingPlayerLate.contains(pending);
    }

    /** Exact active LATE_RUNNING proof after listener.tick; caller owns pending.state. */
    private static boolean pendingRealPlayerAfterPhysicsCurrentLocked(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island) {
        return pendingRealPlayerIdentityCurrentLocked(pending, island)
                && pending.state.session.isRunningRealLate(pending.permit);
    }

    /** Mutable live proof which deliberately tolerates only the soft handoff flag. */
    private static boolean pendingRealPlayerAfterPhysicsPublicationCurrentLocked(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island) {
        return pendingRealPlayerAfterPhysicsPublicationIdentityCurrentLocked(
                pending, island)
                && pending.state.session.isRunningRealLate(pending.permit);
    }

    /** Mutable live proof for a generic handoff or an authenticated soft mutation. */
    private static boolean pendingRealPlayerAfterPhysicsPublicationIdentityCurrentLocked(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island) {
        boolean auditedEpochAdvance = pending != null
                && pending.state.auditedLifecycleMutationPending
                && !pending.state.hardLifecycleExitPending;
        return pendingRealPlayerIdentityCurrentLocked(
                pending, island, true, auditedEpochAdvance);
    }

    /**
     * Immutable source-turn proof. Every property which lifecycle code may mutate
     * after entering listener.tick (connection/player level/epoch/type/removal) is
     * deliberately excluded. An explicit cut may contain those changes, but it can
     * never mask a replaced PendingLate token, owner stamp, listener gate or session.
     * The same exact TURN runs either on its claimed worker or under the typed
     * Main-L rendezvous; physical Main and native-service scopes are not authority.
     */
    private static boolean pendingRealPlayerAfterPhysicsInvariantLocked(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island) {
        if (pending == null || island == null || !Thread.holdsLock(pending.state)
                || pending.player == null || pending.listener == null
                || pending.state.listenerGate != pending.listener
                || pending.owner.playerIdentity() != pending.player
                || pending.owner.listenerIdentity() != pending.listener
                || pending.owner.islandIdentity() != island
                || pending.owner.islandId() != island.id
                || pending.owner.islandGeneration() != island.gen
                || pending.owner.domainIdentity() != island.domain
                || pending.owner.kind() != IslandPlayerTurnSession.PlayerKind.REAL
                || !pending.permit.owner().matchesExactly(pending.owner)
                || !pending.state.session.isRunningRealLate(pending.permit)
                || !AsyncIslandPlayerTicks.isIslandPlayerTickOf(pending.player)
                || !(IslandCommands.ownsPlayerLateOnMain(pending.player, island)
                || Thread.currentThread() instanceof RegionWorkerThread worker
                && island.unitClaim.get()
                && island.claimHolder == worker
                && worker.currentDomain == island.domain)) {
            return false;
        }
        return pending.listener instanceof AsyncIslandListenerAccess listenerAccess
                && listenerAccess.fabrolia$asyncIslandPlayerState() == pending.state;
    }

    /** Explicit in-body lifecycle cut; absence of this marker never hides corruption. */
    private static boolean explicitPostPhysicsLifecycleCutLocked(
            AsyncIslandPlayerTicks.PendingLate pending) {
        if (!Thread.holdsLock(pending.state)) {
            throw new IllegalStateException(
                    "post-physics containment decision without player-state lock");
        }
        return pending.state.hardLifecycleExitPending
                || pending.state.mainDisconnectPending
                || pending.player.getRemovalReason() != null
                || pending.player.isChangingDimension()
                || pending.player.hasDisconnected();
    }

    /** Common PendingLate player/listener/owner/lifecycle identity proof. */
    private static boolean pendingRealPlayerIdentityCurrentLocked(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island) {
        return pendingRealPlayerIdentityCurrentLocked(
                pending, island, false, false);
    }

    /**
     * Full mutable proof. Any handoff may ignore lifecycleInvalidated, while only an
     * audited live wrapper mutation may replace exact epoch equality with a strict
     * epoch advance. Generic topology requests retain exact-epoch enforcement.
     */
    private static boolean pendingRealPlayerIdentityCurrentLocked(
            AsyncIslandPlayerTicks.PendingLate pending,
            IslandRegistry.PIsland island,
            boolean allowHandoffRequest,
            boolean allowAuditedLifecycleEpochAdvance) {
        if (pending == null || island == null || island.retired
                || !Thread.holdsLock(pending.state)
                || pending.player == null || pending.listener == null
                || pending.player.connection != pending.listener
                || pending.listener.player != pending.player
                || (pending.player.isRemoved()
                && (!allowAuditedLifecycleEpochAdvance
                || pending.player.getRemovalReason() != null))
                || pending.player.isChangingDimension()
                || pending.player.hasDisconnected()
                || !pending.listener.hasClientLoaded()
                || pending.state.listenerGate != pending.listener
                || !pending.state.eligible
                || !allowHandoffRequest && pending.state.lifecycleInvalidated
                || pending.state.mainDisconnectPending
                || pending.owner.playerIdentity() != pending.player
                || pending.owner.listenerIdentity() != pending.listener
                || pending.owner.islandIdentity() != island
                || pending.owner.islandId() != island.id
                || pending.owner.islandGeneration() != island.gen
                || pending.owner.domainIdentity() != island.domain
                || pending.owner.kind()
                != IslandPlayerTurnSession.PlayerKind.REAL
                || !pending.permit.owner().matchesExactly(pending.owner)) return false;
        if (!(pending.listener instanceof AsyncIslandListenerAccess listenerAccess)
                || listenerAccess.fabrolia$asyncIslandPlayerState()
                != pending.state) return false;
        if (pending.player.level() != island.domainLevel()
                || !(pending.player instanceof PlayerTickPhaseStamp stamp)) return false;
        long currentLifecycle = stamp.fabrolia$playerLifecycleEpoch();
        if (!AsyncIslandPlayerTicks.lifecycleEpochMatchesPendingAuthority(
                currentLifecycle, pending.owner.lifecycleEpoch(),
                allowAuditedLifecycleEpochAdvance)) return false;
        return (!allowAuditedLifecycleEpochAdvance
                || pending.state.auditedLifecycleMutationPending
                && !pending.state.hardLifecycleExitPending)
                && AsyncIslandPlayerTicks.supportsIslandPlayerType(pending.player)
                && !AsyncIslandPlayerTicks.isCarpetFake(pending.player)
                && AsyncIslandPlayerTicks.shouldDeferTrackingPublication(
                pending.player, island);
    }

    private static boolean publishAndPairUnderTrackerLock(
            IslandTrackedEntityAccess tracker) {
        return publishAndPairUnderTrackerLock(tracker, null);
    }

    private static boolean publishAndPairUnderTrackerLock(
            IslandTrackedEntityAccess tracker, MainTrackingTask continuation) {
        if (continuation != null ? continuation.deferToArrivalLocked()
                : deferPendingArrival(tracker, tracker.fabrolia$trackedEntity())) return false;
        boolean published = publishUnderTrackerLock(tracker);
        if (published || tracker.fabrolia$hasPendingIslandPairings()) {
            // A false result on a live tracker is the exact hard-clock duplicate:
            // this tick's earlier successful body already refreshed ServerEntity's
            // cache. Membership accepted after that refresh still owns a terminal
            // commit here; it must not be stranded merely because sendChanges may
            // not be replayed. This performs no entity/player tick and schedules no
            // retry or successor body.
            RegionScheduler.runSuccessBodyIfHealthy(
                    tracker::fabrolia$commitIslandPairings);
        }
        // A topology diversion detached the cadence before scheduling Main. The
        // duplicate edge must restore or retire that cadence just like the caller
        // which actually published, even when no membership remained to commit.
        finishMainPublication(tracker);
        return published;
    }

    /**
     * Marks only the post-physics crossing re-entry as island-authenticated.
     * Ordinary fallback/Main publications also use PUBLISHING and must retain
     * their hard-clock dedupe/cadence behavior, so they cannot share this scope.
     */
    private static boolean publishCrossOwnerStateUnderTrackerLock(
            IslandTrackedEntityAccess tracker) {
        if (!Thread.holdsLock(tracker)) {
            throw new IllegalStateException(
                    "cross-owner player publication without tracker lock");
        }
        if (POST_PHYSICS_CROSSING_PUBLISHING.get() != null) {
            throw new IllegalStateException("nested post-physics crossing publication");
        }
        POST_PHYSICS_CROSSING_PUBLISHING.set(tracker);
        Throwable failure = null;
        boolean published = false;
        try {
            published = publishUnderTrackerLock(tracker);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            POST_PHYSICS_CROSSING_PUBLISHING.remove();
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    mergeFailure(failure, cleanup));
        }
        if (failure != null) IslandEntityTracking.<RuntimeException>rethrow(failure);
        return published;
    }

    /** Caller owns the exact tracker monitor (and PlayerState for players). */
    private static boolean publishUnderTrackerLock(IslandTrackedEntityAccess tracker) {
        if (!Thread.holdsLock(tracker)) {
            throw new IllegalStateException("island entity publication without tracker lock");
        }
        IslandTrackedEntityAccess prior = PUBLISHING.get();
        if (prior != null) throw new IllegalStateException("nested island entity publication");
        PUBLISHING.set(tracker);
        Throwable failure = null;
        boolean published = false;
        try {
            published = RegionScheduler.callSuccessBooleanIfHealthy(
                    tracker::fabrolia$sendIslandChanges);
        } catch (Throwable thrown) {
            // sendIslandChanges is the entered transformed vanilla body. Select W
            // before removing the publication identity or releasing tracker ownership.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        try {
            PUBLISHING.remove();
        } catch (Throwable cleanup) {
            failure = RegionScheduler.publishFatalAndSelectWinner(
                    mergeFailure(failure, cleanup));
        }
        if (failure != null) IslandEntityTracking.<RuntimeException>rethrow(failure);
        return published;
    }

    private static RegionizedServer runtime(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) return null;
        return RegionScheduler.forLevel(level);
    }

    private static IslandRegistry.PIsland currentOwner(
            RegionizedServer runtime, Entity entity) {
        return runtime == null || entity == null ? null
                : runtime.islandOwnerOfChunk(entity.chunkPosition().pack());
    }

    private static boolean eligibleNonPlayer(Entity entity,
                                             IslandRegistry.PIsland island) {
        if (!islandCompatibleNonPlayer(entity, island)) return false;
        Entity root = entity.getRootVehicle();
        return !portalDeparturePending(root)
                && !portalDeparturePending(entity)
                && entity.level() instanceof ServerLevel level
                && level.getChunkSource().chunkMap.getDistanceManager()
                        .inEntityTickingRange(entity.chunkPosition().pack());
    }

    /** Stable class/root envelope, excluding only temporary portal/DM cuts. */
    private static boolean islandCompatibleNonPlayer(
            Entity entity, IslandRegistry.PIsland island) {
        if (island == null || island.retired || entity == null || entity.isRemoved()
                || mainOwnedEntityType(entity)) return false;
        Entity root = entity.getRootVehicle();
        return !ParallelProcessor.containsServerPlayerTick(root)
                && !ParallelProcessor.requiresMainThread(root)
                && !ParallelProcessor.requiresMainThread(entity);
    }

    private static boolean portalDeparturePending(Entity entity) {
        return entity instanceof ParallelProcessor.TickGuard guard
                && guard.fabrolia$isPortalPending();
    }

    private static boolean arrivalPending(Entity entity) {
        return entity instanceof ParallelProcessor.TickGuard guard
                && guard.fabrolia$tickLease().isArrivalPending();
    }

    /** Preserve the producer obligation, never unrelated recipient packets. */
    private static boolean deferPendingArrival(
            IslandTrackedEntityAccess tracker, Entity entity) {
        if (!arrivalPending(entity)) return false;
        scheduleMainPublication(tracker);
        return true;
    }

    private static boolean physicalMain(Entity entity) {
        return entity != null && entity.level() instanceof ServerLevel level
                && RegionalPlayerEntityTicksV2
                        .isPhysicalServerThread(level.getServer());
    }

    /** Active async worlds permit Main publication only inside the owner claim. */
    private static Route mainRouteOrSchedule(IslandTrackedEntityAccess tracker,
                                             Entity entity,
                                             RegionizedServer runtime) {
        if (runtime == null || !runtime.asyncActive) return Route.MAIN;
        if (physicalMain(entity)) {
            IslandRegistry.PIsland owner = runtime.islandOwnerOfChunk(
                    entity.chunkPosition().pack());
            if (owner == null || runtime.currentThreadHoldsAllClaims()
                    || IslandCommands.mainHolds(owner)) return Route.MAIN;
        }
        scheduleMainPublication(tracker);
        return Route.DROP;
    }

    private static boolean mainOwnedEntityType(Entity entity) {
        // ItemFrame sendChanges mutates shared MapItemSavedData. WitherBoss is
        // the sole exact-26.2 startSeenByPlayer override with a Main-owned
        // BossEvent membership callback. Dragon parts use a shared level index.
        return entity instanceof ItemFrame || entity instanceof WitherBoss
                || entity instanceof EnderDragon;
    }

    /** Main-only simulation/compatibility envelopes need a bounded Main clock. */
    private static boolean requiresPersistentMainPublisher(Entity entity) {
        if (entity == null) return false;
        if (mainOwnedEntityType(entity)) return true;
        if (entity instanceof ServerPlayer player) {
            if (player.isRemoved() || player.hasDisconnected()) return false;
            if (AsyncIslandPlayerTicks.supportsIslandPlayerType(player)
                    && AsyncIslandPlayerTicks.hasAttachedOwner(player)) return false;
            if (ParallelProcessor.isForcedMainThread(entity)
                    || !AsyncIslandPlayerTicks.supportsIslandPlayerType(player)) {
                return true;
            }
            RegionizedServer runtime = runtime(entity);
            IslandRegistry.PIsland owner = currentOwner(runtime, entity);
            return runtime != null && runtime.asyncActive
                    && !AsyncIslandPlayerTicks.shouldDeferTrackingPublication(
                    player, owner);
        }
        Entity root = entity.getRootVehicle();
        return ParallelProcessor.requiresMainThread(root)
                || ParallelProcessor.requiresMainThread(entity);
    }

    private static boolean persistentMainClockEligible(
            Entity entity, RegionizedServer runtime) {
        return requiresPersistentMainPublisher(entity)
                && runtime != null && runtime.asyncActive
                && entity != null && !entity.isRemoved()
                && entity.level() instanceof ServerLevel level
                && level.getChunkSource().chunkMap.getDistanceManager()
                .inEntityTickingRange(entity.chunkPosition().pack());
    }

    /** Called under the exact tracker monitor immediately before original sendChanges. */
    public static boolean beginMainPublication(Object synchronizer, Entity entity) {
        if (!(synchronizer instanceof IslandTrackedEntityAccess tracker)) return true;
        // publishUnderTrackerLock is already authenticated by the exact island
        // turn and tracker monitor. A post-physics boundary crossing makes the
        // player's current spatial owner differ from that source permit, which
        // otherwise misclassifies this recursive wrapper entry as a hard-Main
        // clock and subjects it to lastMainTrackingServerTick dedupe.
        if (POST_PHYSICS_CROSSING_PUBLISHING.get() == tracker) return true;
        RegionizedServer runtime = runtime(entity);
        boolean softMainLate = entity instanceof ServerPlayer player
                && AsyncIslandPlayerTicks.ownsSoftLifecycleLateOnMain(player);
        if (runtime == null || !runtime.asyncActive
                || !softMainLate && !requiresPersistentMainPublisher(entity)) return true;
        return entity.level() instanceof ServerLevel level
                && tracker.fabrolia$tryBeginMainTrackingPublication(
                level.getServer().getTickCount());
    }

    /**
     * A duplicate hard-clock caller has no body left to perform.  The accepted
     * publication task or attached island cadence is already the continuation;
     * replaying this caller at tick+1 would invent a second vanilla invocation.
     */
    public static void deferDuplicateMainPublication(Object synchronizer) {
        // Deliberately empty.  This hook documents the terminal duplicate edge
        // in ServerEntityIslandTrackingMixin without creating recovery work.
    }

    /** Called after a physical-Main sendChanges refresh under tracker lock. */
    public static void commitPendingPairingsAfterMain(Object synchronizer) {
        if (!(synchronizer instanceof IslandTrackedEntityAccess tracker)
                || !tracker.fabrolia$hasPendingIslandPairings()) return;
        // Island/fallback refreshes commit explicitly after clearing their
        // publication marker. Every other call reaching here just completed a
        // successful physical-Main sendChanges; that route decision itself is
        // the authority to finish all staged updatePlayer calls now.
        if (PUBLISHING.get() == tracker || tracker.fabrolia$islandTrackingState()
                == IslandTrackedEntityAccess.TRACKING_RETIRED) return;
        RegionScheduler.runSuccessBodyIfHealthy(
                tracker::fabrolia$commitIslandPairings);
    }

    /** Maintains/stops the bounded cadence token after a successful Main send. */
    public static void finishMainPublication(Object synchronizer) {
        if (!(synchronizer instanceof IslandTrackedEntityAccess tracker)) return;
        // The island caller owns its cadence/explicit crossing reroute. Do not
        // let the recursive ServerEntity wrapper detach or arm a persistent
        // Main clock from the player's post-physics spatial owner.
        if (POST_PHYSICS_CROSSING_PUBLISHING.get() == tracker) return;
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> finishMainPublicationEntered(tracker));
    }

    /** Already admitted success-only state transition; never called after peer W. */
    private static void finishMainPublicationEntered(
            IslandTrackedEntityAccess tracker) {
        synchronized (tracker) {
            if (tracker.fabrolia$islandTrackingState()
                    != IslandTrackedEntityAccess.TRACKING_ACTIVE) return;
            Entity entity = tracker.fabrolia$trackedEntity();
            if (!requiresPersistentMainPublisher(entity)) return;
            if (!tracker.fabrolia$hasIslandTrackingViewers()
                    && !tracker.fabrolia$hasPendingIslandPairings()) {
                detach(tracker);
                return;
            }
            RegionizedServer runtime = runtime(entity);
            IslandRegistry.PIsland owner = currentOwner(runtime, entity);
            if (runtime != null && runtime.asyncActive
                    && owner != null && !owner.retired
                    && (tracker.fabrolia$hasPendingIslandPairings()
                    || persistentMainClockEligible(entity, runtime))) {
                attach(tracker, owner);
            } else if (!tracker.fabrolia$hasPendingIslandPairings()) {
                detach(tracker);
            }
        }
    }

    /** Topology OFF/gap transition is quiescent and may finish on Main now. */
    private static void fallbackPendingPairingsOnMain(
            IslandTrackedEntityAccess tracker) {
        synchronized (tracker) {
            if (!tracker.fabrolia$hasPendingIslandPairings()
                    || tracker.fabrolia$islandTrackingState()
                    == IslandTrackedEntityAccess.TRACKING_RETIRED) return;
            if (handoffMainPublicationToActiveOwnerLocked(
                    tracker, tracker.fabrolia$trackedEntity())) {
                // The exact attached owner tail is the only accepted
                // continuation.  Main neither publishes nor replays this call.
                return;
            }
            // Includes the terminal hard-clock edge: commit any membership which
            // arrived after this tick's earlier successful cache refresh, without
            // replaying sendChanges, then reconcile the detached cadence.
            publishAndPairUnderTrackerLock(tracker);
        }
    }

    /**
     * Exact physical-Main successor of an A5 topology-gap close.  The receipt is
     * retained until this tracker monitor is acquired, so every intervening
     * send/pairing caller either upgrades the one-shot obligation or observes its
     * terminal disconnect cleanup.  No player entity body is executed here.
     */
    static void finishTopologyGapPublicationOnMain(
            AsyncIslandPlayerTicks.TrackingTopologyGap receipt) {
        if (receipt == null) {
            throw new IllegalStateException("topology-gap publication lost its receipt");
        }
        ServerPlayer player = receipt.player();
        IslandTrackedEntityAccess tracker = receipt.tracker();
        if (!physicalMain(player)) {
            throw new IllegalStateException(
                    "topology-gap publication entered off physical Main");
        }
        RegionScheduler.runWithMainEntityClaim(player, () -> {
            synchronized (tracker) {
                if (AsyncIslandPlayerTicks.suppressTrackingTopologyGapIfRequired(
                        receipt)) return;
                Boolean publicationOwed =
                        AsyncIslandPlayerTicks.trackingTopologyGapPublicationIfReady(
                                receipt);
                if (publicationOwed == null) return;
                if (!currentLivePlayerTrackerLocked(tracker, player)
                        || tracker.fabrolia$islandTrackingOwner() != null) {
                    throw new IllegalStateException(
                            "topology-gap publication lost its detached live tracker");
                }
                boolean published = publicationOwed
                        && publishUnderTrackerLock(tracker);
                if (publicationOwed && (published
                        || tracker.fabrolia$hasPendingIslandPairings())) {
                    // A false body result is the exact hard-clock duplicate: the
                    // earlier successful call already refreshed ServerEntity's
                    // cache. Pending membership accepted after that refresh may
                    // therefore commit here without replaying sendChanges.
                    RegionScheduler.runSuccessBodyIfHealthy(
                            tracker::fabrolia$commitIslandPairings);
                }
                AsyncIslandPlayerTicks.completeTrackingTopologyGap(
                        receipt, publicationOwed);
            }
        });
    }

    /** Schedules a physical-Main publication required by ownership/topology. */
    private static void scheduleMainPublication(
            IslandTrackedEntityAccess tracker) {
        if (tracker == null) return;
        // Close admission before taking the tracker token.  A concurrent fatal
        // after this read is caught again immediately before the unique body.
        RegionScheduler.throwWorkerFailureIfAny();
        long scheduleToken = tracker.fabrolia$tryScheduleMainTracking();
        if (scheduleToken == 0L) return;
        MainPublicationReceipt receipt = null;
        MainTrackingTask task = null;
        boolean tokenReleased = false;
        try {
            // A caller may have passed the first W read and then waited for the
            // tracker token.  Recheck after acquiring this exact generation.
            RegionScheduler.throwWorkerFailureIfAny();
            Entity entity = tracker.fabrolia$trackedEntity();
            if (entity == null || !(entity.level() instanceof ServerLevel level)) {
                tracker.fabrolia$finishMainTrackingSchedule(scheduleToken);
                tokenReleased = true;
                return;
            }
            receipt = new MainPublicationReceipt();
            // MinecraftServer.execute() may inline on physical Main. Route and
            // membership staging are called with the exact tracker monitor
            // held; an inline claim would create tracker -> island while the
            // island tail uses island -> tracker. Typed control publication is an
            // unconditional enqueue and therefore releases the monitor first.
            task = new MainTrackingTask(
                    tracker, entity, level, scheduleToken, receipt);
            if (!NetherPortalPlayerAccess.deferArrivalPublication(entity, task)) {
                task.publish();
            }
            MainPublicationReceipt commitReceipt = receipt;
            RegionScheduler.commitSuccessIfHealthy(commitReceipt::accept);
        } catch (Throwable schedulingFailure) {
            // A scheduler may enqueue and then throw.  Publish the failure while
            // the token is still closed, so that queued task can perform only
            // its mandatory token cleanup and can never enter the body.
            Throwable primary = RegionScheduler.publishFatalAndSelectWinner(
                    schedulingFailure);
            if (receipt != null) receipt.cancel();
            if (!tokenReleased) {
                try {
                    synchronized (tracker) {
                        if (task != null) task.releaseScheduleTokenLocked();
                        else tracker.fabrolia$finishMainTrackingSchedule(scheduleToken);
                        tokenReleased = true;
                    }
                } catch (Throwable cleanupFailure) {
                    primary = mergeFailure(primary, cleanupFailure);
                }
            }
            IslandEntityTracking.<RuntimeException>rethrow(primary);
        }
    }

    /** Exact receipt for the scheduler's offer-then-throw ambiguity window. */
    private static final class MainPublicationReceipt {
        private static final int OFFERING = 0;
        private static final int ACCEPTED = 1;
        private static final int CANCELLED = 2;

        private final AtomicInteger state = new AtomicInteger(OFFERING);

        private boolean accept() {
            return state.compareAndSet(OFFERING, ACCEPTED);
        }

        private void cancel() {
            state.set(CANCELLED);
        }

        private boolean awaitAccepted() {
            int spins = 0;
            int observed;
            while ((observed = state.get()) == OFFERING) {
                if (spins++ < 64) Thread.onSpinWait();
                else LockSupport.parkNanos(this, 1_000L);
            }
            return observed == ACCEPTED;
        }
    }

    /** One exact continuation, held by either the scheduler or arrival settlement. */
    private static final class MainTrackingTask
            implements Runnable, NetherPortalPlayerAccess.ArrivalPublication {
        private final IslandTrackedEntityAccess tracker;
        private final Entity entity;
        private final ServerLevel scheduledLevel;
        private final long scheduleToken;
        private final MainPublicationReceipt publication;
        private boolean arrivalHeldUnderTracker;
        private boolean scheduleReleasedUnderTracker;

        private MainTrackingTask(IslandTrackedEntityAccess tracker, Entity entity,
                                 ServerLevel scheduledLevel, long scheduleToken,
                                 MainPublicationReceipt publication) {
            this.tracker = tracker;
            this.entity = entity;
            this.scheduledLevel = scheduledLevel;
            this.scheduleToken = scheduleToken;
            this.publication = publication;
        }

        @Override
        public void publish() {
            try {
                // Never execute inline: settlement can still be unwinding the
                // caller's tracker scope, and entity claims are acquired by run.
                RegionScheduler.scheduleMainControlTask(scheduledLevel.getServer(), this);
            } catch (Throwable thrown) {
                Throwable failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
                try {
                    cancel();
                } catch (Throwable cleanup) {
                    failure = mergeFailure(failure, cleanup);
                }
                IslandEntityTracking.<RuntimeException>rethrow(failure);
            }
        }

        @Override
        public void cancel() {
            publication.cancel();
            synchronized (tracker) {
                releaseScheduleTokenLocked();
            }
        }

        @Override
        public void run() {
            Throwable failure = null;
            try {
                if (!publication.awaitAccepted()) {
                    // Cancellation may be a normal arrival retirement. A
                    // scheduler failure has already selected W before cancel.
                    RegionScheduler.throwWorkerFailureIfAny();
                    synchronized (tracker) {
                        releaseScheduleTokenLocked();
                    }
                    return;
                }
                // BlockableEventLoop may otherwise log an ordinary task failure
                // and continue. First close the already-retired/already-owned
                // outcomes without waiting for an island Main claim. The same
                // predicate is revalidated after a claim when this preflight
                // observes detached Main work and ownership changes concurrently.
                RegionScheduler.throwWorkerFailureIfAny();
                boolean completedWithoutClaim =
                        RegionScheduler.callSuccessBooleanIfHealthy(
                                this::preflightWithoutMainClaim);
                if (!completedWithoutClaim) {
                    RegionScheduler.runWithMainEntityClaim(
                            entity, this::runClaimed, this::preflightWithoutMainClaim);
                }
            } catch (Throwable thrown) {
                // The task token remains closed on every exceptional exit from
                // runClaimed. Publish first, then perform cleanup only.
                failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            }

            if (!scheduleReleasedUnderTracker
                    && (failure != null || !arrivalHeldUnderTracker)) {
                try {
                    synchronized (tracker) {
                        releaseScheduleTokenLocked();
                    }
                } catch (Throwable cleanupFailure) {
                    if (failure == null) {
                        failure = RegionScheduler.publishFatalAndSelectWinner(
                                cleanupFailure);
                    } else {
                        failure = mergeFailure(failure, cleanupFailure);
                    }
                }
            }
            if (failure != null) {
                IslandEntityTracking.<RuntimeException>rethrow(failure);
            }
        }

        /**
         * Tracker-locked fast terminal before the potentially blocking island
         * claim. Returning false is only a hint: {@link #runClaimed()} repeats
         * the exact predicate under the authoritative post-claim validation.
         */
        private boolean preflightWithoutMainClaim() {
            synchronized (tracker) {
                arrivalHeldUnderTracker = false;
                if (scheduleReleasedUnderTracker) return true;
                if (entity.level() != scheduledLevel) {
                    releaseScheduleTokenLocked();
                    return true;
                }
                RegionizedServer runtime = runtime(entity);
                if (runtime != null) runtime.enterTrackingTopologyRouting();
                try {
                    return finishRetiredOrActiveOwnerLocked();
                } finally {
                    if (runtime != null) runtime.exitTrackingTopologyRouting();
                }
            }
        }

        private void runClaimed() {
            synchronized (tracker) {
                // Authoritative revalidation: tracker state/owner may change in
                // the interval between the preflight and exact island claim.
                if (finishRetiredOrActiveOwnerLocked()) {
                    return;
                }

                // Exactly one attempt belongs to this accepted continuation.
                // A hard-clock duplicate is terminal too: another caller already
                // performed this tick's vanilla body, so no replay is invented.
                publishAndPairUnderTrackerLock(tracker, this);
                if (!arrivalHeldUnderTracker) releaseScheduleTokenLocked();
            }
        }

        /** Caller holds the exact tracker monitor. */
        private boolean finishRetiredOrActiveOwnerLocked() {
            if (!Thread.holdsLock(tracker)) {
                throw new IllegalStateException(
                        "Main tracking preflight without tracker lock");
            }
            if (scheduleReleasedUnderTracker) return true;
            if (entity.level() != scheduledLevel || entity.isRemoved()
                    || tracker.fabrolia$islandTrackingState()
                    == IslandTrackedEntityAccess.TRACKING_RETIRED) {
                releaseScheduleTokenLocked();
                return true;
            }
            if (deferToArrivalLocked()) return true;
            if (!handoffMainPublicationToActiveOwnerLocked(tracker, entity)) return false;
            releaseScheduleTokenLocked();
            return true;
        }

        /** Transfer this accepted envelope; its exact token remains closed. */
        private boolean deferToArrivalLocked() {
            if (!NetherPortalPlayerAccess.deferArrivalPublication(entity, this)) return false;
            arrivalHeldUnderTracker = true;
            return true;
        }

        private void releaseScheduleTokenLocked() {
            if (!Thread.holdsLock(tracker)) {
                throw new IllegalStateException(
                        "Main tracking schedule release without tracker lock");
            }
            if (scheduleReleasedUnderTracker) return;
            tracker.fabrolia$finishMainTrackingSchedule(scheduleToken);
            scheduleReleasedUnderTracker = true;
        }
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // The server is already failing with primary.  Suppression is
                // diagnostic only and may itself allocate under low memory.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * Transfers a queued Main obligation to the exact, already attached A5 island
     * tail. Normally the A5 session owner and tracker owner are identical. A
     * quiescent topology rebuild may already have carried the tracker cadence to
     * the exact fresh spatial owner while the session still names its predecessor;
     * the next A5 admission collapses that bridge. Both forms are validation-only:
     * a detached/gapped owner is corruption, never a repair/retry opportunity.
     * MAIN_FALLBACK returns false and stays physical-Main work.
     */
    private static boolean handoffMainPublicationToActiveOwnerLocked(
            IslandTrackedEntityAccess tracker, Entity entity) {
        if (!Thread.holdsLock(tracker)) {
            throw new IllegalStateException(
                    "active-owner Main publication handoff without tracker lock");
        }
        if (!(entity instanceof ServerPlayer player)
                || !AsyncIslandPlayerTicks.supportsIslandPlayerType(player)) return false;
        AsyncIslandPlayerTicks.PlayerState state = AsyncIslandPlayerTicks.state(player);
        synchronized (state) {
            if (AsyncIslandPlayerTicks.ownsTrackingTopologyGap(player, tracker)) return true;
            IslandPlayerTurnSession.View view = state.session.view();
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            if (owner == null || owner.playerIdentity() != player
                    || owner.listenerIdentity() != player.connection
                    || owner.kind() != AsyncIslandPlayerTicks.classifyPlayer(player)
                    || !AsyncIslandPlayerTicks.isIslandListenerOwnedPhase(view.phase())
                    || state.mainDisconnectPending) return false;
            // Orderly retirement retains only an admission receipt, not a live
            // self-publication binding. The successor follows ordinary admission.
            if (state.trackingPublication.retiredGeneration(owner)
                    && currentLivePlayerTrackerLocked(tracker, player)) return false;
            RegionizedServer runtime = runtime(entity);
            IslandRegistry.PIsland cadence = tracker.fabrolia$islandTrackingOwner();
            RegionizedServer.TrackingTopologyTransitionReceipt transition =
                    runtime == null ? null : runtime.trackingTopologyTransitionReceipt();
            boolean exactGeneration = currentLivePlayerTrackerLocked(tracker, player)
                    && state.trackingPublication.matches(tracker, owner);
            boolean venueLive = runtime != null && (runtime.asyncActive || transition != null);
            try {
                if (!exactGeneration || !venueLive) {
                    throw new IllegalStateException(
                            "active A5 owner has no live tracker publication handoff");
                }
                return state.trackingPublication.publicationCadence(cadence, cadence != null && !cadence.retired, cadence != null && cadence.pendingEntityUpdates.contains(tracker), transition == null ? null : transition.cadences) != null;
            } catch (Throwable failure) {
                IslandEntityTracking.<RuntimeException>rethrow(
                        RegionScheduler.publishFatalAndSelectWinner(failure));
                return false;
            }
        }
    }

    public static void retire(IslandTrackedEntityAccess tracker) {
        if (tracker == null) return;
        synchronized (tracker) {
            synchronized (ARRIVAL_PAIRINGS) {
                ARRIVAL_PAIRINGS.remove(tracker);
            }
            unregisterPlayerTracker(tracker);
            detach(tracker);
        }
    }

    private static void attach(IslandTrackedEntityAccess tracker,
                               IslandRegistry.PIsland island) {
        if (tracker == null || island == null || island.retired) return;
        synchronized (tracker) {
            if (tracker.fabrolia$islandTrackingState()
                    != IslandTrackedEntityAccess.TRACKING_ACTIVE) return;
            if (tracker.fabrolia$trackedEntity() instanceof ServerPlayer player
                    && AsyncIslandPlayerTicks.supportsIslandPlayerType(player)) {
                AsyncIslandPlayerTicks.PlayerState state = AsyncIslandPlayerTicks.state(player);
                synchronized (state) {
                    IslandPlayerTurnSession.OwnerStamp owner = state.session.view().owner();
                    if (state.trackingPublication.matches(tracker, owner)) {
                        IslandRegistry.PIsland prior = tracker.fabrolia$islandTrackingOwner();
                        if (!currentLivePlayerTrackerLocked(tracker, player)
                                || prior == null || state.trackingPublication.cadenceOwner() != prior
                                || !prior.pendingEntityUpdates.contains(tracker)) {
                            IslandEntityTracking.<RuntimeException>rethrow(
                                    RegionScheduler.publishFatalAndSelectWinner(new IllegalStateException(
                                            "self-tracker carry lost its authorized cadence")));
                        }
                        attachCadence(tracker, island);
                        state.trackingPublication.carry(tracker, owner, prior, island);
                        return;
                    }
                }
            }
            attachCadence(tracker, island);
        }
    }

    /** Caller owns the tracker; player admission/carry publishes its binding in the same lock. */
    private static void attachCadence(IslandTrackedEntityAccess tracker,
                                      IslandRegistry.PIsland island) {
        if (!Thread.holdsLock(tracker)) {
            throw new IllegalStateException("tracker cadence mutation without tracker lock");
        }
        try {
            IslandRegistry.PIsland prior = tracker.fabrolia$islandTrackingOwner();
            if (prior != island) {
                if (prior != null) prior.pendingEntityUpdates.remove(tracker);
                tracker.fabrolia$setIslandTrackingOwner(island);
            }
            island.pendingEntityUpdates.add(tracker);
        } catch (Throwable failure) {
            IslandEntityTracking.<RuntimeException>rethrow(
                    RegionScheduler.publishFatalAndSelectWinner(failure));
        }
    }

    private static void detach(IslandTrackedEntityAccess tracker) {
        synchronized (tracker) {
            IslandRegistry.PIsland prior = tracker.fabrolia$islandTrackingOwner();
            if (prior != null) {
                prior.pendingEntityUpdates.remove(tracker);
                tracker.fabrolia$setIslandTrackingOwner(null);
            }
        }
    }
}
