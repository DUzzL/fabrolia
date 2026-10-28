package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.regions.AsyncIslandPacketContinuations;
import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * JMM-visible marker for the removePlayerImmediately(CHANGED_DIMENSION)
 * lifecycle inside ServerPlayer.teleport. Region entity workers use the same
 * monitor to distinguish that temporary removal from death or logout.
 */
public final class PlayerDimensionTransition {

    /**
     * Identity-keyed depth rather than a boolean marker. A mod hook can
     * re-enter teleport for the same player while the outer transition is
     * still inside removePlayerImmediately; the inner finally must not clear
     * the publication that protects the outer frame.
     *
     * <p>The monitor is intentional. Teleports are rare, and the matching
     * synchronized read gives region workers a JMM acquire for the lifecycle
     * publication instead of relying on ServerPlayer's plain removal fields.
     */
    private static final java.util.IdentityHashMap<ServerPlayer, Integer> ACTIVE_DEPTH =
            new java.util.IdentityHashMap<>();

    private PlayerDimensionTransition() {}

    /**
     * Destination lookup can load chunks and mutate the destination world.
     * Move the complete operation to the existing listener Main FIFO before
     * those effects. The current island body finishes normally; its handoff, not a
     * worker waiting on Main, excludes the next player turn. A stale lifecycle
     * capability cancels the request without touching a replacement player.
     * The caller still installs cooldown in its owned source turn, before
     * later block contacts can recreate the consumed portal processor.
     */
    public static boolean deferPortal(
            ServerPlayer player, ServerLevel source, PortalProcessor process) {
        if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(source.getServer())) {
            return false;
        }
        var owner = AsyncIslandPacketContinuations.capture(player.connection);
        if (owner == null) return false;
        var portal = process.portal;
        var entry = process.getEntryPosition().immutable();
        return AsyncIslandPacketContinuations.enqueueMain(owner, "player portal",
                () -> AsyncIslandPlayerTicks.runFencedMainMutation("player portal", () -> {
                    if (player.level() != source || player.isRemoved() || !player.isAlive()) return;
                    var profiler = Profiler.get();
                    profiler.push("portal");
                    try {
                        TeleportTransition transition = portal.getPortalDestination(source, player, entry);
                        if (transition != null) {
                            player.teleportToPortalDestination(source, transition);
                        }
                    } finally {
                        profiler.pop();
                    }
                }));
    }

    /**
     * First End return bypasses the portal processor and removes the player in
     * showEndCredits. A piston can reach it after early but before the owed late
     * listener body, so retain the entire native call behind that terminal edge.
     * Capture authenticates the listener lifecycle, not an active player body;
     * the owning island's block-entity phase can publish the same Main marker.
     */
    public static boolean deferEndCredits(ServerPlayer player, Runnable credits) {
        if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(player.level().getServer())) {
            return false;
        }
        var owner = AsyncIslandPacketContinuations.capture(player.connection);
        if (owner == null) return false;
        return AsyncIslandPacketContinuations.enqueueMain(owner, "End credits",
                () -> AsyncIslandPlayerTicks.runFencedMainMutation("End credits", credits));
    }

    public static synchronized void enter(ServerPlayer player) {
        ACTIVE_DEPTH.merge(player, 1, Integer::sum);
    }

    public static synchronized void exit(ServerPlayer player) {
        Integer depth = ACTIVE_DEPTH.get(player);
        if (depth == null || depth <= 1) {
            ACTIVE_DEPTH.remove(player);
        } else {
            ACTIVE_DEPTH.put(player, depth - 1);
        }
    }

    /**
     * Recover an exact transitioning session without racing marker exit. If
     * the transition already ended, repeat vanilla owner resolution while
     * holding the monitor so a new transition cannot begin inside that lookup.
     */
    public static synchronized Player resolvePlayer(
            UUID ownerUuid, Supplier<Player> vanillaLookup) {
        for (ServerPlayer player : ACTIVE_DEPTH.keySet()) {
            if (ownerUuid.equals(player.getUUID())) {
                return player;
            }
        }
        return vanillaLookup.get();
    }

    /**
     * Re-evaluate a failed interaction check in one order with marker
     * enter/exit. CHANGED_DIMENSION is the only removed state repaired; a null
     * reason is rechecked through vanilla because unsetRemoved may have won
     * after the caller observed its false result.
     */
    public static synchronized boolean recheckCanInteractWithLevel(
            ServerPlayer player, BooleanSupplier vanillaCheck) {
        if (!ACTIVE_DEPTH.containsKey(player)) {
            return vanillaCheck.getAsBoolean();
        }
        return transitionCanInteractWithLevel(
                player.getHealth(), player.isSpectator(),
                player.getRemovalReason(), vanillaCheck);
    }

    static boolean transitionCanInteractWithLevel(
            float health, boolean spectator, Entity.RemovalReason removalReason,
            BooleanSupplier vanillaCheck) {
        if (health <= 0.0F || spectator) {
            return false;
        }
        return removalReason == Entity.RemovalReason.CHANGED_DIMENSION
                || (removalReason == null && vanillaCheck.getAsBoolean());
    }

    public static synchronized boolean contains(ServerPlayer player) {
        return ACTIVE_DEPTH.containsKey(player);
    }
}
