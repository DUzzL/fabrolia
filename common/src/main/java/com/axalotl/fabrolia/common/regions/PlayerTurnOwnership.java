package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Session-based answer to "may the current thread mutate this player's
 * per-player gameplay state (advancements, recipe book, stats) right now".
 *
 * <p>Ownership follows the A5 turn session, never geometry alone: a region
 * worker holding the player's cell does not own a player that physical Main
 * ticks through MAIN_SYNC. Exactly one venue answers {@code true} at a time:
 * <ul>
 *   <li>the player's own A5 turn (early body incl. packet replay, late body);</li>
 *   <li>an island worker of the island attached to the player's session while
 *       that session rests between turns (ISLAND_READY, EARLY_DONE) and the
 *       worker's cell neighbours the player
 *       ({@link AsyncIslandPlayerTicks#ownsLifecycleMutation}, the same authority
 *       death/ride lifecycle mutations use);</li>
 *   <li>physical Main while no island holds the session (MAIN, Main fallback,
 *       Main handoff/drain, disconnected, unsupported player type,
 *       constructor-time load), or while Main holds the global fence, every
 *       claim of the player's level, or the claim of the attached island;</li>
 *   <li>the exact V2 player actor (Main parked, region pool fenced);</li>
 *   <li>physical Main whenever no async island is live: lockstep region
 *       workers and pool threads never own a player, which ticks on Main.</li>
 * </ul>
 * Ordered cheapest first: thread-local turn markers, then volatile fence
 * state, then the session view; the worker probe takes the scheduler
 * lifecycle monitor only after every cheaper answer was negative.
 */
public final class PlayerTurnOwnership {

    private PlayerTurnOwnership() {}

    public static boolean currentThreadOwns(ServerPlayer player) {
        if (AsyncIslandPlayerTicks.isIslandPlayerTickOf(player)) return true;
        if (RegionalPlayerEntityTicksV2.inExactPlayerActor()
                || RegionalPlayerEntityTicksV2.inWorkerPlayerTick()) {
            return true;
        }
        MinecraftServer server = com.axalotl.fabrolia.common.ParallelProcessor.getServer();
        boolean physicalMain = server == null
                ? isPlayerServerThread(player)
                : RegionalPlayerEntityTicksV2.isPhysicalServerThread(server);
        boolean islandsLive = IslandCommands.requiresMainFence();
        if (physicalMain) {
            if (!islandsLive || IslandCommands.fenceWindowHeld()) return true;
            // Constructor-time PlayerAdvancements.load (checkForAutomaticTriggers)
            // runs before the listener exists; no island can reach the object yet.
            if (player.connection == null) return true;
            return mainOwnsAttached(player);
        }
        if (!islandsLive) return false;
        if (!(Thread.currentThread() instanceof RegionWorkerThread)
                || !(player instanceof AsyncIslandPlayerAccess)) {
            return false;
        }
        AsyncIslandPlayerTicks.PlayerState state = AsyncIslandPlayerTicks.state(player);
        if (state == null) return false;
        IslandPlayerTurnSession.Phase phase = state.session.phase();
        return (phase == IslandPlayerTurnSession.Phase.ISLAND_READY
                || phase == IslandPlayerTurnSession.Phase.EARLY_DONE)
                && AsyncIslandPlayerTicks.ownsLifecycleMutation(player);
    }

    /** Diagnostic facts for a refused ownership probe; never a hot path. */
    public static String describe(ServerPlayer player) {
        StringBuilder out = new StringBuilder("player=")
                .append(player.getScoreboardName())
                .append(" thread=").append(Thread.currentThread().getName());
        IslandPlayerTurnSession.View view = sessionView(player);
        if (view != null) {
            out.append(" phase=").append(view.phase());
            IslandPlayerTurnSession.OwnerStamp owner = view.owner();
            if (owner != null) out.append(" island=").append(owner.islandId());
        }
        if (player.level() instanceof ServerLevel level) {
            out.append(' ').append(RegionScheduler.describeContext(level, player.blockPosition()));
        }
        return out.toString();
    }

    private static boolean mainOwnsAttached(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            RegionizedServer runtime = RegionScheduler.publishedForLevel(level);
            if (runtime != null && runtime.currentThreadHoldsAllClaims()) return true;
        }
        if (!(player instanceof AsyncIslandPlayerAccess)) return true;
        AsyncIslandPlayerTicks.PlayerState state = AsyncIslandPlayerTicks.state(player);
        if (state == null || !islandHeld(state.session.phase())) return true;
        IslandPlayerTurnSession.OwnerStamp owner = state.session.view().owner();
        return owner != null && owner.islandIdentity() instanceof IslandRegistry.PIsland island
                && IslandCommands.mainHolds(island);
    }

    /** Phases in which the attached island may run or touch the player without Main. */
    private static boolean islandHeld(IslandPlayerTurnSession.Phase phase) {
        return switch (phase) {
            case ISLAND_READY, EARLY_RESERVED, EARLY_RUNNING, EARLY_DONE, LATE_RUNNING,
                    TRANSFERRING -> true;
            case MAIN, MAIN_FALLBACK_RESERVED, MAIN_FALLBACK_RUNNING, MAIN_HANDOFF,
                    MAIN_DRAINING, DISCONNECTED -> false;
        };
    }

    private static IslandPlayerTurnSession.View sessionView(ServerPlayer player) {
        if (!(player instanceof AsyncIslandPlayerAccess)) return null;
        AsyncIslandPlayerTicks.PlayerState state = AsyncIslandPlayerTicks.state(player);
        return state == null ? null : state.session.view();
    }

    private static boolean isPlayerServerThread(ServerPlayer player) {
        MinecraftServer server = player.level() instanceof ServerLevel level
                ? level.getServer() : null;
        return server == null || server.isSameThread();
    }
}
