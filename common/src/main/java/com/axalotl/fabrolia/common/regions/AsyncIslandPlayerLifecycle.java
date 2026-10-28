package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * Lifecycle exclusion for A5-owned players.
 *
 * <p>An autonomous island owns the whole early/BE/late turn with {@link IslandRegistry.PIsland#unitClaim},
 * so a physical-main lifecycle writer must quiesce that claim before it replaces,
 * removes, teleports or changes the ride topology of a player.  Mutations reached
 * from the player's own island turn are reentrant and must never try to claim the
 * island which the current worker already owns.
 *
 * <p>Main-side player lifecycle changes are rare (login/logout, respawn and admin
 * transitions).  Taking the existing global island fence here is intentionally
 * conservative: it also covers a topology rebuild which changes the player's
 * current island between the initial ride-component snapshot and the mutation.
 */
final class AsyncIslandPlayerLifecycle {

    private AsyncIslandPlayerLifecycle() {}

    static Scope begin(RegionalPlayerEntityTicksV2.LifecycleMutationKind kind,
                       Entity... roots) {
        if (kind == null) {
            throw new IllegalArgumentException("A5 lifecycle mutation kind is required");
        }
        boolean reservedRoot = false;
        if (roots != null) {
            for (Entity root : roots) {
                reservedRoot |= NetherPortalPlayerAccess.needsMetadataSettlement(root);
            }
        }
        if (!AsyncIslandPlayerTicks.enabledForAsync() && !reservedRoot) return Scope.NOOP;

        boolean tookFence = false;
        ArrayList<AsyncIslandPlayerTicks.LifecycleBodyToken> bodyTokens = null;
        int markedBodyCount = 0;
        Throwable failure = null;
        try {
            MinecraftServer currentServer = com.axalotl.fabrolia.common.ParallelProcessor.getServer();
            boolean physicalMain = RegionalPlayerEntityTicksV2.isPhysicalServerThread(currentServer);
            // A captured Main lifecycle request samples ride topology only after the
            // in-flight transfer and its ordered metadata have finished.
            if (reservedRoot && physicalMain && NetherPortalTransfers.currentTransfer() == null) {
                tookFence = IslandCommands.quiesceAllExcept(null);
                if (!tookFence && !IslandCommands.fenceWindowHeld()) {
                    throw new IllegalStateException("Nether lifecycle request lacks its Main fence");
                }
                NetherPortalPlayerAccess.drainMetadata();
            }
            IdentityHashMap<Entity, Boolean> explicitRoots = explicitRoots(roots);
            IdentityHashMap<Entity, Boolean> before = collect(roots);
            ArrayList<ServerPlayer> players = ownedPlayers(before);
            if (players.isEmpty() && !reservedRoot) return Scope.NOOP;
            MinecraftServer server = commonServer(players);
            if (server != null && server != currentServer) {
                throw new IllegalStateException("A5 lifecycle request crossed its server generation");
            }
            boolean ownerReentrant = true;
            for (ServerPlayer player : players) {
                if (!AsyncIslandPlayerTicks.ownsLifecycleMutation(player)) {
                    ownerReentrant = false;
                    break;
                }
            }
            if (!ownerReentrant && !physicalMain) {
                throw new IllegalStateException(
                        "A5 player lifecycle mutation entered from neither physical Main nor the exact island owner");
            }
            if (!ownerReentrant && !tookFence) {
                tookFence = IslandCommands.quiesceAllExcept(null);
                if (!tookFence && !IslandCommands.fenceWindowHeld()) {
                    throw new IllegalStateException("A5 player lifecycle mutation could not establish its Main fence");
                }
                IdentityHashMap<Entity, Boolean> after = collect(roots);
                if (!sameIdentitySet(before, after)) {
                    throw new IllegalStateException("player ride topology changed before the A5 lifecycle fence");
                }
                players = ownedPlayers(after);
            }
            if (roots != null) {
                for (Entity root : roots) NetherPortalTransfers.cancelForLifecycle(root);
            }

            // Arm every exact body marker before the first handoff continuation
            // can become visible. This also covers nested wrappers and captured
            // retired sources which are absent from the physical-Main fence.
            // Resolve and allocate every exact PlayerState token before changing a
            // depth. Once arming starts, only the primitive prefix count changes:
            // even OOME in Scope construction can retire every token it armed, and
            // replacement/respawn cannot redirect cleanup to another PlayerState.
            bodyTokens = new ArrayList<>(players.size());
            for (ServerPlayer player : players) {
                bodyTokens.add(AsyncIslandPlayerTicks.prepareLifecycleMutationBody(player));
            }
            for (AsyncIslandPlayerTicks.LifecycleBodyToken token : bodyTokens) {
                AsyncIslandPlayerTicks.beginLifecycleMutationBody(token);
                markedBodyCount++;
            }
            for (ServerPlayer player : players) {
                RegionalPlayerEntityTicksV2.LifecycleMutationKind playerKind =
                        effectivePlayerMutationKind(
                                kind, explicitRoots.containsKey(player));
                // Publish the ingress handoff gate first. Netty must remain on the
                // old exact FIFO until Main atomically closes that handoff; only then
                // may later packets take vanilla's normal Main scheduling path.
                AsyncIslandPlayerTicks.invalidateForLifecycleMutation(player, playerKind);
                ((PlayerTickPhaseStamp) player).fabrolia$bumpPlayerLifecycleEpoch();
            }
            return new Scope(tookFence, bodyTokens, markedBodyCount);
        } catch (Throwable thrown) {
            // No lifecycle cleanup may run while a newly divergent generation
            // still admits workers. Select the shared winner before fence/token
            // rollback, then rethrow that same object below.
            failure = AsyncIslandPlayerTicks.publishFatalFailure(thrown);
        }

        if (tookFence) {
            try {
                IslandCommands.releaseAllExcept();
            } catch (Throwable cleanup) {
                failure = AsyncIslandPlayerTicks.publishFatalFailure(
                        FailStops.mergeFailure(failure, cleanup));
            }
        }
        failure = finishMarkedBodies(bodyTokens, markedBodyCount, failure);
        FailStops.rethrow(failure);
        return Scope.NOOP;
    }

    private static MinecraftServer commonServer(ArrayList<ServerPlayer> players) {
        MinecraftServer server = null;
        for (ServerPlayer player : players) {
            MinecraftServer candidate = player.level().getServer();
            if (candidate == null) continue;
            if (server != null && server != candidate) {
                throw new IllegalStateException(
                        "one player lifecycle mutation spans different MinecraftServer instances");
            }
            server = candidate;
        }
        return server;
    }

    private static IdentityHashMap<Entity, Boolean> collect(Entity... roots) {
        IdentityHashMap<Entity, Boolean> found = new IdentityHashMap<>();
        if (roots == null) return found;
        for (Entity root : roots) collectRideComponent(root, found);
        return found;
    }

    private static IdentityHashMap<Entity, Boolean> explicitRoots(Entity... roots) {
        IdentityHashMap<Entity, Boolean> found = new IdentityHashMap<>();
        if (roots == null) return found;
        for (Entity root : roots) {
            if (root != null) found.put(root, Boolean.TRUE);
        }
        return found;
    }

    /** A terminal/death reason authorizes only explicitly named player identities. */
    static RegionalPlayerEntityTicksV2.LifecycleMutationKind effectivePlayerMutationKind(
            RegionalPlayerEntityTicksV2.LifecycleMutationKind requested,
            boolean explicitPlayerRoot) {
        return explicitPlayerRoot
                ? requested
                : RegionalPlayerEntityTicksV2.LifecycleMutationKind.SOFT_HANDOFF;
    }

    private static void collectRideComponent(Entity entity,
                                             IdentityHashMap<Entity, Boolean> found) {
        if (entity == null || found.put(entity, Boolean.TRUE) != null) return;
        collectRideComponent(entity.getVehicle(), found);
        for (Entity passenger : entity.getPassengers()) {
            collectRideComponent(passenger, found);
        }
    }

    /**
     * Ordinary MAIN players have no island body or FIFO left to exclude. Published
     * camera followers are different: their ride publication must remain coherent
     * against Nether admission even while their session is held on Main.
     */
    private static ArrayList<ServerPlayer> ownedPlayers(
            IdentityHashMap<Entity, Boolean> component) {
        ArrayList<ServerPlayer> result = new ArrayList<>();
        for (Entity entity : component.keySet()) {
            if (!(entity instanceof ServerPlayer player)) {
                continue;
            }
            if (participatesInLifecycle(player)) {
                result.add(player);
            }
        }
        return result;
    }

    static boolean participatesInLifecycle(ServerPlayer player) {
        return AsyncIslandPlayerTicks.hasAttachedOwner(player)
                || NetherPortalPlayerAccess.owns(player)
                || NetherPortalPlayerAccess.hasPublishedCamera(player)
                || NetherPortalPlayerAccess.requestForLifecycle(player) != null;
    }

    private static boolean sameIdentitySet(IdentityHashMap<Entity, Boolean> first,
                                           IdentityHashMap<Entity, Boolean> second) {
        if (first.size() != second.size()) return false;
        for (Entity entity : first.keySet()) {
            if (!second.containsKey(entity)) return false;
        }
        return true;
    }

    static final class Scope {
        private static final Scope NOOP = new Scope(false, new ArrayList<>(), 0);

        private final boolean releaseFence;
        private final ArrayList<AsyncIslandPlayerTicks.LifecycleBodyToken> bodyTokens;
        private final int markedBodyCount;
        private boolean closed;

        private Scope(boolean releaseFence,
                      ArrayList<AsyncIslandPlayerTicks.LifecycleBodyToken> bodyTokens,
                      int markedBodyCount) {
            this.releaseFence = releaseFence;
            this.bodyTokens = bodyTokens;
            this.markedBodyCount = markedBodyCount;
        }

        Throwable finish(Throwable primary) {
            if (this == NOOP) return primary;
            if (closed) {
                Throwable repeated = FailStops.mergeFailure(primary, new IllegalStateException(
                        "A5 player lifecycle scope closed twice"));
                return AsyncIslandPlayerTicks.publishFatalFailure(repeated);
            }
            closed = true;
            // An entered vanilla-body failure is authoritative before any fence,
            // token or handoff cleanup. Select the shared first-wins object now;
            // every cleanup problem below can only become its suppressed context.
            Throwable failure = primary == null
                    ? null
                    : AsyncIslandPlayerTicks.publishFatalFailure(primary);
            if (releaseFence) {
                try {
                    IslandCommands.releaseAllExcept();
                } catch (Throwable cleanup) {
                    failure = AsyncIslandPlayerTicks.publishFatalFailure(
                            FailStops.mergeFailure(failure, cleanup));
                }
            }
            // Retire only after the original body and any global fence release.
            // Every successful token only stages its retained one-shot closer;
            // the whole scope publishes one batch after all tokens retire cleanly.
            // Any body/release failure cancels every closer without a successor.
            return finishMarkedBodies(bodyTokens, markedBodyCount, failure);
        }
    }

    private static Throwable finishMarkedBodies(
            ArrayList<AsyncIslandPlayerTicks.LifecycleBodyToken> bodyTokens,
            int markedBodyCount,
            Throwable primary) {
        Throwable failure = primary;
        for (int index = markedBodyCount - 1; index >= 0; index--) {
            try {
                failure = AsyncIslandPlayerTicks.finishLifecycleMutationBody(
                        bodyTokens.get(index), failure);
                if (failure != null) {
                    // A successful vanilla body can still expose the first W while
                    // retiring its exact token (for example, a duplicate staged
                    // closer). Close global admission before touching the next token.
                    failure = AsyncIslandPlayerTicks.publishFatalFailure(failure);
                }
            } catch (Throwable cleanup) {
                failure = AsyncIslandPlayerTicks.publishFatalFailure(
                        FailStops.mergeFailure(failure, cleanup));
            }
        }
        try {
            failure = AsyncIslandPlayerTicks.finishLifecycleHandoffPublications(
                    bodyTokens, markedBodyCount, failure);
            return failure == null
                    ? null
                    : AsyncIslandPlayerTicks.publishFatalFailure(failure);
        } catch (Throwable cleanup) {
            return AsyncIslandPlayerTicks.publishFatalFailure(
                    FailStops.mergeFailure(failure, cleanup));
        }
    }
}
