package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Owner-lane hand-off for Carpet's {@code /player} mutations.
 *
 * <p>This is deliberately outside Fabrolia's configured {@code .mixin} package.
 * Mixin forbids transformed target classes from directly loading an ordinary helper
 * from a package it owns.
 *
 * <p>Audited against fabric-carpet {@code 26.2+v260616}, artifact SHA-256
 * {@code f6ada912af65c91536d4b0d80adf26cc438253252ddaf259f2c6617ae471311c}.
 * Carpet injects {@code EntityPlayerActionPack.onUpdate()} at the HEAD of
 * {@code ServerPlayer.tick()}.  With A5 that read/update therefore runs on the island,
 * while {@code PlayerCommand.manipulate} would otherwise mutate the same action pack and
 * player immediately on Main.
 *
     * <p>A5's listener-lifetime Main FIFO is the linearization point. Every command for one
     * exact player/listener state appends to the same lane as MAIN-class packets and returns
     * immediately. The rare command may hand one turn to Main; ordinary fake-player ticks
     * remain island-owned. This avoids racing {@code actionPack.onUpdate()}, preserves FIFO
     * across region migration, and prevents a command reached from another island's chat
     * replay from mutating the target inline on that foreign worker.
 */
public final class CarpetPlayerMutationRouter {

    private static final String CARPET_FAKE = "carpet.patches.EntityPlayerMPFake";

    static final LongAdder ROUTED = new LongAdder();
    private CarpetPlayerMutationRouter() {}

    /**
     * @return true when A5 accepted the mutation into the per-player FIFO; false only
     *         outside the exact supported/enabled A5 envelope, where the caller retains
     *         Carpet's original inline call.
     */
    public static boolean route(ServerPlayer player, Runnable mutation, String operation) {
        Objects.requireNonNull(mutation, "mutation");
        if (!supportedExactClass(player)) return false;

        RoutedMutation routed = new RoutedMutation(player, mutation, operation);
        if (!AsyncIslandPlayerTicks.enqueueRoutedMutation(
                player, operation, routed::run)) return false;
        ROUTED.increment();
        return true;
    }

    /** Runs only after A5 dequeues this exact FIFO entry on physical Main. */
    private static final class RoutedMutation {
        private final ServerPlayer player;
        private final Runnable mutation;
        private final String operation;

        private RoutedMutation(ServerPlayer player, Runnable mutation, String operation) {
            this.player = player;
            this.mutation = mutation;
            this.operation = operation;
        }

        private void run(RegionizedServer runtime, Region region,
                         IslandRegistry.PIsland island) {
            try {
                if (player.isRemoved() || !supportedExactClass(player)) {
                    throw failure(this, "player lifecycle ended before Main commit", null);
                }
                if (runtime != null || region != null || island != null) {
                    throw failure(this, "mutation escaped the physical-Main FIFO", null);
                }
                if (player.connection == null || player.connection.player != player) {
                    throw failure(this, "listener no longer points at the exact player", null);
                }
                mutation.run();
            } catch (Throwable thrown) {
                Throwable winner = RegionScheduler.publishFatalAndSelectWinner(thrown);
                FailStops.<RuntimeException>rethrow(winner);
            }
        }
    }

    private static boolean supportedExactClass(ServerPlayer player) {
        if (player == null) return false;
        Class<?> type = player.getClass();
        return type == ServerPlayer.class || CARPET_FAKE.equals(type.getName());
    }

    private static IllegalStateException failure(RoutedMutation routed, String reason,
                                                 Throwable cause) {
        return new IllegalStateException(
                "Routed Carpet /player mutation '" + routed.operation + "' for "
                        + routed.player.getGameProfile().name() + " failed: " + reason,
                cause);
    }
}
