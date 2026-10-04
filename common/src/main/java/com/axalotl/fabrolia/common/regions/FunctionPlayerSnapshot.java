package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Immutable copy of {@code PlayerList.players}, republished by Main after every
 * mutation of that list. Off-Main readers (island function passes, worker-side
 * scoreboard broadcasts) iterate this snapshot instead of the live ArrayList
 * that Main mutates at login/logout/respawn.
 */
public final class FunctionPlayerSnapshot {

    private record Snapshot(List<ServerPlayer> players, IdentityHashMap<ServerPlayer, Integer> rank,
                            Set<String> names) {}

    private static volatile Snapshot current =
            new Snapshot(List.of(), new IdentityHashMap<>(), Set.of());

    private FunctionPlayerSnapshot() {}

    /** Main only, after the live list changed. */
    public static void publish(List<ServerPlayer> live) {
        List<ServerPlayer> copy = List.copyOf(live);
        IdentityHashMap<ServerPlayer, Integer> rank = new IdentityHashMap<>(copy.size() * 2);
        HashSet<String> names = new HashSet<>(copy.size() * 2);
        for (int i = 0; i < copy.size(); i++) {
            ServerPlayer p = copy.get(i);
            rank.put(p, i);
            names.add(p.getScoreboardName());
        }
        current = new Snapshot(copy, rank, Set.copyOf(names));
    }

    public static List<ServerPlayer> players() {
        return current.players();
    }

    /** True when a connected player is scored under {@code holder}. */
    public static boolean isOnlinePlayerName(String holder) {
        return current.names().contains(holder);
    }

    /** Vanilla {@code @a} relative order; unknown players (mid-login) last. */
    static void sortByPlayerList(List<ServerPlayer> players) {
        IdentityHashMap<ServerPlayer, Integer> rank = current.rank();
        players.sort(Comparator.comparingInt(p -> rank.getOrDefault(p, Integer.MAX_VALUE)));
    }
}
