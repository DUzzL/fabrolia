package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.PlayerTurnOwnership;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BooleanSupplier;

/**
 * Venue rules for advancement state (contract C1).
 *
 * <p>{@link #ownsPlayer}: the current thread may evaluate the player's criteria and
 * mutate its {@code PlayerAdvancements} now ({@link PlayerTurnOwnership}). Award, revoke
 * and flush run synchronously there with vanilla's return value; anywhere else they
 * are rejected loudly.
 *
 * <p>{@link #quiescentContext}: the current thread runs with no concurrently
 * ticking island — physical Main holding the global fence, physical Main with no
 * live island, or the exact V2 player actor. Everything advancement-related runs
 * there exactly as vanilla, including reward functions and announcements.
 */
public final class AdvancementOwnership {

    private AdvancementOwnership() {}

    public static boolean ownsPlayer(ServerPlayer player) {
        return PlayerTurnOwnership.currentThreadOwns(player);
    }

    public static boolean quiescentContext() {
        if (RegionalPlayerEntityTicksV2.inExactPlayerActor()
                || RegionalPlayerEntityTicksV2.inWorkerPlayerTick()) {
            return true;
        }
        if (!onPhysicalMain()) return false;
        return !IslandCommands.requiresMainFence() || IslandCommands.fenceWindowHeld();
    }

    /**
     * Quiescent AND outside an owner-local function pass. Inside a pass, work
     * run "now" is either queued into the pass's ExecutionContext or executed
     * under its ownership guards (vanilla nests a function call inside the
     * active context), so the vanilla shortcut never applies there: reward
     * functions, loot and announcements take the routing point's decision.
     */
    public static boolean quiescentOutsidePass() {
        return com.axalotl.fabrolia.common.regions.IslandFunctionScope.current() == null
                && quiescentContext();
    }

    /** Physical Main (or the logical server thread when no server is published yet). */
    static boolean onPhysicalMain() {
        MinecraftServer server = ParallelProcessor.getServer();
        return server == null || RegionalPlayerEntityTicksV2.isPhysicalServerThread(server);
    }

    /**
     * C1 award/revoke body: runs {@code vanilla} synchronously on the player's owner and
     * returns its exact result; off the owner it is rejected before vanilla is entered.
     */
    public static boolean mutateOnOwner(String operation, ServerPlayer player, Object subject,
                                        BooleanSupplier vanilla) {
        if (!ownsPlayer(player)) throw offOwnerMutation(operation, player, subject);
        return vanilla.getAsBoolean();
    }

    /** Off-owner mutation of a player's advancements: no deferral, no fabricated result. */
    public static IllegalStateException offOwnerMutation(String operation, ServerPlayer player, Object subject) {
        return new IllegalStateException("PlayerAdvancements." + operation + "(" + subject
                + ") off the player's owner thread: " + PlayerTurnOwnership.describe(player));
    }
}
