package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared player-tick gate and server-tick phase marker.
 *
 * <p>The mutable runtime gate remains here, while
 * {@link FabroliaConfig#enablePlayerTickActor} stores the operator preference restored
 * after scheduler startup. With autonomous islands active the gate is consumed by
 * {@link AsyncIslandPlayerTicks} (A5: players tick on their own island). Synchronous
 * region threading always ticks players on physical main in vanilla order.
 */
public final class RegionalPlayerEntityTicksV2 {

    private RegionalPlayerEntityTicksV2() {}

    private static volatile boolean enabled;
    private static final AtomicReference<GlobalPhase> CURRENT = new AtomicReference<>();
    private static final GlobalPhase DISABLED_PHASE = new GlobalPhase();

    public static boolean enabled() {
        return enabled;
    }

    /** Physical-main authority is intentionally distinct from logical isSameThread(). */
    public static boolean isPhysicalServerThread(MinecraftServer server) {
        if (server == null) return false;
        return server.isSameThread();
    }

    /** Scheduler generations may change only between complete tickChildren phases. */
    public static boolean schedulerLifecycleTransitionAllowed(MinecraftServer server) {
        return isPhysicalServerThread(server) && CURRENT.get() == null;
    }

    /** One authoritative runtime gate. Safety predicates are not feature gates. */
    public static boolean setEnabled(MinecraftServer server, boolean value) {
        // Advancement reward functions and mod callbacks can execute commands from
        // inside a player body. A gate transition is a server-phase operation:
        // never let such a callback split the current phase or stop the worker pool
        // from one of its own threads.
        if (server != null && !isPhysicalServerThread(server)) return false;
        if (value && refusalReason(server) != null) return false;

        // A live A5 transition is an explicit operator boundary.  Quiesce every
        // autonomous island once so no early/late transaction straddles the volatile
        // gate publication. Ordinary ticks never take this global fence.
        ArrayList<RegionizedServer> quiesced = new ArrayList<>();
        Throwable failure = null;
        try {
            if (server != null && RegionScheduler.anyAsyncActive()) {
                for (RegionizedServer rs : RegionScheduler.allAsync()) {
                    if (rs.quiesceAllIslands()) quiesced.add(rs);
                }
            }
            // Claim ownership and global-failure admission are separate cuts. A
            // peer can select W after the last island claim is acquired, so the
            // actual A5 lifecycle mutation must atomically win body entry too.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                if (!value) {
                    // Keep the old gate published until every exact A5 session has
                    // entered MAIN_HANDOFF. Netty then continues appending to that old
                    // FIFO until physical Main closes it; publishing OFF first allowed a
                    // vanilla Main task to overtake already-intercepted packets.
                    for (RegionizedServer rs : RegionScheduler.allAsync()) {
                        for (IslandRegistry.PIsland island : rs.islandRegistry.islands()) {
                            AsyncIslandPlayerTicks.clearIsland(island);
                        }
                    }
                }
                enabled = value;
            });
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        for (int i = quiesced.size() - 1; i >= 0; i--) {
            try {
                // Pass the selected body W through every release so cleanup is
                // exhaustive without ever reopening admission between levels.
                failure = quiesced.get(i).releaseAllIslands(failure);
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        mergeFailure(failure, cleanup));
            }
        }
        if (failure == null) {
            try {
                // Claim release is mandatory cleanup, not permission to report a
                // successful gate transition. Refuse normal return if W won there.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        rethrow(failure);
        return true;
    }

    /**
     * Applies the persisted operator preference through the same fail-closed runtime
     * transition used by the command. This must run only after the region scheduler has
     * published an active generation. A saved ON which is currently unsafe remains saved,
     * but the runtime gate is normalized to OFF until a later safe startup/reload.
     */
    public static boolean applyConfiguredPreference(MinecraftServer server) {
        if (!isPhysicalServerThread(server)) return false;
        if (!FabroliaConfig.enablePlayerTickActor) {
            return setEnabled(server, false);
        }
        if (setEnabled(server, true)) return true;
        setEnabled(server, false);
        return false;
    }

    public static String refusalReason(MinecraftServer server) {
        if (server == null) return "server is unavailable";
        if (!isPhysicalServerThread(server)) {
            return "player-tick mode may only change on the physical server thread";
        }
        if (FabroliaConfig.disabled || !FabroliaConfig.enableRegionThreading
                || !RegionScheduler.isActive()) {
            return "region threading is not active";
        }
        return null;
    }

    public static String status(MinecraftServer server) {
        String refusal = refusalReason(server);
        String effective = enabled && refusal == null
                ? "effective"
                : enabled ? "requested but currently ineligible" : "inactive";
        boolean asyncVenue = RegionScheduler.anyAsyncActive();
        return (enabled ? "ON" : "off") + " (saved="
                + (FabroliaConfig.enablePlayerTickActor ? "on" : "off")
                + "; single runtime gate, " + effective
                + (asyncVenue
                    ? "; venue=autonomous island owner (real early+listener late; exact Carpet combined)"
                    : "; venue=physical main (synchronous region threading)")
                + (refusal == null ? "" : "; reason=" + refusal)
                + (asyncVenue
                    ? "; " + AsyncIslandPlayerTicks.status()
                    : "")
                + onlineEligibilityStatus(server) + ")";
    }

    /**
     * Operational explanation for an enabled mode which is deliberately leaving
     * one or more online players on the canonical physical-main path. This runs
     * only for the status command; the entity hot path remains allocation-free.
     */
    private static String onlineEligibilityStatus(MinecraftServer server) {
        if (server == null) return "; eligibleOnline=0/0";
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        int eligible = 0;
        String firstRejected = null;
        for (ServerPlayer player : players) {
            String reason = onlinePlayerRefusal(server, player);
            if (reason == null) {
                eligible++;
            } else if (firstRejected == null) {
                firstRejected = player.getGameProfile().name() + ": " + reason;
            }
        }
        return "; eligibleOnline=" + eligible + "/" + players.size()
                + (firstRejected == null ? "" : "; firstRejected=" + firstRejected);
    }

    private static String onlinePlayerRefusal(MinecraftServer server, ServerPlayer player) {
        if (!RegionScheduler.anyAsyncActive()) {
            return "synchronous region threading ticks players on physical main";
        }
        if (player.level().getServer() != server) return "belongs to another server";
        RegionizedServer owner = RegionScheduler.forLevel(player.level());
        if (owner == null || !owner.asyncActive) return "level is not autonomous";
        IslandRegistry.PIsland island = owner.islandOwnerOfChunk(player.chunkPosition().pack());
        if (island == null) return "player chunk has no island owner";
        return AsyncIslandPlayerTicks.playerRefusal(player);
    }

    /** Called by the tickChildren wrapper before any level ticks. */
    public static void beginServerTick(MinecraftServer server) {
        if (!isPhysicalServerThread(server)) {
            rethrow(RegionScheduler.publishFatalAndSelectWinner(
                    new IllegalStateException(
                            "regional player phase began off the server thread")));
        }
        // Always publish an allocation-free phase marker: a command/mod-thread toggle
        // during tickChildren can therefore affect only the next server tick, and a
        // scheduler lifecycle transition can never split the current level sequence.
        if (!CURRENT.compareAndSet(null, DISABLED_PHASE)) {
            rethrow(RegionScheduler.publishFatalAndSelectWinner(
                    new IllegalStateException("regional player tick phase already open")));
        }
    }

    /** Final HB/assertion edge immediately before vanilla tickConnection. */
    public static void beforeConnections(MinecraftServer server) {
        if (!isPhysicalServerThread(server)) {
            throw new IllegalStateException(
                    "regional player connection barrier ran off main");
        }
        GlobalPhase phase = CURRENT.get();
        if (phase == null) return;
        if (!CURRENT.compareAndSet(phase, null)) {
            rethrow(RegionScheduler.publishFatalAndSelectWinner(
                    new IllegalStateException(
                            "disabled player phase changed before connections")));
        }
    }

    /** Successful tickChildren epilogue; the connection barrier closed the phase. */
    public static Throwable finishServerTick() {
        GlobalPhase phase = CURRENT.get();
        if (phase == null) return null;
        return RegionScheduler.publishFatalAndSelectWinner(
                new IllegalStateException(
                        "disabled player phase survived tickChildren"));
    }

    /**
     * Cleanup when tickChildren exits exceptionally before its connection phase.
     *
     * @return a secondary failure to suppress onto the original world failure
     */
    public static Throwable abortServerTick() {
        GlobalPhase phase = CURRENT.get();
        if (phase == null) return null;
        if (!CURRENT.compareAndSet(phase, null)) {
            return RegionScheduler.publishFatalAndSelectWinner(
                    new IllegalStateException(
                            "disabled regional player phase changed during exceptional cleanup"));
        }
        return null;
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (secondary == null) return primary;
        if (primary == null) return secondary;
        if (primary != secondary) {
            try {
                if (primary.getSuppressed().length < 8) primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Suppression is diagnostic only. A secondary allocation failure must
                // never prevent the remaining leases/TLs/claims from being released.
            }
        }
        return primary;
    }

    /**
     * Kind of a player lifecycle mutation (teleport, respawn, removal, death).
     * Only autonomous island turns need a lifecycle scope: synchronous region
     * threading ticks players on physical main, which already owns them.
     */
    public enum LifecycleMutationKind {
        /** The player stays live, so its completed physics still needs publication. */
        SOFT_HANDOFF,
        /** Exact ServerPlayer.die origin; its ordinary listener stage remains owed. */
        DEATH,
        /** The mutation removes or replaces the player and makes old state unpublishable. */
        HARD_EXIT
    }

    public static LifecycleLeaseScope beginLifecycleMutation(
            LifecycleMutationKind kind, Entity... roots) {
        if (kind == null) {
            throw new IllegalArgumentException("player lifecycle mutation kind is required");
        }
        if (!AsyncIslandPlayerTicks.enabledForAsync()) return LifecycleLeaseScope.NOOP;
        AsyncIslandPlayerLifecycle.Scope asyncScope =
                AsyncIslandPlayerLifecycle.begin(kind, roots);
        try {
            return LifecycleLeaseScope.async(asyncScope);
        } catch (Throwable wrapperFailure) {
            // AsyncIslandPlayerLifecycle.begin may already have armed an exact
            // body depth, retained a handoff closer and taken the global
            // island fence.  Losing that scope because this tiny facade could
            // not be allocated would strand all three authorities forever.
            Throwable failure = RegionScheduler.publishFatalAndSelectWinner(wrapperFailure);
            try {
                failure = asyncScope.finish(failure);
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        mergeFailure(failure, cleanup));
            }
            rethrow(RegionScheduler.publishFatalAndSelectWinner(failure));
            return LifecycleLeaseScope.NOOP;
        }
    }

    public static final class LifecycleLeaseScope {
        private static final LifecycleLeaseScope NOOP = new LifecycleLeaseScope(null);

        private final AsyncIslandPlayerLifecycle.Scope asyncScope;

        private LifecycleLeaseScope(AsyncIslandPlayerLifecycle.Scope asyncScope) {
            this.asyncScope = asyncScope;
        }

        private static LifecycleLeaseScope async(AsyncIslandPlayerLifecycle.Scope scope) {
            return new LifecycleLeaseScope(scope);
        }

        /** Return the shared first-wins body failure with cleanup only as context. */
        public Throwable finish(Throwable primary) {
            if (asyncScope == null) return primary;
            Throwable failure = primary == null
                    ? null
                    : RegionScheduler.publishFatalAndSelectWinner(primary);
            try {
                return asyncScope.finish(failure);
            } catch (Throwable cleanup) {
                Throwable candidate = mergeFailure(failure, cleanup);
                return RegionScheduler.publishFatalAndSelectWinner(candidate);
            }
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure != null) RegionalPlayerEntityTicksV2.<RuntimeException>sneakyThrow(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /** Allocation-free marker of an open tickChildren phase. */
    private static final class GlobalPhase {
    }
}
